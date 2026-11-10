# Subida completa com Docker Compose — T14

O Compose sobe os seis serviços da solução e um job curto `rabbitmq-init`. Os Dockerfiles compilam e testam cada aplicação com Maven/Java 21 em um estágio de build e executam os JARs em imagens JRE, com usuário 10001. O job prepara as definições do RabbitMQ diretamente do contrato aprovado, em um volume nomeado. Não é necessário executar Python, Maven, Java ou `configure.py` no host para iniciar a solução.

## Quickstart

Requisitos: Docker ativo, BuildKit, Compose v2 ou posterior e acesso às imagens/dependências na primeira construção. A verificação desta etapa usou Compose 5.1.4. As portas abaixo precisam estar livres. Se estiver usando os JARs das etapas anteriores em 8080/8081, finalize-os com Ctrl+C ou altere `CUSTOMER_HTTP_PORT`/`NOTIFICATION_HTTP_PORT` no `.env`.

Somente em um clone novo, prepare o arquivo de demonstração:

```sh
cp .env.example .env
```

Se o `.env` já existe, preserve-o e suas credenciais. As novas variáveis possuem defaults no Compose; acrescente apenas os valores que quiser personalizar. As senhas do exemplo são exclusivas de demonstração local.

Na raiz do projeto:

```sh
docker compose up --build -d
```

Acompanhe a convergência:

```sh
docker compose ps -a
docker compose logs -f customer-service customer-notification-service
```

Os seis serviços de longa duração devem ficar `healthy`. O job `rabbitmq-init` termina com `Exited (0)`, resultado esperado. `up -d` devolve o terminal após iniciar as aplicações; o status de saúde pode levar mais alguns segundos para convergir. Ctrl+C encerra o acompanhamento de logs, não os containers.

```sh
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8081/actuator/health
```

Espere 200 com `{"status":"UP"}`. Swagger: [localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html). Obtenha tokens no Postman conforme o README principal; o fluxo continua Authorization Code + PKCE com os usuários `api-user` e `api-admin`.

## Serviços e portas

Todas as publicações usam `127.0.0.1`. Não é uma configuração para exposição pública.

| Serviço | Host padrão | Rede Compose | Finalidade |
|---|---|---|---|
| customer-service | `8080` | `customer-service:8080` | Clientes, Score, Swagger e Actuator |
| customer-notification-service | `8081` | `customer-notification-service:8081` | Actuator; negócio assíncrono |
| PostgreSQL | `15432` | `postgres:5432` | Dois bancos; acesso local pelo DBeaver |
| RabbitMQ AMQP | `15673` | `rabbitmq:5672` | Aplicações e publicador local de status |
| RabbitMQ Management | `15672` | `rabbitmq:15672` | Console e verificações administrativas |
| Keycloak | `18090` | `keycloak:8080` | Browser/Postman, discovery e JWKS |
| WireMock | `18082` | `wiremock:8080` | Provedor de Score simulado |
| rabbitmq-init | sem porta/rede | sem conexão de rede | Geração inicial de definitions |

O `.env.example` documenta as variáveis correspondentes. Para mudar somente a porta da API, altere `CUSTOMER_HTTP_PORT`; o processo no container continua em 8080, assim como seu healthcheck. A notificação possui `NOTIFICATION_HTTP_PORT`, independente. Não há `container_name` fixo: Compose qualifica os nomes pelo projeto e permite ambientes isolados quando suas portas também forem distintas.

## Ordem e saúde

1. `rabbitmq-init` gera quatro contas com hashes, quatro exchanges, dez filas, doze bindings e as políticas aprovadas no volume `rabbitmq_bootstrap`.
2. RabbitMQ inicia depois da conclusão bem-sucedida do job e importa `/etc/rabbitmq/bootstrap/definitions.json`, montado somente para leitura.
3. O customer-service aguarda PostgreSQL, RabbitMQ, Keycloak e WireMock saudáveis. A notificação aguarda PostgreSQL e RabbitMQ.
4. Cada aplicação aplica/valida suas migrations antes de ficar saudável. Seu healthcheck faz uma requisição HTTP local com Bash, sem instalar curl na imagem JRE.

