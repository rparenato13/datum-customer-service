# Registro de decisões arquiteturais

[README](../../README.md) · [Arquitetura](../README.md) · [Evidências](../../infra/testing/README.md)

Revisão documental em 10/11/2026. ADR-001 a ADR-005 preservam as decisões consolidadas antes do desenvolvimento; a quinta foi concretizada com dois bancos/credenciais. ADR-006 a ADR-011 registram escolhas já incorporadas nas etapas e verificações locais. Este registro não aprova funcionalidades novas.

## ADR-001 — Score direto no customer-service

**Estado:** aprovada e implementada.

**Contexto:** o endpoint público recebe ID; somente o dono do cadastro conhece o CPF usado pelo provedor.

**Decisão:** consultar o cliente e chamar diretamente o provedor por ScoreClient/RestClient. Encerrar a transação de leitura antes da rede. Não persistir nem cachear Score.

**Alternativa avaliada:** score-service intermediário. Rejeitada por adicionar rede, deploy e autorização sem capacidade de negócio própria neste escopo.

**Consequências:** menos um salto interno; customer precisa impor limites e validar contrato externo. Integração síncrona ocupa thread. Um domínio de Score com regras/provedores próprios poderia justificar separação futura.

**Evidência:** CustomerScoreIT e testes do adaptador/configuração; [matriz](../../infra/testing/README.md).

## ADR-002 — Clientes como domínio coeso

**Estado:** aprovada e implementada.

**Contexto:** CRUD, status, consultas e publicação compartilham invariantes e dados.

**Decisão:** customer-service possui o ciclo de vida completo, com camadas internas e um único proprietário da escrita.

**Alternativa avaliada:** separar read/write/status em microsserviços. Rejeitada porque não há requisito de equipes, modelos ou escala independente que justifique consistência distribuída adicional.

**Consequências:** invariantes e transações locais simples; crescimento futuro deve ser tratado por fronteira de negócio, não pelo verbo HTTP.

**Evidência:** CustomerApiIT, CustomerQueryIT e CustomerPersistenceIT.

## ADR-003 — Notificação independente e simulada

**Estado:** aprovada e implementada; capacidade adicional ao mínimo do enunciado.

**Contexto:** é necessário demonstrar microsserviços e reação independente à criação, sem introduzir um serviço de Score artificial.

**Decisão:** customer-notification-service consome CUSTOMER_CREATED e registra CUSTOMER_CREATED_SIMULATION em armazenamento próprio. Não consulta clientes, não altera status e não envia e-mail real.

**Alternativa avaliada:** consumidor dentro de customer. Mais simples, porém não demonstra a mesma independência de implantação/falha. SMTP externo foi excluído para não depender de destinatários/segredos.

**Consequências:** consistência eventual, segundo banco/consumidor e operação adicional. Evento mínimo sem dados pessoais é suficiente para registrar a simulação; envio real exigiria contrato e regras novos.

**Evidência:** NotificationConsumerIT inicia sem API de clientes; cenários notification/broker e replay.

## ADR-004 — Status atualizado pelo proprietário dos dados

**Estado:** aprovada e implementada.

**Contexto:** um evento externo solicita alteração de status; efeito e deduplicação precisam ser consistentes.

**Decisão:** customer-service consome CUSTOMER_STATUS_CHANGE e atualiza seu banco na mesma transação da inbox.

**Alternativa avaliada:** worker separado chama API interna. Rejeitada por introduzir outra rede, credencial e coordenação de ACK sem retirar a responsabilidade do dono dos dados.

**Consequências:** consumidor escala junto à aplicação; integrações externas publicam intenção com versão e precisam tratar rejeições permanentes.

**Evidência:** CustomerStatusChangeIT e testes de ACK/retry.

## ADR-005 — Persistência segregada por serviço

**Estado:** aprovada em princípio no desenho; concretizada e verificada na implementação.

**Contexto:** deduplicação deve sobreviver a reinícios sem compartilhar tabelas de domínio.

**Decisão:** mesmo PostgreSQL local, customer_db/customer_app e notification_db/notification_app, sem CONNECT cruzado ou uso de conta administrativa pelas aplicações. Flyway por serviço; JPA em customer, JDBC na notificação.

**Alternativa avaliada:** schema/entidades compartilhados ou deduplicação em memória. Rejeitados por acoplamento e perda da garantia após reinício, respectivamente. Servidores separados seriam apropriados para isolamento físico, mas ampliariam a infraestrutura local.

**Consequências:** transações apenas locais, mesmo ponto de falha físico em demonstração; nenhuma foreign key entre serviços.

**Evidência:** bootstrap PostgreSQL, NotificationConsumerIT, DatabaseResilienceIT e persistência no verificador Compose.

## ADR-006 — ACTIVE/INACTIVE e exclusão lógica

**Estado:** escolha confirmada durante a implementação e testada.

**Contexto:** status de negócio não deve se confundir com remoção e reserva de CPF.

**Decisão:** DELETE marca deleted_at, preservando status e CPF único. Consultas omitem excluídos; repetição de DELETE retorna 404. Nome normalizado ignora caixa, sem remover acentos.

**Alternativa avaliada:** exclusão física. Rejeitada na escolha do responsável pelo projeto. Reutilização de CPF após exclusão não foi adotada.

**Consequências:** histórico permanece e armazenamento cresce; ainda não há restauração, expurgo ou política de retenção/anonimização.

