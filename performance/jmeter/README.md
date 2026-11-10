# Performance e diagnóstico — T15

A suíte usa Apache JMeter **5.6.3**, distribuição oficial verificada por SHA-512, em container Java 21. Toda carga é explícita e direcionada a um **Compose novo e descartável**. O Compose principal não inicia JMeter ou carga automaticamente. Os serviços Java continuam sem alterações de negócio nesta etapa.

## Executar por cenário

Requisitos: Docker/Compose ativos e a `.venv` já utilizada no projeto, com `infra/rabbitmq/requirements-test.txt`. O executor usa UID/GID Unix para escrever os artefatos montados; execute em macOS/Linux ou em um ambiente WSL compatível. Não requer instalação local de JMeter/Java.

```sh
infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario all
```

A primeira execução constrói `datum/jmeter:5.6.3` e as imagens T14 das aplicações. O instalador baixa o arquivo versionado da Apache e confere o [checksum oficial](https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.6.3.tgz.sha512) antes da extração. A imagem, iniciada sem plano, mostra apenas a versão.

Para selecionar uma etapa e reutilizar imagens da mesma revisão:

```sh
infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario list --skip-build
infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario search --skip-build
infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario score --skip-build
infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario create --skip-build
infra/rabbitmq/.venv/bin/python performance/jmeter/run.py --scenario events --skip-build
```

`score` executa as três condições rápida/lenta/timeout, permitindo validar a comparação antes de uma rodada completa. Os processos usam tokens reais ADMIN obtidos por Code + PKCE; nenhum token é passado em argumento de linha de comando ou gravado em properties/datasets. O token entra no container por variável de ambiente e permanece em memória. Não há grant password ou bypass de assinatura.

Os planos `.jmx` podem ser inspecionados no JMeter GUI. A execução de carga é non-GUI. O host dos planos é fixo `customer-service:8080`, na rede do ambiente criado pelo executor; não são planos para executar inadvertidamente contra localhost/produção. O CLI não aceita URL, broker, projeto ou banco externos.

## Carga e limites

| Parâmetro | Default | Intervalo aceito |
|---|---:|---:|
| `--threads` | 4 | 1–8 |
| `--duration` medida alvo por leitura | 15 s | 10–60 s |
| `--warmup` | 5 s | 3–15 s, pelo menos o ramp |
| `--ramp` | 3 s | 1–5 s |
| `--customers` seed | 100 | 20–500 |
| `--creates` POSTs medidos | 40 | 10–100 |

Todos os planos têm think time de 100 ms. A carga é fechada: cada thread aguarda sua resposta e o timer antes de solicitar novamente. O máximo de oito threads não representa oito CPUs nem taxa de chegada fixa. A redução de req/s com Score lento é consequência também do modelo de carga; não indica automaticamente saturação da API.

O gerador tem limite de 1 CPU e 512 MiB, heap de 128–256 MiB e `ActiveProcessorCount=1`. O executor fixa os defaults T14 de timeout/retry/pool/prefetch/batch no seu ambiente, evitando herdar ajustes desses parâmetros do terminal. Os serviços conservam os limites T14 (1 GiB por aplicação e `ActiveProcessorCount=2`), com perfil de observabilidade, logs INFO e coleta habilitados. CPU/RAM do host e Docker, versões, opções JVM, IDs das imagens, revisão Git, parâmetros e estado da árvore são registrados. O gerador, as aplicações e o coletor compartilham a máquina; isso limita extrapolações.

## Cenários e comprovação

| Cenário | Plano/execução | Resultado verificado |
|---|---|---|
| A — Listagem | [A-list.jmx](A-list.jmx), GET página 0/20 | HTTP 200, 20 elementos e total igual à massa inicial |
| B — Busca/filtro | [B-search.jmx](B-search.jmx), nome CLIENTE + ACTIVE | HTTP 200, página e total corretos |
| C — Score rápido | [C-score.jmx](C-score.jmx), atraso zero | HTTP 200, score 750/LOW_RISK |
| C — Score lento | Mesmo cliente, CPF, endpoint, corpo e plano; somente delay 200 ms | HTTP 200, latência externa medida |
| C — Timeout | Mesmo caso com delay 3000 ms, acima do timeout de leitura de 1000 ms | HTTP 504/SCORE_TIMEOUT, `success=false`; erro permanece no JTL |
| D — Criação | [D-create.jmx](D-create.jmx), oito registros de warm-up e dataset finito de POSTs medidos | HTTP 201, id/versão/status; CPF único; contagem final de outbox publicado e notificações |
| E — Status AMQP | Publicador Pika em `events_case`, sem JMeter/JMS plugin | Publisher confirms, inbox APPLIED, status/versão e métricas diretas do consumidor/broker |

O seed usa o mesmo endpoint de cadastro e aguarda publicação/notificação de todos os clientes antes de medir leituras. Um cliente possui a fixture de Score conhecida; os demais vêm do gerador [data.py](data.py). O [CSV exemplo](data/example.csv) contém somente dados sintéticos. Cada execução gera a massa real em seu diretório privado. Com defaults e `all`, são 148 clientes (100 iniciais + oito de warm-up + 40 medidos), 148 eventos de criação e até 100 eventos de status. Nunca se tenta reutilizar CPF para simular criação bem-sucedida.

