# Escopo e rastreabilidade para avaliação

[README](../README.md) · [Matriz detalhada de testes](../infra/testing/README.md) · [Decisões](../architecture/decisions/README.md)

Conferência com o enunciado original “Teste técnico - senior.pdf”, seis páginas, em 10/11/2026. O material do cliente permanece local em docs, ignorado pelo Git. Esta matriz resume requisitos; os exemplos conceituais do enunciado não são copiados como schemas normativos. Os contratos v1 implementados acrescentam versão de schema, instante UTC e controle de versão esperado.

A coluna de evidência identifica código/testes reproduzíveis. Os resultados executados anteriormente não substituem uma nova execução quando o código ou ambiente muda.

## Requisitos do enunciado

| Requisito | Implementação / comportamento | Evidência reproduzível |
|---|---|---|
| Java 17+, Spring Boot, Maven e microsserviços | Java 21, Boot 3.5.16, Wrapper 3.9.9; dois módulos/JARs/imagens independentes | [POM pai](../pom.xml), build e [arquitetura](../architecture/README.md) |
| Banco relacional e Spring Data JPA | PostgreSQL; entidades/repositório customer, migrations Flyway e validação de schema | CustomerPersistenceIT e DatabaseResilienceIT |
| Dados mínimos de cliente | id/name/cpf/email/status, com timestamps/version adicionais | CustomerApiIT; schemas OpenAPI em execução |
| POST/PUT/DELETE/GET por ID e listagem | DTOs, validação, códigos 201/200/204 e erros apropriados | CustomerApiIT e smoke |
| Busca por nome e filtro por status | /customers/search?name e /customers?status, combináveis, paginados | CustomerQueryIT; smoke/Compose |
| GET /customers/{id}/score via GET externo /scores/{cpf} | Cliente resolve CPF; adaptador HTTP direto, contrato estrito, timeout/retry limitado | CustomerScoreIT, HttpScoreClientTest e cenário score |
| Spring Security/OAuth 2.0/JWT Resource Server | JWT assinado, issuer/audience/validade e roles; USER leitura, ADMIN escrita | Testes de segurança, Keycloak real e Compose |
| Publicar CUSTOMER_CREATED após cadastro | Transação cliente/outbox e publisher confirm; broker fora não perde intenção | CustomerOutboxIT e cenário broker |
| Consumir CUSTOMER_STATUS_CHANGE | Alteração no banco proprietário, expectedVersion e inbox | CustomerStatusChangeIT e jornada Compose |
| Consistência sob duplicatas | Inbox transacional/fingerprint; mesmo evento não repete efeito | Status/Notification IT, replay e cenário ack |
| Validação, CPF duplicado, inexistente, 401/403 | 400/409/404/401/403 com ProblemDetail, sem dados rejeitados | Testes HTTP/segurança e smoke |
| Falha/demora de Score e componentes indisponíveis | 502/503/504 classificados; pools e espera limitados; recuperação | Score/DatabaseResilience IT e [suíte de falhas](../infra/resilience/README.md) |
| Falha no processamento assíncrono | Rollback, retries confirmados, exaustão/DLQ, sem deduplicação falsa | Consumidores IT e cenários retries/ack/dlq |
| Configurações externalizadas | Parâmetros por serviço, .env local e credenciais segregadas | [.env.example](../.env.example), [Compose](../docker-compose.yml) e configuração de testes |
| JUnit e testes das regras/integrações | 297 rápidos + 251 integração; smoke com IdP real | [Executor e manifesto](../infra/testing/README.md) |
| Containers, inicialização simples e README | Compose único, bootstrap automático, healthchecks e volumes | [Verificador Compose](../infra/compose/verify.py): 57 verificações na execução validada |
| Repositório e instruções suficientes | README, exemplos/token, contratos, arquitetura, decisões, runbooks e demo | [Roteiro manual](demo.md), revisão pelo avaliador |

As classes citadas têm links de código na matriz de testes. Integração executa segurança e persistência reais; os testes HTTP rápidos isolam a camada substituindo o serviço de aplicação apenas nos testes.

## Escolhas adicionais desta solução

| Escolha | Motivo e limite |
|---|---|
| Dois serviços específicos de negócio | Número/fronteiras escolhidos; notificação adicional é simulada, sem SMTP/WhatsApp |
| PostgreSQL, Keycloak e WireMock | Produtos escolhidos para atender banco/identidade/provedor; não impostos nominalmente |
| ACTIVE/INACTIVE, exclusão lógica e CPF reservado | Decisão de negócio confirmada no desenvolvimento; sem restauração/expurgo |
| Outbox/inbox, versão esperada e envelope v1 | Mecanismos escolhidos para consistência/concorrência; não promessa de exactly-once |
| Quorum, três retries, DLQ e contas por papel | Robustez adicional com custo operacional; nó único local não oferece HA |
| Testcontainers, PKCE real nos smokes e cenários de falha | Evidência de integração/recuperação; não meta de percentual de cobertura |
| Swagger/OpenAPI | Contrato de API documentado e testado; documentação pública apenas na configuração local |
| Logs JSON, correlação e métricas protegidas | Diagnóstico sem dados pessoais; sem tracing/APM/Dynatrace instalado |
| JMeter, planos e relatório de referência | Medição curta/limitada em ambiente próprio; não SLA/capacidade máxima |

## Onde validar

- Jornada manual: [demo](demo.md).
- Código/contratos/cenários: [testes](../infra/testing/README.md), [eventos](../contracts/events/README.md).
- Subida e persistência: [Compose](../infra/compose/README.md).
- Falhas e diagnóstico: [resiliência](../infra/resilience/README.md), [observabilidade](../infra/observability/README.md).
- Medições e limitações: [JMeter](../performance/jmeter/README.md), [baseline real](../performance/performance-report.md).

Não estão demonstrados: cluster/failover de infraestrutura, partição entre regiões, envio externo de notificação, política de retenção/apagamento definitivo, pentest, capacidade/SLO produtivos ou backup/restore. Essas limitações são explícitas nas ADRs e não são tratadas como requisitos concluídos.
