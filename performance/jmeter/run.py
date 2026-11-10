#!/usr/bin/env python3
"""Carga T15 limitada a um Compose novo e descartavel, sem destinos externos."""
import argparse
import csv
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'infra/compose'))
try:
    from compose_environment import ComposeEnvironment, Failure
except ImportError:
    print('Instale infra/rabbitmq/requirements-test.txt na .venv do projeto.', file=sys.stderr)
    sys.exit(2)
from data import customers, write_csv, status_requests
from measurements import Monitor, analyse
from reporting import render

HERE = Path(__file__).resolve().parent
IMAGE = 'datum/jmeter:5.6.3'


class PerformanceEnvironment(ComposeEnvironment):
    def __init__(self, output):
        super().__init__()
        self.env.update(APP_PROFILES='observability',
            CUSTOMER_JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=60.0 -XX:ActiveProcessorCount=2',
            NOTIFICATION_JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=60.0 -XX:ActiveProcessorCount=2',
            SCORE_CONNECT_TIMEOUT_MS='500', SCORE_READ_TIMEOUT_MS='1000', SCORE_MAX_ATTEMPTS='2', SCORE_RETRY_DELAY_MS='100',
            OUTBOX_BATCH_SIZE='10', OUTBOX_POLL_DELAY_MS='1000', OUTBOX_CONFIRM_TIMEOUT_MS='5000', OUTBOX_LEASE_MS='30000',
            STATUS_CONSUMER_PREFETCH='10', NOTIFICATION_CONSUMER_PREFETCH='10',
            POSTGRES_MEMORY_LIMIT='768m', RABBITMQ_MEMORY_LIMIT='1g', KEYCLOAK_MEMORY_LIMIT='1g', WIREMOCK_MEMORY_LIMIT='512m')
        for prefix in ('CUSTOMER','NOTIFICATION'):
            for suffix,value in dict(POOL_MAX_SIZE=10,POOL_MIN_IDLE=2,POOL_CONNECTION_TIMEOUT_MS=2000,
                POOL_VALIDATION_TIMEOUT_MS=1000,CONNECT_TIMEOUT_SECONDS=2,SOCKET_TIMEOUT_SECONDS=15,
                STATEMENT_TIMEOUT_MS=5000,LOCK_TIMEOUT_MS=2000,IDLE_TRANSACTION_TIMEOUT_MS=15000,TRANSACTION_TIMEOUT_SECONDS=10).items():
                self.env[prefix+'_DB_'+suffix]=str(value)
        self.output = output
        self.personal = []

    def run(self, command, timeout=240, required=True, extra_env=None):
        try:
            result = subprocess.run(command, cwd=ROOT, env=dict(self.env, **(extra_env or {})), capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            self.private_failure((error.stdout or b'') + (error.stderr or b''))
            raise Failure('Comando de medicao excedeu seu prazo.') from None
        if required and result.returncode:
            target = self.private_failure(result.stdout + result.stderr)
            raise Failure('Comando de medicao falhou; log privado: ' + str(target))
        return result.stdout.decode()

    def private_failure(self, content):
        path = self.output / ('failure-' + uuid.uuid4().hex + '.log')
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as stream:
            stream.write(content)
        return path

    def close(self):
        self.run(['docker', 'rm', '-f', self.project + '-jmeter'], required=False)
        super().close()


def bounded(low, high):
    def parse(value):
        number = int(value)
        if not low <= number <= high:
            raise argparse.ArgumentTypeError('Use valor entre ' + str(low) + ' e ' + str(high) + '.')
        return number
    return parse


def wiremock(env, delay):
    url = 'http://127.0.0.1:' + env.ports['wiremock']
    import urllib.request
    with urllib.request.urlopen(url + '/__admin/mappings', timeout=5) as response:
        mappings = json.load(response)['mappings']
    mapping = next(m for m in mappings if m.get('request', {}).get('urlPath') == '/scores/16899535009')
    mapping['response']['fixedDelayMilliseconds'] = delay
    request = urllib.request.Request(url + '/__admin/mappings/' + mapping['id'], method='PUT',
        data=json.dumps(mapping).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=5) as response:
        response.read()


def seed(env, count):
    rows = [dict(name='Cliente Score Sintetico', cpf='16899535009', email='score@example.test', status='ACTIVE')]
    rows += list(customers(count - 1))
    ids = []
    for row in rows:
        status, customer = env.api('POST', '/customers', row)
        if status != 201:
            raise Failure('Seed sintetico nao concluiu.')
        ids.append(customer['id'])
        env.personal.extend([row['cpf'], row['email']])
    drain(env, count)
    env.sql('customer_db', 'ANALYZE customers')
    return ids


def drain(env, expected):
    env.until(lambda: env.sql('customer_db', "SELECT count(*) FROM customer_outbox WHERE state='PUBLISHED'") == str(expected)
              and env.sql('notification_db', 'SELECT count(*) FROM notification_records') == str(expected),
              timeout=120, description='publicacao e notificacoes confirmadas')


def delivery_lag(env, count):
    # Apenas IDs/instantes em memoria; metricas publicadas nao incluem identidades.
    events = json.loads(env.sql('customer_db', "SELECT json_agg(json_build_object('id',o.event_id,'at',extract(epoch FROM o.occurred_at))) FROM customer_outbox o JOIN customers c ON c.id=o.customer_id WHERE left(c.cpf,9)::bigint BETWEEN 200000008 AND " + str(200000007 + count)))
    records = json.loads(env.sql('notification_db', "SELECT json_agg(json_build_object('id',event_id,'at',extract(epoch FROM registered_at))) FROM notification_records"))
    registered = {row['id']:row['at'] for row in records}
    lag = [(registered[row['id']]-row['at'])*1000 for row in events]
    if len(lag) != count or any(value < 0 for value in lag):
        raise Failure('Lags persistidos ausentes ou relogios inconsistentes.')
    from measurements import quantile
    return dict(samples=len(lag),p50Ms=quantile(lag,.5),p95Ms=quantile(lag,.95),p99Ms=quantile(lag,.99),maxMs=max(lag))


def command(env, directory, plan, options, score_id, expected):
    return ['docker', 'run', '--rm', '--name', env.project + '-jmeter', '--network', env.project + '_default',
        '--user', str(os.getuid()) + ':' + str(os.getgid()), '--memory', '512m', '--cpus', '1',
        '--env', 'DATUM_TOKEN', '--volume', str(directory) + ':/work', '--volume', str(HERE) + ':/plans:ro',
        '--workdir', '/work', IMAGE, '-n', '-t', '/plans/' + plan + '.jmx', '-q', '/plans/user.properties',
        '-Jthreads=' + str(options.threads), '-Jramp=' + str(options.ramp),
        '-Jduration=' + str(options.duration + options.warmup), '-Jcustomer_count=' + str(options.customers), '-Jcustomer_id=' + str(score_id),
        '-Jexpected_status=' + str(expected), '-l', '/work/raw.jtl', '-j', '/work/jmeter.log']


def measured_csv(raw, target, warmup, create=False):
    with raw.open(newline='') as stream:
        reader = csv.DictReader(stream)
        fields = reader.fieldnames
        rows = list(reader)
    if not rows:
        raise Failure('JMeter nao gerou amostras.')
    cutoff = min(int(row['timeStamp']) for row in rows) + warmup * 1000
    selected = [r for r in rows if r.get('phase') == 'measurement'] if create else [r for r in rows if int(r['timeStamp']) >= cutoff]
    with target.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(selected)
    return cutoff


def http_case(env, name, plan, options, score_id, expected, create_rows=None):
    print('Cenario: ' + name + '.', flush=True)
    directory = env.output / name
    directory.mkdir(mode=0o700)
    if create_rows is not None:
        (directory / 'input').mkdir(mode=0o700)
        write_csv(directory / 'input/customers.csv', create_rows)
    env.refresh_token()
    env_payload = dict(env.env, DATUM_TOKEN=env.token)
    log = directory / 'driver.log'
    start = time.monotonic()
    with log.open('wb') as stream:
        with subprocess.Popen(command(env, directory, plan, options, score_id, expected), cwd=ROOT, env=env_payload,
                              stdout=stream, stderr=subprocess.STDOUT) as process:
            try:
                env.until(lambda: (directory / 'raw.jtl').is_file() and len((directory / 'raw.jtl').read_text().splitlines()) >= 2,
                          timeout=45, description='primeira amostra JMeter')
                with (directory / 'raw.jtl').open(newline='') as csvfile:
                    first = next(csv.DictReader(csvfile))
                boundary = int(first['timeStamp']) / 1000 + (options.warmup if create_rows is None else 0)
                while time.time() < boundary:
                    time.sleep(.1)
                with Monitor(env) as monitor:
                    before = monitor.sample()
                    try:
                        status = process.wait(timeout=options.duration + options.warmup + 60)
                    except subprocess.TimeoutExpired:
                        raise Failure('Plano JMeter excedeu o prazo.') from None
                    http_done = time.monotonic()
                    delivery_wait = None
                    if create_rows is not None and status == 0:
                        drain(env, options.customers + len(create_rows))
                        delivery_wait = time.monotonic() - http_done
                    after = monitor.sample()
                if status:
                    raise Failure('JMeter falhou; consulte o log privado do cenario.')
            finally:
                if process.poll() is None:
                    env.run(['docker', 'rm', '-f', env.project + '-jmeter'], required=False)
                    process.wait(timeout=20)
    measured_csv(directory / 'raw.jtl', directory / 'measurement.jtl', options.warmup, create=create_rows is not None)
    raw_stats = analyse(directory / 'raw.jtl', expected)
    stats = analyse(directory / 'measurement.jtl', expected)
    stats['warmupSamples'] = raw_stats['samples'] - stats['samples']
    stats['resources'] = monitor.summary(before, after)
    if delivery_wait is not None:
        stats['postDriverDrainSeconds'] = delivery_wait
        stats['notificationLag'] = delivery_lag(env, options.creates)
    stats['name'] = name
    stats['durationIncludingDriverSeconds'] = round(time.monotonic() - start, 3)
    (directory / 'metrics.json').write_text(json.dumps(monitor.rows, indent=2))
    env.run(['docker', 'run', '--rm', '--network', 'none', '--user', str(os.getuid()) + ':' + str(os.getgid()),
        '--memory', '512m', '--cpus', '1', '--volume', str(directory) + ':/work', '--volume', str(HERE) + ':/plans:ro',
        '--workdir', '/work', IMAGE, '-q', '/plans/user.properties', '-g', '/work/measurement.jtl', '-o', '/work/html', '-j', '/work/dashboard.log'], timeout=120)
    if not (directory / 'html/index.html').is_file():
        raise Failure('Dashboard JMeter ausente.')
    print('OK: ' + name + ': ' + str(stats['samples']) + ' amostras; p95=' + str(stats['p95Ms']) + ' ms; erros=' + str(stats['errors']) + '.', flush=True)
    return stats


def events_case(env, ids):
    print('Cenario: E-status-amqp.', flush=True)
    events = status_requests(ids)
    from jsonschema import Draft202012Validator, FormatChecker
    schema = json.loads((ROOT/'contracts/events/schemas/customer-status-change.v1.schema.json').read_text())
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    for event in events:
        validator.validate(event)
    env.refresh_token()
    with Monitor(env) as monitor:
        before = monitor.sample()
        start = time.monotonic()
        with env.connection('status_publisher') as connection:
            channel = connection.channel()
            channel.confirm_delivery()
            import pika
            for event in events:
                channel.basic_publish(exchange='datum.customer.events', routing_key='customer.status-change.v1',
                    body=json.dumps(event).encode(), mandatory=True, properties=pika.BasicProperties(content_type='application/json',
                        content_encoding='UTF-8', delivery_mode=2, message_id=event['eventId'], correlation_id=event['eventId'], type=event['eventType']))
        confirmed = time.monotonic() - start
        env.until(lambda: env.sql('customer_db', "SELECT count(*) FROM customer_event_inbox WHERE outcome='APPLIED'") == str(len(events)))
        elapsed = time.monotonic() - start
        after = monitor.sample()
    valid = env.sql('customer_db', "SELECT count(*) FROM customers WHERE version=1 AND status='INACTIVE'")
    env.check(valid == str(len(events)), 'Batch AMQP aplicado uma vez por cliente, com inbox e versao confirmadas.')
    directory = env.output / 'E-status-amqp'
    directory.mkdir(mode=0o700)
    (directory / 'metrics.json').write_text(json.dumps(monitor.rows, indent=2))
    return dict(name='E-status-amqp', published=len(events), publisherConfirmSeconds=confirmed, allAppliedSeconds=elapsed,
        endToEndEventsPerSecond=len(events) / elapsed, resources=monitor.summary(before, after))


def metadata(env, options):
    information = json.loads(env.run(['docker', 'info', '--format', '{{json .}}']))
    host_ram = None
    if sys.platform == 'darwin':
        host_ram = int(subprocess.check_output(['sysctl', '-n', 'hw.memsize']))
    return dict(startedAt=datetime.now(timezone.utc).isoformat(), host=dict(os=platform.system(), architecture=platform.machine(),
        logicalCpus=os.cpu_count(), memoryBytes=host_ram), docker={key: information[key] for key in ('ServerVersion','NCPU','MemTotal','Architecture')},
        compose=env.run(['docker', 'compose', 'version', '--short']).strip(),
        java=env.run(['docker', 'exec', env.customer, 'sh', '-c', 'java -version 2>&1']).strip(),
        jmeter='5.6.3', applicationImage='datum/customer-service:t14',
        imageIds={name:env.run(['docker','inspect','--format','{{.Image}}',env.service_id(name)]).strip()
                  for name in ('customer-service','customer-notification-service')},
        jvmOptions='-XX:MaxRAMPercentage=60.0 -XX:ActiveProcessorCount=2', appMemoryBytes=1073741824,
        driverMemoryBytes=536870912, driverCpuLimit=1,
        gitRevision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
        workingTreeDirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),
        parameters=vars(options), logLevel='INFO', metricCollection='Prometheus + Management, intervalo alvo 1 s; overhead incluido')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=['all','list','search','score','create','events'], default='all')
    parser.add_argument('--skip-build', action='store_true', help='Reutiliza imagens :t14 e JMeter desta revisao')
    parser.add_argument('--threads', type=bounded(1,8), default=4)
    parser.add_argument('--duration', type=bounded(10,60), default=15)
    parser.add_argument('--warmup', type=bounded(3,15), default=5)
    parser.add_argument('--ramp', type=bounded(1,5), default=3)
    parser.add_argument('--customers', type=bounded(20,500), default=100)
    parser.add_argument('--creates', type=bounded(10,100), default=40)
    options = parser.parse_args()
    if options.warmup < options.ramp:
        parser.error('--warmup deve ser maior ou igual a --ramp')
    output = ROOT / '.local/performance' / uuid.uuid4().hex
    output.mkdir(parents=True,mode=0o700)
    print('Artefatos privados: ' + str(output), flush=True)
    env = PerformanceEnvironment(output)
    report = dict(status='running', cases=[], output=str(output))
    try:
        if not options.skip_build:
            print('Construindo JMeter oficial 5.6.3.', flush=True)
            env.run(['docker','build','-f','performance/jmeter/Dockerfile','-t',IMAGE,'.'],timeout=600)
        env.start(build=not options.skip_build)
        report['environment'] = metadata(env, options)
        ids = seed(env, options.customers)
        print('Dataset sintetico pronto: ' + str(len(ids)) + ' clientes, com eventos/notificacoes drenados.', flush=True)
        explain = env.sql('customer_db', "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT * FROM customers WHERE deleted_at IS NULL AND status='ACTIVE' AND upper(name_normalized) LIKE upper('%cliente%') ORDER BY id LIMIT 20")
        (output / 'query-plan.json').write_text(explain)
        for selection, name, plan in [('list','A-list','A-list'),('search','B-search','B-search')]:
            if options.scenario in ('all',selection):
                report['cases'].append(http_case(env, name, plan, options, ids[0], 200))
        if options.scenario in ('all','score'):
            for name,delay,status in [('C-score-fast',0,200),('C-score-slow',200,200),('C-score-timeout',3000,504)]:
                wiremock(env, delay)
                result = http_case(env, name, 'C-score', options, ids[0], status)
                result['providerDelayMs'] = delay
                report['cases'].append(result)
            wiremock(env, 0)
        if options.scenario in ('all','create'):
            rows = [dict(row,phase='warmup' if i<8 else 'measurement') for i,row in enumerate(customers(options.creates+8,offset=200000000))]
            env.personal.extend(value for row in rows for value in (row['cpf'],row['email']))
            result = http_case(env,'D-create','D-create',options,ids[0],201,create_rows=rows)
            if result['samples'] != options.creates:
                raise Failure('Volume de criacao diverge do dataset finito.')
            result['warmupCreates'] = 8
            report['cases'].append(result)
        if options.scenario in ('all','events'):
            report['cases'].append(events_case(env,ids[:min(100,len(ids))]))
        private = env.personal + env.tokens_issued + [v for k,v in env.env.items() if k.endswith('PASSWORD')]
        pattern = re.compile('|'.join(re.escape(value) for value in private))
        for artifact in output.rglob('*'):
            if artifact.is_file() and 'input' not in artifact.parts:
                if pattern.search(artifact.read_text(errors='ignore')):
                    raise Failure('Dado pessoal ou segredo encontrado nos artefatos; revise o diretorio privado.')
        if pattern.search(env.logs('customer') + env.logs('notification')):
            raise Failure('Dado pessoal ou segredo encontrado nos logs das aplicacoes.')
        report['status'] = 'measured'
    finally:
        try:
            env.close()
            report['cleanup'] = True
        finally:
            report['status'] = 'passed' if report['status'] == 'measured' and report.get('cleanup') is True else 'failed'
            (output / 'summary.json').write_text(json.dumps(report,indent=2,ensure_ascii=False))
    (output / 'report.md').write_text(render(report))
    print('T15: medicao concluida. Relatorio: ' + str(output / 'report.md'), flush=True)


if __name__ == '__main__':
    try:
        main()
    except Failure as error:
        print('FALHA: ' + str(error),file=sys.stderr)
        sys.exit(1)
    except Exception:
        print('FALHA: medicao nao concluiu; confira o diretorio privado e a limpeza.',file=sys.stderr)
        sys.exit(1)
