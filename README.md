# Datum Customer Services

Solução para o desafio técnico Datum / DXC / Claro, organizada em dois serviços Spring Boot independentes:

- `customer-service`: responsável pelo domínio de clientes e pela futura integração direta com o provedor externo de Score.
- `customer-notification-service`: responsável pelo futuro consumo de eventos de criação e registro de notificações simuladas, com armazenamento próprio.

## Estado da implementação

Etapas T01.1 e T01.2: build Maven multimódulo, inicialização das aplicações, testes de saúde HTTP e imagens Docker independentes. Os endpoints de negócio, persistência, autenticação, mensageria e orquestração com Docker Compose serão implementados em etapas posteriores. O status `UP` atual valida somente a inicialização de cada aplicação; ainda não há dependências externas integradas.

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

Para a execução via Docker, é necessário Docker com suporte a BuildKit (incluído no Docker Desktop), daemon ativo e acesso ao Docker Hub e ao Maven Central. Nesse caminho, o build roda dentro do container e dispensa JDK e Maven instalados no host. Bancos de dados ainda não são necessários.

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

## Construir e testar as imagens Docker

Execute todos os comandos abaixo na raiz do projeto. O ponto final dos comandos de build indica o contexto da raiz, necessário para acessar o POM pai e os POMs dos módulos.

```sh
docker build -f customer-service/Dockerfile -t datum/customer-service:t01.2 .
docker build -f customer-notification-service/Dockerfile -t datum/customer-notification-service:t01.2 .
```

Cada Dockerfile tem dois estágios:

1. `build`: Maven 3.9.9 com JDK 21 compila e executa os testes do serviço selecionado (`verify`). A versão do Maven coincide com a fixada pelo Wrapper usado no host. O cache de dependências acelera builds seguintes; os arquivos `target/` do host são ignorados.
2. `runtime`: Eclipse Temurin JRE 21 recebe somente o JAR do serviço e o executa como usuário `app` (UID/GID 10001). O Maven e o código-fonte ficam no estágio de build.

Os dois POMs de módulos são copiados para permitir a leitura do agregador Maven; cada build copia o código-fonte e executa os testes apenas do seu serviço. As tags das imagens-base recebem atualizações dos respectivos fornecedores, portanto builds futuros podem incorporar novos patches do JDK/JRE e do sistema operacional.

O `.dockerignore` exclui `docs/`, histórico Git, saídas Maven, arquivos locais e `.env*` do contexto enviado ao Docker. Essa exclusão é independente do `.gitignore`.

Inicie os containers:

```sh
docker run -d --rm --name datum-customer-t01-2 -p 127.0.0.1:18080:8080 datum/customer-service:t01.2
docker run -d --rm --name datum-notification-t01-2 -p 127.0.0.1:18081:8081 datum/customer-notification-service:t01.2
```

As portas 18080 e 18081 no host evitam conflito com a execução Java nas portas 8080 e 8081. O acesso publicado fica restrito à máquina local. Aguarde as mensagens de inicialização nos logs:

```sh
docker logs datum-customer-t01-2
docker logs datum-notification-t01-2
curl -i http://localhost:18080/actuator/health
curl -i http://localhost:18081/actuator/health
```

Critério de validação: ambos respondem HTTP 200 com `{"status":"UP"}`. Se o `curl` ocorrer antes da inicialização, aguarde a mensagem `Started ...Application` e repita a consulta.

Confira o usuário dos processos:

```sh
docker exec datum-customer-t01-2 id
docker exec datum-notification-t01-2 id
```

Resultado esperado: UID e GID 10001 (`app`). Para encerrar:

```sh
docker stop datum-customer-t01-2 datum-notification-t01-2
```

O `--rm` remove os containers após a parada; as imagens permanecem disponíveis para novos testes. Nenhuma imagem é publicada em registry por esses comandos.

## Fluxo de desenvolvimento

Cada incremento tem escopo explicado, alterações revisáveis, testes automatizados e validação manual local pelo responsável pelo projeto. O commit ocorre após essa validação, com mensagem que descreve o resultado do incremento; o envio ao GitHub é uma ação separada.

Para revisar as alterações antes de cada commit:

```sh
git status --short
git diff
git ls-files --others --exclude-standard
```

As saídas de build, configurações locais e a pasta `docs/` são ignoradas pelo Git. O README concentra as instruções necessárias para quem clonar o repositório.
