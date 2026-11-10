# Datum Customer Services

Solução para o desafio técnico Datum / DXC / Claro, organizada em dois serviços Spring Boot independentes:

- `customer-service`: responsável pelo domínio de clientes e pela integração HTTP direta com o provedor externo de Score.
- `customer-notification-service`: responsável pelo futuro consumo de eventos de criação e registro de notificações simuladas, com armazenamento próprio.

## Estado da implementação

Etapas T01.1 e T01.2: build Maven multimódulo, inicialização das aplicações, testes de saúde HTTP e imagens Docker independentes. T02.1: PostgreSQL local com bancos e credenciais segregadas. T02.2: `customer-service` integrado a JPA/Flyway, tabela de clientes e exclusão lógica. T03.1: cadastro e consulta de clientes por ID, com DTOs, validação e erros padronizados. T03.2: atualização completa e exclusão lógica pela API. T03.3: listagem paginada, busca por nome e filtro por status. T04.1: Keycloak local e emissão de JWTs. T04.2: API como OAuth 2.0 Resource Server, validando JWT e aplicando permissões USER/ADMIN. T05.1: provedor de Score simulado com WireMock e cenários reproduzíveis. T05.2: consulta de Score pela API, com timeouts, retries limitados e erros controlados. T06: contratos de eventos aprovados. T07.1: RabbitMQ local com topologia e permissões provisionadas. Outbox, publicação pelas aplicações, consumidores e orquestração completa permanecem pendentes. O serviço de notificações ainda não está conectado ao banco.

## Decisões da base

A T06 tem **contratos aprovados pelo responsável pelo projeto em 10/11/2026**, disponíveis em [contracts/events](contracts/events/README.md). Esta etapa ainda não adiciona mensageria às aplicações.

- Java 21 LTS: atende ao requisito Java 17+ e está disponível no ambiente de desenvolvimento.
- Spring Boot 3.5.16: versão fixa da linha 3.5, compatível com Java 21.
- Maven 3.9.9, fixado pelo Maven Wrapper: permite reproduzir o build sem depender da versão do Maven instalada na máquina.
- POM pai: centraliza versões e agrega os módulos. Cada serviço gera seu próprio JAR executável; nenhum serviço depende do outro.
- Spring MVC e Actuator: servidor HTTP e verificação operacional de saúde. No serviço de notificações, HTTP atende somente à operação; seu processamento de negócio será assíncrono.

## Pré-requisitos

- JDK 21, com `JAVA_HOME` apontando para essa versão.
- Acesso ao Maven Central na primeira execução para baixar Maven e dependências.
- No macOS/Linux, `curl` ou `wget` e `unzip` para o Wrapper.

Para a execução via Docker, é necessário Docker com suporte a BuildKit e Compose v2 ou superior (incluídos no Docker Desktop), daemon ativo e acesso ao Docker Hub e ao Maven Central. Nesse caminho, o build roda dentro do container e dispensa JDK e Maven instalados no host. O PostgreSQL precisa estar ativo para iniciar o `customer-service`; os testes de integração criam seu próprio banco descartável.

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

Resultado esperado: `BUILD SUCCESS`, 113 testes rápidos aprovados (48 de segurança, 62 do cliente HTTP/configuração de Score, dois de clientes e um de notificações) e os arquivos:

- `customer-service/target/customer-service-0.0.1-SNAPSHOT.jar`
- `customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar`

Os testes rápidos verificam saúde, resposta segura para falha interna, autenticação/autorização e o adaptador HTTP de Score com WireMock em porta aleatória. O perfil de teste `smoke` exclui a persistência e substitui `CustomerService` por um mock somente nesses testes HTTP. A segurança permanece ativa: os JWTs são assinados com chaves exclusivas de teste, e o decoder de produção consulta um JWKS HTTP local. Não há decoder falso nem validação de assinatura desabilitada. Persistência e endpoints com banco real são verificados pelo perfil Maven `integration`, obrigatório para validar a T05.2:

```sh
./mvnw -Pintegration clean verify
```

Esse comando executa 113 testes rápidos e 153 cenários de integração: 12 de persistência em `CustomerPersistenceIT`, 68 HTTP de cadastro/alteração/exclusão em `CustomerApiIT`, 54 de listagem/busca em `CustomerQueryIT` e 19 do endpoint de Score em `CustomerScoreIT`, usando Testcontainers com PostgreSQL 17.11. Os testes HTTP de negócio passam pela segurança com JWTs assinados. O bootstrap é o mesmo de `infra/postgres/init/`, e a aplicação de teste conecta como `customer_app`, sem privilégios administrativos. Não é necessário subir o Compose/Keycloak/WireMock para a suíte Maven. O Docker precisa estar ativo para os testes com banco; se estiver indisponível, esses testes falham, em vez de serem ignorados. O banco local do DBeaver não recebe os dados dos testes.

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

