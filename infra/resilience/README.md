# T11 — Runbook de falhas distribuídas

O verificador cria um projeto Compose aleatório `datum-resilience-<id>`, portas livres, volumes e credenciais de teste. Constrói as duas imagens a partir do checkout e usa os provisionamentos reais de PostgreSQL, Keycloak, RabbitMQ e WireMock. Não aceita URL, banco, fila ou projeto externo como destino. Os serviços locais nas portas usuais podem continuar ativos.

## Executar um cenário por vez

Na raiz do projeto, com Docker ativo:

```sh
python3 -m venv infra/rabbitmq/.venv
infra/rabbitmq/.venv/bin/python -m pip install -r infra/rabbitmq/requirements-test.txt
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py --scenario score
```

A execução inicial constrói `datum/customer-service:t11` e `datum/customer-notification-service:t11`. O build executa os testes rápidos de cada serviço; testes de integração devem ser executados separadamente por `./mvnw -Pintegration clean verify`. Para reutilizar as imagens da **mesma revisão** nos próximos cenários:

```sh
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py --skip-build --scenario broker
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py --skip-build --scenario database
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py --skip-build --scenario notification
```

Cada comando cria um ambiente novo e o remove ao terminar, inclusive quando uma verificação falha. Não é necessário parar os JARs locais nem executar `docker compose down -v` no projeto principal. Os comandos de falha e limpeza são internos ao ambiente descartável.

## Suíte completa

```sh
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py
```

Sem `--scenario`, executa todos os cenários da tabela, na ordem. Os três TTLs não são reduzidos: a exaustão consome aproximadamente 155 segundos, além do tempo de operações/recuperação. O teste de DLQ também respeita o intervalo interno do dead-letter worker, consultado no broker (normalmente 180 segundos), sem encurtá-lo. Reserve cerca de 8–12 minutos para o ciclo completo, conforme cache do Docker e disponibilidade da máquina. Durante esperas longas, imprime progresso sem payloads, senhas ou tokens. O código de saída deve ser zero e o final indicar verificações aprovadas e limpeza do projeto descartável.

`--scenario` aceita um ou mais nomes, por exemplo `--scenario ack saturation`. `--skip-build` só deve ser usado quando as imagens T11 correspondem ao código que se quer avaliar. Nenhuma imagem é enviada a um registry.

| Cenário | Injeção no ambiente descartável | Resultado conferido |
|---|---|---|
| `score` | Para WireMock e depois usa a fixture de atraso de 3000 ms | Score saudável 200; falha/timeout sanitizado e limitado; leitura de cliente disponível; retorno do provedor recupera a consulta |
| `broker` | Para RabbitMQ, cadastra cliente e reinicia a API com pendência | Cadastro 201 e health UP; outbox PENDING; retorno do broker entrega o mesmo UUID, instante e bytes, com uma notificação |
| `database` | Para PostgreSQL e depois inicia o mesmo container/volume | Health 503/DOWN e POST 500/INTERNAL_ERROR em espera limitada; sem cadastro parcial; pools e aplicações recuperam o processamento |
| `notification` | Desabilita temporariamente novas conexões de notification_app e encerra suas conexões existentes | Cadastro continua 201, outbox PUBLISHED, rollback de notificação/inbox e recuperação por retry após restaurar o acesso |
| `retries` | Constraints de teste impedem a inserção dos eventos selecionados nas inboxes dos dois consumidores | Rodadas 1/2/3 com TTLs reais; DLQs preservam bytes/identidade; nenhum efeito parcial nem quarta rodada; corrigir o destino não dispara replay automático |
| `ack` | Com aplicações paradas, entrega por AMQP, confirma cópia no retry e fecha a conexão sem ACK da origem | Original e cópia são processados pelas aplicações reais; um efeito por consumidor e DUPLICATE para a reentrega |
| `saturation` | Pausa notificações, reduz temporariamente a fila principal para uma mensagem e publica até rejeição efetiva | NACK conserva evento PENDING no outbox; o primeiro não é descartado; restaurar capacidade permite entrega de todos. Quorum pode admitir algumas mensagens antes de propagar overflow ao publicador |
| `dlq` | Remove temporariamente o binding da DLQ de status | Mensagem definitivamente rejeitada chega intacta após restaurar a rota, comprovando retenção dead-letter at-least-once quando o destino não recebe |
| `delivery-limit` | Fecha conexões com mensagens sem ACK repetidamente, com aplicações paradas | As duas filas terminam em suas DLQs com motivo delivery_limit; não há ciclo ilimitado na principal |

