# Relatorio de performance — T15

Execucao UTC: 2026-11-10T17:52:18.010818+00:00. Status: passed; limpeza: True.
Status passed significa protocolo experimental e persistencia conferidos; nao significa SLO aprovado ou ausencia de erros HTTP.

## Ambiente e controle

- Host: {'os': 'Darwin', 'architecture': 'arm64', 'logicalCpus': 10, 'memoryBytes': 68719476736}.
- Docker: {'ServerVersion': '29.5.3', 'NCPU': 10, 'MemTotal': 26216435712, 'Architecture': 'aarch64'}; Compose 5.1.4.
- Java: `Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=60.0 -XX:ActiveProcessorCount=2; openjdk version "21.0.13" 2026-10-20 LTS; OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS); OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing)`; JMeter 5.6.3.
- Imagens das aplicacoes: `{'customer-service': 'sha256:17124f5cf7436e76a8d7d02229ea757edf2803e3826410001e3dde5f14f006d6', 'customer-notification-service': 'sha256:1378a1601d3b1c4c996196671bfe9b2f0552bf9a91612d0d0ddf2632d3d8a640'}`.
- Aplicacoes: imagem T14, 1 GiB por servico, `-XX:MaxRAMPercentage=60.0 -XX:ActiveProcessorCount=2`; gerador: 512 MiB e limite de 1 CPU.
- Revisao HEAD: `b0bcaf9b28b606cbdabfa04f21d1e9587f23678c`; arvore com alteracoes: True.
- Parametros: `{'scenario': 'all', 'skip_build': True, 'threads': 4, 'duration': 15, 'warmup': 5, 'ramp': 3, 'customers': 100, 'creates': 40}`.
- Logs INFO e metricas habilitados; Prometheus + Management, intervalo alvo 1 s; overhead incluido.

## HTTP medido pelo JMeter

| Cenario | N | req/s | Erros | P50 ms | P95 ms | P99 ms | HTTP |
|---|---:|---:|---:|---:|---:|---:|---|
| A-list | 520 | 36.61 | 0.0% | 6 | 12 | 14 | {'200': 520} |
| B-search | 526 | 36.95 | 0.0% | 5 | 8 | 43 | {'200': 526} |
| C-score-fast | 508 | 35.99 | 0.0% | 8 | 16 | 27 | {'200': 508} |
| C-score-slow | 183 | 12.78 | 0.0% | 209 | 216 | 219 | {'200': 183} |
| C-score-timeout | 49 | 3.42 | 100.0% | 1008 | 1033 | 1105 | {'504': 49} |
| D-create | 40 | 30.72 | 0.0% | 6 | 22 | 31 | {'201': 40} |

Percentis empiricos por nearest-rank sobre todas as amostras medidas, incluindo erros. Warm-up excluido do JTL usado pelo dashboard. Throughput = amostras / janela entre inicio da primeira e termino da ultima. O gerador usa carga fechada e think time de 100 ms; os req/s oferecidos diminuem quando a dependencia demora. Nao e uma medicao de capacidade maxima ou de taxa de chegada constante.

## Sinais de causa e recursos

| Cenario | Score medio/tentativa ms | SQL medio ms | CPU API max observado | Heap API MiB max observado | Pool pendente max observado | HTTP busy max observado | GC API s | CPU gerador max observado |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A-list | — | 0.05 | 25.3% | 59.6 | 0.0 | 2.0 | 0.0350 | 13.3% |
| B-search | — | 0.08 | 19.1% | 65.2 | 0.0 | 2.0 | 0.1270 | 17.2% |
| C-score-fast | 4.66 | 0.12 | 72.9% | 71.2 | 0.0 | 5.0 | 0.0330 | 14.1% |
| C-score-slow | 205.49 | 0.02 | 8.5% | 72.0 | 0.0 | 5.0 | 0.0160 | 6.8% |
| C-score-timeout | 1004.18 | 0.18 | 4.8% | 71.4 | 0.0 | 6.0 | 0.0060 | 9.8% |
| D-create | — | 0.21 | 15.0% | 70.2 | 0.0 | 2.0 | 0.0120 | 7.8% |
| E-status-amqp | — | 0.00 | 0.0% | 46.8 | 0.0 | 2.0 | 0.0030 | indisponivel |

Os deltas de contadores usam scrapes nas bordas da medicao e podem incluir requisicoes em voo no limite, healthchecks e consultas de outbox. Nao substituem os percentis do cliente. O timer SQL tem resolucao de milissegundos e pode registrar zero em consultas muito curtas; — indica ausencia de amostras, nao latencia zero. Maximos sao apenas os observados nas amostras; intervalo alvo de 1 s inclui o tempo de coleta e pode ser maior. CPU da JVM e normalizada aos processadores disponiveis; CPU do gerador e a medida do Docker, com limite de um core. Scrapes e gerador compartilham a maquina com os servicos.

