# Datum Customer Services

Solução para o desafio técnico Datum / DXC / Claro, organizada em dois serviços Spring Boot independentes:

- `customer-service`: responsável pelo domínio de clientes e pela integração HTTP direta com o provedor externo de Score.
- `customer-notification-service`: responsável pelo consumo de eventos de criação e registro de notificações simuladas, com armazenamento próprio.

## Estado da implementação

Etapas T01.1 e T01.2: build Maven multimódulo, inicialização das aplicações, testes de saúde HTTP e imagens Docker independentes. T02.1: PostgreSQL local com bancos e credenciais segregadas. T02.2: `customer-service` integrado a JPA/Flyway, tabela de clientes e exclusão lógica. T03.1: cadastro e consulta de clientes por ID, com DTOs, validação e erros padronizados. T03.2: atualização completa e exclusão lógica pela API. T03.3: listagem paginada, busca por nome e filtro por status. T04.1: Keycloak local e emissão de JWTs. T04.2: API como OAuth 2.0 Resource Server, validando JWT e aplicando permissões USER/ADMIN. T05.1: provedor de Score simulado com WireMock e cenários reproduzíveis. T05.2: consulta de Score pela API, com timeouts, retries limitados e erros controlados. T06: contratos de eventos aprovados. T07.1: RabbitMQ local com topologia e permissões provisionadas. T07.2: cadastro e evento atômicos no outbox, com publicação confirmada e recuperação de pendências. T08: consumo de status com controle de versão, inbox transacional, retry confirmado e DLQ. T09: consumidor de criação com inbox e notificação simulada transacionais em banco próprio, retries e DLQ. T10: Swagger UI/OpenAPI com autenticação Bearer, schemas, exemplos e respostas por operação. T11: limites de banco e suíte reproduzível de falhas distribuídas em ambiente descartável. A orquestração completa permanece pendente.

## Decisões da base

A T06 tem **contratos aprovados pelo responsável pelo projeto em 10/11/2026**, disponíveis em [contracts/events](contracts/events/README.md). Outbox e consumidor de status implementam esses contratos nas T07.2/T08.

- Java 21 LTS: atende ao requisito Java 17+ e está disponível no ambiente de desenvolvimento.
- Spring Boot 3.5.16: versão fixa da linha 3.5, compatível com Java 21.
- Maven 3.9.9, fixado pelo Maven Wrapper: permite reproduzir o build sem depender da versão do Maven instalada na máquina.
- POM pai: centraliza versões e agrega os módulos. Cada serviço gera seu próprio JAR executável; nenhum serviço depende do outro.
- Spring MVC e Actuator: servidor HTTP e verificação operacional de saúde. No serviço de notificações, HTTP atende somente à operação; seu processamento de negócio é assíncrono.

## Pré-requisitos

- JDK 21, com `JAVA_HOME` apontando para essa versão.
- Acesso ao Maven Central na primeira execução para baixar Maven e dependências.
- No macOS/Linux, `curl` ou `wget` e `unzip` para o Wrapper.

Para a execução via Docker, é necessário Docker com suporte a BuildKit e Compose v2 ou superior (incluídos no Docker Desktop), daemon ativo e acesso ao Docker Hub e ao Maven Central. Nesse caminho, o build roda dentro do container e dispensa JDK e Maven instalados no host. O PostgreSQL precisa estar ativo para iniciar os dois serviços; os testes de integração criam seus próprios bancos e brokers descartáveis.

## Validar o ambiente e o build

Execute na raiz do projeto. No macOS, selecione o JDK 21 no terminal atual:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./mvnw -version
./mvnw clean verify
```

No Linux/Windows, configure `JAVA_HOME` para o JDK 21 instalado. No Windows, use `mvnw.cmd` no lugar de `./mvnw`.

Confirme Java 21 e Maven 3.9.9 na saída do Wrapper. O arquivo `.java-version` também indica Java 21 para quem usa jEnv; ele não substitui a configuração de `JAVA_HOME`.

Resultado esperado: `BUILD SUCCESS`, 269 testes rápidos aprovados (208 do customer-service e 61 do serviço de notificações) e os arquivos:

- `customer-service/target/customer-service-0.0.1-SNAPSHOT.jar`
- `customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar`

Os testes rápidos verificam saúde, resposta segura para falha interna, autenticação/autorização e o adaptador HTTP de Score com WireMock em porta aleatória. O perfil de teste `smoke` exclui a persistência e substitui `CustomerService` por um mock somente nesses testes HTTP. A segurança permanece ativa: os JWTs são assinados com chaves exclusivas de teste, e o decoder de produção consulta um JWKS HTTP local. Não há decoder falso nem validação de assinatura desabilitada. Persistência e endpoints com banco real são verificados pelo perfil Maven `integration`, obrigatório para validar os incrementos com persistência/mensageria:

```sh
./mvnw -Pintegration clean verify
```

Esse comando executa 269 testes rápidos e 248 cenários de integração: 12 de persistência em `CustomerPersistenceIT`, 68 HTTP de cadastro/alteração/exclusão em `CustomerApiIT`, 54 de listagem/busca em `CustomerQueryIT`, 19 de Score em `CustomerScoreIT`, 29 de outbox/publicação em `CustomerOutboxIT`, 25 de consumo de status em `CustomerStatusChangeIT`, 20 de notificações em `NotificationConsumerIT`, 13 de contrato OpenAPI em `OpenApiContractIT` e oito de limites de banco em `DatabaseResilienceIT` (quatro por serviço). São **517 testes**. PostgreSQL 17.11 e RabbitMQ 4.4.0 são criados por Testcontainers. Os testes HTTP de negócio passam pela segurança com JWTs assinados. O bootstrap PostgreSQL é o mesmo de `infra/postgres/init/`, e cada aplicação conecta como seu próprio usuário (`customer_app` ou `notification_app`), sem privilégios administrativos. Não é necessário subir as dependências do Compose para a suíte Maven. O Docker precisa estar ativo para os testes de integração; se estiver indisponível, esses testes falham, em vez de serem ignorados. O banco e o broker locais não recebem os dados dos testes.

Os 29 cenários de outbox verificam commit/rollback atômicos, falha na inserção do evento, CPF duplicado sem novo evento, payload/propriedades AMQP, broker parado, mandatory return, confirmação ausente/negativa, backoff persistido, evento inválido bloqueado, expiração de reserva, proteção contra confirmação por dono antigo, claims/publicações concorrentes e duplicata após queda entre confirm e atualização local. Os testes controlam o relay explicitamente para reproduzir falhas; apenas o agendamento fica desativado neles. O caminho real agendado é verificado na imagem Docker. Os 20 cenários de integração de notificações verificam transação atômica, duplicatas, concorrência, recuperação após commit sem ACK, falha de conexões, TTL, retry sem rota, DLQ e isolamento de banco/credencial. Os testes da T09 iniciam o serviço sem uma API de clientes em execução.

Score acrescenta 81 cenários: 49 do adaptador HTTP, 13 da configuração e 19 da API integrada. Eles verificam validação estrita do contrato (inclusive CPF divergente, campos ausentes, tipos incorretos, duplicidade de propriedades, JSON incompleto e corpo acima de 4 KiB), contagem de tentativas, recuperação de falha transitória, timeout antes dos headers e durante o corpo, conexão recusada, redirect bloqueado, interrupção, USER/ADMIN, 401/403/400/404/502/503/504 e ausência de CPF/token nos erros e logs. A integração carrega as mesmas fixtures de `infra/wiremock/`, verifica que clientes INACTIVE continuam consultáveis e que excluídos/inexistentes não chamam o provedor. A transação do banco termina antes da chamada HTTP; Score não é persistido.

Os 48 casos de segurança cobrem todas as operações de clientes, USER sem escrita, ADMIN com leitura/escrita, 401/403, privacidade dos erros, sessão/Basic desabilitados e tokens malformados, sem assinatura, com chave/kid/algoritmo/issuer/audiência incorretos, expirados, com nbf futuro ou sem exp/sub. Roles ausentes, malformadas ou vindas de claims não autorizados não concedem acesso. As chaves privadas de teste são geradas em memória e o JWKS publica somente a chave pública.

Os cenários HTTP cobrem cadastro/consulta, atualização completa, repetição de PUT, exclusão lógica, 400 para campos/JSON/IDs inválidos, 404 para inexistentes/excluídos, 409 para CPF reservado e rollback integral de atualizações recusadas. Há testes de cadastro concorrente com o mesmo CPF, DELETEs simultâneos e PUT aguardando uma exclusão não confirmada: o bloqueio é observado no PostgreSQL, e o PUT retorna 404 após o commit da exclusão. Verificações de privacidade conferem que erros e logs da aplicação não expõem os valores de CPF usados nos testes. A mensagem `Falha inesperada na API. Tipo: IllegalStateException` é produzida intencionalmente pelo teste da resposta 500; confira os contadores finais para avaliar o resultado.

Os cenários de consulta conferem ordenação e limites das páginas, contagem sem excluídos, filtros combinados, busca sem diferenciar maiúsculas/minúsculas, preservação de acentos e busca literal de caracteres especiais. Também cobrem páginas além do fim, ausência de resultados e parâmetros inválidos, incluindo combinações de page/size incompatíveis com o offset do JPA.

No Java 21, o suporte de testes do Mockito pode emitir um aviso sobre carregamento dinâmico de agente. Esse aviso foi observado na validação inicial, com os dois testes aprovados; confira os contadores de falhas e o resultado final do build.

## Testar manualmente os serviços

Depois do build, prepare PostgreSQL e Keycloak pelas seções T02.1/T04.1 (`docker compose up -d --wait postgres keycloak`). Encerre instâncias antigas antes de usar as mesmas portas. Abra dois terminais na raiz e configure Java 21 em ambos. No primeiro, configure as variáveis do `customer-service`:

```sh
export CUSTOMER_DB_URL='jdbc:postgresql://localhost:15432/customer_db'
export CUSTOMER_DB_USERNAME='customer_app'
export CUSTOMER_DB_PASSWORD='customer_local_only'
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

Use a senha efetiva de `CUSTOMER_DB_PASSWORD` do seu `.env` e ajuste a URL se alterou `POSTGRES_PORT`. A senha acima é apenas o valor de demonstração. O Compose lê `.env` automaticamente; uma aplicação iniciada por `java -jar`, Maven ou IDE precisa receber suas variáveis de ambiente explicitamente. Não passe credenciais de `datum_admin` à aplicação.

O issuer padrão é `http://localhost:18090/realms/datum` e o JWKS é derivado desse endereço. Se alterou a porta do Keycloak, exporte `CUSTOMER_JWT_ISSUER_URI` com o issuer efetivo antes de iniciar a API; `CUSTOMER_JWT_JWK_SET_URI` pode definir separadamente o endereço para buscar as chaves. A audiência padrão é `customer-api`.

Na primeira inicialização, o Flyway aplica V1 (clientes), V2 (outbox) e V3 (versão/inbox de status). Nas seguintes, valida o histórico e aplica somente migrations pendentes. O Hibernate usa `ddl-auto=validate`; divergências de schema interrompem a inicialização. O endpoint de saúde considera a conexão com o banco. O RabbitMQ não compõe esse healthcheck: seu estado é verificado separadamente, pois uma indisponibilidade do broker não impede o cadastro com evento pendente no banco.

No segundo, configure a senha efetiva de `NOTIFICATION_DB_PASSWORD` do seu `.env` (abaixo, default local). O serviço aplica sua migration em `notification_db` e começa a consumir mensagens de criação que já estiverem na fila:

```sh
export NOTIFICATION_DB_URL=jdbc:postgresql://localhost:15432/notification_db
export NOTIFICATION_DB_PASSWORD=notification_local_only
export NOTIFICATION_RABBITMQ_HOST=localhost
export NOTIFICATION_RABBITMQ_PORT=15673
export NOTIFICATION_RABBITMQ_PASSWORD=notification_rabbitmq_local_only
java -jar customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar
```

Em um terceiro terminal:

```sh
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8081/actuator/health
```

Ambos devem responder HTTP 200 com `{"status":"UP"}`. Finalize cada aplicação com `Ctrl+C`.

As portas padrão são 8080 e 8081. Para usar outra porta:

```sh
SERVER_PORT=8090 java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

Também é possível executar um serviço diretamente pelo Maven:

```sh
./mvnw -pl customer-service spring-boot:run
./mvnw -pl customer-notification-service spring-boot:run
```

Execute cada comando em seu próprio terminal.

### Tokens nos exemplos curl

Todos os comandos de clientes das seções T03 abaixo usam `$TOKEN_ADMIN`. Obtenha um access token novo no Postman, conforme a seção T04.1. No terminal em que executará os curls, copie o token para o prompt oculto:

```sh
export TOKEN_ADMIN="$(python3 -c 'import getpass; print(getpass.getpass("Access token ADMIN: "))')"
```

O valor fica na variável do terminal, sem ser incluído no comando salvo no histórico. Os tokens duram cinco minutos; renove a variável quando necessário. Para consultas com o perfil USER, faça o mesmo com `TOKEN_USER`. Requisições sem token não executarão os exemplos de negócio: retornarão 401. No Postman, selecione o token na autorização OAuth 2.0/Bearer, sem necessidade das variáveis do shell.

## Construir e testar as imagens Docker

Execute todos os comandos abaixo na raiz do projeto. O ponto final dos comandos de build indica o contexto da raiz, necessário para acessar o POM pai e os POMs dos módulos.

```sh
docker build -f customer-service/Dockerfile -t datum/customer-service:t11 .
docker build -f customer-notification-service/Dockerfile -t datum/customer-notification-service:t11 .
```

Cada Dockerfile tem dois estágios:

1. `build`: Maven 3.9.9 com JDK 21 compila e executa os testes rápidos do serviço selecionado (`verify`). Os testes de integração devem ser executados antes, no host, com `-Pintegration`: eles precisam de acesso ao Docker e não rodam dentro do build da imagem. A versão do Maven coincide com a fixada pelo Wrapper usado no host. O cache de dependências acelera builds seguintes; os arquivos `target/` do host são ignorados.
2. `runtime`: Eclipse Temurin JRE 21 recebe somente o JAR do serviço e o executa como usuário `app` (UID/GID 10001). O Maven e o código-fonte ficam no estágio de build.

Os dois POMs de módulos são copiados para permitir a leitura do agregador Maven; cada build copia o código-fonte e executa os testes apenas do seu serviço. As tags das imagens-base recebem atualizações dos respectivos fornecedores, portanto builds futuros podem incorporar novos patches do JDK/JRE e do sistema operacional.

O `.dockerignore` exclui `docs/`, histórico Git, saídas Maven, arquivos locais e `.env*` do contexto enviado ao Docker. Essa exclusão é independente do `.gitignore`.

Prepare PostgreSQL/Keycloak/WireMock/RabbitMQ pelo Compose e exporte `CUSTOMER_DB_PASSWORD`, `CUSTOMER_RABBITMQ_PASSWORD`, `NOTIFICATION_DB_PASSWORD` e `NOTIFICATION_RABBITMQ_PASSWORD` conforme o seu `.env`, como na execução Java. Se usa o default local do broker e ainda não exportou a senha, faça `export CUSTOMER_RABBITMQ_PASSWORD='customer_rabbitmq_local_only'`. Inicie os containers:

```sh
python3 infra/rabbitmq/configure.py
docker compose up -d --wait postgres keycloak wiremock rabbitmq
docker run -d --rm --name datum-customer-t11 --network datum_default --env CUSTOMER_DOCS_ENABLED --env CUSTOMER_DB_PASSWORD --env CUSTOMER_DB_URL=jdbc:postgresql://postgres:5432/customer_db --env CUSTOMER_JWT_ISSUER_URI --env CUSTOMER_JWT_JWK_SET_URI=http://keycloak:8080/realms/datum/protocol/openid-connect/certs --env SCORE_BASE_URL=http://wiremock:8080 --env CUSTOMER_RABBITMQ_HOST=rabbitmq --env CUSTOMER_RABBITMQ_PORT=5672 --env CUSTOMER_RABBITMQ_PASSWORD -p 127.0.0.1:18080:8080 datum/customer-service:t11
docker run -d --rm --name datum-notification-t11 --network datum_default --env NOTIFICATION_DB_URL=jdbc:postgresql://postgres:5432/notification_db --env NOTIFICATION_DB_PASSWORD --env NOTIFICATION_RABBITMQ_HOST=rabbitmq --env NOTIFICATION_RABBITMQ_PORT=5672 --env NOTIFICATION_RABBITMQ_PASSWORD -p 127.0.0.1:18081:8081 datum/customer-notification-service:t11
```

As portas 18080 e 18081 no host evitam conflito com a execução Java nas portas 8080 e 8081. O acesso publicado fica restrito à máquina local. Aguarde as mensagens de inicialização nos logs:

```sh
docker logs datum-customer-t11
docker logs datum-notification-t11
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18081/actuator/health
```

Critério de validação: ambos respondem HTTP 200 com `{"status":"UP"}`. Se o `curl` ocorrer antes da inicialização, aguarde a mensagem `Started ...Application` e repita a consulta.

Confira o usuário dos processos:

```sh
docker exec datum-customer-t11 id
docker exec datum-notification-t11 id
```

Resultado esperado: UID e GID 10001 (`app`). Para encerrar:

```sh
docker stop datum-customer-t11 datum-notification-t11
```

O `--rm` remove os containers das aplicações após a parada; as imagens permanecem disponíveis para novos testes. Nenhuma imagem é publicada em registry por esses comandos. O PostgreSQL permanece ativo até `docker compose down`. Dentro da rede `datum_default`, a aplicação usa `postgres:5432`; no host, usa `localhost:15432`.

## PostgreSQL local e isolamento de bancos (T02.1)

O `docker-compose.yml` contém PostgreSQL, Keycloak, WireMock e RabbitMQ. Os comandos desta seção iniciam somente o PostgreSQL. Execute na raiz do projeto:

```sh
cp .env.example .env
docker compose config --quiet
docker compose up -d --wait postgres
docker compose ps
sh infra/postgres/verify.sh
```

Se `.env` já existir, preserve o arquivo e complete as variáveis necessárias em vez de sobrescrevê-lo. As senhas do `.env.example` são fictícias e exclusivas para demonstração local. O `.env` é ignorado pelo Git. O comando `config --quiet` valida a configuração sem imprimir as senhas.

Resultado esperado: serviço `postgres` saudável (`healthy`) e todas as verificações do script com prefixo `OK`, terminando em `validação de acesso e isolamento concluída`. O cliente `psql` usado pelo script já está na imagem; não é necessário instalá-lo no host. No Windows, execute o script usando um shell compatível, como Git Bash ou WSL.

### Organização e decisões

| Serviço futuro | Banco | Usuário |
|---|---|---|
| `customer-service` | `customer_db` | `customer_app` |
| `customer-notification-service` | `notification_db` | `notification_app` |

- PostgreSQL 17.11, imagem `postgres:17.11-bookworm`: versão de manutenção fixa de uma linha suportada.
- Uma instância local com bancos e credenciais distintos atende à segregação lógica aprovada. Cada serviço será dono do seu banco e das suas migrations.
- `datum_admin` é o administrador de bootstrap; suas credenciais não devem ser usadas pelas aplicações.
- Usuários de serviço podem criar tabelas no próprio schema para as futuras migrations, mas não são superusuários e não podem criar bancos ou outros usuários.
- A permissão padrão de conexão de `PUBLIC` é revogada nos bancos de aplicação e nos bancos de manutenção `postgres`/`template1`. Assim, conhecer o endereço do servidor não permite que um usuário de serviço conecte no banco do outro.
- As conexões TCP exigem senha com SCRAM-SHA-256. A validação usa TCP, evitando a autenticação local por socket usada no bootstrap da imagem oficial.
- A porta `127.0.0.1:15432` fica restrita ao host e evita conflito com outros PostgreSQL locais. Para alterá-la, edite `POSTGRES_PORT` no `.env`. Dentro da futura rede Compose, o endereço será `postgres:5432`.
- O volume `datum_postgres_data` mantém os dados entre paradas. O healthcheck usa `pg_isready` via TCP; o script de verificação testa autenticação, escrita e isolamento.

O script `infra/postgres/init/01-create-databases.sh` cria bancos e usuários apenas na primeira inicialização de um volume vazio. Ele não substitui as futuras migrations Flyway das aplicações. Alterar uma senha no `.env` depois dessa inicialização não altera automaticamente a senha já armazenada no banco.

### Validação manual e reinício

Para consultar o banco de clientes com suas próprias credenciais:

```sh
docker compose exec postgres sh -c 'PGPASSWORD="$CUSTOMER_DB_PASSWORD" psql -h 127.0.0.1 -U customer_app -d customer_db -c "SELECT current_user, current_database();"'
```

Para conferir a preservação dos bancos e usuários após reiniciar:

```sh
docker compose restart postgres
docker compose up -d --wait postgres
sh infra/postgres/verify.sh
```

O teste cria tabelas de prova dentro de transações revertidas ao final; não mantém dados ou tabelas de teste. Ele verifica acesso ao próprio banco, capacidade de criação/escrita, ausência de privilégios administrativos, bloqueio de conexão cruzada e rejeição de senhas incorretas.

Para encerrar o ambiente preservando os dados:

```sh
docker compose down
```

Para recriar o ambiente do zero, **o comando abaixo apaga todos os volumes do projeto, incluindo dados PostgreSQL, realm/chaves do Keycloak e mensagens/dados do RabbitMQ**. Use somente quando quiser descartar esses dados:

```sh
docker compose down --volumes
docker compose up -d --wait postgres
```

Se o bootstrap falhar, consulte `docker compose logs postgres` e corrija a causa. Os scripts de inicialização não são retomados automaticamente sobre um volume já inicializado; em ambiente descartável, recrie o volume pelo procedimento acima.

## Modelo e migração de clientes (T02.2)

O Flyway do `customer-service` é responsável pela tabela `customers` e pelo histórico `flyway_schema_history`, no banco `customer_db`. Inicializar somente o PostgreSQL não cria tabelas de negócio: é necessário iniciar o serviço para aplicar as migrations. O Flyway independente do `customer-notification-service` cria suas tabelas de inbox/notificações em `notification_db` na T09. Cada serviço inicia e valida apenas suas próprias migrations.

| Campo | Regra de persistência |
|---|---|
| `id` | `BIGINT` gerado pelo PostgreSQL |
| `name` | Obrigatório, até 150 caracteres, não vazio |
| `name_normalized` | Gerado pelo banco com `lower(btrim(name))`, base para busca sem diferenciar maiúsculas/minúsculas; acentos são preservados |
| `cpf` | Obrigatório, 11 dígitos sem máscara, único incluindo registros excluídos |
| `email` | Obrigatório, até 254 caracteres, não vazio; não é chave única |
| `status` | `ACTIVE` ou `INACTIVE`, persistido como texto |
| `created_at`, `updated_at` | Instantes com fuso, atualizados pelo ciclo de persistência JPA |
| `deleted_at` | Nulo para registros disponíveis; preenchido na exclusão lógica |

### Decisão: exclusão lógica

A exclusão lógica foi aprovada na T02.2 e exposta pela API na T03.2: o DELETE marca `deleted_at`, preservando o registro. `INACTIVE` permanece um estado de negócio consultável e não significa exclusão. As consultas expostas pelo repository filtram `deleted_at IS NULL`; a verificação de CPF inclui o histórico. O repository não expõe remoção física.

O CPF continua reservado após a exclusão lógica. Reativação, restauração de registros excluídos e políticas de retenção não foram implementadas. A entidade JPA fica em `infrastructure/persistence`; o enum de status fica em `domain`. A API usará DTOs próprios quando for implementada.

As constraints da T02.2 protegem formato básico, campos obrigatórios, status e unicidade no banco. A T03.1 acrescenta validação de dígitos verificadores de CPF, formato de e-mail e contratos HTTP, descritos abaixo. Não há seed de demonstração; o banco começa vazio e recebe os cadastros feitos pela API.

### Conferência pelo DBeaver

Conecte como `customer_app` em `localhost:15432`, banco `customer_db`, com a senha do `.env`. Após iniciar o serviço, atualize o schema `public` e execute:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT column_name, data_type, is_nullable, is_generated
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'customers'
ORDER BY ordinal_position;
SELECT count(*) FROM customers;
```

O esperado agora é migration `1` de clientes e migration `2` de outbox com sucesso, as colunas de clientes descritas acima e zero clientes se você ainda não inseriu dados. Reinicie a aplicação e confira que não há reaplicação de migrations. Não edite migrations já aplicadas; mudanças posteriores devem ganhar novas versões.

## Cadastro e consulta por ID (T03.1)

O `CustomerController` recebe e valida DTOs, o `CustomerService` delimita as transações, e o repository persiste/consulta os clientes. Entidades JPA não são serializadas diretamente. As datas retornadas usam a precisão de microssegundos do PostgreSQL, preservando os mesmos valores no POST e no GET subsequente.

| Operação | Sucesso | Erros previstos nesta etapa |
|---|---|---|
| `POST /customers` | 201, corpo do cliente e `Location: /customers/{id}` | 400 para entrada inválida; 409 para CPF já registrado |
| `GET /customers/{id}` | 200 e corpo do cliente | 400 para ID inválido; 404 para inexistente ou excluído logicamente |

O cadastro exige `name`, `cpf`, `email` e `status`. Nome tem limite de 150 caracteres; e-mail, 254. Espaços nas extremidades de nome/CPF/e-mail são removidos antes da validação. CPF deve conter 11 dígitos sem máscara e dígitos verificadores válidos; valores com dígitos todos iguais são recusados. Status aceita exatamente `ACTIVE` ou `INACTIVE`. JSON malformado e propriedades desconhecidas também são recusados. O ID e as datas são definidos pelo servidor.

Os erros usam `application/problem+json` (`ProblemDetail`, RFC 9457), com `type`, `title`, `status`, `detail`, `instance` e extensão `code`. Erros de campos incluem `errors`, com `field` e `message`, sem os valores rejeitados. Códigos desta etapa: `VALIDATION_ERROR`, `INVALID_JSON`, `INVALID_REQUEST`, `CUSTOMER_NOT_FOUND`, `CPF_ALREADY_REGISTERED` e `INTERNAL_ERROR` (500, mensagem genérica). Somente a violação da constraint de CPF é convertida em 409; outras falhas de integridade não são apresentadas como CPF duplicado.

A aplicação não registra payloads nem mensagens SQL de constraints, que podem conter dados pessoais. O handler de erro inesperado registra somente a classe da falha. Logs estruturados e correlação serão tratados na etapa de observabilidade.

O POST exige JWT ADMIN desde a T04.2. Desde a T07.2, persiste cliente e `CUSTOMER_CREATED` na mesma transação; o relay publica o evento após o commit, conforme os contratos aprovados na T06. A migration V1 permanece inalterada; a V2 cria o outbox.

