# Observabilidade e diagnóstico — T12

Os dois serviços emitem JSON, com `timestamp` UTC, `level`, `service` e `environment`. Conclusões HTTP, consultas ao Score, publicação e consumo incluem `correlationId`, `operation`, `outcome` e `durationMs`. O HTTP inclui método, rota parametrizada, status, `dbDurationMs` e `dbQueryCount`. O Score acrescenta dependência, padrão da URL, tentativa, status HTTP e classificação de timeout. Os consumidores registram `eventId`, tipo, fila, rodada, ACK, duplicata e motivo de rejeição. IDs são campos de diagnóstico, nunca labels de métricas.

Não há tracing distribuído instrumentado nesta etapa: `correlationId` permite relacionar os eventos; `traceId` e `spanId` não são inventados. A correlação não substitui autenticação nem deduplicação.

## 1. Habilitar localmente

Faça o build com Java 21 e as dependências locais preparadas conforme o README principal. Finalize as versões anteriores dos serviços com `Ctrl+C`. Nos dois terminais, mantenha as variáveis de conexão já utilizadas e acrescente o perfil:

```sh
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=observability
```

```sh
java -jar customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=observability
```

`APP_ENVIRONMENT` define o ambiente dos logs e métricas; default `local`. `DB_SLOW_QUERY_THRESHOLD_MS` define o limiar de query lenta; default 250 ms. Esses nomes constam no `.env.example`; o processo Java recebe somente variáveis exportadas no terminal. Em Docker, passe `--env SPRING_PROFILES_ACTIVE=observability --env APP_ENVIRONMENT` e, na notificação, `--env NOTIFICATION_MANAGEMENT_PASSWORD` aos comandos do README.

A V4 do `customer-service` acrescenta `customer_outbox.correlation_id` e um índice parcial para coletar o backlog sem percorrer o histórico publicado. Eventos existentes recebem `correlation_id=event_id`; payload, instante, estado, tentativas e IDs permanecem preservados. O serviço de notificações mantém sua migration V1. Não apague volumes nem altere migrations anteriores para habilitar a etapa.

Sem o perfil, somente health fica exposto. Com o perfil, ficam disponíveis health, metrics e prometheus; não são expostos env, configprops, heapdump, loggers nem beans. A autenticação continua ativa em todos os perfis.

## 2. Saúde e controle de acesso

```sh
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8081/actuator/health
curl -i http://localhost:8080/actuator/prometheus
curl -i http://localhost:8081/actuator/prometheus
```

Espere 200/UP nos dois primeiros e 401 nos dois últimos. No Postman, use o token ADMIN já obtido pelo fluxo PKCE para GET `/actuator/metrics` e `/actuator/prometheus` do customer-service: 200. Com USER: 403. POST não é autorizado. Os tokens não aparecem nos logs ou em labels.

O notification-service possui uma conta de monitoramento independente: `notification_monitor`, senha `NOTIFICATION_MANAGEMENT_PASSWORD` (default de demonstração `notification_monitor_local_only`). Essa conta só lê os três caminhos de métricas, incluindo `/actuator/metrics/{name}`; health permanece público. Não concede acesso ao banco, RabbitMQ nem à API de clientes. Configure a senha antes de iniciar o processo para substituir o default local. No curl abaixo, digite-a no prompt, sem colocá-la no histórico:

```sh
curl -i --user notification_monitor http://localhost:8081/actuator/metrics
```

Espere 200 e ausência de `Set-Cookie`. Senha incorreta retorna 401. Os exemplos pressupõem uso local; um coletor remoto precisa de transporte HTTPS e armazenamento apropriado das credenciais. Não exponha credenciais administrativas do RabbitMQ aos processos das aplicações.

## 3. Rastrear uma consulta e um evento

No Postman, acrescente `X-Correlation-Id` com um UUID v4 minúsculo, por exemplo `8cda6187-f732-4ae3-966d-c9654b01c089`, a uma consulta autenticada de Score. O header é devolvido na resposta e propagado na chamada ao provedor. Ausência, duplicidade ou formato inválido geram um novo UUID; o valor arbitrário recebido não é registrado. Erros padronizados também incluem a referência em `correlationId`.

