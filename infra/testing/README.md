# Plano e execução dos testes — T13

Esta matriz relaciona os cenários do desafio às evidências executáveis. A suíte Maven possui 297 testes rápidos e 251 de integração (548 ao todo), em 34 classes. Os testes rápidos incluem JUnit/Mockito, Spring Boot com servidor HTTP e WireMock em portas aleatórias; não são todos testes unitários puros. Os de integração usam PostgreSQL/RabbitMQ reais via Testcontainers. O smoke acrescenta 36 verificações da jornada entre as duas imagens Java e todas as dependências, com tokens emitidos pelo Keycloak real.

A evidência é por cenário. Não há percentual de cobertura de linhas presumido nem comprovação de capacidade sob carga nesta etapa.

## Executar uma camada por vez

Requisitos: Java 21, Maven Wrapper, Python 3 e, para integração/smoke, Docker/Compose ativos. A primeira execução pode baixar dependências e imagens. Não é preciso iniciar o Compose do ambiente de demonstração ou importar dados no DBeaver. Execute na raiz do projeto, com as dependências Python já utilizadas nas etapas anteriores:

```sh
python3 -m venv infra/rabbitmq/.venv
infra/rabbitmq/.venv/bin/python -m pip install -r infra/rabbitmq/requirements-test.txt
```

| Camada | Comando | O que executa |
|---|---|---|
| Rápida | `infra/rabbitmq/.venv/bin/python infra/testing/run.py --suite fast` | Maven `clean verify`, 139 verificações de contratos, quatro testes do coletor, 11 do executor e sete do bootstrap RabbitMQ e 17 do executor/analise T15; sem Docker |
| Integração | `infra/rabbitmq/.venv/bin/python infra/testing/run.py --suite integration` | Maven `-Pintegration clean verify`, incluindo os rápidos, e as mesmas verificações Python |
| Smoke | `infra/rabbitmq/.venv/bin/python infra/testing/run.py --suite smoke` | Build de duas imagens `:t13` e jornada completa em ambiente descartável |
| Completa | `infra/rabbitmq/.venv/bin/python infra/testing/run.py --suite all` | Integração + verificações Python + smoke, executando o Maven uma única vez |

Para repetir somente o smoke com imagens da mesma revisão:

```sh
infra/rabbitmq/.venv/bin/python infra/testing/run.py --suite smoke --skip-build
```

`--skip-build` não reconstrói nem comprova que as imagens correspondem a alterações feitas depois do último build. Após mudar o código das aplicações ou os Dockerfiles, execute sem essa opção. Ela só é aceita em `smoke`/`all`.

Os comandos Maven anteriores continuam funcionando diretamente. O executor T13 acrescenta conferência de classes e resumo da execução; ele não desativa segurança, Flyway ou Testcontainers.

## Matriz de cenários e evidências

Os links abaixo apontam para as classes de teste. Testes parametrizados expandem múltiplas entradas e resultados no relatório JUnit.