### Roteiro de validação local

Execute `./mvnw -Pintegration clean verify` com Java 21 e Docker ativo. Encerre a versão anterior do serviço e inicie o novo JAR com as variáveis do banco, conforme a seção de execução manual.

Cadastre um cliente fictício com CPF válido no algoritmo:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cliente de Demonstracao","cpf":"52998224725","email":"cliente@example.test","status":"ACTIVE"}'
```

Esperado: 201 com ID, dados cadastrados, datas e header `Location`. Use o ID retornado na consulta abaixo (substitua `1` se o ID for diferente):

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/1
```

Esperado: 200 com o mesmo cliente. Repetir o POST com o mesmo CPF deve produzir 409 e não criar outra linha. Se esse CPF já existir no banco local, o primeiro POST também retorna 409; consulte o cadastro existente no DBeaver ou use outro CPF fictício válido.

Teste uma entrada inválida e um ID inexistente:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"","cpf":"123","email":"invalido","status":"ACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/9223372036854775807
```

Esperado: 400 com erros de campos e 404, respectivamente. No DBeaver, confira os dados gravados:

```sql
SELECT id, name, status, created_at, updated_at, deleted_at
FROM customers ORDER BY id;
```

Somente o cadastro válido deve gerar registro; IDs podem apresentar intervalos por causa de tentativas de inserção rejeitadas. Para testar a API na imagem Docker, use os comandos da seção de containers e substitua a porta 8080 por 18080 nos exemplos.

## Atualização e exclusão lógica (T03.2)

| Operação | Sucesso | Erros previstos |
|---|---|---|
| `PUT /customers/{id}` | 200 com o cliente atualizado | 400 para corpo/ID inválido; 404 para inexistente/excluído; 409 para CPF de outro registro, inclusive excluído |
| `DELETE /customers/{id}` | 204, sem corpo | 400 para ID inválido; 404 para inexistente ou já excluído |

O PUT exige todos os campos `name`, `cpf`, `email` e `status`, com as mesmas validações e normalização do cadastro. Ele substitui esses dados em um cliente disponível e preserva ID e `createdAt`; não cria um registro quando o ID não existe. Alterações efetivas atualizam `updatedAt`. Repetir o mesmo PUT mantém o mesmo estado. O DTO `UpdateCustomerRequest` explicita o contrato de atualização separadamente do cadastro.

Se o CPF estiver reservado, toda a atualização é revertida: nome, e-mail, status e datas anteriores são preservados. O CPF pode ser alterado para outro CPF válido e disponível; a constraint única permanece como garantia final de consistência.

O DELETE preenche `deleted_at` e atualiza `updated_at`, mantendo nome, CPF, e-mail, status e data de criação. GET e PUT subsequentes retornam 404. Um novo cadastro com o CPF do registro excluído continua retornando 409. Repetir o DELETE retorna 404 e mantém a primeira data de exclusão. A exclusão é idempotente em seus efeitos, mesmo com respostas diferentes na repetição.

PUT e DELETE usam transações com bloqueio de linha (`PESSIMISTIC_WRITE`) na leitura do cliente disponível. A operação concorrente sobre o mesmo registro aguarda a transação anterior; o filtro de exclusão é reavaliado após essa espera, impedindo que uma atualização restaure uma linha excluída por concorrência. GET continua como consulta sem esse bloqueio de escrita. O schema V1 permanece inalterado.

O handler de erros também trata a validação conjunta de ID e corpo do PUT: falhas de campos continuam retornando `VALIDATION_ERROR` com `errors`, e IDs inválidos retornam `INVALID_REQUEST`.

### Roteiro de validação local

Execute `./mvnw -Pintegration clean verify`, encerre a versão anterior do serviço e inicie o novo JAR com as variáveis do banco. Use um cliente de demonstração disponível criado pelo POST da seção anterior. Os exemplos usam ID `1` e CPF `52998224725`; ajuste o ID conforme o cadastro que você está testando.

Atualize o cliente para INACTIVE e consulte o resultado:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X PUT http://localhost:8080/customers/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cliente Atualizado","cpf":"52998224725","email":"atualizado@example.test","status":"INACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/1
```

Esperado: 200 nos dois comandos, com o mesmo ID e data de criação, novos dados e `updatedAt` atualizado. `INACTIVE` continua sendo consultável. Repetir esse PUT deve manter os mesmos dados e datas.

Teste validação e ID inexistente:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X PUT http://localhost:8080/customers/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"","cpf":"123","email":"invalido","status":"ACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X PUT http://localhost:8080/customers/9223372036854775807 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cliente Atualizado","cpf":"52998224725","email":"atualizado@example.test","status":"INACTIVE"}'
```

Esperado: 400 com erros de campos e 404, respectivamente. Os dados do cliente disponível devem permanecer iguais após o PUT inválido.

Para testar conflito, cadastre outro cliente fictício e tente usar seu CPF no primeiro:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Outro Cliente","cpf":"11144477735","email":"outro@example.test","status":"ACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X PUT http://localhost:8080/customers/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alteracao Recusada","cpf":"11144477735","email":"recusado@example.test","status":"ACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/1
```

Esperado: 201 para o segundo cadastro (ou 409 se esse CPF já estiver reservado), 409 para o PUT e 200 no GET com os dados anteriores, sem alteração parcial.

Por último, exclua logicamente o primeiro cliente:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X DELETE http://localhost:8080/customers/1
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/1
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X DELETE http://localhost:8080/customers/1
```

Esperado: 204 sem corpo, 404 e 404. Repetir o PUT válido para esse ID também deve retornar 404. No DBeaver, confira que a linha continua presente com status INACTIVE e `deleted_at` preenchido:

```sql
SELECT id, name, status, created_at, updated_at, deleted_at
FROM customers WHERE id = 1;
```

Os comandos de exclusão deste roteiro preservam as linhas, mas retiram os clientes da API. O CPF continua reservado e não há endpoint de restauração implementado.

## Listagem, busca e filtro (T03.3)

| Operação | Parâmetros | Resultado |
|---|---|---|
| `GET /customers` | `status` opcional, `page`, `size` | 200 com clientes disponíveis e paginação |
| `GET /customers/search` | `name` obrigatório, `status` opcional, `page`, `size` | 200 com os resultados da busca e paginação |

- `page`: inteiro maior ou igual a zero; padrão 0.
- `size`: inteiro de 1 a 100; padrão 20. Page e size ausentes ou vazios usam os padrões.
- `status`: opcional; quando informado, aceita exatamente `ACTIVE` ou `INACTIVE`. Valor vazio é inválido.
- `name`: obrigatório na busca, não vazio, com até 150 caracteres. Espaços nas extremidades são removidos para a consulta.
- A ordenação é fixa por `id` crescente; não há parâmetro de ordenação nesta etapa.

O nome é procurado por trecho, sem diferenciar maiúsculas/minúsculas, usando `name_normalized`. Acentos são preservados: `ÁGATA` encontra `Ágata`, enquanto `agata` não equivale a `Ágata`. `%`, `_` e barra invertida são caracteres literais, não curingas fornecidos pelo cliente. As consultas derivadas do Spring Data fazem o escape e usam parâmetros vinculados.

Toda consulta inclui `deleted_at IS NULL`, tanto nos itens quanto na contagem total. O filtro por status pode ser combinado com o nome. Páginas além do fim e consultas sem correspondência retornam 200 com `content: []`; ausência de resultados não é erro 404.

### Formato da resposta

O DTO `CustomerPageResponse` expõe um contrato próprio, sem serializar diretamente o `PageImpl` do Spring Data:

| Campo | Significado |
|---|---|
| `content` | Lista de `CustomerResponse`, com os mesmos campos da consulta por ID |
| `page` / `size` | Página solicitada e tamanho da página |
| `totalElements` | Total de registros disponíveis que atendem aos filtros |
| `totalPages` | Número de páginas para o tamanho solicitado; zero se não houver resultados |
| `first` / `last` | Indicadores da posição da página; `last` também é verdadeiro nas páginas além do fim |

Os parâmetros são validados antes da consulta. Falhas retornam 400 em `application/problem+json`, com `code: INVALID_REQUEST` e mensagens sem reproduzir os valores enviados. Quando disponíveis, os detalhes em `errors` identificam o parâmetro e a regra violada. Combinações em que `page * size` excede 2.147.483.647 são recusadas, pois o JPA usa offset inteiro. A T03.3 não altera o schema.

### Roteiro de validação local

Execute `./mvnw -Pintegration clean verify`, encerre a versão anterior e inicie o novo JAR com as variáveis do banco. Para testar a busca e os filtros com dados disponíveis, cadastre dois clientes fictícios:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Ana Silva","cpf":"16899535009","email":"ana.silva@example.test","status":"ACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Ana Souza","cpf":"39053344705","email":"ana.souza@example.test","status":"INACTIVE"}'
```

Esperado: 201 nos dois. Se algum CPF já estiver reservado, use outro CPF fictício válido. Os totais das consultas abaixo refletem todos os registros disponíveis do seu banco, inclusive cadastros de etapas anteriores.

Confira a listagem e duas páginas de um item:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers?page=0&size=1'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers?page=1&size=1'
```

Esperado: 200 com conteúdo em ID crescente, páginas distintas e totais consistentes. O cliente excluído na T03.2 não deve aparecer no conteúdo nem em `totalElements`.

Confira filtros, busca sem diferenciar maiúsculas/minúsculas e a combinação dos dois:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers?status=ACTIVE'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers?status=INACTIVE'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers/search?name=ANA'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers/search?name=ana&status=ACTIVE'
```

Esperado: 200, somente o status solicitado nos filtros e correspondências de Ana na busca. A combinação deve retornar somente correspondências disponíveis com status ACTIVE.

Teste ausência de resultados e validação:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers/search?name=nomesemcorrespondencia'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers?size=101'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" 'http://localhost:8080/customers?status=BLOCKED'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/search
```

Esperado: 200 com conteúdo vazio no primeiro; 400 com `INVALID_REQUEST` nos demais. No DBeaver, compare os totais com:

```sql
SELECT count(*) FROM customers WHERE deleted_at IS NULL;
SELECT status, count(*) FROM customers
WHERE deleted_at IS NULL GROUP BY status ORDER BY status;
```

## Keycloak e tokens de demonstracao (T04.1)

Esta etapa prepara o provedor de identidade. A API exige Bearer JWT e aplica permissões USER/ADMIN desde a T04.2, descrita adiante.

### Iniciar e verificar

Com Docker ativo e o `.env` existente preservado, execute na raiz:

```sh
docker compose config --quiet
docker compose up -d --wait keycloak
docker compose ps
python3 infra/keycloak/verify.py
```

O Compose usa os novos valores de demonstração da `.env.example` como defaults, portanto você pode manter seu `.env` da etapa anterior. Para personalizar a porta ou as senhas, acrescente as variáveis de Keycloak ao `.env` antes da primeira inicialização do realm. Não sobrescreva as credenciais PostgreSQL já configuradas.

Esperado: `keycloak` saudável (`healthy`) e a verificação concluída com todas as linhas `OK`. O script exige Python 3, OpenSSL e Docker CLI no host; não instala pacotes e não precisa que a API de clientes esteja ativa. Ele simula o formulário do Keycloak somente com os usuários de demonstração e usa Authorization Code + PKCE, com checagem de state. É um smoke test do IdP, não um cliente de autenticação para produção. O login interativo normal é feito pelo Postman, conforme abaixo.

A verificação usa OpenSSL para conferir a assinatura RSA com as chaves públicas do JWKS e testa issuer, audiência, expiração, perfis, callbacks registrados, senha incorreta, assinatura adulterada, code_verifier inválido, PKCE obrigatório e direct grant desabilitado. Confere também discovery/JWKS pelo endereço interno do Docker. Não imprime tokens ou senhas, não visita os callbacks externos do Postman e não grava tokens em arquivos. O cliente de teste reproduz o envio de cookies Secure em HTTP apenas para endereços de loopback, como os navegadores fazem para localhost. Erros de login/PKCE nos logs do Keycloak durante as provas negativas são esperados; confira o resultado final do script.

### Configuracao e contas locais

- Imagem oficial `quay.io/keycloak/keycloak:26.8.2`, com versão fixa.
- Realm `datum`, importado de `infra/keycloak/datum-realm.json`.
- Cliente público `datum-local`, sem client secret, com Authorization Code e PKCE S256 obrigatório. Direct Access Grants, implicit flow e service accounts estão desabilitados.
- Roles de negócio `USER` e `ADMIN` em `realm_access.roles` e audiência `customer-api` nos access tokens. A role ADMIN da API não concede administração do Keycloak.
- Tokens assinados com RS256, duração de cinco minutos. O issuer padrão é `http://localhost:18090/realms/datum`.
- Acesso publicado somente em `127.0.0.1:18090`; para mudar a porta, configure `KEYCLOAK_PORT` antes de iniciar.
- `start-dev`, HTTP e armazenamento H2 em volume `datum_keycloak_data` são escolhas para esta demonstração local. Esse armazenamento pertence ao Keycloak e não compartilha bancos ou credenciais de clientes. Um ambiente de produção exigiria configuração própria, TLS e banco externo suportado.
- Healthcheck em `/health/ready`, porta interna 9000, usando Bash/TCP porque a imagem não inclui curl. Essa porta não é publicada no host.

| Finalidade | Usuario | Senha padrao local | Perfil/realm |
|---|---|---|---|
| Consulta da API | `api-user` | `user_local_only` | USER, realm datum |
| Leitura/escrita da API | `api-admin` | `admin_local_only` | ADMIN, realm datum |
| Console de administracao | `keycloak_admin` | `keycloak_admin_local_only` | Administrador de bootstrap, realm master |