O job usa Python 3.13.18 da imagem oficial e somente a biblioteca padrão; não tem rede nem Docker socket. O modelo e o manifesto são os mesmos usados pelas ferramentas das etapas anteriores. A escrita é atômica; uma nova execução com as mesmas credenciais preserva hashes e conteúdo. O arquivo contém hashes, nunca senhas abertas; não é gravado na pasta do projeto. A importação não purga mensagens nem remove filas existentes. As credenciais continuam segregadas, com vhost `datum` e ausência de `guest`.

Os consumidores e o relay ficam ativos ao iniciar e processam pendências elegíveis nas filas/outbox. `OUTBOX_PUBLISHER_ENABLED`, `STATUS_CONSUMER_ENABLED` e `NOTIFICATION_CONSUMER_ENABLED` permitem controlar cada processamento; DLQs continuam sem replay automático.

`configure.py` permanece como comando operacional opcional: executa esse mesmo job no volume. `configure.py --check` compara sem alterar o conteúdo. O verificador administrativo lê o arquivo do broker em memória e faz seus GETs de conferência. O antigo bind de `.local/rabbitmq/definitions.json` deixou de ser usado; arquivos antigos nessa pasta não são apagados automaticamente.

Health das aplicações confirma o banco próprio, sem detalhes internos. O broker continua fora desse health para preservar a possibilidade de cadastrar com outbox PENDING durante indisponibilidade posterior. As condições de `depends_on` controlam a subida inicial; durante a execução permanecem os timeouts, recuperação AMQP, outbox e inbox das etapas anteriores. `restart: unless-stopped` reinicia processos que terminam e recupera containers no retorno do daemon; um healthcheck negativo, por si só, não reinicia o processo.