| Grupo | Cenários verificados | Evidência principal |
|---|---|---|
| Persistência | Migrations, credencial sem privilégios, isolamento de bancos, constraints, CPF reservado e upgrade V3→V4 preservando evento pendente | [CustomerPersistenceIT](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/persistence/CustomerPersistenceIT.java) |
| Clientes | Cadastro, consulta, PUT completo, exclusão lógica, dados/JSON/IDs inválidos, CPF duplicado, inexistente/excluído, concorrência de cadastro/PUT/DELETE e rollback | [CustomerApiIT](../../customer-service/src/test/java/br/com/datum/customer/api/CustomerApiIT.java) |
| Consultas | Páginas, ordenação, status combinado com nome, caixa/acentos, busca literal de caracteres especiais, ausência de resultados, limites e excluídos omitidos | [CustomerQueryIT](../../customer-service/src/test/java/br/com/datum/customer/api/CustomerQueryIT.java) |
| Segurança | USER leitura, USER sem escrita, ADMIN CRUD, 401/403, assinatura/algoritmo/kid/issuer/aud incorretos, expiração/nbf, roles inválidas, ausência de sessão e privacidade | [CustomerSecurityTest](../../customer-service/src/test/java/br/com/datum/customer/security/CustomerSecurityTest.java); tokens reais USER/ADMIN no [smoke](smoke.py) |
| Score | Contrato válido, 4xx/5xx externo, corpo/tipos/CPF inválidos, tamanho limitado, redirects, conexão recusada, timeout antes/depois de headers, interrupção, retries limitados e sem propagação de credenciais | [HttpScoreClientTest](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/integrations/score/HttpScoreClientTest.java), [ScoreClientPropertiesTest](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/integrations/score/ScoreClientPropertiesTest.java), [CustomerScoreIT](../../customer-service/src/test/java/br/com/datum/customer/api/CustomerScoreIT.java) |
| Outbox | Cadastro+evento atômicos, rollback, broker fora, confirm/return/nack/timeout, backoff persistido, leases/fencing, concorrência, reenvio após confirm sem marca local e correlação preservada | [CustomerOutboxIT](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/outbox/CustomerOutboxIT.java), [RabbitEventPublisherTest](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/outbox/RabbitEventPublisherTest.java) |
| Status assíncrono | Aplicação com versão, duplicata, conflito de conteúdo/versão, concorrência, rollback, ACK pós-commit, retry confirmado, TTL, falha de ACK, rejeição permanente e DLQ | [CustomerStatusChangeIT](../../customer-service/src/test/java/br/com/datum/customer/status/CustomerStatusChangeIT.java), [StatusDeliveryHandlerTest](../../customer-service/src/test/java/br/com/datum/customer/status/StatusDeliveryHandlerTest.java), [ConfirmedStatusRetrySenderTest](../../customer-service/src/test/java/br/com/datum/customer/status/ConfirmedStatusRetrySenderTest.java) |
| Contratos de eventos | Schemas/envelope/JSON estritos, propriedades duplicadas, tamanho, inteiros/versão, routing keys e topologia aprovada; metadata AMQP e headers técnicos validados antes da aplicação | [Verificador T06](../../contracts/events/verify.py), [StatusChangeCodecTest](../../customer-service/src/test/java/br/com/datum/customer/status/StatusChangeCodecTest.java), [CustomerCreatedCodecTest](../../customer-notification-service/src/test/java/br/com/datum/notification/CustomerCreatedCodecTest.java), metadata tests listados em [suites.json](suites.json) |
| Notificações | Registro e inbox no mesmo commit, falha em qualquer escrita, concorrência, replay sem novo efeito, commit sem ACK, retry com TTL, falha de handoff, DLQ e segregação de dados | [NotificationConsumerIT](../../customer-notification-service/src/test/java/br/com/datum/notification/NotificationConsumerIT.java), [CreatedDeliveryHandlerTest](../../customer-notification-service/src/test/java/br/com/datum/notification/CreatedDeliveryHandlerTest.java), [ConfirmedNotificationRetrySenderTest](../../customer-notification-service/src/test/java/br/com/datum/notification/ConfirmedNotificationRetrySenderTest.java) |
| Limites de banco | Configuração efetiva, espera de pool, query/lock timeout, rollback e recuperação do pool | [DatabaseResilienceIT de clientes](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/persistence/DatabaseResilienceIT.java) e [de notificações](../../customer-notification-service/src/test/java/br/com/datum/notification/DatabaseResilienceIT.java) |
| OpenAPI | Schema/respostas por operação, exemplos executados com banco/segurança/provedor, UI pública e desativação explícita | [OpenApiContractIT](../../customer-service/src/test/java/br/com/datum/customer/api/OpenApiContractIT.java), [OpenApiDocumentationTest](../../customer-service/src/test/java/br/com/datum/customer/api/OpenApiDocumentationTest.java), [OpenApiDisabledTest](../../customer-service/src/test/java/br/com/datum/customer/api/OpenApiDisabledTest.java) |
| Observabilidade | Correlation ID validado, MDC restaurado inclusive em falhas, headers HTTP/AMQP e retries, métricas protegidas, ausência de IDs em labels, SQL medido sem parâmetros, pool/JPA reais | [Testes de observabilidade de clientes](../../customer-service/src/test/java/br/com/datum/customer/observability), [de notificações](../../customer-notification-service/src/test/java/br/com/datum/notification/observability), [CustomerOutboxIT](../../customer-service/src/test/java/br/com/datum/customer/infrastructure/outbox/CustomerOutboxIT.java) |
| Jornada operacional | Dependências saudáveis, health sanitizado, PKCE real, USER/ADMIN, Swagger/OpenAPI, CRUD/consulta/filtro/Score, entrega de criação, replay, status, duplicata, conflito/DLQ, payload inválido, exclusão lógica e privacidade de logs | [Smoke T13](smoke.py) |
| Bootstrap Compose | Geração sem senha aberta, idempotência, validação sem escrita, senha ausente/alterada, escrita atômica e arquivo inválido preservado | [test_bootstrap.py](../rabbitmq/test_bootstrap.py); [verificador T14](../compose/verify.py) para subida, volumes, issuer e persistência |
| Evidência do executor | XML ausente/antigo/inconsistente, classes ausentes/duplicadas/novas, falhas/skips/reruns, falha do subprocesso, logs privados e ausência de prova de limpeza | [test_runner.py](test_runner.py) |

As classes HTTP rápidas usam uma aplicação sem persistência, com `CustomerService` substituído apenas nesses testes. Os casos HTTP de integração usam o banco e a segurança reais. Os testes negativos de JWT usam pares de chaves exclusivos de teste e um JWKS local; não desligam a verificação de assinatura. O smoke usa o Keycloak em container para emitir tokens USER e ADMIN por Authorization Code + PKCE.

