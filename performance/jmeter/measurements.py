"""Estatisticas empiricas e amostragem de sinais; nenhum dado de cliente como label."""
from collections import Counter
import base64
import csv
from datetime import datetime, timezone
import json
import math
import re
import threading
import time
import urllib.request

LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+([^ ]+)')
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def prometheus(text):
    values = []
    for line in text.splitlines():
        if line.startswith('#'):
            continue
        match = LINE.match(line)
        if match:
            name, labels, value = match.groups()
            number = float(value)
            if math.isfinite(number):
                values.append((name, dict(LABEL.findall(labels or '')), number))
    return values


def total(rows, name, **labels):
    return sum(value for metric, tags, value in rows if metric == name and all(tags.get(k) == v for k, v in labels.items()))


def signals(rows):
    result = {key: total(rows, metric) for key, metric in {
        'cpu': 'process_cpu_usage', 'poolActive': 'hikaricp_connections_active', 'poolPending': 'hikaricp_connections_pending',
        'poolIdle': 'hikaricp_connections_idle', 'poolAcquireCount': 'hikaricp_connections_acquire_seconds_count',
        'poolAcquireSeconds': 'hikaricp_connections_acquire_seconds_sum', 'poolTimeouts': 'hikaricp_connections_timeout_total',
        'httpBusy': 'tomcat_threads_busy_threads', 'httpMax': 'tomcat_threads_config_max_threads',
        'gcPauseSeconds': 'jvm_gc_pause_seconds_sum', 'dbCount': 'datum_db_query_duration_seconds_count',
        'dbSeconds': 'datum_db_query_duration_seconds_sum', 'scoreCount': 'datum_score_client_duration_seconds_count',
        'scoreSeconds': 'datum_score_client_duration_seconds_sum', 'outboxPending': 'datum_outbox_pending',
        'outboxOldestSeconds': 'datum_outbox_oldest_pending_age_seconds',
        'messageCount': 'datum_messaging_process_duration_seconds_count',
        'messageSeconds': 'datum_messaging_process_duration_seconds_sum'}.items()}
    result['heapBytes'] = total(rows, 'jvm_memory_used_bytes', area='heap')
    return result


def scrape(env, service):
    if service == 'customer':
        authorization = 'Bearer ' + env.token
    else:
        authorization = 'Basic ' + base64.b64encode(('notification_monitor:' + env.env['NOTIFICATION_MANAGEMENT_PASSWORD']).encode()).decode()
    request = urllib.request.Request('http://127.0.0.1:' + env.ports[service] + '/actuator/prometheus', headers={'Authorization': authorization})
    with urllib.request.urlopen(request, timeout=5) as response:
        rows = prometheus(response.read().decode())
    required = {'process_cpu_usage','hikaricp_connections_active','hikaricp_connections_pending','tomcat_threads_busy_threads','tomcat_threads_config_max_threads'}
    if not required <= {name for name,_,_ in rows}:
        raise ValueError('Scrape sem sinais JVM esperados.')
    return signals(rows)


class Monitor:
    def __init__(self, env):
        self.env = env
        self.rows = []
        self.errors = 0
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def sample(self):
        row = {'at': datetime.now(timezone.utc).isoformat()}
        for service in ('customer', 'notification'):
            row[service] = scrape(self.env, service)
        stats = self.env.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', self.env.project + '-jmeter'], required=False).strip()
        cpu = json.loads(stats).get('CPUPerc', '') if stats.startswith('{') else ''
        row['generatorCpuPercent'] = float(cpu.removesuffix('%')) if re.fullmatch(r'[0-9.]+%', cpu) else None
        queues = self.env.management('GET', 'queues/datum')
        # Dez nomes de fila ja aprovados; nunca inclui corpos ou headers de mensagens.
        row['broker'] = {queue['name']: {key: queue.get(key) for key in ('messages_ready', 'messages_unacknowledged', 'messages')}
                         for queue in queues if queue['name'] in {q['name'] for q in self.env.definitions['queues']}}
        return row

    def collect(self):
        while not self.stop.is_set():
            try:
                self.rows.append(self.sample())
            except Exception:
                self.errors += 1
            self.stop.wait(1)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=15)
        if self.thread.is_alive() or self.errors or not self.rows:
            raise ValueError('Amostragem incompleta; resultados nao podem ser aprovados.')

    def summary(self, before, after):
        result = {'samples': len(self.rows), 'intervalTargetSeconds': 1, 'errors': self.errors}
        driver = [row['generatorCpuPercent'] for row in self.rows if row.get('generatorCpuPercent') is not None]
        result['generatorCpuPercentMaxObserved'] = max(driver) if driver else None
        for service in ('customer', 'notification'):
            values = [row[service] for row in self.rows]
            out = {key + 'MaxObserved': max(row[key] for row in values) for key in
                   ('cpu', 'heapBytes', 'poolActive', 'poolIdle', 'poolPending', 'httpBusy', 'httpMax', 'outboxPending', 'outboxOldestSeconds')}
            for key in ('poolAcquireCount', 'poolAcquireSeconds', 'poolTimeouts', 'gcPauseSeconds', 'dbCount', 'dbSeconds', 'scoreCount', 'scoreSeconds', 'messageCount', 'messageSeconds'):
                delta = after[service][key] - before[service][key]
                if delta < 0:
                    raise ValueError('Contadores reiniciados durante a medicao.')
                out[key + 'Delta'] = delta
            result[service] = out
        result['brokerMaxObserved'] = {}
        for row in self.rows:
            for name, queue in row['broker'].items():
                current = result['brokerMaxObserved'].setdefault(name, {})
                for key, value in queue.items():
                    if value is not None:
                        current[key] = max(current.get(key, 0), value)
        return result


def quantile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        raise ValueError('Sem amostras.')
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def analyse(path, expected_status):
    with path.open(newline='') as stream:
        rows = list(csv.DictReader(stream))
    if not rows:
        raise ValueError('JTL vazio.')
    statuses = Counter(row['responseCode'] for row in rows)
    if set(statuses) != {str(expected_status)}:
        raise ValueError('Status inesperado no JTL: ' + ','.join(sorted(statuses)))
    if expected_status == 504 and any(row.get('applicationCode') != 'SCORE_TIMEOUT' or row['success'] != 'false' for row in rows):
        raise ValueError('Timeout esperado foi classificado incorretamente ou ocultado.')
    if expected_status != 504 and any(row['success'] != 'true' for row in rows):
        raise ValueError('Assertion ou HTTP falhou durante a medicao.')
    elapsed = [int(row['elapsed']) for row in rows]
    starts = [int(row['timeStamp']) for row in rows]
    seconds = (max(start + duration for start, duration in zip(starts, elapsed)) - min(starts)) / 1000
    if seconds <= 0:
        raise ValueError('Janela de medicao invalida.')
    return dict(samples=len(rows), windowSeconds=round(seconds, 3), throughput=len(rows) / seconds,
        errors=sum(row['success'] != 'true' for row in rows), errorRate=sum(row['success'] != 'true' for row in rows) / len(rows),
        p50Ms=quantile(elapsed, .5), p95Ms=quantile(elapsed, .95), p99Ms=quantile(elapsed, .99),
        statuses=dict(statuses), quantileMethod='nearest-rank', meanMs=sum(elapsed) / len(elapsed))