Referências de funcionamento: [ordem de inicialização do Compose](https://docs.docker.com/compose/how-tos/startup-order/) e [importação de definitions no boot do RabbitMQ](https://www.rabbitmq.com/docs/definitions).

## URLs e issuer do Keycloak

A URL canônica permanece `http://localhost:${KEYCLOAK_PORT}/realms/datum`. Ela aparece no discovery e no `iss` dos tokens emitidos ao browser/host. O Compose configura a API para validar exatamente esse issuer e buscar as chaves pelo endereço interno `http://keycloak:8080/realms/datum/protocol/openid-connect/certs`. A porta publicada do Keycloak pode ser alterada sem editar outra URL do Compose.

As variáveis de URL do `.env` destinadas a Java/IDE no host continuam documentadas para esse uso. Dentro do Compose, DB usa `postgres:5432`, AMQP usa `rabbitmq:5672` e Score usa `wiremock:8080`; o Compose define esses endereços explicitamente. Uma URL `localhost` exportada para um JAR não é reutilizada como endereço de outro container. A API recebe somente suas próprias credenciais de banco/broker; não recebe senhas administrativas ou do outro serviço.

O verificador T14 consulta discovery/JWKS nos dois contextos, compara o issuer e usa tokens reais na jornada completa. Não desativa validação de assinatura, issuer, audiência ou roles.

## Persistência, reinício e limpeza

Os volumes são `postgres_data`, `rabbitmq_data`, `keycloak_data` e `rabbitmq_bootstrap`, qualificados pelo projeto (por padrão, prefixo `datum_`). Os nomes dos volumes de dados existentes foram preservados.

```sh
docker compose down
docker compose up -d
```

`down` remove containers e a rede desse projeto, preservando volumes. Clientes/exclusões, outbox, inboxes, notificações, filas/mensagens e dados do Keycloak permanecem. A política de replay/DLQ continua explícita: reiniciar o ambiente não reprocessa automaticamente mensagens em quarentena.

**Atenção: o comando abaixo remove os volumes e todos os dados locais desse projeto**, incluindo bancos, histórico de idempotência, mensagens, DLQs e usuários/sessões do Keycloak. Use somente quando quiser descartar esse ambiente:

```sh
docker compose down -v
```

Mudar senhas no `.env` não redefine automaticamente usuários de PostgreSQL ou de um realm Keycloak já persistidos. Preserve as credenciais efetivas dos volumes existentes ou faça a alteração administrativa correspondente. O bootstrap de definitions é importado pelo RabbitMQ no boot; não confunda a geração do arquivo com uma rotação já aplicada a um broker que ainda está em execução.

## Recursos, observabilidade e diagnóstico

| Serviço | Limite padrão | Variável |
|---|---|---|
| Cada aplicação Java | 1 GiB | `CUSTOMER_MEMORY_LIMIT`, `NOTIFICATION_MEMORY_LIMIT` |
| PostgreSQL | 768 MiB | `POSTGRES_MEMORY_LIMIT` |
| RabbitMQ | 1 GiB | `RABBITMQ_MEMORY_LIMIT` |
| Keycloak | 1 GiB | `KEYCLOAK_MEMORY_LIMIT` |
| WireMock | 512 MiB | `WIREMOCK_MEMORY_LIMIT` |
| Job de bootstrap | 128 MiB | fixo; execução curta |

Os limites dos seis serviços somam aproximadamente 5,25 GiB. Reserve margem no Docker Desktop para builds e outros processos. As duas aplicações usam `MaxRAMPercentage=60.0` e `ActiveProcessorCount=2`; os parâmetros podem ser ajustados nas variáveis `CUSTOMER_JAVA_TOOL_OPTIONS` e `NOTIFICATION_JAVA_TOOL_OPTIONS`. Não representam dimensionamento produtivo nem resultado de carga. Timeouts, pools e concorrência continuam externalizados por serviço e são encaminhados explicitamente pelo Compose.

Para habilitar as métricas protegidas da T12, defina no `.env`:

```dotenv
APP_PROFILES=observability
```

```sh
docker compose up -d
```

As aplicações são recriadas com o perfil; o banco e as filas persistem. Prometheus é acessível em `/actuator/prometheus` com ADMIN/Bearer na API e com `notification_monitor`/`NOTIFICATION_MANAGEMENT_PASSWORD` na notificação. As credenciais são as mesmas do [runbook T12](../observability/README.md). Grafana e um servidor Prometheus não fazem parte desta composição; o endpoint é compatível com um coletor externo autenticado. Nenhuma carga JMeter é iniciada pelo Compose.

Diagnóstico inicial:

```sh
docker compose config --quiet
docker compose ps -a
docker compose logs --tail=100 rabbitmq-init rabbitmq postgres keycloak
docker compose logs --tail=100 customer-service customer-notification-service
```

Use `config --quiet` para validar sem imprimir a configuração completa, que contém credenciais. Em porta ocupada, pare o processo conhecido ou ajuste a variável de porta. Não encerre processos desconhecidos. Em bootstrap com saída diferente de zero, examine `rabbitmq-init`; o broker fica bloqueado pela dependência. Em aplicação reiniciando, confira credenciais efetivas, migrations e memória. `OOMKilled` indica necessidade de revisar o limite/consumo; aumentar tempos de espera não corrige falta de memória. Em startup lento, verifique primeiro logs e disponibilidade de recursos. As primeiras construções dependem do download das imagens e do Maven Central.

## Verificação descartável da T14

Somente para os verificadores, use a `.venv` com `infra/rabbitmq/requirements-test.txt` já preparada. Na raiz:

```sh
infra/rabbitmq/.venv/bin/python -m unittest discover -s infra/rabbitmq -p 'test_*.py'
infra/rabbitmq/.venv/bin/python -m unittest discover -s infra/compose -p 'test_*.py'
infra/rabbitmq/.venv/bin/python infra/compose/verify.py
```

São sete testes unitários do bootstrap e sete testes de configuração (sem iniciar containers). O teste funcional cria projeto, credenciais e sete portas aleatórios, executa `up --build -d` sem chamar `configure.py` e aguarda os seis serviços saudáveis. Verifica usuário não root/limites, issuer/JWKS no host e na rede, 38 conferências administrativas do RabbitMQ, idempotência do job e as 36 verificações da jornada T13.

Também para a notificação, cria uma mensagem confirmada ainda não consumida, faz `down` sem apagar volumes e sobe novamente. Confere conservação dos clientes/versões/inbox, recuperação única da entrega, preservação das notificações anteriores e da DLQ. Por fim habilita observabilidade, verifica autenticação/exportação e privacidade dos logs. O total é **57 verificações T14**, sem contar individualmente as conferências internas do verificador administrativo. A limpeza final remove somente os recursos do projeto descartável.

Para repetir com as três imagens `:t14` construídas da mesma revisão:

```sh
infra/rabbitmq/.venv/bin/python infra/compose/verify.py --skip-build
```

Esse verificador não aceita destino externo, projeto do usuário nem argumentos Compose. As suítes [T13](../testing/README.md), [T11](../resilience/README.md) e [T12](../observability/README.md) continuam disponíveis. As verificações descartáveis não usam nem apagam os dados da demonstração local.