Procure nos logs o mesmo ID nas operações `http.request` e `score.request`. Compare duração total, tempo/quantidade de queries, tentativas e duração externa. A fixture lenta retorna `outcome=timeout`, `timeout=true` e HTTP 504 em espera limitada. Um 503 HTTP do provedor resulta em `outcome=unavailable`, sem confundir seu status com um timeout do cliente.

Em um cadastro ADMIN com esse header, o log `outbox.record` só ocorre depois do commit. Consulte no DBeaver, em `customer_db`, sem incluir payloads nos relatórios:

```sql
SELECT event_id, correlation_id, state, attempt_count, last_error_code
FROM customer_outbox
ORDER BY occurred_at DESC
LIMIT 10;
```

A correlação segue em `x-correlation-id` até `outbox.publish` e `message.consume` na notificação. `message_id` e o campo AMQP `correlation_id` continuam iguais a `eventId`; o JSON v1 não recebe novos campos. O identificador HTTP é técnico, não participa do fingerprint da inbox. Retry e replay preservam a correlação, e uma duplicata gera `duplicate=true` sem novo efeito. Uma mensagem antiga sem o novo header usa `eventId` como referência. Em rollout separado, atualize os consumidores antes do produtor que envia o header novo.

O MDC é restaurado ao sair da requisição/entrega, inclusive após falha de processamento ou ACK incerto. Logs não incluem CPF, e-mail, JWT, senha, corpo, SQL ou parâmetros. Stack traces ficam suprimidos no JSON para impedir exposição de causas JDBC/HTTP; a classificação segura da falha permanece disponível.

## 4. Métricas e interpretação

No Postman, use ADMIN para GET `/actuator/metrics/{name}`. Na notificação, use a conta de monitoramento. Métricas aparecem após a primeira operação correspondente.

| Sinal | Nome no Actuator | Interpretação |
|---|---|---|
| HTTP | `datum.http.request.duration`, `http.server.requests` | Latência, volume e status por método/rota |
| Score | `datum.score.client.duration` | Uma amostra por tentativa, com outcome, tentativa e classe de status |
| Score total | `datum.score.request.duration` | Uma amostra por consulta, incluindo retries e backoff |
| SQL | `datum.db.query.duration` | Tempo de execução por categoria fixa de statement; nenhum texto SQL |
| Pool | `hikaricp.connections.active`, `idle`, `pending`, `acquire`, `timeout` | Pool ocupado, espera por conexão e esgotamento |
| JPA | `hibernate.sessions.open`, `hibernate.transactions` | Atividade agregada do Hibernate no customer-service |
| Publicação | `datum.outbox.publish.duration` | Resultado do publisher confirm, inclusive timeout/return/nack |
| Backlog | `datum.outbox.pending`, `blocked`, `oldest.pending.age` | Pendências e idade em segundos, sem IDs |
| Qualidade da coleta | `datum.outbox.collection.up`, `collection.age` | 1 indica último sucesso; idade indica há quanto tempo houve atualização |
| Consumo | `datum.messaging.process.duration` | Duração e resultado por consumidor, rodada e ACK |
| Transferência de retry | `datum.messaging.retry.transfer.duration` | Custo e resultado da publicação confirmada do retry |
| JVM e servidor | `jvm.*`, `process.*`, `system.*`, `tomcat.*` | Heap, GC, CPU, threads e ocupação do servidor |

O backlog é atualizado a cada dez segundos no perfil `observability`; scrapes leem um snapshot em memória. Antes da primeira coleta, valores são NaN. Uma falha conserva o último snapshot com `collection.up=0`; não interprete dados antigos como fila vazia. A coleta possui os limites de conexão/query da T11. `oldest.pending.age` é zero quando não há pendências, mesmo que existam eventos bloqueados.

As durações SQL não incluem espera por uma conexão nem o custo completo da transação. Compare-as com métricas Hikari e tempo total da operação. Queries lentas ou com erro geram `operation=db.query`, categoria de statement e classe segura da exceção. `dbDurationMs` e `dbQueryCount` agregam as queries na thread da operação; não são spans.

## 5. Ready, unacked, retries e DLQ

O snapshot abaixo faz apenas GET no Management local, usando em memória a credencial já configurada no Compose. Não publica, consome, confirma, remove mensagens nem altera políticas. As aplicações continuam usando suas contas restritas.

```sh
python3 infra/observability/broker_metrics.py
```