O cenário `ack` reproduz a janela de protocolo de confirm/ACK por um cliente AMQP com a credencial do consumidor; não introduz um botão de crash ou bypass no código de produção. Os testes Maven também verificam a janela commit/ACK, rollback, concorrência, return de retry, conflitos permanentes e repetição de rejeição persistida.

O script faz consultas administrativas e alterações de teste apenas nos bancos/filas que criou. Constraints, NOLOGIN, redução de capacidade, leitura e purge de mensagens aparecem somente nessa suíte descartável. Não copie esses comandos para os bancos/filas usados na demonstração local. As mensagens de DLQ produzidas no ambiente local continuam em quarentena para análise, sem consumidor automático.

## Limites de banco implementados

Os valores abaixo são os padrões de cada aplicação. Use prefixo `CUSTOMER_DB_` no serviço de clientes e `NOTIFICATION_DB_` no de notificações. As variáveis estão em `.env.example`; precisam ser exportadas para Java/IDE ou passadas ao container.

| Sufixo da variável | Padrão | Camada e unidade |
|---|---|---|
| `POOL_MAX_SIZE` | 10 | Máximo de conexões físicas por instância |
| `POOL_MIN_IDLE` | 2 | Conexões ociosas mínimas do pool |
| `POOL_CONNECTION_TIMEOUT_MS` | 2000 | Espera para obter conexão do Hikari, em ms |
| `POOL_VALIDATION_TIMEOUT_MS` | 1000 | Validação de conexão pelo Hikari, em ms |
| `CONNECT_TIMEOUT_SECONDS` | 2 | Abertura de socket pelo driver PostgreSQL, em segundos |
| `SOCKET_TIMEOUT_SECONDS` | 15 | Espera por leitura de socket; driver encerra conexão ao exceder, em segundos |
| `STATEMENT_TIMEOUT_MS` | 5000 | Limite de execução de cada comando PostgreSQL, em ms |
| `LOCK_TIMEOUT_MS` | 2000 | Limite de espera por lock no servidor, em ms |
| `IDLE_TRANSACTION_TIMEOUT_MS` | 15000 | Encerra sessão parada dentro de transação, em ms |
| `TRANSACTION_TIMEOUT_SECONDS` | 10 | Timeout padrão do gerenciador Spring, em segundos |

As opções PostgreSQL são enviadas na criação das conexões físicas da própria aplicação. Não alteram `postgresql.conf`, privilégios, defaults dos outros usuários nem a conexão do DBeaver. `idle_in_transaction_session_timeout` não é o tempo de uma conexão ociosa fora de transação; o outbox mantém sua reserva persistida sem segurar transação enquanto aguarda o broker.

O timeout padrão Spring vale quando a operação não declara outro explicitamente. Os dois serviços de processamento de eventos já declaram 10 segundos. Os limites por comando, transação, pool e socket são distintos e não constituem um único prazo absoluto para toda requisição HTTP. Tempo de DNS, múltiplos comandos e commit também influenciam a duração total.

Mantenha espera de lock inferior à de comando e validação de pool inferior à espera por conexão; minimum-idle não deve superar maximum-pool-size. A migração Flyway usa o datasource da aplicação e também recebe os limites de sessão: uma migração maior pode exigir ajuste consciente desses valores. Os defaults são dimensionados para esta demonstração e deverão ser medidos na T12/T15 antes de tuning de carga.

