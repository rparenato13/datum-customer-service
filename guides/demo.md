# Roteiro de demonstração e defesa

[README e exemplos completos](../README.md) · [Arquitetura](../architecture/README.md) · [ADRs](../architecture/decisions/README.md)

Execute em sua demonstração local com dados sintéticos. O roteiro cria/altera registros e mantém evidências em bancos/filas; não limpa os volumes. Falhas de infraestrutura são demonstradas pelos verificadores descartáveis. Use um passo por vez e confira o resultado antes de continuar.

Os comandos partem da raiz do repositório, em macOS/Linux ou WSL. Use as portas efetivas do seu .env. Não compartilhe .env, JWTs ou saídas privadas brutas. A demonstração pode durar mais que os cinco minutos do access token: renove TOKEN_USER/TOKEN_ADMIN ao expirar.

## 1. Mostrar fronteiras e ambiente

Explique o diagrama do README: clientes e status pertencem ao customer; notificação tem banco/consumidor próprio; WireMock simula integração externa.

```sh
docker compose config --quiet
docker compose up --build -d
docker compose ps -a
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8081/actuator/health
```

Esperado: seis healthy, rabbitmq-init Exited (0), 200/UP sem detalhes. Abra [Swagger](http://localhost:8080/swagger-ui/index.html) e [OpenAPI](http://localhost:8080/v3/api-docs). Não rode config sem --quiet em tela compartilhada: sua saída pode conter credenciais.

## 2. Demonstrar identidade e permissões

Obtenha tokens por Code + PKCE no Postman com api-user e api-admin, conforme o README, e preencha TOKEN_USER/TOKEN_ADMIN por prompt oculto. Defina BASE_URL e confira:

```sh
export BASE_URL=http://localhost:8080
curl -i "$BASE_URL/customers"
curl -i -X POST "$BASE_URL/customers" \
  -H "Authorization: Bearer $TOKEN_USER" -H 'Content-Type: application/json' -d '{}'
curl -i -X POST "$BASE_URL/customers" \
  -H "Authorization: Bearer $TOKEN_ADMIN" -H 'Content-Type: application/json' -d '{}'
```

Esperado, nessa ordem: 401, 403 e 400. USER é barrado antes do processamento; ADMIN alcança validação, mas payload inválido não grava cliente/outbox. Mostre os codes padronizados, sem expor valores de tokens.

## 3. Cadastro, consultas e Score

Execute o POST sintético do README com CPF 16899535009 e o header de correlação. Em ambiente já usado, CPF duplicado retorna 409 mesmo após exclusão; use outro CPF válido para uma nova criação. A fixture Score do exemplo exige exatamente o CPF indicado, portanto prefira um cadastro não excluído já existente com esse CPF ou a jornada automatizada descartável se ele estiver reservado por excluído.

Copie o ID real do Location/resposta, sem assumir ID 1:

```sh
export CUSTOMER_ID="$(python3 -c 'print(int(input("ID retornado no POST: ")))')"
curl -i "$BASE_URL/customers/$CUSTOMER_ID" -H "Authorization: Bearer $TOKEN_USER"
curl -i "$BASE_URL/customers?page=0&size=1&status=ACTIVE" -H "Authorization: Bearer $TOKEN_USER"
curl -i --get "$BASE_URL/customers/search" \
  -H "Authorization: Bearer $TOKEN_USER" --data-urlencode 'name=ANA'
curl -i "$BASE_URL/customers/$CUSTOMER_ID/score" -H "Authorization: Bearer $TOKEN_USER"
```

Esperado: 200 nas consultas; cadastro recém-criado em version 0; Score 750/LOW_RISK na fixture indicada. Repita o mesmo POST para 409/CPF_ALREADY_REGISTERED, sem novo evento. A paginação em base existente pode conter outros registros; não deduza sua contagem a partir de uma base nova.

## 4. Conferir publicação e notificação

Abra DBeaver no database customer_db como customer_app e execute somente SELECT:

```sql
SELECT event_id, customer_id, correlation_id, state, attempt_count, last_error_code
FROM customer_outbox ORDER BY occurred_at DESC LIMIT 20;
```

Identifique o evento do CUSTOMER_ID. Aguarde PUBLISHED: o relay pode precisar de ciclos e backoff; não equivale a consumo concluído. Copie seu event_id.

Em outra conexão, database notification_db como notification_app:

```sql
SELECT notification_id, event_id, customer_id, kind, registered_at
FROM notification_records ORDER BY registered_at DESC LIMIT 20;

SELECT event_id, customer_id, outcome, processed_at
FROM notification_event_inbox ORDER BY processed_at DESC LIMIT 20;
```

Esperado: evento correspondente, kind CUSTOMER_CREATED_SIMULATION e inbox NOTIFICATION_REGISTERED. No [RabbitMQ UI](http://localhost:15672/), vhost datum, confira principal sem pendência depois do ACK; estatísticas podem demorar a atualizar. Não use purge/Get messages para provar consumo.

Prepare a .venv conforme o README. Para repetir a criação sem gerar outro evento:

```sh
export CREATED_EVENT_ID="$(python3 -c 'import uuid; print(uuid.UUID(input("event_id PUBLISHED: ")))')"
infra/rabbitmq/.venv/bin/python infra/rabbitmq/replay_created.py --event-id "$CREATED_EVENT_ID"
```

Esperado: confirm; depois log DUPLICATE. Reexecute os SELECTs: notification_id e registered_at iguais, sem segundo efeito. Esse script consulta o outbox como produtor e republica os mesmos bytes; não lê o banco de notificações nem retira mensagens da DLQ.

## 5. Atualizar por REST e por evento

Execute o PUT do README, preservando o CPF real do cliente escolhido. Para o cadastro sintético recém-criado, o PUT para INACTIVE retorna 200/version 1. Consulte novamente e copie a versão real antes de publicar:

```sh
curl -i "$BASE_URL/customers/$CUSTOMER_ID" -H "Authorization: Bearer $TOKEN_USER"
export EXPECTED_VERSION="$(python3 -c 'print(int(input("version atual do GET: ")))')"
infra/rabbitmq/.venv/bin/python infra/rabbitmq/publish_status.py \
  --customer-id "$CUSTOMER_ID" --expected-version "$EXPECTED_VERSION" --status ACTIVE
```

O script imprime o eventId/caminho do arquivo. Consulte o GET até refletir a mudança; em customer_db:

```sql
SELECT id, status, version, updated_at, deleted_at
FROM customers ORDER BY id DESC LIMIT 20;

SELECT event_id, customer_id, outcome, reason_code, processed_at
FROM customer_event_inbox ORDER BY processed_at DESC LIMIT 20;
```

Esperado: ACTIVE, versão anterior + 1, inbox APPLIED. Broker confirm sozinho não garante esse resultado. Salve version/updatedAt e reenvie o mesmo arquivo, usando o ID real impresso:

```sh
export STATUS_EVENT_ID="$(python3 -c 'import uuid; print(uuid.UUID(input("eventId publicado: ")))')"
infra/rabbitmq/.venv/bin/python infra/rabbitmq/publish_status.py \
  --file ".local/rabbitmq/status-events/$STATUS_EVENT_ID.json"
```

Esperado: DUPLICATE nos logs, sem nova versão/updatedAt. Agora publique uma intenção nova com a versão antiga, preservada em EXPECTED_VERSION:

```sh
infra/rabbitmq/.venv/bin/python infra/rabbitmq/publish_status.py \
  --customer-id "$CUSTOMER_ID" --expected-version "$EXPECTED_VERSION" --status INACTIVE
```

Esperado: inbox REJECTED/VERSION_CONFLICT para o novo eventId, cliente inalterado e mensagem na fila customer-service.status-change.v1.dlq. A mensagem permanece em quarentena; o roteiro não a remove. Não confunda esse evento novo com replay do anterior.

## 6. Exclusão lógica

Execute DELETE do README com ADMIN: 204. GET e segundo DELETE: 404. As listagens omitem o cliente; a linha continua em customers com deleted_at, estado de negócio preservado e CPF reservado. Tente o mesmo POST: 409. Não há novo CUSTOMER_CREATED por PUT/status/DELETE.

## 7. Correlacionar logs e métricas

```sh
docker compose logs --tail=100 customer-service customer-notification-service
```

Procure o correlationId devolvido no POST e o eventId do outbox: http.request, outbox.record/publish, message.consume e DUPLICATE devem ser explicáveis pelos passos anteriores. Logs não devem expor CPF, e-mail, JWT, senha, corpo ou SQL com parâmetros.

Para métricas, configure APP_PROFILES=observability no .env e aplique docker compose up -d. Use token ADMIN renovado e senha de monitoramento no prompt:

```sh
curl -i http://localhost:8080/actuator/prometheus
curl -i http://localhost:8080/actuator/prometheus -H "Authorization: Bearer $TOKEN_ADMIN"
curl -i --user notification_monitor http://localhost:8081/actuator/prometheus
python3 infra/observability/broker_metrics.py
```

Esperado: 401 sem token, 200 autenticado. Mostre HTTP/Score, Hikari, JVM/GC, backlog/idade e filas Ready/Unacked/DLQ. O snapshot de outbox tem intervalo e sinal de coleta; números antigos/NaN não provam ausência de pendência. [Interpretação e PromQL](../infra/observability/README.md).

## 8. Reproduzir uma falha sem alterar a demonstração

Com a .venv e Docker, execute **um cenário por vez**:

```sh
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py --scenario broker
```

O teste cria seu próprio Compose/portas/volumes/credenciais. Prova cadastro 201 com broker indisponível, outbox PENDING, reinício com mesmo UUID, recuperação e uma notificação; confirma limpeza somente desse ambiente. Não para o broker do projeto datum.

Depois, se necessário, `--scenario score --skip-build` demonstra espera limitada/504 e leitura de cadastro independente. Use --skip-build somente se as imagens da mesma revisão já foram construídas. [Outros cenários e duração](../infra/resilience/README.md): retries reais levam cerca de 155 s; destino DLQ pode exigir 180 s de recuperação. Não simule quedas removendo permissões/constraints/filas dos seus dados locais.

Para uma prova completa reproduzível, sem depender dos CPFs já usados nesta demonstração:

```sh
infra/rabbitmq/.venv/bin/python infra/compose/verify.py
```

Esperado: 57 verificações aprovadas e limpeza confirmada do projeto descartável, incluindo down/up preservando volumes durante o teste. Verificador e demo manual se complementam; não representam ensaio de HA produtiva.

## 9. Explicar performance e limites

Abra o [relatório de referência](../performance/performance-report.md) e os [planos JMeter](../performance/jmeter/README.md). Compare Score rápido/lento/timeout, SQL/pool/CPU e lag de eventos. Caso queira repetir, faça uma execução explícita em ambiente descartável, sem carga sobre os dados da demonstração.

| Pergunta de defesa | Resposta e limite |
|---|---|
| Por que dois serviços? | Clientes é dono dos dados; notificação é capacidade independente. Score é integração direta, ADRs 001–005. |
| Por que outbox? | Evita dual write não atômico cliente/broker. Há publicação duplicada possível, absorvida por inbox. |
| O que 201 / confirm / ACK provam? | Commit do cadastro / aceitação pelo broker / conclusão reconhecida da entrega, respectivamente. Falhas entre passos são cobertas por reentrega e deduplicação. |
| Como impedir status atrasado? | expectedVersion; rejeição explícita, não ordenação global nem last-write-wins por relógio. |
| Por que UP com broker fora? | Banco próprio responde; cadastro pode persistir evento pendente. Backlog e broker têm sinais separados. |
| Onde está o gargalo? | Na carga medida, latência Score acompanha atraso externo; pool sem espera observada. Isso não prova capacidade máxima. |
| POST rápido prova notificação rápida? | Não; lag persistido é medido separadamente. Lote/poll do relay é hipótese de próximo experimento. |
| O que falta para produção? | TLS/segredos, IdP produtivo, HA, backup/restore, retenção, operação de DLQ, SLOs/alertas e carga representativa. Não há envio de e-mail, tracing ou agente Dynatrace. |

Ao terminar: `unset TOKEN_ADMIN TOKEN_USER`. `docker compose down` preserva evidências; `down -v` só deve ser usado se desejar descartar todos os dados do projeto.