A saída Prometheus contém `datum_broker_collection_up` e 30 amostras: `datum_broker_messages_ready`, `unacked` e `total` para as dez filas. Os únicos labels são os dois consumidores e os cinco tipos fixos de fila (`main`, `retry1`, `retry2`, `retry3`, `dlq`). Filas desconhecidas não viram labels. Se uma fila ou estatística estiver ausente, o comando falha com `collection_up=0`, sem fabricar zeros. Aguarde o intervalo de estatísticas do Management logo após subir o broker. Os argumentos `-f`, `--env-file` e `-p` do Compose podem selecionar outro ambiente local próprio.

Esse comando gera um snapshot; não inicia um endpoint HTTP nem mantém histórico. O Management continua disponível para inspeção das filas. Acúmulo em main com unacked alto sugere consumidores ocupados; crescimento em retry/DLQ exige verificar outcomes e causas antes de qualquer replay.

## 6. Tendências e percentis opcionais

`/actuator/prometheus` usa o formato de exposição Prometheus. Timers customizados exportam buckets, count e sum; as unidades são segundos. Prometheus/Grafana não são necessários para executar a solução. Um coletor externo pode fazer scrape autenticado, com Bearer ADMIN renovado para o customer-service e Basic da conta de monitoramento para notificações. Os snapshots de broker podem ser armazenados por uma infraestrutura de coleta já existente; não são automaticamente coletados pelo endpoint das aplicações.

Exemplos de PromQL após configurar um coletor:

```promql
# P95 do Score por tentativa; para P99, substitua 0.95 por 0.99.
histogram_quantile(0.95, sum by (le, service) (rate(datum_score_client_duration_seconds_bucket[5m])))

# Throughput HTTP da rota de Score, sem IDs.
sum by (service) (rate(datum_http_request_duration_seconds_count{route="/customers/{id}/score"}[5m]))

# Falhas de timeout do Score por segundo.
sum by (service) (rate(datum_score_client_duration_seconds_count{outcome="timeout"}[5m]))

# Pool à espera de conexão.
hikaricp_connections_pending

# Backlog só deve ser interpretado com coleta saudável e recente.
datum_outbox_pending and on(service, environment) (datum_outbox_collection_up == 1)
```

Percentis dependem de volume e janela representativos; uma execução de demonstração não comprova capacidade. Histograms são limitados a 1 ms–30 s para os timers customizados, com bucket +Inf preservando observações maiores. Filtros de rota e outcomes têm cardinalidade limitada; CPF, e-mail, IDs, URLs reais e SQL não são tags.

Para investigar busca por nome/status, use uma base descartável representativa e `EXPLAIN (ANALYZE, BUFFERS)` de uma consulta SELECT equivalente, relacionando plano/leituras com `datum.db.query.duration` e as métricas de pool. Um plano com tabela quase vazia não justifica criar índices. A análise sob carga e o relatório de capacidade permanecem nas etapas de performance do planejamento.

Os logs de conclusão são INFO e podem afetar testes de carga. Ajuste os níveis dos loggers de observação por ambiente e registre essa configuração na medição; mantenha WARN para falhas/queries lentas. Não habilite SQL, bind parameters ou DEBUG HTTP como instrumento de performance. Um APM compatível pode receber instrumentação futura OpenTelemetry/Micrometer; nenhum agente Dynatrace está instalado por padrão.

## 7. Verificação reproduzível

Com Docker ativo e a `.venv` do RabbitMQ instalada:

```sh
./mvnw -Pintegration clean verify
python3 -m unittest discover -s infra/observability -p 'test_*.py'
infra/rabbitmq/.venv/bin/python contracts/events/verify.py
infra/rabbitmq/.venv/bin/python infra/observability/verify.py
```

O verificador T12 constrói imagens `:t12` e cria portas, volumes, credenciais e um projeto Compose aleatórios. Valida autenticação real, JSON UTC, correlação HTTP→Score e HTTP→outbox→notificação, replay, retry real com TTL, ausência de dados pessoais nos logs/labels, métricas JVM/pool/SQL e snapshot das dez filas. Remove somente os recursos que criou. `--skip-build` permite reutilizar imagens da mesma revisão. Credenciais e tokens não são impressos. Não utiliza os bancos/filas do ambiente de desenvolvimento.