**Evidência:** CustomerApiIT, CustomerQueryIT e smoke/Compose verificam linha preservada, 404 e 409 de CPF.

## ADR-007 — Outbox e inbox transacionais

**Estado:** implementada e validada.

**Contexto:** commit do cliente e disponibilidade do broker não são atômicos; reentrega pode ocorrer mesmo após um processamento concluído.

**Decisão:** cliente + evento no mesmo commit, relay com lease/fencing e confirm mandatory; inbox + efeito no commit do consumidor; ACK depois. PUBLISHED comprova broker, não notificação.

**Alternativas avaliadas:** publicar antes/depois do commit sem outbox permite evento órfão ou perdido. Transação distribuída não foi adotada. Deduplicar em memória falha após reinício.

**Consequências:** consistência eventual e publicação/entrega ao menos uma vez; duplicatas de transporte são esperadas. Tabelas e relay custam operação e precisam de retenção futura. Não se promete exactly-once global.

**Evidência:** CustomerOutboxIT, NotificationConsumerIT e cenários broker/ack/saturation.

## ADR-008 — Envelope v1 mínimo e versão esperada

**Estado:** contrato aprovado na T06; aplicado nos produtores/consumidores.

**Contexto:** identificar intenção, deduplicar e evitar sobrescrita por mensagens concorrentes/reordenadas, sem propagar CPF/e-mail.

**Decisão:** schemas fechados com eventId UUID v4, eventType, schemaVersion, occurredAt UTC e customerId; status acrescenta status/expectedVersion. Inbox compara fingerprint dos campos normalizados. Metadata de transporte é validada separadamente.

**Alternativas avaliadas:** confiar somente em eventId não detecta conteúdo conflitante; last-write-wins por horário pode sobrescrever intenção com relógios divergentes; ordenar/reter todas as versões requer armazenamento/política adicional.

**Consequências:** versão obsoleta é rejeitada e enviada à DLQ, sem buffer/reordenação automática. Alterar campos/semântica do JSON fechado exige v2 e migração coordenada. Correlação técnica opcional não altera JSON v1 nem fingerprint.

**Evidência:** [contratos](../../contracts/events/README.md), 139 verificações estáticas, codecs e CustomerStatusChangeIT.

## ADR-009 — Retries finitos e DLQ explícita

**Estado:** contrato aprovado e verificado com RabbitMQ 4.4.0.

**Contexto:** dependências falham; requeue imediato pode gerar loop e descarte silencioso não é aceitável.

**Decisão:** três retries por TTL 5/30/120 s, cópia confirmada antes de ACK da origem, redelivery específico por consumidor. Quorum/DLX at-least-once, overflow reject-publish e delivery-limit nas principais. Erro permanente/exaustão fica em DLQ sem replay automático.

**Alternativas avaliadas:** requeue em loop, drop-head e DLX sem retenção durante falha não atendem à semântica desejada. Retry infinito de falha permanente impede diagnóstico.

**Consequências:** mais filas/políticas e possibilidade de duplicata no handoff. DLQ requer decisão operacional; um nó quorum local não oferece HA. Três rodadas não significam no máximo quatro execuções físicas.

**Evidência:** [resiliência](../../infra/resilience/README.md): retries, ack, dlq, saturation e delivery-limit.

## ADR-010 — Identidade real e permissões mínimas

**Estado:** implementada e validada.

**Contexto:** o avaliador precisa obter JWT real e demonstrar USER/ADMIN sem API de login própria.

**Decisão:** Keycloak local, cliente público Code + PKCE S256, RS256/issuer/audience/validade conferidos pelo Resource Server. USER consulta; ADMIN também escreve. AMQP/bancos usam contas independentes. Docs/health públicos locais; métricas protegidas.

**Alternativas avaliadas:** JWT montado manualmente, decoder permissivo ou grant password. Não adotados: ocultam falhas de integração ou ampliam exposição de senha. Segurança produtiva do IdP/TLS foi deixada fora da demonstração local.

**Consequências:** issuer precisa ser consistente host/container; tokens curtos exigem renovação. Realm persistido não é recriado ao editar .env. Basic da conta de monitoramento é restrito à notificação, não autenticação de negócio.

**Evidência:** verificador Keycloak real, testes de segurança, observabilidade e Compose.

## ADR-011 — Limites explícitos, sinais e experimentos antes de tuning

**Estado:** implementada e medida.

**Contexto:** Score lento, pool indisponível e backlogs precisam de espera limitada e diagnóstico reproduzível.

**Decisão:** timeouts separados por dependência; retries Score restritos e sem repetição de timeout; pools/query/lock/transaction limitados; logs JSON com correlação e métricas com labels limitados. Compose reproduzível com bootstrap automático e cargas em ambientes descartáveis.

**Alternativas avaliadas:** cache/circuit breaker, novos índices ou aumento de pools/lotes preventivamente. Não adotados sem regra/evidência. Logs de SQL/parâmetros e IDs como labels foram excluídos por exposição/cardinalidade.

**Consequências:** não existe capacidade/SLA demonstrado por uma carga curta. O lag de criação observado motiva experimento de lote/poll, não tuning automático. Não há tracing/APM instrumentado nem Prometheus/Grafana instalados.

**Evidência:** DatabaseResilienceIT, suíte de falhas, [observabilidade](../../infra/observability/README.md) e [baseline JMeter](../../performance/performance-report.md).
