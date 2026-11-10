# Datum Customer Services

Solução para o desafio técnico Datum / DXC / Claro, organizada em dois serviços Spring Boot independentes:

- `customer-service`: responsável pelo domínio de clientes e pela futura integração direta com o provedor externo de Score.
- `customer-notification-service`: responsável pelo futuro consumo de eventos de criação e registro de notificações simuladas, com armazenamento próprio.

## Estado da implementação

Etapas T01.1 e T01.2: build Maven multimódulo, inicialização das aplicações, testes de saúde HTTP e imagens Docker independentes. T02.1: PostgreSQL local com bancos e credenciais segregadas. T02.2: `customer-service` integrado a JPA/Flyway, tabela de clientes e exclusão lógica. Os endpoints de negócio, autenticação, mensageria e orquestração completa serão implementados em etapas posteriores. O serviço de notificações ainda não está conectado ao banco.

## Decisões da base

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

Resultado esperado: `BUILD SUCCESS`, dois testes rápidos aprovados (um por serviço) e os arquivos:

- `customer-service/target/customer-service-0.0.1-SNAPSHOT.jar`
- `customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar`

Cada teste rápido inicializa um servidor em porta aleatória e verifica `GET /actuator/health`. No `customer-service`, o perfil de teste `smoke` exclui a infraestrutura de persistência apenas desse teste HTTP. A persistência é verificada separadamente pelo perfil Maven `integration`, obrigatório para validar a T02.2:

```sh
./mvnw -Pintegration clean verify
```

Esse comando executa os dois testes rápidos e 12 cenários de integração em `CustomerPersistenceIT`, usando Testcontainers com PostgreSQL 17.11. O bootstrap é o mesmo de `infra/postgres/init/`, e a aplicação de teste conecta como `customer_app`, sem privilégios administrativos. Não é necessário subir o Compose para esses testes. O Docker precisa estar ativo; se estiver indisponível, os testes de integração falham, em vez de serem ignorados. O banco local do DBeaver não recebe os dados dos testes.

No Java 21, o suporte de testes do Mockito pode emitir um aviso sobre carregamento dinâmico de agente. Esse aviso foi observado na validação inicial, com os dois testes aprovados; confira os contadores de falhas e o resultado final do build.

## Testar manualmente os serviços

Depois do build, prepare o PostgreSQL conforme a seção T02.1. Encerre instâncias antigas antes de usar as mesmas portas. Abra dois terminais na raiz e configure Java 21 em ambos. No primeiro, configure as variáveis do `customer-service`:

```sh
export CUSTOMER_DB_URL='jdbc:postgresql://localhost:15432/customer_db'
export CUSTOMER_DB_USERNAME='customer_app'
export CUSTOMER_DB_PASSWORD='customer_local_only'
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

Use a senha efetiva de `CUSTOMER_DB_PASSWORD` do seu `.env` e ajuste a URL se alterou `POSTGRES_PORT`. A senha acima é apenas o valor de demonstração. O Compose lê `.env` automaticamente; uma aplicação iniciada por `java -jar`, Maven ou IDE precisa receber suas variáveis de ambiente explicitamente. Não passe credenciais de `datum_admin` à aplicação.

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

## Construir e testar as imagens Docker

Execute todos os comandos abaixo na raiz do projeto. O ponto final dos comandos de build indica o contexto da raiz, necessário para acessar o POM pai e os POMs dos módulos.

```sh
docker build -f customer-service/Dockerfile -t datum/customer-service:t02.2 .
docker build -f customer-notification-service/Dockerfile -t datum/customer-notification-service:t01.2 .
```

Cada Dockerfile tem dois estágios:

1. `build`: Maven 3.9.9 com JDK 21 compila e executa os testes rápidos do serviço selecionado (`verify`). Os testes de integração devem ser executados antes, no host, com `-Pintegration`: eles precisam de acesso ao Docker e não rodam dentro do build da imagem. A versão do Maven coincide com a fixada pelo Wrapper usado no host. O cache de dependências acelera builds seguintes; os arquivos `target/` do host são ignorados.
2. `runtime`: Eclipse Temurin JRE 21 recebe somente o JAR do serviço e o executa como usuário `app` (UID/GID 10001). O Maven e o código-fonte ficam no estágio de build.

Os dois POMs de módulos são copiados para permitir a leitura do agregador Maven; cada build copia o código-fonte e executa os testes apenas do seu serviço. As tags das imagens-base recebem atualizações dos respectivos fornecedores, portanto builds futuros podem incorporar novos patches do JDK/JRE e do sistema operacional.

O `.dockerignore` exclui `docs/`, histórico Git, saídas Maven, arquivos locais e `.env*` do contexto enviado ao Docker. Essa exclusão é independente do `.gitignore`.

Prepare o PostgreSQL pelo Compose e exporte `CUSTOMER_DB_PASSWORD` conforme o seu `.env`, como na execução Java. Inicie os containers:

```sh
docker compose up -d --wait postgres
docker run -d --rm --name datum-customer-t02-2 --network datum_default --env CUSTOMER_DB_PASSWORD --env CUSTOMER_DB_URL=jdbc:postgresql://postgres:5432/customer_db -p 127.0.0.1:18080:8080 datum/customer-service:t02.2
docker run -d --rm --name datum-notification-t01-2 -p 127.0.0.1:18081:8081 datum/customer-notification-service:t01.2
```

As portas 18080 e 18081 no host evitam conflito com a execução Java nas portas 8080 e 8081. O acesso publicado fica restrito à máquina local. Aguarde as mensagens de inicialização nos logs:

```sh
docker logs datum-customer-t02-2
docker logs datum-notification-t01-2
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18081/actuator/health
```

Critério de validação: ambos respondem HTTP 200 com `{"status":"UP"}`. Se o `curl` ocorrer antes da inicialização, aguarde a mensagem `Started ...Application` e repita a consulta.

Confira o usuário dos processos:

```sh
docker exec datum-customer-t02-2 id
docker exec datum-notification-t01-2 id
```

Resultado esperado: UID e GID 10001 (`app`). Para encerrar:

```sh
docker stop datum-customer-t02-2 datum-notification-t01-2
```

O `--rm` remove os containers das aplicações após a parada; as imagens permanecem disponíveis para novos testes. Nenhuma imagem é publicada em registry por esses comandos. O PostgreSQL permanece ativo até `docker compose down`. Dentro da rede `datum_default`, a aplicação usa `postgres:5432`; no host, usa `localhost:15432`.

## PostgreSQL local e isolamento de bancos (T02.1)

O `docker-compose.yml` contém, por enquanto, somente PostgreSQL. Execute na raiz do projeto:

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

Para recriar o ambiente do zero, **o comando abaixo apaga o volume e todos os dados locais deste projeto**. Use somente quando quiser descartar os dados:

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

A escolha foi aprovada para este incremento: o futuro DELETE marcará `deleted_at`, preservando o registro. `INACTIVE` permanece um estado de negócio consultável e não significa exclusão. As consultas expostas pelo repository filtram `deleted_at IS NULL`; a verificação de CPF inclui o histórico. O repository não expõe remoção física.

O CPF continua reservado após a exclusão lógica. Reativação, restauração de registros excluídos e políticas de retenção não foram implementadas. A entidade JPA fica em `infrastructure/persistence`; o enum de status fica em `domain`. A API usará DTOs próprios quando for implementada.

As constraints desta etapa protegem formato básico, campos obrigatórios, status e unicidade no banco. Validação de dígitos verificadores de CPF, formato de e-mail, contratos de entrada e respostas HTTP de erro serão implementados com a API na T03. Não há endpoint ou seed de demonstração de clientes nesta etapa; o banco local começa vazio.

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

## Fluxo de desenvolvimento

Cada incremento tem escopo explicado, alterações revisáveis, testes automatizados e validação manual local pelo responsável pelo projeto. O commit ocorre após essa validação, com mensagem que descreve o resultado do incremento; o envio ao GitHub é uma ação separada.

Para revisar as alterações antes de cada commit:

```sh
git status --short
git diff
git ls-files --others --exclude-standard
```

As saídas de build, configurações locais e a pasta `docs/` são ignoradas pelo Git. O README concentra as instruções necessárias para quem clonar o repositório.