Na primeira inicialização, o Flyway aplica `V1__create_customers.sql`. Nas seguintes, valida o histórico e aplica somente migrations pendentes. O Hibernate usa `ddl-auto=validate`; divergências de schema interrompem a inicialização. O endpoint de saúde do serviço iniciado normalmente passa a considerar a conexão com o banco.

No segundo:

```sh
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
docker build -f customer-service/Dockerfile -t datum/customer-service:t05.2 .
docker build -f customer-notification-service/Dockerfile -t datum/customer-notification-service:t01.2 .
```

Cada Dockerfile tem dois estágios:

1. `build`: Maven 3.9.9 com JDK 21 compila e executa os testes rápidos do serviço selecionado (`verify`). Os testes de integração devem ser executados antes, no host, com `-Pintegration`: eles precisam de acesso ao Docker e não rodam dentro do build da imagem. A versão do Maven coincide com a fixada pelo Wrapper usado no host. O cache de dependências acelera builds seguintes; os arquivos `target/` do host são ignorados.
2. `runtime`: Eclipse Temurin JRE 21 recebe somente o JAR do serviço e o executa como usuário `app` (UID/GID 10001). O Maven e o código-fonte ficam no estágio de build.

Os dois POMs de módulos são copiados para permitir a leitura do agregador Maven; cada build copia o código-fonte e executa os testes apenas do seu serviço. As tags das imagens-base recebem atualizações dos respectivos fornecedores, portanto builds futuros podem incorporar novos patches do JDK/JRE e do sistema operacional.

O `.dockerignore` exclui `docs/`, histórico Git, saídas Maven, arquivos locais e `.env*` do contexto enviado ao Docker. Essa exclusão é independente do `.gitignore`.

Prepare PostgreSQL/Keycloak/WireMock pelo Compose e exporte `CUSTOMER_DB_PASSWORD` conforme o seu `.env`, como na execução Java. Inicie os containers:

```sh
docker compose up -d --wait postgres keycloak wiremock
docker run -d --rm --name datum-customer-t05-2 --network datum_default --env CUSTOMER_DB_PASSWORD --env CUSTOMER_DB_URL=jdbc:postgresql://postgres:5432/customer_db --env CUSTOMER_JWT_ISSUER_URI --env CUSTOMER_JWT_JWK_SET_URI=http://keycloak:8080/realms/datum/protocol/openid-connect/certs --env SCORE_BASE_URL=http://wiremock:8080 -p 127.0.0.1:18080:8080 datum/customer-service:t05.2
docker run -d --rm --name datum-notification-t01-2 -p 127.0.0.1:18081:8081 datum/customer-notification-service:t01.2
```

As portas 18080 e 18081 no host evitam conflito com a execução Java nas portas 8080 e 8081. O acesso publicado fica restrito à máquina local. Aguarde as mensagens de inicialização nos logs:

```sh
docker logs datum-customer-t05-2
docker logs datum-notification-t01-2
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18081/actuator/health
```

Critério de validação: ambos respondem HTTP 200 com `{"status":"UP"}`. Se o `curl` ocorrer antes da inicialização, aguarde a mensagem `Started ...Application` e repita a consulta.

Confira o usuário dos processos:

```sh
docker exec datum-customer-t05-2 id
docker exec datum-notification-t01-2 id
```

Resultado esperado: UID e GID 10001 (`app`). Para encerrar:

```sh
docker stop datum-customer-t05-2 datum-notification-t01-2
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

O Flyway do `customer-service` é responsável pela tabela `customers` e pelo histórico `flyway_schema_history`, no banco `customer_db`. Inicializar somente o PostgreSQL não cria tabelas de negócio: é necessário iniciar o serviço para aplicar as migrations. O banco `notification_db` continua sem tabelas de negócio.

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

O esperado é migration versão `1` com sucesso, as colunas descritas acima e zero clientes se você ainda não inseriu dados. Reinicie a aplicação e confira que o Flyway mantém uma única migration aplicada. Não edite a V1 depois de aplicada; mudanças posteriores devem ganhar novas migrations (`V2__...`, etc.).

## Cadastro e consulta por ID (T03.1)

O `CustomerController` recebe e valida DTOs, o `CustomerService` delimita as transações, e o repository persiste/consulta os clientes. Entidades JPA não são serializadas diretamente. As datas retornadas usam a precisão de microssegundos do PostgreSQL, preservando os mesmos valores no POST e no GET subsequente.

| Operação | Sucesso | Erros previstos nesta etapa |
|---|---|---|
| `POST /customers` | 201, corpo do cliente e `Location: /customers/{id}` | 400 para entrada inválida; 409 para CPF já registrado |
| `GET /customers/{id}` | 200 e corpo do cliente | 400 para ID inválido; 404 para inexistente ou excluído logicamente |

O cadastro exige `name`, `cpf`, `email` e `status`. Nome tem limite de 150 caracteres; e-mail, 254. Espaços nas extremidades de nome/CPF/e-mail são removidos antes da validação. CPF deve conter 11 dígitos sem máscara e dígitos verificadores válidos; valores com dígitos todos iguais são recusados. Status aceita exatamente `ACTIVE` ou `INACTIVE`. JSON malformado e propriedades desconhecidas também são recusados. O ID e as datas são definidos pelo servidor.

Os erros usam `application/problem+json` (`ProblemDetail`, RFC 9457), com `type`, `title`, `status`, `detail`, `instance` e extensão `code`. Erros de campos incluem `errors`, com `field` e `message`, sem os valores rejeitados. Códigos desta etapa: `VALIDATION_ERROR`, `INVALID_JSON`, `INVALID_REQUEST`, `CUSTOMER_NOT_FOUND`, `CPF_ALREADY_REGISTERED` e `INTERNAL_ERROR` (500, mensagem genérica). Somente a violação da constraint de CPF é convertida em 409; outras falhas de integridade não são apresentadas como CPF duplicado.

A aplicação não registra payloads nem mensagens SQL de constraints, que podem conter dados pessoais. O handler de erro inesperado registra somente a classe da falha. Logs estruturados e correlação serão tratados na etapa de observabilidade.

O POST persiste o cliente e, desde a T04.2, exige JWT com perfil ADMIN. A publicação de `CUSTOMER_CREATED` será acrescentada na etapa de mensageria, conforme os contratos aprovados na T06. Não houve alteração da migration V1.

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

Este incremento prepara a infraestrutura aprovada na T06. O POST ainda não grava outbox nem publica eventos; a publicação confiável será o próximo incremento T07.2. Não há consumidor de negócio nesta etapa. Nenhuma migration ou classe Java foi alterada.

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

Os exchanges internos de retry são `direct`, conforme aprovado: neles, a ACL limita o acesso ao exchange, não a routing key. A publicação de retry pela aplicação deverá derivar a chave de seu próprio consumidor, sem aceitar destinos arbitrários, nas T08/T09. O publicador externo não pode escrever nos exchanges internos. O broker controla autenticação e roteamento; ele não valida o JSON do corpo contra o schema, responsabilidade dos produtores/consumidores.

Variáveis de porta: `RABBITMQ_AMQP_PORT` (15673) e `RABBITMQ_MANAGEMENT_PORT` (15672). Seus defaults e os quatro passwords locais constam de `.env.example`. As contas importadas pertencem ao volume persistente: alterar `.env`/arquivo gerado não é um procedimento de rotação de senhas já existentes. Uma rotação exige alteração explícita no broker e sincronização das configurações; não apague o volume para trocar uma senha. O verificador detecta credenciais/topologia divergentes.

### Conferência manual no console

Acesse o console com `rabbitmq_admin` e a senha efetiva do `.env` (default de demonstração: `rabbitmq_admin_local_only`). Selecione `datum` e confira:

- Exchanges `datum.customer.events` (topic), `datum.customer.retry`, `datum.customer.redelivery` e `datum.customer.dead` (direct).
- Fila principal `customer-notification-service.created.v1`, três filas `.retry.1/.2/.3` e uma `.dlq`.
- Fila principal `customer-service.status-change.v1`, três filas `.retry.1/.2/.3` e uma `.dlq`.
- Filas quorum duráveis, limites de 10000 mensagens/64 MiB e overflow `reject-publish`.
- Policy das principais: delivery-limit 5 e dead-letter at-least-once para sua DLQ.
- Policies de retry: TTLs de 5000/30000/120000 ms e retorno pelo exchange `datum.customer.redelivery` apenas à fila do consumidor correspondente.

As filas devem começar sem mensagens, pois as aplicações ainda não publicam ou consomem eventos. Estatísticas do console podem levar alguns segundos para aparecer. A verificação usa policies declaradas e seus padrões/prioridades para conferir a seleção mesmo antes da coleta de estatísticas. Não usa contadores de mensagens como prova de publicação pela aplicação.

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

## Fluxo de desenvolvimento

Cada incremento tem escopo explicado, alterações revisáveis, testes automatizados e validação manual local pelo responsável pelo projeto. O commit ocorre após essa validação, com mensagem que descreve o resultado do incremento; o envio ao GitHub é uma ação separada.

Para revisar as alterações antes de cada commit:

```sh
git status --short
git diff
git ls-files --others --exclude-standard
```

As saídas de build, configurações locais e a pasta `docs/` são ignoradas pelo Git. O README concentra as instruções necessárias para quem clonar o repositório.