As senhas são exclusivamente de demonstração. Elas são fornecidas pelas variáveis `KEYCLOAK_DEMO_USER_PASSWORD`, `KEYCLOAK_DEMO_ADMIN_PASSWORD` e `KEYCLOAK_ADMIN_PASSWORD`, respectivamente. O JSON versionado usa placeholders de ambiente para os usuários; não contém credenciais reais.

Console: [Keycloak local](http://localhost:18090/admin/). Use `keycloak_admin` para inspecionar o realm `datum`, seus usuários, roles e cliente. Esse usuário administrativo não é uma das contas de teste da API.

### Obter tokens pelo Postman

Na autorização de uma requisição, escolha OAuth 2.0 e configure:

| Campo | Valor local |
|---|---|
| Tipo de grant | Authorization Code (With PKCE) |
| Autorizar pelo navegador | Habilitado |
| Callback | `https://oauth.pstmn.io/v1/callback` (ou `https://oauth.pstmn.io/v1/browser-callback`, se apresentado pelo Postman) |
| Auth URL | `http://localhost:18090/realms/datum/protocol/openid-connect/auth` |
| Access Token URL | `http://localhost:18090/realms/datum/protocol/openid-connect/token` |
| Client ID | `datum-local` |
| Client Secret | Vazio |
| Scope | `openid` |
| Metodo do desafio | SHA-256 (S256); deixe o Postman gerar o verifier |
| Autenticacao do cliente | Credenciais no corpo, com client ID e sem secret |

Solicite um novo token e autentique no formulário do Keycloak com `api-user` ou `api-admin`, usando a senha local correspondente. Ao alternar perfis, encerre a sessão anterior ou acrescente `prompt=login` aos parâmetros da requisição de autorização, para solicitar novamente as credenciais. Use o access token obtido na própria requisição do Postman. O token USER permite consultas; o ADMIN também permite escrita.

Somente os dois callbacks exatos do Postman e `http://127.0.0.1:18091/callback` (interceptado localmente pelo smoke test) estão registrados. Não há wildcard de redirecionamento. O fluxo escolhido segue as orientações de [Keycloak](https://www.keycloak.org/securing-apps/oidc-layers) e [Postman](https://learning.postman.com/docs/use/send-requests/authorization/oauth-20).

### Issuer no host e na rede Docker

| Uso | Endereco padrao |
|---|---|
| Issuer canonico, inclusive dentro do JWT | `http://localhost:18090/realms/datum` |
| Discovery pelo host | `http://localhost:18090/realms/datum/.well-known/openid-configuration` |
| JWKS pelo host | `http://localhost:18090/realms/datum/protocol/openid-connect/certs` |
| JWKS na rede Compose | `http://keycloak:8080/realms/datum/protocol/openid-connect/certs` |

O hostname do Keycloak fixa o issuer público, inclusive quando discovery é consultado pelo endereço interno. O Resource Server valida esse issuer e usa a URL interna do JWKS quando configurado em container. A URL para buscar a chave pode diferir do issuer; o valor de `iss` continua sendo conferido. A validação de assinatura e de issuer permanece ativa nos dois contextos.

### Reinicio, importacao e limpeza

```sh
docker compose restart keycloak
docker compose up -d --wait keycloak
python3 infra/keycloak/verify.py
```

O volume preserva o realm, os usuários e as chaves. A importação na inicialização ignora um realm que já existe. Alterar o JSON ou as senhas no `.env` não modifica automaticamente o realm persistido. Para encerrar somente o Keycloak preservando seus dados:

```sh
docker compose stop keycloak
```

`docker compose down` encerra os dois serviços e preserva seus volumes. Para descartar **somente os dados do Keycloak** e importar tudo novamente, os comandos abaixo removem seu realm, chaves e sessões; os tokens antigos deixarão de ser válidos:

```sh
docker compose stop keycloak
docker compose rm -f keycloak
docker volume rm datum_keycloak_data
docker compose up -d --wait keycloak
```

O nome de volume acima corresponde ao projeto Compose padrão `datum`. Esse procedimento não remove o volume PostgreSQL. Execute-o apenas quando quiser reinicializar o IdP local; para ajustes preservando seu estado, use a administração do Keycloak.

## Protecao da API com JWT (T04.2)

O `customer-service` usa Spring Security como OAuth 2.0 Resource Server. O Keycloak emite os tokens; a API recebe somente access tokens no header `Authorization: Bearer ...`. Não há login próprio, autenticação Basic, sessão HTTP de autenticação ou token aceito por query string. CSRF está desabilitado para essa API stateless baseada em Bearer.

| Operacao | USER | ADMIN | Sem token/token invalido |
|---|---|---|---|
| GET de clientes, listagem, busca e filtro | Permitido | Permitido | 401 |
| POST, PUT e DELETE de clientes | 403 | Permitido | 401 |
| GET/HEAD `/actuator/health` | Publico, sem token necessario | Publico | Publico sem header de autenticacao |

HEAD das consultas segue as mesmas permissões do GET. Rotas/métodos sem regra explícita são bloqueados. IDs inválidos, clientes inexistentes e dados inválidos continuam produzindo os erros da aplicação somente após a autorização. Assim, um USER tentando POST recebe 403 antes de qualquer gravação ou validação do corpo.

O decoder Nimbus verifica assinatura RS256 pelas chaves do JWKS, issuer exato, audiência contendo `customer-api` e validade temporal (`exp`/`nbf`, com a tolerância padrão de relógio de 60 segundos do Spring Security). `exp` e `sub` são obrigatórios e subject não pode ser vazio. Somente USER/ADMIN da lista `realm_access.roles` viram authorities; outros claims como scope ou resource_access não concedem essas permissões.

Erros 401/403 são tratados na cadeia de filtros e usam `application/problem+json`, com códigos `AUTHENTICATION_REQUIRED` e `ACCESS_DENIED`, respectivamente, além de `WWW-Authenticate: Bearer`. As mensagens não reproduzem tokens ou detalhes internos da falha de autenticação.

### Configuracoes do Resource Server

| Variavel | Padrao no host | Uso |
|---|---|---|
| `CUSTOMER_JWT_ISSUER_URI` | `http://localhost:18090/realms/datum` | Valor esperado de iss |
| `CUSTOMER_JWT_JWK_SET_URI` | Issuer + `/protocol/openid-connect/certs` | Endereco das chaves publicas |
| `CUSTOMER_JWT_AUDIENCE` | `customer-api` | Audiencia esperada |

Em container, mantenha o issuer público e configure o JWKS para `http://keycloak:8080/realms/datum/protocol/openid-connect/certs`, conforme o comando Docker deste README. O decoder não precisa consultar discovery durante a inicialização; ele busca chaves ao validar tokens e usa cache. Isso não dispensa o Keycloak para emitir tokens ou obter novas chaves. A implementação segue o suporte oficial de [Resource Server JWT do Spring Security](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).

### Conferencia automatizada com tokens reais

Depois de iniciar o Keycloak e o novo JAR da API, execute:

```sh
python3 infra/keycloak/verify.py --api-url http://localhost:8080
```

Além das verificações do IdP, o script obtém tokens reais dos dois perfis e confere 401 sem token/token malformado, GET 200 para ambos e USER 403 em POST/PUT/DELETE. Para ADMIN, ele usa um POST inválido (400) e um ID inexistente em PUT/DELETE (404), comprovando que a aplicação foi alcançada sem inserir, alterar ou excluir clientes. O teste aceita somente URL de API em loopback e não imprime tokens. Para a imagem Docker, use `--api-url http://localhost:18080`.

### Validacao manual pelo Postman e curl

Execute `./mvnw -Pintegration clean verify`, inicie PostgreSQL/Keycloak e reinicie a API com o novo JAR. Gere tokens novos no Postman, pois tokens da etapa anterior podem ter expirado. Confira:

1. GET `/customers` sem autorização: 401 com `AUTHENTICATION_REQUIRED`.
2. GET `/customers` com token de `api-user`: 200.
3. POST `/customers` com `api-user`: 403 com `ACCESS_DENIED`.
4. O mesmo POST com `api-admin`: 201, ou 409 se o CPF já estiver reservado.
5. PUT/DELETE de cliente de demonstração com `api-user`: 403; com `api-admin`, aplicam-se os resultados da T03.2.

Para curl, carregue tokens novos sem eco no terminal:

```sh
export TOKEN_USER="$(python3 -c 'import getpass; print(getpass.getpass("Access token USER: "))')"
export TOKEN_ADMIN="$(python3 -c 'import getpass; print(getpass.getpass("Access token ADMIN: "))')"
curl -i http://localhost:8080/customers
curl -i -H "Authorization: Bearer $TOKEN_USER" http://localhost:8080/customers
curl -i -H "Authorization: Bearer $TOKEN_USER" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cliente Seguranca","cpf":"93541134780","email":"seguranca@example.test","status":"ACTIVE"}'
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cliente Seguranca","cpf":"93541134780","email":"seguranca@example.test","status":"ACTIVE"}'
```

Esperado: 401, 200, 403 e 201 (ou 409 se já cadastrado), nessa ordem. A T04.2 não altera migrations nem credenciais de banco e não publica eventos; mensageria permanece nas próximas etapas.

## Provedor externo de Score simulado (T05.1)

O WireMock representa a dependência HTTP externa `GET /scores/{cpf}`. Ele pode ser testado isoladamente, sem acessar banco ou autenticação. A T05.2 conecta o endpoint público `GET /customers/{id}/score` a esse provedor, conforme a próxima seção.

Usamos a [imagem oficial do WireMock](https://wiremock.org/docs/standalone/docker/), fixada em `3.13.2`. Os mappings e corpos em `infra/wiremock/` são montados somente para leitura. A porta padrão é `18082`, publicada exclusivamente em loopback. Dentro da rede Compose, o endereço do provedor é `http://wiremock:8080`.

### Iniciar e verificar

Na raiz do projeto, com Docker ativo e o `.env` já utilizado nas etapas anteriores:

```sh
docker compose up -d --wait wiremock
docker compose ps wiremock
python3 infra/wiremock/verify.py
```

Resultado esperado: serviço `healthy` e **14 verificações aprovadas**. O script confere saúde, contratos de sucesso, falhas, JSON inválido, atraso, timeout do cliente, resposta para requisições sem fixture e ausência de armazenamento no journal. Ele usa apenas Python padrão e Docker Compose, aceita somente URL de loopback e não acessa a API nem o banco de clientes. Não imprime CPFs ou corpos das respostas.

`WIREMOCK_PORT` tem default no Compose; não é necessário substituir seu `.env` existente. Se precisar mudar a porta, acrescente, por exemplo, `WIREMOCK_PORT=18083` ao `.env` e recrie apenas esse serviço. O script obtém a porta pela configuração efetiva do Compose. Também aceita endereço explícito:

```sh
python3 infra/wiremock/verify.py --base-url http://localhost:18082
```

### Cenários fixos de demonstração

Todos os CPFs abaixo são dados fictícios de teste com dígitos verificadores válidos. Os números e classificações são fixtures; não representam uma regra de negócio que calcule classificação a partir do score.

| CPF fictício | HTTP do provedor | Resposta/cenário |
|---|---|---|
| `16899535009` | 200 | score 750, `LOW_RISK` |
| `39053344705` | 200 | score 450, `MEDIUM_RISK` |
| `93541134780` | 200 | score 150, `HIGH_RISK` |
| `52998224725` | 404 | `SCORE_NOT_FOUND` |
| `11144477735` | 500 | `PROVIDER_ERROR` |
| `12345678909` | 503 | `PROVIDER_UNAVAILABLE` |
| `98765432100` | 200 | Corpo JSON incompleto, deliberadamente inválido |
| `01234567890` | 200 após 3000 ms | score 650, `LOW_RISK` |

A resposta válida tem o formato:

```json
{"cpf":"16899535009","score":750,"classification":"LOW_RISK"}
```

Os dois primeiros CPFs correspondem aos exemplos Ana Silva e Ana Souza da T03.3. Não é preciso cadastrar clientes para consultar diretamente o WireMock. CPF sem fixture, rota inexistente e método diferente de GET recebem 404 com `STUB_NOT_FOUND`; esse retorno indica ausência de fixture, enquanto `SCORE_NOT_FOUND` simula uma resposta de negócio do provedor.

Os códigos acima pertencem ao provedor simulado. O mapeamento para os erros públicos da API e a política do cliente Java estão descritos na T05.2. O timeout de 300 ms do script demonstra somente a interrupção da espera pelo stub lento. A alternativa seria alternar um cenário global do WireMock; fixtures por CPF permitem reproduzir sucesso e falhas simultaneamente, sem mudar o estado do servidor.

### Conferência manual

```sh
curl -i http://localhost:18082/__admin/health
curl -i http://localhost:18082/scores/16899535009
curl -i http://localhost:18082/scores/52998224725
curl -i http://localhost:18082/scores/12345678909
curl -i http://localhost:18082/scores/98765432100
curl -i --max-time 1 http://localhost:18082/scores/01234567890
```

Esperado: saúde 200, sucesso 200, erro 404, indisponibilidade 503, HTTP 200 com JSON incompleto e, por último, erro de timeout do curl (código de saída 28). O atraso é configurado por [fixedDelayMilliseconds](https://wiremock.org/docs/simulating-faults/) no mapping; sem `--max-time`, esse cenário retorna 200 após cerca de três segundos.

### Logs e recarga das fixtures

O Compose usa `--disable-request-logging` e `--no-request-journal`, conforme as [opções oficiais do WireMock](https://wiremock.org/docs/standalone/java-jar/), para evitar registrar URLs com CPF e armazenar requisições/headers. Uma resposta genérica para requisições sem fixture evita a página padrão de diagnóstico, que poderia reproduzir a URL recebida. A interface administrativa permanece local e não deve receber tokens reais da API; os testes diretos do provedor não precisam de autenticação.

Nesta versão, consultar `/__admin/requests` com journal desligado retorna HTTP 500 com `RequestJournalDisabledException`. O script reconhece essa resposta administrativa específica como confirmação da configuração; o healthcheck e os endpoints simulados continuam funcionando normalmente.

Depois de alterar fixtures, recarregue somente o WireMock e execute novamente a verificação:

```sh
docker compose up -d --force-recreate --wait wiremock
python3 infra/wiremock/verify.py
```

Para pará-lo sem afetar PostgreSQL/Keycloak:

```sh
docker compose stop wiremock
```

## Consulta de Score pela API (T05.2)

`GET /customers/{id}/score` aceita tokens USER e ADMIN. O `CustomerScoreService` recupera um cliente disponível, encerra a transação de leitura e consulta `ScoreClient`. O adaptador `HttpScoreClient` usa [Spring RestClient](https://docs.spring.io/spring-framework/reference/6.2/integration/rest-clients.html) com transporte HTTP do JDK para chamar diretamente `GET /scores/{cpf}`. Não há serviço intermediário, persistência ou cache do resultado. Clientes INACTIVE continuam consultáveis; a exclusão lógica impede a consulta.

### Contrato público e erros

Sucesso 200, com `Cache-Control: no-store`:

```json
{"customerId":4,"score":750,"classification":"LOW_RISK"}
```

O CPF é obtido do cadastro, sem aceitá-lo como parâmetro do consumidor deste endpoint. Ele é enviado ao provedor e validado na resposta, mas não é incluído no DTO de Score. Uma resposta de outro CPF é rejeitada. O token da API não é repassado ao provedor e redirecionamentos não são seguidos.

| Situação | HTTP público | code |
|---|---|---|
| ID inválido | 400 | `INVALID_REQUEST` |
| Token ausente/inválido ou perfil sem permissão | 401 / 403 | `AUTHENTICATION_REQUIRED` / `ACCESS_DENIED` |
| Cliente inexistente ou excluído; nenhuma chamada externa | 404 | `CUSTOMER_NOT_FOUND` |
| Provedor retorna 404 | 404 | `SCORE_NOT_FOUND` |
| JSON/contrato inválido, CPF divergente, corpo excessivo, media type inesperado, 3xx, 2xx diferente de 200 ou demais 4xx | 502 | `SCORE_INVALID_RESPONSE` |
| Provedor retorna 429/5xx, conexão recusada ou falha de I/O sem timeout | 503 | `SCORE_UNAVAILABLE` |
| Timeout de conexão/resposta/leitura do corpo | 504 | `SCORE_TIMEOUT` |

As falhas usam o mesmo `ProblemDetail` da API, sem CPF, URL externa, headers, corpo ou stack trace. Um 404 distingue ausência do cliente de ausência do Score pelo `code`.

O contrato adotado aceita `score` inteiro de 0 a 1000 e `classification` igual a `LOW_RISK`, `MEDIUM_RISK` ou `HIGH_RISK`. Essas são escolhas desta implementação para validar o provedor simulado; não há algoritmo interno para calcular classificação. Os três campos externos (`cpf`, `score`, `classification`) são obrigatórios e não nulos. Propriedades adicionais do provedor são ignoradas para permitir metadados, enquanto propriedades duplicadas e conteúdo após o JSON são recusados. Corpos de sucesso são limitados a 4096 bytes.

### Timeouts e política de novas tentativas

| Variável | Default | Limite aceito |
|---|---|---|
| `SCORE_BASE_URL` | `http://localhost:18082` | Origem HTTP/HTTPS sem credenciais, caminho, query ou fragmento |
| `SCORE_CONNECT_TIMEOUT_MS` | 500 | 1 a 5000 ms |
| `SCORE_READ_TIMEOUT_MS` | 1000 | 1 a 10000 ms |
| `SCORE_MAX_ATTEMPTS` | 2 | 1 a 3 tentativas totais no adaptador, incluindo a inicial |
| `SCORE_RETRY_DELAY_MS` | 100 | 0 a 500 ms entre tentativas |

O timeout de resposta do [JdkClientHttpRequestFactory](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/http/client/JdkClientHttpRequestFactory.html) limita a espera também durante a leitura do corpo. O adaptador repete somente 500/502/503/504 do provedor e falhas de I/O sem timeout, até o máximo configurado. Timeout, 404, 429, demais 4xx, outros 5xx e resposta inválida não são repetidos. Uma thread interrompida mantém a interrupção e encerra a operação.

A política padrão faz uma tentativa adicional após 100 ms para falhas transitórias, enquanto interrompe o stub lento em cerca de um segundo, sem retry. Isso limita a espera e evita amplificar um provedor já lento. Uma alternativa é `SCORE_MAX_ATTEMPTS=1`, reduzindo chamadas e latência, mas sem recuperação automática de falhas transitórias. Não há circuit breaker ou retry ilimitado. Limites inválidos impedem a inicialização.

No host, os defaults funcionam com a porta padrão do WireMock. Se mudou `WIREMOCK_PORT`, exporte `SCORE_BASE_URL` com a porta efetiva antes de iniciar o Java. Dentro do container, use `SCORE_BASE_URL=http://wiremock:8080`, como no comando Docker. As variáveis do `.env` não são exportadas automaticamente para o Java iniciado no terminal.

### Validação local

Com Docker ativo, execute os testes e prepare as dependências:

```sh
./mvnw -Pintegration clean verify
docker compose up -d --wait postgres keycloak wiremock
python3 infra/wiremock/verify.py
```

Encerre a versão anterior da API. No terminal com as variáveis de banco já configuradas:

```sh
export SCORE_BASE_URL=http://localhost:18082
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

Gere tokens novos no Postman e carregue `TOKEN_USER`/`TOKEN_ADMIN` conforme a seção de autenticação. Consulte a listagem ou a busca para obter IDs atuais. Se preservou os dados da T03.3, Ana Silva era o ID 4 e Ana Souza, o ID 5:

```sh
curl -i -H "Authorization: Bearer $TOKEN_USER" http://localhost:8080/customers/4/score
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/customers/5/score
curl -i http://localhost:8080/customers/4/score
curl -i -H "Authorization: Bearer $TOKEN_USER" http://localhost:8080/customers/9223372036854775807/score
```

Esperado: 200 com score 750/LOW_RISK para Ana Silva; 200 com 450/MEDIUM_RISK para Ana Souza, mesmo INACTIVE; 401 sem token; e 404 `CUSTOMER_NOT_FOUND` para ID inexistente. Ajuste os IDs conforme seu banco. Se os clientes ainda não existem, cadastre-os com os payloads da T03.3; não substitua o CPF por um valor aleatório, pois o WireMock usa fixtures fixas. CPFs de registros já excluídos continuam reservados e não podem ser recadastrados.

Para explorar falhas manualmente, use clientes disponíveis com os CPFs da tabela do provedor. Os retornos da API serão 404 para a fixture sem Score, 503 para as de 500/503, 502 para JSON inválido e 504 para atraso de três segundos. Não é preciso modificar seus dados locais para comprovar esses casos: `CustomerScoreIT` cadastra cada fixture em PostgreSQL descartável e verifica os erros e a contagem de chamadas. O teste do adaptador também cobre recuperação de 503 seguida de sucesso.

Opcionalmente, pare somente o WireMock e repita uma consulta de Score de cliente disponível: uma conexão recusada retorna 503 `SCORE_UNAVAILABLE`. Se a rede aguardar até o timeout, o retorno é 504 `SCORE_TIMEOUT`, situação que também pode ocorrer pela rede Docker. Em seguida, inicie o simulador novamente:

```sh
docker compose stop wiremock
curl -i -H "Authorization: Bearer $TOKEN_USER" http://localhost:8080/customers/4/score
docker compose up -d --wait wiremock
```

O CRUD e o healthcheck não dependem de Score e continuam operando quando o provedor está fora do ar. Esta etapa não altera migrations, CPF reservado ou regras de exclusão lógica, nem publica eventos.

## Contratos de eventos aprovados (T06)

O documento aprovado está em [contracts/events/README.md](contracts/events/README.md), com schemas JSON v1, exemplos válidos/inválidos, topologia e políticas de publicação, concorrência, idempotência, ACK, retry e DLQ. Os contratos compartilhados ficam fora de `docs/`, que continua ignorado pelo Git.

**Aprovado explicitamente em 10/11/2026:** criação com payload mínimo; solicitação de status com `expectedVersion`; exchange de domínio topic; filas quorum próprias por consumidor; três rodadas de retry (5 s, 30 s, 120 s), seguidas de DLQ. Versionar o cliente e expor sua versão na API será uma mudança dos próximos incrementos.

Para validar os contratos isoladamente (Python 3.10+):

```sh
python3 -m venv contracts/events/.venv
contracts/events/.venv/bin/python -m pip install -r contracts/events/requirements.txt
contracts/events/.venv/bin/python contracts/events/verify.py
```

O ambiente `.venv` é ignorado pelo Git. A instalação inicial baixa dependências; a verificação seguinte é offline. Ela não inicia broker, publica mensagens, modifica dados nem executa produtores/consumidores. O checkpoint da T06 foi aprovado; os testes com broker e a implementação permanecem nas etapas seguintes.

## RabbitMQ local e topologia (T07.1)

Este incremento preparou a infraestrutura aprovada na T06. A T07.2, descrita adiante, conecta o cadastro ao outbox e à publicação. Ainda não há consumidor de negócio.

A imagem oficial está fixada em `rabbitmq:4.4.0-management`, com suporte a quorum queues, TTL e dead-letter at-least-once. A topologia é gerada diretamente de [contracts/events/topology.v1.json](contracts/events/topology.v1.json); não mantemos uma segunda lista manual de filas. São quatro exchanges, dez filas, doze bindings e dez policies. O volume `rabbitmq_data` preserva os dados e o hostname fixo `rabbitmq` mantém a identidade do nó durante recriações. É um nó local; não representa um cluster de alta disponibilidade.

### Preparar e iniciar

Na raiz, usando o `.env` já existente:

```sh
python3 infra/rabbitmq/configure.py
python3 infra/rabbitmq/configure.py --check
docker compose up -d --wait rabbitmq
python3 infra/rabbitmq/verify.py
```

O primeiro comando cria `.local/rabbitmq/definitions.json`, com hashes SHA-256 e salt aleatório no formato do RabbitMQ. O diretório é local, privado e ignorado pelo Git/Docker build; senhas em texto aberto não são gravadas no arquivo. Execuções repetidas com os mesmos dados preservam os hashes e o arquivo. O broker importa esse arquivo no boot, montado somente para leitura. O arquivo precisa existir antes do `up`; o Compose não cria um diretório no lugar dele se estiver ausente.

Há defaults de demonstração no Compose para as novas variáveis: não sobrescreva o `.env` existente com `.env.example`. Você pode personalizá-las antes da primeira inicialização. `configure.py` usa Python padrão e a configuração efetiva do Compose, sem acessar o broker. `--check` confere o arquivo sem modificá-lo.

O verificador faz somente GETs administrativos: **38 verificações**, sem publicar, consumir, purgar mensagens ou exigir filas vazias. Confere versão, saúde, vhost, contas, ACLs de recursos/tópicos, exchanges, filas quorum, policies e bindings. Senhas e hashes não são impressos. Falhas exibem um diagnóstico sanitizado. Endereço alternativo de Management deve ser de loopback:

```sh
python3 infra/rabbitmq/verify.py --base-url http://localhost:15672
```

### Endereços e contas

| Acesso | Host | Rede Compose |
|---|---|---|
| Console/HTTP Management | [localhost:15672](http://localhost:15672/) | `rabbitmq:15672` |
| AMQP 0-9-1 | `localhost:15673` | `rabbitmq:5672` |

As duas portas são publicadas exclusivamente em `127.0.0.1`. A porta de console não aceita conexões AMQP. Vhost: `datum`. Não confundir essas credenciais com as do PostgreSQL ou com tokens USER/ADMIN do Keycloak.

| Conta RabbitMQ | Variável de senha | Escopo |
|---|---|---|
| `rabbitmq_admin` | `RABBITMQ_ADMIN_PASSWORD` | Provisionamento e console; não usar nas aplicações |
| `customer_app` | `CUSTOMER_RABBITMQ_PASSWORD` | Publicar criação, ler fila de status, publicar retries |
| `notification_app` | `NOTIFICATION_RABBITMQ_PASSWORD` | Ler fila de criação, publicar retries |
| `status_publisher` | `STATUS_PUBLISHER_RABBITMQ_PASSWORD` | Publicar apenas a routing key de status no exchange de domínio |

O usuário `guest` e o vhost `/` não são criados no bootstrap. Apenas a conta administrativa possui tag de administrador; contas das aplicações não recebem permissão de configurar recursos nem acesso ao console. Topic permissions impedem que `status_publisher` publique criação ou que `customer_app` publique status no exchange compartilhado. Nenhuma aplicação pode consumir a fila de outra.

Os exchanges internos de retry são `direct`, conforme aprovado: neles, a ACL limita o acesso ao exchange, não a routing key. Cada consumidor deriva a chave de retry da sua própria fila, sem aceitar destinos arbitrários. O publicador externo não pode escrever nos exchanges internos. O broker controla autenticação e roteamento; ele não valida o JSON do corpo contra o schema, responsabilidade dos produtores/consumidores.

Variáveis de porta: `RABBITMQ_AMQP_PORT` (15673) e `RABBITMQ_MANAGEMENT_PORT` (15672). Seus defaults e os quatro passwords locais constam de `.env.example`. As contas importadas pertencem ao volume persistente: alterar `.env`/arquivo gerado não é um procedimento de rotação de senhas já existentes. Uma rotação exige alteração explícita no broker e sincronização das configurações; não apague o volume para trocar uma senha. O verificador detecta credenciais/topologia divergentes.

### Conferência manual no console

Acesse o console com `rabbitmq_admin` e a senha efetiva do `.env` (default de demonstração: `rabbitmq_admin_local_only`). Selecione `datum` e confira:

- Exchanges `datum.customer.events` (topic), `datum.customer.retry`, `datum.customer.redelivery` e `datum.customer.dead` (direct).
- Fila principal `customer-notification-service.created.v1`, três filas `.retry.1/.2/.3` e uma `.dlq`.
- Fila principal `customer-service.status-change.v1`, três filas `.retry.1/.2/.3` e uma `.dlq`.
- Filas quorum duráveis, limites de 10000 mensagens/64 MiB e overflow `reject-publish`.
- Policy das principais: delivery-limit 5 e dead-letter at-least-once para sua DLQ.
- Policies de retry: TTLs de 5000/30000/120000 ms e retorno pelo exchange `datum.customer.redelivery` apenas à fila do consumidor correspondente.

Na infraestrutura recém-criada, antes dos cadastros da T07.2, as filas começam vazias. Desde a T07.2, novos cadastros podem acrescentar mensagens à fila de criação; ainda não há consumidor de negócio. Estatísticas do console podem levar alguns segundos para aparecer. A verificação usa policies declaradas e seus padrões/prioridades para conferir a seleção mesmo antes da coleta de estatísticas. O verificador de infraestrutura não usa contadores como prova de publicação pela aplicação; a T07.2 verifica também o conteúdo e a confirmação do envio.

Para parar apenas o broker preservando dados:

```sh
docker compose stop rabbitmq
```

Para recriar o container com o mesmo volume:

```sh
docker compose up -d --force-recreate --wait rabbitmq
python3 infra/rabbitmq/verify.py
```

### Teste AMQP isolado e reproduzível

O smoke cria seu próprio projeto Compose com nome aleatório, portas livres e credenciais de teste. Ele inicia apenas outro RabbitMQ e remove somente esse ambiente e seu volume ao final. Não recebe endereço de broker externo e não publica/consome mensagens no ambiente local do projeto. Requer Docker e Pika, instalado somente em uma venv de teste:

```sh
python3 -m venv infra/rabbitmq/.venv
infra/rabbitmq/.venv/bin/python -m pip install -r infra/rabbitmq/requirements-test.txt
infra/rabbitmq/.venv/bin/python infra/rabbitmq/smoke.py
```

Esse teste confere credenciais AMQP reais, permissões positivas/negativas, publisher confirms, mandatory return, limite de corpo de 16 KiB, persistência após recriação, rejeição à DLQ, retorno dos retries de 5 segundos para ambos os consumidores e ausência de senhas/hashes nos logs. São probes de infraestrutura; não implementam consumidores de negócio, inbox ou outbox.

Para esperar também pelos TTLs reais de 30 e 120 segundos, rode a variante completa (as mensagens aguardam em paralelo; o teste dura alguns minutos):

```sh
infra/rabbitmq/.venv/bin/python infra/rabbitmq/smoke.py --all-retries
```

A transferência de uma mensagem com o DLX funcionando não comprova todos os casos de falha possíveis. Perda de conexão entre confirm/ACK, destino indisponível, saturação, delivery-limit sob crashes e deduplicação transacional continuarão sendo verificados com as aplicações nas T07.2/T08/T09/T11. O RabbitMQ disponível nesta etapa não implica que cadastros antigos já tenham eventos.

Referências: [imagem oficial](https://github.com/docker-library/official-images/blob/master/library/rabbitmq), [importação de definitions](https://www.rabbitmq.com/docs/definitions), [credenciais e hashes](https://www.rabbitmq.com/docs/passwords), [permissões](https://www.rabbitmq.com/docs/access-control).

## Cadastro e publicação confiável por outbox (T07.2)

`POST /customers` agora grava cliente e um registro em `customer_outbox` na mesma transação PostgreSQL. Falha ao persistir qualquer um deles desfaz ambos. A resposta continua sendo 201 após o commit, sem aguardar o RabbitMQ. CPF duplicado continua 409 e não gera um segundo evento. PUT/DELETE não geram eventos de criação.

A nova migration `V2__create_customer_outbox.sql` preserva os clientes existentes. Não há backfill: apenas novos cadastros feitos pelo serviço geram eventos. A tabela tem FK para o cliente, unicidade por cliente/tipo de criação, corpo de até 16 KiB, estado, tentativas, próximo envio, reserva e instante de publicação. Não é exposta por endpoint público.

### Fluxo e garantias

1. O cadastro cria um UUID v4 e persiste o JSON mínimo v1 com `occurredAt` igual ao `created_at` do cliente. Nome, CPF e e-mail não entram no evento.
2. O agendador busca registros PENDING vencidos com `FOR UPDATE SKIP LOCKED`, reserva um por vez e confirma essa transação curta. A reserva tem token aleatório e expiração.
3. Fora da transação do banco, valida o corpo contra as regras do contrato e envia bytes UTF-8 originais com delivery mode persistente, message_id/correlation_id iguais ao eventId, tipo CUSTOMER_CREATED e mandatory habilitado.
4. Aguarda confirm correlacionado por até 5 s. Somente ACK positivo sem basic.return permite marcar PUBLISHED. Falha, NACK, return e timeout mantêm PENDING, com backoff de 1, 2, 4, 8, 16 e depois 30 s, sem limite de tentativas que descarte o evento.
5. Um corpo estruturalmente inválido é marcado BLOCKED/INVALID_EVENT para análise, sem publicar ou impedir eventos saudáveis. Não há endpoint automático de replay/desbloqueio.

O mesmo UUID, instante e corpo são preservados em todas as tentativas. Spring acrescenta headers técnicos de correlação do transporte, que podem mudar entre tentativas; não são campos do contrato nem contêm nomes de classes/dados pessoais. Não é usado conversor de objetos Java para serialização AMQP.

A reserva padrão é de 30 s. Se a instância morrer, outra poderá retomar após a expiração. Atualizações finais verificam o token da reserva: um dono antigo não pode sobrescrever o resultado de uma reserva mais recente. O relay não mantém conexão/transação PostgreSQL ocupada enquanto aguarda o broker. Cada ciclo trabalha até dez eventos; após falha de transporte, encerra o ciclo para evitar multiplicar conexões.

Uma queda depois de o broker aceitar o evento e antes de marcar PUBLISHED pode produzir nova entrega do mesmo evento. Portanto, a garantia é **at-least-once**, com deduplicação dos efeitos no consumidor da T09; não existe promessa de exactly-once. PUBLISHED significa confirmação pelo broker, não notificação processada. Um cliente excluído logicamente após o cadastro continua tendo seu fato de criação publicado.

O publicador usa `customer_app`, sem permissão de configurar recursos; `spring.rabbitmq.dynamic=false` evita declaração por RabbitAdmin. A topologia deve ser preparada pela T07.1. Falha de credencial ou configuração de rota conserva o registro pendente e sinaliza código técnico, sem apagar o cadastro. Os logs próprios registram somente eventId, código e classe de falha, nunca payload, CPF, e-mail, senha ou token.

### Configuração

| Variável | Padrão | Uso |
|---|---|---|
| `CUSTOMER_RABBITMQ_HOST` | localhost | Em container: rabbitmq |
| `CUSTOMER_RABBITMQ_PORT` | 15673 | Em container: 5672 |
| `CUSTOMER_RABBITMQ_PASSWORD` | customer_rabbitmq_local_only | Senha da conta customer_app no broker |
| `OUTBOX_PUBLISHER_ENABLED` | true | false pausa apenas o envio; novos cadastros continuam gravando outbox |
| `OUTBOX_POLL_DELAY_MS` | 1000 | Intervalo após cada ciclo, de 100 a 10000 ms |
| `OUTBOX_BATCH_SIZE` | 10 | Até 100 reservas individuais por ciclo, mínimo 1 |
| `OUTBOX_CONFIRM_TIMEOUT_MS` | 5000 | De 100 a 5000 ms para confirm |
| `OUTBOX_LEASE_MS` | 30000 | De 20000 a 120000 ms; ao menos confirm timeout + 15000 ms |

Vhost `datum`, usuário `customer_app`, exchange `datum.customer.events`, routing key `customer.created.v1`. Conexão tem timeout de 2 s e RPC de canal, 5 s. Confirms correlacionados, returns e mandatory são exigidos na configuração do publicador. Não há retry oculto no RabbitTemplate; a política de novas tentativas pertence ao outbox. Valores inválidos de configuração impedem a inicialização.

As variáveis do `.env` não são exportadas automaticamente para o Java no terminal. Se usa senhas ou portas próprias, exporte os valores efetivos. A porta AMQP não é a porta do console.

### Validação local: publicação normal

Com Docker ativo, execute na raiz:

```sh
./mvnw -Pintegration clean verify
python3 infra/rabbitmq/configure.py
docker compose up -d --wait postgres keycloak rabbitmq wiremock
python3 infra/rabbitmq/verify.py
```

Encerre a versão antiga da API. No terminal com as variáveis de banco já configuradas, ajuste as do broker (exemplo com defaults locais) e inicie o novo JAR:

```sh
export CUSTOMER_RABBITMQ_HOST=localhost
export CUSTOMER_RABBITMQ_PORT=15673
export CUSTOMER_RABBITMQ_PASSWORD=customer_rabbitmq_local_only
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

Gere token ADMIN novo no Postman. Cadastre um cliente fictício novo:

```sh
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cadastro Outbox Online","cpf":"24681035726","email":"outbox-online@example.test","status":"ACTIVE"}'
```

Esperado: 201. Se o CPF já estiver reservado, o 409 não gera evento; para repetir, use outro CPF fictício válido e ainda não reservado. No DBeaver, conectado ao `customer_db` como `customer_app`:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT event_id, customer_id, event_type, schema_version, state, attempt_count,
       occurred_at, next_attempt_at, last_error_code, published_at
FROM customer_outbox ORDER BY occurred_at DESC;
```

Esperado: V1/V2/V3 aplicadas e o novo evento PUBLISHED após o próximo ciclo. No console RabbitMQ, vhost datum, a fila `customer-notification-service.created.v1` deve ganhar uma mensagem Ready. Se o serviço de notificações da T09 estiver ativo, ele consome e confirma a mensagem, registrando-a no seu próprio banco; com ele parado, ela permanece Ready. Evite Get messages com remoção/purge durante a validação; a contagem Ready pode demorar alguns segundos para atualizar.

### Validação local: broker indisponível e recuperação

Pare somente o broker, mantendo API e banco em execução, e faça outro cadastro com CPF novo:

```sh
docker compose stop rabbitmq
curl -i -H "Authorization: Bearer $TOKEN_ADMIN" -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Cadastro Outbox Pendente","cpf":"24681035807","email":"outbox-pendente@example.test","status":"ACTIVE"}'
curl -i http://localhost:8080/actuator/health
```

Esperado: cadastro 201, health UP e evento PENDING. Confira no SQL eventId/occurredAt, contagem de tentativas e last_error_code. O horário do próximo envio avança com o backoff; as tentativas não acontecem em loop apertado. Não é necessário alterar dados no SQL para forçar retry.

Inicie o broker novamente:

```sh
docker compose up -d --wait rabbitmq
```

Espere o horário de `next_attempt_at` mais o intervalo de polling (padrão: backoff até 30 s, mais o tempo de conexão/confirm). O evento deve mudar para PUBLISHED com o mesmo UUID e instante, e a mensagem chegar à fila. Reiniciar o JAR enquanto há pendência também deve preservar o registro e permitir recuperação; uma reserva interrompida pode precisar expirar por até 30 s antes de ser retomada.

Os testes automatizados também simulam perda de confirmação e queda entre confirm e marcação local, situação em que uma duplicata é esperada. A deduplicação da notificação é validada na T09. A versão de agregado e o consumo de CUSTOMER_STATUS_CHANGE estão descritos na T08 abaixo.

Referências: [confirms e returns correlacionados do Spring AMQP](https://docs.spring.io/spring-amqp/reference/3.2/amqp/template.html), [SKIP LOCKED no PostgreSQL](https://www.postgresql.org/docs/17/sql-select.html).

## Consumo idempotente de status (T08)

O `customer-service` consome exclusivamente `customer-service.status-change.v1`, usando a conta `customer_app` já provisionada. Não há endpoint HTTP para publicar eventos nem dependência do serviço de notificações. O simulador externo usa `status_publisher`, sem acesso ao banco, leitura de filas ou escrita nos exchanges internos.

### Versão e resultado transacional

A migration V3 acrescenta `customers.version` e `customer_event_inbox`. Clientes existentes começam na versão **0**, sem alterar seu status nem recuperar um histórico anterior. POST, GET, PUT, listagem e busca passam a devolver `version`; o campo é somente de saída, não deve ser incluído nos corpos de POST/PUT.

- Uma solicitação válida precisa trazer `expectedVersion` igual à versão corrente. Aplica status, incrementa a versão e avança `updated_at`, inclusive quando o status solicitado já é o atual.
- PUT com mudança efetiva e DELETE lógico também incrementam a versão, usando a mesma disciplina de locks. PUT sem mudança efetiva pode manter a versão. A exclusão continua independente de ACTIVE/INACTIVE.
- Versão passada ou futura é `VERSION_CONFLICT`; cliente inexistente/excluído é `CUSTOMER_NOT_FOUND`. O serviço registra REJECTED e encaminha a mensagem para DLQ. Não espera uma versão futura tornar-se válida.
- O inbox tem chave única `(consumer_id, event_id)` e fingerprint SHA-256 dos campos validados/normalizados. Ordem, espaços, casas fracionárias equivalentes e representação inteira equivalente não criam conflito. BIGINT não passa por double.
- Uma duplicata já APPLIED recebe ACK sem alterar versão, status ou horários. Uma duplicata REJECTED continua para DLQ. Mesmo ID com outro conteúdo é `EVENT_ID_CONFLICT`; preserva a decisão original. Nova intenção precisa de novo ID.

Atualização e resultado no inbox pertencem à mesma transação PostgreSQL. Inserção concorrente do mesmo ID aguarda o resultado anterior. Não há expiração automática da deduplicação. A tabela não possui FK para clientes, permitindo registrar rejeições de clientes inexistentes; não guarda corpo JSON, CPF, nome ou e-mail. `PROCESSING` é um estado interno da transação; só APPLIED/REJECTED devem permanecer após um processamento concluído.

### ACK, retry e quarentena

O listener usa bytes JSON e ACK manual, sem conversão automática de classes Java. Valida contrato fechado, limite de 16 KiB, UTF-8, IDs/tipos/datas, propriedades AMQP e origem da entrega antes de abrir a transação. Headers de retry fornecidos pela entrada de domínio são rejeitados. Dados inválidos seguem diretamente à DLQ sem acesso ao cliente.

ACK ocorre depois do commit. Falha antes do commit desfaz cliente/inbox e permite nova tentativa. Se o canal cair depois do commit e antes do ACK, a reentrega consulta o resultado persistido. Essa garantia é **at-least-once**, com efeito local idempotente.

Falha de processamento/transação transfere para `.retry.1`, `.retry.2` e `.retry.3`, com TTLs de 5, 30 e 120 s da topologia aprovada. O serviço mantém os bytes/identidade, deriva a routing key da sua própria fila e espera confirm positivo sem return **antes de ACK da origem**. A quarta rodada com falha vai para DLQ. Falha/ambiguidade ao transferir não confirma a origem: aguarda o intervalo de recuperação e fecha o canal, permitindo reentrega. Não usa NACK/requeue imediato em loop. Crashes podem gerar mais entregas físicas que as quatro rodadas lógicas; o delivery-limit do broker também se aplica.

DLQ não tem consumidor nem replay automático. Logs próprios registram ID, código e tipo de falha, sem payload ou mensagem da exceção. Mensagens inválidas na DLQ podem conter dados indevidos; a análise é administrativa, sem exportá-las para logs. Confirm do publicador comprova aceitação pelo broker, não aplicação do status.

| Variável | Padrão | Uso |
|---|---|---|
| `STATUS_CONSUMER_ENABLED` | true | false pausa somente este consumidor |
| `STATUS_CONSUMER_PREFETCH` | 10 | De 1 a 100 entregas antecipadas por consumidor |
| `STATUS_CONSUMER_RECOVERY_DELAY_MS` | 5000 | De 1000 a 30000 ms; recuperação e pausa após transferência incerta |
| `STATUS_CONSUMER_CONFIRM_TIMEOUT_MS` | 5000 | De 100 a 5000 ms para confirmar transferência ao retry |

Há um consumidor por instância. A topologia pertence ao provisionamento, com declarações automáticas desabilitadas. `OUTBOX_PUBLISHER_ENABLED=false` não pausa o consumidor de status, e `STATUS_CONSUMER_ENABLED=false` não pausa o outbox. O `.env` precisa ser exportado explicitamente quando a aplicação roda por Java/IDE.

### Validar localmente, antes do commit

1. Execute `./mvnw -Pintegration clean verify`. Inicie PostgreSQL, Keycloak e RabbitMQ já configurados, encerre a API antiga e inicie o novo JAR com as mesmas variáveis da T07.2. Confira V3 no histórico Flyway.
2. No Postman, consulte um cliente existente não excluído com token USER/ADMIN: `GET http://localhost:8080/customers/7`. Use o ID real do seu ambiente e anote `status`, `version` e `updatedAt`.
3. Prepare o publicador local (Python 3.10+, na raiz):

```sh
python3 -m venv infra/rabbitmq/.venv
infra/rabbitmq/.venv/bin/python -m pip install -r infra/rabbitmq/requirements-test.txt
```

4. Publique com o ID e a versão **acabados de consultar**. Exemplo para cliente 7 na versão 0:

```sh
infra/rabbitmq/.venv/bin/python infra/rabbitmq/publish_status.py \
  --customer-id 7 --expected-version 0 --status INACTIVE
```

O script valida o schema, salva o evento em `.local/rabbitmq/status-events/<eventId>.json` com permissão privada e imprime o caminho para replay. Lê porta/senha efetivas do Compose em memória; não imprime credenciais. Pode receber `--env-file`/`-p`/`-f` para selecionar o Compose. O arquivo e `.venv` são ignorados pelo Git/Docker. Se a publicação não for confirmada, preserve o arquivo e reenvie o mesmo ID.

5. Consulte novamente o cliente e o inbox no DBeaver (`customer_db`, `customer_app`):

```sql
SELECT id, status, version, updated_at, deleted_at FROM customers WHERE id = 7;
SELECT event_id, customer_id, outcome, reason_code, processed_at
FROM customer_event_inbox ORDER BY processed_at DESC;
```

Esperado: INACTIVE, versão 1 e uma linha APPLIED; fila principal sem mensagem pendente após ACK. A contagem no console pode demorar para atualizar.

6. Reenvie usando o caminho exato impresso (substitua `<eventId>`):

```sh
infra/rabbitmq/.venv/bin/python infra/rabbitmq/publish_status.py \
  --file '.local/rabbitmq/status-events/<eventId>.json'
```

Esperado: o mesmo registro APPLIED, versão 1 e `updated_at` inalterado. O log registra DUPLICATE. Para testar conflito, publique uma **nova** solicitação com a versão antiga 0: novo ID, inbox REJECTED/VERSION_CONFLICT, cliente inalterado e mensagem na DLQ. Não repita o comando de nova solicitação para simular duplicata, pois ele gera outro ID.

Os testes automatizados usam recursos descartáveis: duplicatas normalizadas e concorrentes, IDs conflitantes, versões divergentes, clientes ausentes/excluídos, corrida com PUT/DELETE, rollback integral, janela commit/ACK, JSON/metadados inválidos, TTL real, conexões PostgreSQL indisponíveis, retry sem rota e exaustão. Não altere permissões/constraints nem apague filas do seu ambiente para reproduzir essas falhas manualmente.

## Notificações simuladas independentes (T09)

O `customer-notification-service` consome `CUSTOMER_CREATED` da fila `customer-notification-service.created.v1`. O efeito de negócio é um registro fictício auditável: **nenhum e-mail, SMS ou envio externo é executado**. HTTP continua disponível somente para operação via Actuator, sem endpoints de notificações ou de clientes.

### Banco próprio e transação

A migration `V1__create_notification_records_and_inbox.sql` pertence exclusivamente ao `notification_db`. O número V1 é independente das migrations do `customer_db`. O serviço usa JDBC e transações Spring, com usuário `notification_app`, e não depende do artefato Java, API ou banco do `customer-service`.

| Tabela | Conteúdo |
|---|---|
| `notification_event_inbox` | Chave única `(consumer_id, event_id)`, customerId, occurredAt, fingerprint normalizado, resultado e processedAt |
| `notification_records` | notificationId UUID, mesmo evento/consumidor, customerId, kind `CUSTOMER_CREATED_SIMULATION` e registeredAt |

O customerId é uma referência do evento, sem FK para outro banco e sem consulta ao estado atual do cliente. Um evento de criação válido pode ser processado mesmo que o cliente tenha sido atualizado/excluído depois ou a API de clientes esteja parada. Não há nome, CPF, e-mail, JWT, corpo JSON ou mensagem de exceção nessas tabelas.

Inbox e registro fictício são gravados na mesma transação. A unicidade do evento serializa duplicatas concorrentes e há uma segunda unicidade por evento no registro. Falha em qualquer escrita reverte ambas. `PROCESSING` é transitório dentro da transação; o resultado concluído é `NOTIFICATION_REGISTERED`. A simulação confirma o registro, sem indicar entrega real de e-mail.

O fingerprint SHA-256 usa os campos validados, IDs inteiros exatos e instante UTC normalizado, conforme a T06. Reordenação/whitespace/casas decimais equivalentes não mudam a identidade semântica. Duplicata idêntica recebe ACK e conserva notificationId/registeredAt; mesmo eventId com conteúdo diferente recebe `EVENT_ID_CONFLICT` e vai à DLQ, preservando o registro original. Não há expiração automática das chaves. UUIDs diferentes representam ocorrências diferentes, mesmo com customerId igual.

### Entrega, falhas e isolamento

O listener valida corpo UTF-8/schema fechado de até 16 KiB e metadados AMQP antes da transação, sem desserialização arbitrária de classes. ACK manual ocorre após o commit. Falha depois do commit e antes do ACK permite reentrega sem repetir o registro. Logs registram eventId/código/tipo de falha, sem payload nem dados pessoais.

Falhas de processamento usam as três filas de retry já provisionadas: 5, 30 e 120 s. A publicação mantém bytes/identidade, deriva a routing key apenas da própria fila e exige confirm sem return antes de confirmar a origem. Transferência incerta deixa a origem sem ACK e recupera o canal após pausa. Falha na última rodada, mensagem inválida ou conflito definitivo vai à `.dlq`, sem requeue imediato/replay automático. A entrega é **at-least-once**, com deduplicação durável do efeito local; crashes podem gerar entregas físicas adicionais.

A conta de banco não tem CONNECT em `customer_db`; a conta AMQP `notification_app` só lê a fila de criação e publica no exchange de retry. Não pode configurar a topologia, ler status ou publicar eventos de domínio. O serviço não recebe credenciais de clientes/Keycloak. Cada imagem contém apenas seu módulo. Não foi extraída uma biblioteca comum que imponha deploy conjunto aos serviços.

Falha de notificação não desfaz um cadastro já confirmado. Com o consumidor parado, a fila conserva o evento; com uma falha transitória, o processamento posterior usa retry e a inbox. Health considera o banco próprio; RabbitMQ é verificado separadamente com a T07.1 e inspeção de fila/consumidor. `UP` sozinho não comprova consumo. Para testar falhas de banco/constraints, use a suíte descartável, sem alterar permissões ou dados do ambiente local.

| Variável | Padrão | Uso |
|---|---|---|
| `NOTIFICATION_DB_URL` | jdbc:postgresql://localhost:15432/notification_db | No container: jdbc:postgresql://postgres:5432/notification_db |
| `NOTIFICATION_DB_PASSWORD` | Obrigatória | Senha do usuário notification_app no banco |
| `NOTIFICATION_RABBITMQ_HOST` | localhost | No container: rabbitmq |
| `NOTIFICATION_RABBITMQ_PORT` | 15673 | No container: 5672 |
| `NOTIFICATION_RABBITMQ_PASSWORD` | notification_rabbitmq_local_only | Senha da conta notification_app no broker |
| `NOTIFICATION_CONSUMER_ENABLED` | true | false pausa somente esse consumidor |
| `NOTIFICATION_CONSUMER_PREFETCH` | 10 | De 1 a 100 entregas antecipadas |
| `NOTIFICATION_CONSUMER_RECOVERY_DELAY_MS` | 5000 | De 1000 a 30000 ms para recuperação/pausa após handoff incerto |
| `NOTIFICATION_CONSUMER_CONFIRM_TIMEOUT_MS` | 5000 | De 100 a 5000 ms para confirmar retry |

Vhost/usuários/filas são fixos conforme o contrato. O `.env` é lido pelo Compose; exporte as variáveis explicitamente para Java/IDE. Não passe `CUSTOMER_DB_PASSWORD` nem usuário administrativo ao serviço de notificações.

### Validação local em etapas

1. Execute `./mvnw -Pintegration clean verify`, com Docker ativo. O comando usa PostgreSQL/RabbitMQ descartáveis e não toca o banco nem as filas locais.
2. Com a topologia já preparada, inicie `docker compose up -d --wait postgres rabbitmq`. Encerre o JAR antigo de notificações e, em outro terminal na raiz, configure os valores efetivos do seu `.env` (abaixo os defaults locais):

```sh
export NOTIFICATION_DB_URL=jdbc:postgresql://localhost:15432/notification_db
export NOTIFICATION_DB_PASSWORD=notification_local_only
export NOTIFICATION_RABBITMQ_HOST=localhost
export NOTIFICATION_RABBITMQ_PORT=15673
export NOTIFICATION_RABBITMQ_PASSWORD=notification_rabbitmq_local_only
java -jar customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar
```

**Ao iniciar, o consumidor processa também os eventos de criação que já estão pendentes na fila.** Não será necessário recadastrar clientes. Cadastros anteriores à implantação do outbox, sem evento, continuam sem backfill.

Para conferir primeiro somente a migration/saúde, inicie com `NOTIFICATION_CONSUMER_ENABLED=false` no mesmo terminal. Nesse modo não existe listener ativo. Depois de conferir as tabelas, encerre o JAR, exporte `NOTIFICATION_CONSUMER_ENABLED=true` e inicie novamente para consumir as mensagens pendentes.

3. Confira `curl -i http://localhost:8081/actuator/health` e os logs com `code=NOTIFICATION_REGISTERED`. No DBeaver, crie uma conexão ao mesmo PostgreSQL/porta, mas com **Database `notification_db`**, usuário **`notification_app`** e senha `NOTIFICATION_DB_PASSWORD`. Não use `postgres` nem `customer_db` nessa conexão:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT notification_id, event_id, customer_id, kind, registered_at
FROM notification_records ORDER BY registered_at;
SELECT event_id, customer_id, outcome, processed_at
FROM notification_event_inbox ORDER BY processed_at;
```

Esperado: V1 aplicada, um registro por evento consumido e inbox `NOTIFICATION_REGISTERED`. No console RabbitMQ, as mensagens saem de Ready e não permanecem Unacked. Não use Get messages com remoção/purge para verificar.

4. Escolha o eventId de um registro confirmado, anote notificationId/registeredAt e faça replay pelo script de demonstração (substitua `<eventId>` pelo UUID real):

```sh
python3 -m venv infra/rabbitmq/.venv
infra/rabbitmq/.venv/bin/python -m pip install -r infra/rabbitmq/requirements-test.txt
infra/rabbitmq/.venv/bin/python infra/rabbitmq/replay_created.py --event-id '<eventId>'
```

O script é uma ferramenta do lado produtor: consulta **somente** um evento CUSTOMER_CREATED PUBLISHED no outbox com `customer_app`, via PostgreSQL do Compose, e republica seus bytes/UUID/instante com a credencial AMQP do produtor. Não cria evento novo, não altera cliente/outbox e não lê o banco de notificações. Credenciais/configuração permanecem em memória, sem imprimir payload. Aceita `--env-file`/`-p`/`-f` para selecionar outro Compose. Usar o usuário de notificações para essa consulta é proibido pelas permissões.

Esperado: confirm do broker e log `code=DUPLICATE`; o registro de notificação, seu ID e seus horários permanecem iguais. Repita após encerrar/iniciar o JAR de notificações: o resultado deve ser o mesmo. Esse replay explícito parte de evento já publicado, sem retirar mensagens da DLQ.

5. Para comprovar independência, pare somente o JAR de notificações e cadastre um cliente fictício novo pela API com token ADMIN, usando CPF válido ainda não reservado. Esperado: 201, outbox PUBLISHED e evento pendente na fila de criação. Inicie notificações novamente: deve registrar o evento sem alterar o cliente. Evite limpar tabelas/filas, pois elas preservam o histórico para as próximas etapas.

## Swagger UI e OpenAPI (T10)

O `customer-service` usa `springdoc-openapi-starter-webmvc-ui` **2.10.0**, fixado no POM e compatível com Spring Boot 3.5 pela [matriz oficial](https://springdoc.org/v2/). O documento é OpenAPI 3.0 e gerado a partir dos controllers/DTOs, com respostas e exemplos explícitos por operação. A T10 não acrescenta migrations nem altera os contratos HTTP de negócio. O serviço de notificações continua somente com Actuator e processamento assíncrono.

| Recurso | Execução Java | Execução Docker documentada |
|---|---|---|
| Interface | http://localhost:8080/swagger-ui/index.html | http://localhost:18080/swagger-ui/index.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs | http://localhost:18080/v3/api-docs |
| OpenAPI YAML | http://localhost:8080/v3/api-docs.yaml | http://localhost:18080/v3/api-docs.yaml |

`/swagger-ui.html` redireciona para a interface. Com a documentação habilitada, seus GET/HEAD são públicos para a demonstração local. Os endpoints de clientes continuam exigindo JWT/roles; abrir a interface não concede acesso. O servidor do documento é relativo (`/`), usando a mesma origem/porta da interface, inclusive a porta publicada pelo Docker. Não aponta as requisições para uma API externa nem oferece o exemplo Petstore.

### Autenticar e testar pela interface

1. Execute `./mvnw -Pintegration clean verify`. Encerre somente o JAR antigo de clientes e inicie o novo com as variáveis anteriores de banco/Keycloak/broker. O padrão local é `CUSTOMER_DOCS_ENABLED=true`.
2. Abra a interface e confira os grupos **Clientes** e **Score**, com sete operações e cadeados. Expanda uma operação para consultar parâmetros, request body, schema, exemplos e respostas.
3. Obtenha um **access token novo** pelo Postman usando o fluxo Authorization Code + PKCE já documentado na T04. O Swagger usa o mesmo token; não existe grant de senha, novo cliente Keycloak ou login próprio na API.
4. Clique **Authorize**, cole **somente o JWT**, sem o prefixo `Bearer`, e confirme. O esquema `bearerAuth` acrescenta o prefixo nas chamadas. Não use ID token nem refresh token.
5. Use **Try it out** e **Execute**. Comece por GET `/customers`: USER e ADMIN retornam 200. Para GET por ID/Score, use um ID real, não necessariamente o `123` fictício dos exemplos.
6. Para conferir escrita com USER, execute um POST com o exemplo válido: esperado 403 antes de gravar dados. Com ADMIN, um cadastro novo retorna 201 + Location; se o CPF já estiver reservado, retorna 409. PUT/DELETE com ADMIN alteram dados reais do ambiente local, como no Postman; escolha um cliente de teste.

Os tokens locais duram cinco minutos. Quando expirarem, gere outro e substitua em Authorize. Para trocar de perfil, use Logout no diálogo de autorização e informe o novo token. A configuração não persiste autorização no armazenamento do navegador; recarregar a página exige autorizar novamente. O validador remoto da interface está desabilitado e os recursos são servidos pela própria aplicação.

### Contratos documentados

| Operação | Perfis | Sucesso | Erros de contrato |
|---|---|---|---|
| POST `/customers` | ADMIN | 201, Location e cliente com version 0 | 400, 401, 403, 409, 500 |
| PUT `/customers/{id}` | ADMIN | 200, substituição completa | 400, 401, 403, 404, 409, 500 |
| DELETE `/customers/{id}` | ADMIN | 204, exclusão lógica sem corpo | 400, 401, 403, 404, 500 |
| GET `/customers/{id}` | USER/ADMIN | 200, cliente não excluído | 400, 401, 403, 404, 500 |
| GET `/customers` | USER/ADMIN | 200, página ordenada por ID | 400, 401, 403, 500 |
| GET `/customers/search` | USER/ADMIN | 200, busca parcial literal/paginada | 400, 401, 403, 500 |
| GET `/customers/{id}/score` | USER/ADMIN | 200, Score e Cache-Control no-store | 400, 401, 403, 404, 500, 502, 503, 504 |

Cada operação inclui schema e exemplo do sucesso, permissões em texto e extensão `x-required-roles`, além das respostas de erro aplicáveis. Roles são claims do JWT, não scopes OAuth2: `bearerAuth` não solicita scopes fictícios. DTOs documentam obrigatoriedade, tamanhos, CPF sem máscara/checksum, e-mail, status, paginação, limites Long e `version` somente leitura. POST/PUT recusam campos desconhecidos; não envie id/datas/version. Um PUT sem alteração efetiva conserva o estado; DELETE repetido retorna 404. Clientes INACTIVE continuam consultáveis, enquanto excluídos logicamente são omitidos.

As respostas de erro conhecidas usam `application/problem+json` com campos RFC 9457 e extensão `code`; validação pode acrescentar `errors` com field/message, sem valores rejeitados. Os exemplos usam os códigos implementados: `VALIDATION_ERROR`, `INVALID_JSON`, `INVALID_REQUEST`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `CPF_ALREADY_REGISTERED`, `CUSTOMER_NOT_FOUND`, `SCORE_NOT_FOUND`, `SCORE_INVALID_RESPONSE`, `SCORE_UNAVAILABLE`, `SCORE_TIMEOUT` e `INTERNAL_ERROR`. A documentação não antecipa `traceId`, que ainda não faz parte das respostas.

No Score, 404 possui dois exemplos: cliente ausente/excluído (sem chamar o provedor) ou Score ausente no provedor. Resposta inválida resulta em 502; falha transitória/conexão após tentativas limitadas, 503; timeout, 504. Falha não tratada de banco continua 500/INTERNAL_ERROR, inclusive no POST; 503 não foi acrescentado ao CRUD apenas para coincidir com uma proposta do planejamento. Eventos RabbitMQ permanecem documentados em `contracts/events`, sem endpoints HTTP fictícios.

### Controlar exposição

| Variável | Padrão local | Efeito |
|---|---|---|
| `CUSTOMER_DOCS_ENABLED` | true | Habilita spec JSON/YAML, configuração e Swagger UI |

Fora da demonstração local, configure `CUSTOMER_DOCS_ENABLED=false`. Exporte antes de iniciar o JAR, ou passe como variável ao container e recrie-o. Isso desabilita os recursos springdoc e remove sua permissão pública na cadeia de segurança. Nas URLs documentais, o comportamento esperado é 401 sem token e 403 com token ADMIN; a documentação não fica acessível apenas por autenticar. Clientes/Actuator preservam suas regras. O Compose lê `.env`, mas Java/IDE precisa receber a variável explicitamente; `docker run` precisa recebê-la com `--env`.

### Verificações automatizadas

Os 15 testes rápidos da documentação conferem as sete operações/matriz de respostas, schemas/referências locais, campos obrigatórios, entrada fechada, version somente leitura, exemplos válidos na Bean Validation, arquivos HTML/CSS/JS e configuração local sem persistência de autorização. Também verificam que a API continua protegida e que desabilitar a documentação impede acesso anônimo e ADMIN.

Os 13 cenários de `OpenApiContractIT` executam os exemplos **obtidos do documento gerado** contra controllers/segurança reais, PostgreSQL e WireMock descartáveis. Cobrem cadastro/409, consulta, página/busca, Score, PUT repetido, exclusão/404, validação, JSON inválido, 401/403 em todas as operações e os quatro erros mapeados de Score. As respostas são comparadas aos exemplos publicados; não acessam os dados locais. A imagem Docker também foi conferida com tokens reais do Keycloak, validação do documento pelo schema oficial OpenAPI e validação dos exemplos contra seus schemas.

## Resiliência e falhas distribuídas (T11)

As aplicações agora têm limites explícitos de conexão, pool, consulta, lock e transação no PostgreSQL. Os defaults por instância são pool máximo 10/mínimo ocioso 2; espera por conexão 2000 ms, validação 1000 ms; driver connect 2 s/socket 15 s; statement timeout 5000 ms, lock timeout 2000 ms e idle em transação 15000 ms. O gerenciador Spring usa timeout padrão de 10 s; operações com timeout explícito mantêm seu valor.

São propriedades por serviço (`CUSTOMER_DB_*` e `NOTIFICATION_DB_*`, em `.env.example`) aplicadas às conexões da aplicação, sem alterar o servidor global, os usuários ou a conexão do DBeaver. Não há nova migration. Os testes reais conferem configurações, limite do pool, SQLSTATE de consulta/lock, rollback e recuperação. A espera limitada por camada não representa um prazo absoluto único para toda requisição.

O [runbook T11](infra/resilience/README.md) descreve os limites, diagnóstico, garantias e nove cenários reproduzíveis. O verificador constrói as imagens T11 e cria um Compose aleatório com portas, volumes e credenciais exclusivos de teste. Não aceita destinos externos nem usa o banco/filas do projeto local.

Para começar por um cenário:

```sh
python3 -m venv infra/rabbitmq/.venv
infra/rabbitmq/.venv/bin/python -m pip install -r infra/rabbitmq/requirements-test.txt
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py --scenario score
```

Para a suíte inteira:

```sh
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py
```

O ciclo inclui Score lento/fora, broker fora e reinício com outbox pendente, PostgreSQL fora, falha isolada de notificações, três retries reais até DLQ, perda entre confirm do retry e ACK, saturação, destino de DLQ sem rota e delivery-limit. Os TTLs de 5/30/120 s permanecem como aprovados; a suíte leva alguns minutos. `--scenario` permite selecionar casos e `--skip-build` reutiliza imagens da mesma revisão. O script limpa somente seus próprios containers/volumes ao final.

Os contratos HTTP/eventos, backoff do outbox e idempotência permanecem os mesmos. No Score, uma conexão recusada resulta em 503; timeout de conexão/leitura resulta em 504. Falha de banco no CRUD continua 500/INTERNAL_ERROR sanitizado. Falhas permanentes não recebem retry de processamento; DLQ não possui replay automático. Após confirmação ambígua, consulte o estado antes de repetir uma intenção. O runbook distingue as provas com aplicações reais da simulação da janela protocolar confirm/ACK.

## Fluxo de desenvolvimento

Cada incremento tem escopo explicado, alterações revisáveis, testes automatizados e validação manual local pelo responsável pelo projeto. O commit ocorre após essa validação, com mensagem que descreve o resultado do incremento; o envio ao GitHub é uma ação separada.

Para revisar as alterações antes de cada commit:

```sh
git status --short
git diff
git ls-files --others --exclude-standard
```

As saídas de build, configurações locais e a pasta `docs/` são ignoradas pelo Git. O README concentra as instruções necessárias para quem clonar o repositório.
