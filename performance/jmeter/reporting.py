"""Relatorio derivado dos resultados reais; sem metas artificiais de desempenho."""

import json
from pathlib import Path

def render(report):
    env = report['environment']
    p = env['parameters']
    out = ['# Relatorio de performance — T15', '',
        'Execucao UTC: ' + env['startedAt'] + '. Status: ' + report['status'] + '; limpeza: ' + str(report.get('cleanup')) + '.',
        'Status passed significa protocolo experimental e persistencia conferidos; nao significa SLO aprovado ou ausencia de erros HTTP.', '',
        '## Ambiente e controle', '',
        '- Host: ' + str(env['host']) + '.',
        '- Docker: ' + str(env['docker']) + '; Compose ' + env['compose'] + '.',
        '- Java: `' + env['java'].replace('\n','; ') + '`; JMeter ' + env['jmeter'] + '.',
        '- Imagens das aplicacoes: `' + str(env.get('imageIds',{})) + '`.',
        '- Aplicacoes: imagem T14, 1 GiB por servico, `' + env['jvmOptions'] + '`; gerador: 512 MiB e limite de 1 CPU.',
        '- Revisao HEAD: `' + env['gitRevision'] + '`; arvore com alteracoes: ' + str(env['workingTreeDirty']) + '.',
        '- Parametros: `' + str(p) + '`.',
        '- Logs INFO e metricas habilitados; ' + env['metricCollection'] + '.', '',
        '## HTTP medido pelo JMeter', '',
        '| Cenario | N | req/s | Erros | P50 ms | P95 ms | P99 ms | HTTP |',
        '|---|---:|---:|---:|---:|---:|---:|---|']
    for case in report['cases']:
        if 'samples' in case:
            out.append('| ' + case['name'] + ' | ' + str(case['samples']) + ' | ' + f"{case['throughput']:.2f}" + ' | '
                + f"{100*case['errorRate']:.1f}%" + ' | ' + str(case['p50Ms']) + ' | ' + str(case['p95Ms']) + ' | '
                + str(case['p99Ms']) + ' | ' + str(case['statuses']) + ' |')
    out += ['', 'Percentis empiricos por nearest-rank sobre todas as amostras medidas, incluindo erros. Warm-up excluido do JTL usado pelo dashboard. Throughput = amostras / janela entre inicio da primeira e termino da ultima. O gerador usa carga fechada e think time de 100 ms; os req/s oferecidos diminuem quando a dependencia demora. Nao e uma medicao de capacidade maxima ou de taxa de chegada constante.', '',
        '## Sinais de causa e recursos', '',
        '| Cenario | Score medio/tentativa ms | SQL medio ms | CPU API max observado | Heap API MiB max observado | Pool pendente max observado | HTTP busy max observado | GC API s | CPU gerador max observado |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for case in report['cases']:
        c = case['resources']['customer']
        mean = lambda key: (f"{1000*c[key+'SecondsDelta']/c[key+'CountDelta']:.2f}") if c[key+'CountDelta'] else '—'
        driver = case['resources']['generatorCpuPercentMaxObserved']
        out.append('| '+case['name']+' | '+mean('score')+' | '+mean('db')+' | '
            +f"{100*c['cpuMaxObserved']:.1f}%"+' | '+f"{c['heapBytesMaxObserved']/1048576:.1f}"+' | '
            +str(c['poolPendingMaxObserved'])+' | '+str(c['httpBusyMaxObserved'])+' | '+f"{c['gcPauseSecondsDelta']:.4f}"+' | '
            +(f'{driver:.1f}%' if driver is not None else 'indisponivel')+' |')
    out += ['', 'Os deltas de contadores usam scrapes nas bordas da medicao e podem incluir requisicoes em voo no limite, healthchecks e consultas de outbox. Nao substituem os percentis do cliente. O timer SQL tem resolucao de milissegundos e pode registrar zero em consultas muito curtas; — indica ausencia de amostras, nao latencia zero. Maximos sao apenas os observados nas amostras; intervalo alvo de 1 s inclui o tempo de coleta e pode ser maior. CPU da JVM e normalizada aos processadores disponiveis; CPU do gerador e a medida do Docker, com limite de um core. Scrapes e gerador compartilham a maquina com os servicos.', '',
        '## Observacoes desta execucao', '']
    by_name={case['name']:case for case in report['cases']}
    if 'C-score-slow' in by_name:
        fast, slow, timeout = (by_name[name] for name in ('C-score-fast','C-score-slow','C-score-timeout'))
        out += ['- O mesmo cliente/endpoint foi consultado com atrasos de 0, 200 e 3000 ms no provedor; contrato e resposta de sucesso permaneceram iguais.',
                '- P95 rapido/lento: ' + str(fast['p95Ms']) + '/' + str(slow['p95Ms']) + ' ms; a media por tentativa externa foi registrada separadamente na tabela.',
                '- A variacao de timeout teve ' + str(timeout['errors']) + ' erros HTTP 504/SCORE_TIMEOUT em ' + str(timeout['samples']) + ' amostras. Esses erros nao foram convertidos em sucesso. O timeout limita a espera do cliente, embora o WireMock ainda possa estar terminando respostas atrasadas.']
    for case in report['cases']:
        c=case['resources']['customer']
        out.append('- '+case['name']+': '+('houve espera de pool nas amostras.' if c['poolPendingMaxObserved'] else 'nao foi observada espera de pool nas amostras.'))
        if 'postDriverDrainSeconds' in case:
            out.append('- Criacao: '+str(p['creates'])+' cadastros medidos e oito de warm-up. O verificador aguardou '+f"{case['postDriverDrainSeconds']:.3f}"+' s adicionais apos o termino do processo JMeter para atingir a contagem esperada; esse intervalo nao mede a latencia individual de evento.')
            lag=case['notificationLag']
            out.append('- Lag persistido ocorrencia→notificacao dos cadastros medidos: P50/P95/P99 = '+f"{lag['p50Ms']:.2f}/{lag['p95Ms']:.2f}/{lag['p99Ms']:.2f}"+' ms; '+str(lag['samples'])+' eventos. Instantes comparados nos dois bancos, com containers no mesmo relogio do host Docker.')
        if 'published' in case:
            out.append('- AMQP: '+str(case['published'])+' publicacoes confirmadas em '+f"{case['publisherConfirmSeconds']:.3f}"+' s; todas as inboxes/versoes foram confirmadas em '+f"{case['allAppliedSeconds']:.3f}"+' s desde o inicio. A taxa ponta a ponta ('+f"{case['endToEndEventsPerSecond']:.2f}"+' eventos/s) inclui publicacao e resolucao do polling; nao representa capacidade isolada do consumidor.')
    if 'C-score-slow' in by_name:
        out += ['- A diferenca de latencia acompanha a espera externa configurada; nesta carga, os sinais de pool nao apontam espera por conexao como explicacao para o acrescimo de cerca de 200 ms. Isso nao demonstra capacidade maxima nem ausencia de outras causas em cargas maiores.']
    if 'D-create' in by_name:
        out += ['- O lag de notificacao e maior que a resposta do POST. O relay esta configurado com lote 10 e pausa de 1000 ms apos cada ciclo; a rajada produz eventos mais rapidamente que essa cadencia nominal. Essa configuracao e uma hipotese concreta para o backlog/lag observado. O proximo experimento deve variar lote/poll mantendo a carga e conferir confirms, pool e consumo antes de qualquer tuning.']
    out += ['', '## Banco, backlog e limites', '',
        'O arquivo query-plan.json contem EXPLAIN (ANALYZE, BUFFERS) de um SELECT com filtros/ordenacao equivalentes a busca. A base foi ANALYZE apos o seed. A massa pequena nao justifica indices novos nem permite extrapolar comportamento para milhoes de clientes.', '',
        'Os JSONs de metricas preservam sinais das duas JVMs, pool, GC, SQL, Score, outbox e ready/unacked/total das dez filas. As metricas de criacao incluem warm-up e confirmacao da entrega assincrona; o JTL exclui os oito cadastros de warm-up. A espera de drenagem e medida antes da geracao HTML. O Management atualiza estatisticas periodicamente: um maximo observado de zero nao prova que nunca houve backlog. A entrega e comprovada adicionalmente por contagens persistidas/inbox, sem inferir consumo a partir apenas do HTTP.', '',
        'Uma execucao de referencia por condicao, sem agregar rodadas de desenvolvimento; ordem fixa, massa pequena, warm-up curto e infraestrutura de no unico. Percentis P99 com poucas amostras sao instaveis. Nao houve busca pelo ponto de saturacao nem validacao de SLA. Nenhuma otimizacao foi aplicada sem evidencia; aumentar carga e repetir condicoes e o proximo experimento, dentro de limites autorizados.', '',
        '## Reproducao e artefatos', '',
        'Com a .venv do projeto e Docker ativos, execute `infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario '+p['scenario']+' --threads '+str(p['threads'])+' --duration '+str(p['duration'])+' --warmup '+str(p['warmup'])+' --ramp '+str(p['ramp'])+' --customers '+str(p['customers'])+' --creates '+str(p['creates'])+'`.', '',
        'Os JTL/HTML/metricas/planos detalhados desta execucao estao no diretorio privado indicado pelo executor, ignorado pelo Git. Os planos .jmx e o dataset exemplo estao versionados. O executor cria e limpa somente seu Compose descartavel; nao recebe URL de ambiente externo.', '']
    plan_file=Path(report['output'])/'query-plan.json'
    if plan_file.is_file():
        plan=json.loads(plan_file.read_text())[0]
        def nodes(node):
            return [node['Node Type']]+[kind for child in node.get('Plans',[]) for kind in nodes(child)]
        out += ['', '## Plano de consulta observado', '', 'Plano: '+ ' → '.join(nodes(plan['Plan']))+'. Execution Time: '+str(plan.get('Execution Time'))+' ms; Planning Time: '+str(plan.get('Planning Time'))+' ms; Shared Hit Blocks no no superior: '+str(plan['Plan'].get('Shared Hit Blocks'))+'.']
    return '\n'.join(out)