No plano D, CSV compartilhado entre threads, `recycle=false` e `stopThread=true` garantem volume finito sem repetição ao atingir EOF. A coluna `phase` separa os oito cadastros de warm-up. Leituras fazem warm-up no mesmo processo JMeter; amostras anteriores ao corte são removidas do JTL medido antes da geração do HTML. O JTL bruto também é validado: erro inesperado durante warm-up não é ocultado. O primeiro processo não é descartado apenas para criar outro JVM frio durante a medição.

Na variação C, o executor muda o mapping do WireMock exclusivamente no seu ambiente, preservando o contrato. Timeout **não** é transformado em sucesso pela assertion. Um 401, status diferente do esperado, contrato inválido ou amostra ausente faz a execução falhar. A classificação de falha no JTL é uma lista técnica permitida, nunca um corpo arbitrário.

## Eventos, banco e sinais de causa

O cenário E publica diretamente com a conta restrita `status_publisher`, usando uma conexão e confirms. Cada solicitação tem ID novo, instante atual e expectedVersion 0 de um cliente do seed. A conclusão exige inbox APPLIED e `version=1/status=INACTIVE` para cada alvo. A taxa ponta a ponta inclui publicação e resolução do polling; não é uma estimativa isolada de capacidade do consumidor, nem uma inferência feita a partir de HTTP.

O cenário D confirma as quantidades em ambos os bancos e mede o lag entre `occurred_at` do outbox e `registered_at` da notificação, cruzados em memória por eventId. Esses instantes vêm de containers no mesmo host Docker. IDs não viram labels ou linhas do relatório. O tempo adicional de espera após encerrar o processo JMeter é separado desse lag; inclui observação/polling, não representa a latência individual de entrega. A confirmação ocorre antes da geração do dashboard.

Durante as medições, são coletados CPU/heap/GC das JVMs, Tomcat busy/max, Hikari active/idle/pending/aquisição/timeouts, SQL, Score, outbox, processamento e profundidade ready/unacked/total das dez filas. Os scrapes autenticados e Docker stats têm intervalo alvo de um segundo, acrescido do custo de coleta. Max observado não significa pico absoluto. O Management tem atualização periódica; uma fila observada vazia pode ter tido backlog entre amostras. Scrapes nas bordas podem incluir requisições em voo e tarefas de infraestrutura.

O banco recebe ANALYZE após o seed, e um `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` de SELECT com filtros/ordenação equivalentes à busca é salvo. A resposta HTTP paginada também executa sua contagem; o EXPLAIN não é medição completa desse endpoint. O timer JDBC tem resolução de milissegundos e pode arredondar queries curtas para zero. Uma tabela pequena ou um Seq Scan barato não justifica criar índices.

## Artefatos e relatório

A saída fica em `.local/performance/<uuid>/`, ignorada pelo Git, com diretório de execução 700:

- `summary.json` e `report.md`: parâmetros, ambiente, resultados e interpretação.
- Por cenário HTTP: `raw.jtl`, `measurement.jtl`, logs, `metrics.json` e `html/index.html`.
- `query-plan.json`: plano observado da busca.
- Por cenário AMQP: amostras e resumo de confirmação/aplicação.
- Em falhas de comandos: log privado; nenhum resultado parcial é aprovado como medição completa.

O HTML é gerado a partir do JTL **medido**, sem warm-up. O relatório Markdown calcula quantis por nearest-rank sobre todas as amostras, inclusive erros; alguns gráficos padrão JMeter filtram respostas malsucedidas. Throughput é N / janela entre primeiro início e último término. Defaults de APDEX do dashboard (500/1500 ms) são apenas configuração de visualização, não SLO acordado.

Request/response bodies e headers não são salvos; URLs ficam desabilitadas no JTL. Os outputs e logs das aplicações são conferidos contra os CPFs/e-mails sintéticos, senhas e tokens daquele teste. Os CSVs de entrada contêm dados sintéticos necessários ao POST e ficam no subdiretório privado `input`; não são respostas de clientes reais. Não publique diretórios brutos sem revisão.

O [relatório de referência versionado](../performance-report.md) contém resultados reais e limitações. A cópia `docs/performance-report.md` atende ao plano local e continua ignorada pelo Git. O executor gera relatórios novos em `.local` e não sobrescreve automaticamente o relatório de referência; revisar uma medição e atualizar a referência é uma etapa explícita.

O ambiente é removido ao final, incluindo bancos/filas de teste; os artefatos locais permanecem para análise. O executor não usa o Compose `datum` nem os dados da demonstração. Uma falha de Docker/encerramento abrupto deve ser investigada pelo projeto aleatório identificado na saída, sem remover recursos globais.

## Verificação e limites da conclusão

```sh
infra/rabbitmq/.venv/bin/python -m unittest discover -s performance/jmeter -p 'test_*.py'
```

São 17 testes de cálculo, classificação de erros, quantis, datasets, EOF, exclusão do warm-up, limites/recusa de destino externo e lag persistido. A rodada real verifica que os planos são executáveis e geram JTL/HTML, as asserções de contrato e os sinais esperados.

Esta carga curta serve para explicar mecanismos e formular o próximo experimento. Não busca o ponto máximo de saturação, não compara regiões/nós e não prova metas de latência. P99 com poucas amostras é instável; há ordem fixa e repetições limitadas. Não foram adicionados índices ou ajustados timeouts/pools/batch para produzir um resultado artificialmente favorável.

Referências: [JMeter non-GUI](https://jmeter.apache.org/usermanual/get-started.html), [CSV Data Set Config](https://jmeter.apache.org/usermanual/component_reference.html#CSV_Data_Set_Config), [dashboard HTML](https://jmeter.apache.org/usermanual/generating-dashboard.html).
