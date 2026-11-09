# Datum Customer Services

Solução para o desafio técnico Datum / DXC / Claro, organizada em dois serviços Spring Boot independentes:

- `customer-service`: responsável pelo domínio de clientes e pela futura integração direta com o provedor externo de Score.
- `customer-notification-service`: responsável pelo futuro consumo de eventos de criação e registro de notificações simuladas, com armazenamento próprio.

## Estado da implementação

Etapa T01.1: build Maven multimódulo, inicialização das aplicações e testes de saúde HTTP. Os endpoints de negócio, persistência, autenticação, mensageria e containers serão implementados em etapas posteriores. O status `UP` atual valida somente a inicialização de cada aplicação; ainda não há dependências externas integradas.

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

Docker e bancos de dados ainda não são necessários nesta etapa.

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

Resultado esperado: `BUILD SUCCESS`, dois testes aprovados (um por serviço) e os arquivos:

- `customer-service/target/customer-service-0.0.1-SNAPSHOT.jar`
- `customer-notification-service/target/customer-notification-service-0.0.1-SNAPSHOT.jar`

Cada teste inicializa um servidor em porta aleatória e verifica que `GET /actuator/health` responde HTTP 200 com `status: UP`.

No Java 21, o suporte de testes do Mockito pode emitir um aviso sobre carregamento dinâmico de agente. Esse aviso foi observado na validação inicial, com os dois testes aprovados; confira os contadores de falhas e o resultado final do build.

## Testar manualmente os serviços

Depois do build, abra dois terminais na raiz e configure Java 21 em ambos. No primeiro:

```sh
java -jar customer-service/target/customer-service-0.0.1-SNAPSHOT.jar
```

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

## Fluxo de desenvolvimento

Cada incremento tem escopo explicado, alterações revisáveis, testes automatizados e validação manual local pelo responsável pelo projeto. O commit ocorre após essa validação, com mensagem que descreve o resultado do incremento; o envio ao GitHub é uma ação separada.

Para revisar os arquivos locais antes do primeiro commit:

```sh
git status --short
git ls-files --others --exclude-standard
```

As saídas de build, configurações locais e a pasta `docs/` são ignoradas pelo Git. O README concentra as instruções necessárias para quem clonar o repositório.