Os testes `DatabaseResilienceIT` de cada módulo usam limites menores somente no banco descartável. Verificam configurações efetivas em conexões físicas, timeout de pool com recuperação, SQLSTATE 57014 para comando cancelado e 55P03 para lock, rollback dos dados e reutilização do pool.

## Política de falhas preservada

| Componente | Comportamento |
|---|---|
| Score | Connect 500 ms, leitura 1000 ms, até duas tentativas; pausa 100 ms apenas nas falhas transitórias aprovadas. Sem retry para 404, corpo inválido ou timeout |
| Outbox | Confirm obrigatório; falha/ambiguidade conserva PENDING. Backoff persistido 1, 2, 4, 8, 16 e depois 30 s; lease e fencing preservados |
| Consumidores | Prefetch 10 e um consumidor por instância; transação antes de ACK; retries 5/30/120 s; falhas permanentes diretamente à DLQ |
| Transferência de retry | Confirm sem return antes de ACK da origem. Transferência incerta conserva origem sem ACK e recupera canal após pausa padrão de 5 s |
| Broker | Quorum duráveis, overflow reject-publish, DLX at-least-once e delivery-limit 5 nas principais |

No Score, conexão recusada/falha imediata produz 503/SCORE_UNAVAILABLE; um timeout de conexão ou leitura produz 504/SCORE_TIMEOUT. Parar um container pode resultar em conexão recusada ou espera até o connect timeout, conforme a rede/cache DNS; o teste aceita somente o par status/código coerente, com espera limitada. Falha de banco continua 500/INTERNAL_ERROR no CRUD; não se altera o contrato para 503 nesta etapa.

Os intervalos aprovados na T06 permanecem fixos. Jitter e circuit breaker são escolhas opcionais que exigem avaliar carga, erros e sincronização de réplicas; não foram acrescentados sem essa evidência. Não existe retry automático de POST/PUT/DELETE HTTP: após uma resposta ambígua, consulte o estado antes de repetir a intenção. Perda de confirmação de commit não equivale a prova de rollback.

## Diagnóstico no ambiente local

Use os mesmos sinais sem injetar falhas no seu banco/fila:

- API indisponível: confira processo/porta e `/actuator/health`. Health considera banco próprio; broker fora não impede cadastro com outbox pendente.
- Outbox pendente: consulte state, attempt_count, next_attempt_at, locked_until e last_error_code. Aguarde o backoff/reserva após restaurar a infraestrutura; não gere outro UUID nem altere PUBLISHED manualmente.
- Retry: observe a fila/rodada; mensagens expiram e retornam somente ao consumidor de origem. Prefetch pode manter entregas Unacked enquanto uma operação termina.
- DLQ: investigue motivo, resultado na inbox e identidade do evento. Rejeição definitiva persistida continua rejeitada após replay; correção de intenção exige novo eventId. Falha transitória sem decisão persistida pode ser recuperada por replay explícito após análise.
- Reinício: preserve volumes e histórico de deduplicação. Limpar inbox/notification_records ou trocar eventId remove a proteção contra repetição.

Esta suíte usa PostgreSQL e RabbitMQ locais de nó único, em containers. Ela verifica falhas e conservação de dados nesses limites; não simula quorum entre três nós, partição entre datacenters, failover PostgreSQL ou um crash em cada instrução possível. Métricas estruturadas e correlação são o escopo da T12.

Referências: [opções do pgJDBC](https://jdbc.postgresql.org/documentation/use/), [timeouts por sessão no PostgreSQL 17](https://www.postgresql.org/docs/17/runtime-config-client.html), [quorum queues e dead-letter at-least-once](https://www.rabbitmq.com/docs/quorum-queues).