O manifesto [suites.json](suites.json) lista 24 classes rápidas e dez de integração, separadas por módulo. Ao acrescentar, renomear ou remover uma classe, revise também esse manifesto e a matriz correspondente. Não atualize o manifesto para esconder um teste que deixou de executar.

## O que o smoke faz

O smoke reaproveita o ambiente fechado das T11/T12 com uma tag própria `:t13` e documentação habilitada apenas nesse teste. Cria um projeto Compose aleatório para PostgreSQL, RabbitMQ, Keycloak e WireMock, inicia as duas imagens Java na mesma rede com `docker run` e aguarda seus healthchecks. A configuração de demonstração do projeto não é alterada. O Compose completo e seu quickstart estão disponíveis no [runbook T14](../compose/README.md). O smoke T13 mantém seu ambiente isolado com as imagens iniciadas separadamente.

A jornada usa dois clientes sintéticos fixos em bancos novos a cada execução. Publica status com a credencial restrita `status_publisher`; espera a inbox confirmar APPLIED, verifica a leitura HTTP e repete o mesmo evento para conferir DUPLICATE sem nova versão/updatedAt. Uma nova intenção com versão obsoleta deve produzir REJECTED/VERSION_CONFLICT e DLQ. Um payload com campo proibido deve chegar à DLQ sem escrita na inbox. O teste nunca interpreta publisher confirm como prova de aplicação.

A criação aguarda outbox PUBLISHED e registros de notificação no banco próprio. O replay confere a preservação de notification_id e registered_at. A exclusão é validada pelo HTTP e pela linha com deleted_at; o estado INACTIVE e a reserva de CPF são preservados. Logs são conferidos contra nomes/CPFs/e-mails sintéticos, tokens e senhas gerados para aquele ambiente.

O teste faz leituras administrativas somente em seus próprios bancos/filas para comprovar os resultados. Mensagens inválidas e DLQs criadas aqui são removidas junto com os volumes desse projeto descartável. O script não aceita URLs de destino, nomes de bancos/filas ou projeto de desenvolvimento como argumentos. Não utiliza nem limpa os dados da demonstração local.

## Relatório, falhas e repetibilidade

Cada execução do executor cria `.local/testing/<uuid>/summary.json` e logs separados por etapa, todos ignorados pelo Git. Diretório de execução: modo 700; arquivos: modo 600. O resumo contém somente camadas executadas, status, quantidades, duração dos subprocessos e caminhos dos logs. Corpos, tokens e credenciais não são incluídos no resumo. Logs brutos de testes ficam privados: uma falha pode conter detalhes das fixtures ou diagnósticos de infraestrutura; não os publique sem revisão.

O Maven é executado com `clean`; a conferência adicional exige XML recente, todos os nomes previstos, quantidade de testcases consistente e nenhum teste vazio, com falha, erro, skip ou rerun. Um processo terminar com código zero não basta se faltarem evidências. Novas classes não mapeadas também interrompem a aprovação. Os XMLs originais ficam em `customer-service/target/` e `customer-notification-service/target/` nos diretórios Surefire/Failsafe.

O executor para na primeira falha e retorna código diferente de zero. O resumo fica `failed` ou `interrupted`, nunca `passed` parcialmente. A etapa interrompida e seu log são indicados; a falha não vira um skip silencioso. No smoke, a prova de sucesso só é escrita depois da limpeza dos recursos próprios. Ctrl+C solicita interrupção ao subprocesso; se a máquina/Docker forem encerrados abruptamente, confira os recursos do projeto aleatório identificado no log antes de qualquer remoção.

Para repetir, execute o mesmo comando: cada execução recebe relatório, portas, bancos, volumes, credenciais e projeto próprios. É necessário ter portas e recursos Docker disponíveis; os números de porta são reservados dinamicamente, podendo ocorrer disputa com outro processo entre a seleção e o bind. Um erro de infraestrutura deve ser corrigido e a camada repetida, preservando o relatório da falha para análise.

## Suítes complementares

A opção `all` não repete os nove testes longos de falhas da T11 nem os 25 casos específicos de observabilidade da T12. Eles possuem verificadores e evidências próprias, e podem ser executados separadamente quando uma mudança afetar esses riscos:

```sh
infra/rabbitmq/.venv/bin/python infra/resilience/verify.py
infra/rabbitmq/.venv/bin/python infra/observability/verify.py
```

Consulte os [limites e cenários T11](../resilience/README.md) e o [runbook T12](../observability/README.md). A evidência de retries exaustos, destino DLQ indisponível, saturação e crashes permanece nessas suítes; o smoke T13 cobre uma jornada funcional e rejeições permanentes, sem simular cada janela de crash.

Os testes usam PostgreSQL/RabbitMQ de nó único. Não demonstram quorum de múltiplos nós, partição entre datacenters ou failover de banco. Testes sob carga e percentis representativos pertencem à T15. Swagger disponível no smoke não substitui os testes de contrato e exemplos da T10.
