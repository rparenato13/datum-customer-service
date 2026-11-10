# Desenvolvimento local com Maven, JAR ou IDE

[README / caminho recomendado com Compose](../README.md) · [Testes](../infra/testing/README.md)

Para desenvolver no host, use JDK 21 e JAVA_HOME correto. O Maven Wrapper fixa Maven 3.9.9; não é necessário Maven global. No macOS, selecione um JDK 21 instalado:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./mvnw -version
./mvnw -B -ntp clean verify
```

No Linux/Windows, configure JAVA_HOME para seu JDK 21; no Windows use mvnw.cmd. O Wrapper precisa de curl ou wget/unzip em macOS/Linux e acesso inicial ao Maven Central.

Para integração com PostgreSQL/RabbitMQ descartáveis:

```sh
./mvnw -B -ntp -Pintegration clean verify
```

Esperado: 548 testes sem falhas/skips. O build sem perfil executa 297 rápidos; não substitui os 251 cenários de integração. Docker precisa estar ativo para Testcontainers. Consulte os detalhes e relatórios na matriz de testes.

## Dependências do JAR

Com .env existente preservado, inicie as dependências e pare somente as aplicações Compose que ocupariam as portas dos JARs:

```sh
docker compose up -d --wait postgres keycloak wiremock rabbitmq
docker compose stop customer-service customer-notification-service
```

A dependência rabbitmq-init é executada automaticamente para o broker. Não rode JAR e container do mesmo consumidor simultaneamente sem intenção: eles competirão na mesma fila e compartilharão o banco de demonstração. O consumidor pode processar mensagens antigas assim que iniciar.

Compose lê .env; java -jar, IDE e spring-boot:run não o fazem automaticamente. Exporte somente as variáveis do serviço, com as senhas/portas **efetivas**. Os prompts abaixo mantêm as senhas fora do histórico; requerem Python 3. Não faça source de um arquivo de origem desconhecida nem repasse credenciais administrativas às aplicações.

Terminal 1, na raiz:

```sh
export CUSTOMER_DB_URL=jdbc:postgresql://localhost:15432/customer_db
export CUSTOMER_DB_USERNAME=customer_app
export CUSTOMER_DB_PASSWORD="$(python3 -c 'import getpass; print(getpass.getpass("CUSTOMER_DB_PASSWORD do .env: "))')"
export CUSTOMER_RABBITMQ_HOST=localhost
export CUSTOMER_RABBITMQ_PORT=15673
export CUSTOMER_RABBITMQ_PASSWORD="$(python3 -c 'import getpass; print(getpass.getpass("CUSTOMER_RABBITMQ_PASSWORD do .env: "))')"
export CUSTOMER_JWT_ISSUER_URI=http://localhost:18090/realms/datum
export CUSTOMER_JWT_JWK_SET_URI=http://localhost:18090/realms/datum/protocol/openid-connect/certs
export SCORE_BASE_URL=http://localhost:18082
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

Terminal 2, na raiz, com o mesmo JDK:

```sh
export NOTIFICATION_DB_URL=jdbc:postgresql://localhost:15432/notification_db
export NOTIFICATION_DB_PASSWORD="$(python3 -c 'import getpass; print(getpass.getpass("NOTIFICATION_DB_PASSWORD do .env: "))')"
export NOTIFICATION_RABBITMQ_HOST=localhost
export NOTIFICATION_RABBITMQ_PORT=15673
export NOTIFICATION_RABBITMQ_PASSWORD="$(python3 -c 'import getpass; print(getpass.getpass("NOTIFICATION_RABBITMQ_PASSWORD do .env: "))')"
java -jar customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar
```

Ajuste URLs/portas se mudou os defaults. O customer valida o mesmo issuer do token, mesmo que o endereço para buscar JWKS seja diferente. Acesse health em 8080/8081 e use os tokens do README para APIs de negócio.

Cada serviço aplica/valida suas migrations: customer V1–V4 e notification V1. Hibernate usa ddl-auto=validate. Não edite migrations já aplicadas nem use conta administrativa para contornar falhas de schema. Banco inacessível impede inicialização/health saudável; broker fora não impede o cadastro com outbox PENDING.

Alternativa ao JAR: com as mesmas variáveis, em terminais separados:

```sh
./mvnw -pl customer-service spring-boot:run
./mvnw -pl customer-notification-service spring-boot:run
```

Para trocar porta do JAR, exporte SERVER_PORT; isso não altera as portas publicadas do Compose. Para métricas, acrescente `--spring.profiles.active=observability` ao java -jar e configure NOTIFICATION_MANAGEMENT_PASSWORD na notificação; [detalhes](../infra/observability/README.md).

Finalize JARs com Ctrl+C antes de retomar containers:

```sh
docker compose up -d customer-service customer-notification-service
```

## Revisão de um incremento

Revise git diff, execute a camada relevante, valide localmente e só depois prepare commit/push. git diff --check verifica whitespace; git status --short permite conferir o escopo. .env, local.env, .local, docs, targets e .venv não pertencem ao commit.

Os avisos de agente dinâmico Mockito no Java 21 não substituem os contadores finais dos testes. Uma suíte com teste ignorado/ausente não deve ser apresentada como integração validada. Testes de falha em ambiente descartável podem gerar WARN esperados; confira o resultado final e a limpeza.