## Observacoes desta execucao

- O mesmo cliente/endpoint foi consultado com atrasos de 0, 200 e 3000 ms no provedor; contrato e resposta de sucesso permaneceram iguais.
- P95 rapido/lento: 16/216 ms; a media por tentativa externa foi registrada separadamente na tabela.
- A variacao de timeout teve 49 erros HTTP 504/SCORE_TIMEOUT em 49 amostras. Esses erros nao foram convertidos em sucesso. O timeout limita a espera do cliente, embora o WireMock ainda possa estar terminando respostas atrasadas.
- A-list: nao foi observada espera de pool nas amostras.
- B-search: nao foi observada espera de pool nas amostras.
- C-score-fast: nao foi observada espera de pool nas amostras.
- C-score-slow: nao foi observada espera de pool nas amostras.
- C-score-timeout: nao foi observada espera de pool nas amostras.
- D-create: nao foi observada espera de pool nas amostras.
- Criacao: 40 cadastros medidos e oito de warm-up. O verificador aguardou 2.381 s adicionais apos o termino do processo JMeter para atingir a contagem esperada; esse intervalo nao mede a latencia individual de evento.
- Lag persistido ocorrencia→notificacao dos cadastros medidos: P50/P95/P99 = 2014.40/3576.28/3667.43 ms; 40 eventos. Instantes comparados nos dois bancos, com containers no mesmo relogio do host Docker.
- E-status-amqp: nao foi observada espera de pool nas amostras.
- AMQP: 100 publicacoes confirmadas em 0.265 s; todas as inboxes/versoes foram confirmadas em 0.401 s desde o inicio. A taxa ponta a ponta (249.12 eventos/s) inclui publicacao e resolucao do polling; nao representa capacidade isolada do consumidor.
- A diferenca de latencia acompanha a espera externa configurada; nesta carga, os sinais de pool nao apontam espera por conexao como explicacao para o acrescimo de cerca de 200 ms. Isso nao demonstra capacidade maxima nem ausencia de outras causas em cargas maiores.
- O lag de notificacao e maior que a resposta do POST. O relay esta configurado com lote 10 e pausa de 1000 ms apos cada ciclo; a rajada produz eventos mais rapidamente que essa cadencia nominal. Essa configuracao e uma hipotese concreta para o backlog/lag observado. O proximo experimento deve variar lote/poll mantendo a carga e conferir confirms, pool e consumo antes de qualquer tuning.

## Banco, backlog e limites

O arquivo query-plan.json contem EXPLAIN (ANALYZE, BUFFERS) de um SELECT com filtros/ordenacao equivalentes a busca. A base foi ANALYZE apos o seed. A massa pequena nao justifica indices novos nem permite extrapolar comportamento para milhoes de clientes.

Os JSONs de metricas preservam sinais das duas JVMs, pool, GC, SQL, Score, outbox e ready/unacked/total das dez filas. As metricas de criacao incluem warm-up e confirmacao da entrega assincrona; o JTL exclui os oito cadastros de warm-up. A espera de drenagem e medida antes da geracao HTML. O Management atualiza estatisticas periodicamente: um maximo observado de zero nao prova que nunca houve backlog. A entrega e comprovada adicionalmente por contagens persistidas/inbox, sem inferir consumo a partir apenas do HTTP.

Uma execucao de referencia por condicao, sem agregar rodadas de desenvolvimento; ordem fixa, massa pequena, warm-up curto e infraestrutura de no unico. Percentis P99 com poucas amostras sao instaveis. Nao houve busca pelo ponto de saturacao nem validacao de SLA. Nenhuma otimizacao foi aplicada sem evidencia; aumentar carga e repetir condicoes e o proximo experimento, dentro de limites autorizados.

## Reproducao e artefatos

Com a .venv do projeto e Docker ativos, execute `infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario all --threads 4 --duration 15 --warmup 5 --ramp 3 --customers 100 --creates 40`.

Os JTL/HTML/metricas/planos detalhados desta execucao estao no diretorio privado indicado pelo executor, ignorado pelo Git. Os planos .jmx e o dataset exemplo estao versionados. O executor cria e limpa somente seu Compose descartavel; nao recebe URL de ambiente externo.


## Plano de consulta observado

Plano: Limit → Sort → Seq Scan. Execution Time: 0.102 ms; Planning Time: 0.297 ms; Shared Hit Blocks no no superior: 6.