# Contratos de eventos — T06

**Estado: APROVADO pelo responsável pelo projeto em 10/11/2026**, mediante aprovação explícita na conversa. A aprovação abrange os contratos, expectedVersion, topologia e políticas de entrega descritos neste documento. Nenhum produtor, consumidor, migration ou provisionamento RabbitMQ foi implementado nesta etapa. Validar os exemplos não comprova entrega no broker.

Este diretório é a fonte compartilhável dos contratos. `docs/` permanece ignorado pelo Git. O enunciado exige publicação de `CUSTOMER_CREATED` e consumo de uma solicitação `CUSTOMER_STATUS_CHANGE`; os campos adicionais, versionamento, topologia e políticas abaixo são escolhas aprovadas deste projeto. As responsabilidades dos dois serviços permanecem as já definidas na arquitetura.

## 1. Decisões aprovadas

| Tema | Escolha aprovada | Alternativa e impacto |
|---|---|---|
| Payload | Envelope mínimo, sem nome, CPF, e-mail ou token | Incluir dados pessoais facilitaria integrações reais de notificação, mas aumenta exposição e acoplamento; a notificação atual será simulada |
| Versão do contrato | `schemaVersion: 1`, schema fechado e routing key `.v1` | Aceitar campos desconhecidos permitiria evolução aditiva sem nova versão, mas torna menos estrita a minimização do payload |
| Concorrência do status | `expectedVersion` obrigatório; divergência vai para DLQ | Last-write-wins por timestamp dispensa leitura da versão, mas depende de relógios e pode sobrescrever alterações concorrentes |
| Exchanges | `topic` no domínio, `direct` em retry/redelivery/DLQ | Apenas `direct` seria suficiente para os dois bindings atuais, mas restringiria futuras assinaturas por padrões |
| Filas | Quorum duráveis, inclusive retry/DLQ, transferência dead-letter `at-least-once` | Classic durável é mais simples, mas o DLX padrão não garante transferência segura em falhas |
| Retry de consumidor | Três rodadas após a inicial: 5 s, 30 s, 120 s; falha definitiva diretamente à DLQ | Retry imediato em memória é mais simples, mas ocupa threads e pode sobrecarregar uma dependência indisponível |
| Notificação | Registro fictício auditável no banco próprio, sem e-mail real | Apenas log não preserva deduplicação após reinícios |

As decisões foram aprovadas antes da implementação da T07/T08/T09. Alterações de contrato ou semântica exigem nova revisão explícita e a política de versionamento da seção 6.

## 2. Envelope v1

Formato JSON UTF-8, objeto único, até **16 KiB de corpo**. Propriedades duplicadas, JSON incompleto, conteúdo após o objeto e constantes como NaN/Infinity são inválidos. Os schemas usam JSON Schema Draft 2020-12 com validação de `format` obrigatoriamente habilitada; `format` sozinho pode ser apenas anotação em alguns validadores.

| Campo | Regra | Significado |
|---|---|---|
| `eventId` | UUID v4 minúsculo, obrigatório | Identidade imutável da ocorrência/solicitação, mantida em retries e replay |
| `eventType` | Constante do schema | `CUSTOMER_CREATED` ou `CUSTOMER_STATUS_CHANGE` |
| `schemaVersion` | Inteiro `1` | Versão do contrato, independente da versão do cliente |
| `occurredAt` | RFC 3339 UTC com `Z`, até 6 casas fracionárias | Momento da ocorrência/solicitação; usado para auditoria, nunca para ordenar alterações |
| `customerId` | Inteiro de 1 até 9223372036854775807 | Identificador do cliente, preservando precisão de BIGINT/Long |

Os schemas são fechados (`additionalProperties: false`): nome, CPF, e-mail, Authorization e quaisquer outros campos não previstos são recusados. Espaços e ordem das propriedades não mudam o significado. Não se deve usar ponto flutuante para armazenar IDs ou versões.

### CUSTOMER_CREATED

Schema: [customer-created.v1.schema.json](schemas/customer-created.v1.schema.json).

```json
{
  "eventId": "7ba85b17-7d33-4d17-9101-569a362239f2",
  "eventType": "CUSTOMER_CREATED",
  "schemaVersion": 1,
  "occurredAt": "2026-11-10T03:00:00Z",
  "customerId": 123
}
```

É um fato produzido pelo `customer-service`: o cadastro foi confirmado. Na T07, cliente e outbox devem ser gravados na mesma transação. UUID, instante e corpo são gerados uma vez, persistidos no outbox e reutilizados em todas as publicações. O `occurredAt` corresponde ao instante do cadastro e não ao instante de cada tentativa de envio. Uma falha do broker não desfaz o cadastro já confirmado.

O `customer-notification-service` registrará no `notification_db` uma notificação fictícia associada ao evento/cliente, com instante do processamento. Não precisa consultar dados do cliente ou acessar `customer_db`. Esta mensagem não contém endereço para envio de e-mail real.

### CUSTOMER_STATUS_CHANGE

Schema: [customer-status-change.v1.schema.json](schemas/customer-status-change.v1.schema.json).

```json
{
  "eventId": "cbca5352-22ad-48f2-aaf2-704735bc7737",
  "eventType": "CUSTOMER_STATUS_CHANGE",
  "schemaVersion": 1,
  "occurredAt": "2026-11-10T03:01:00.123456Z",
  "customerId": 123,
  "status": "INACTIVE",
  "expectedVersion": 0
}
```

Mantemos o nome do enunciado, mas sua semântica é **solicitar uma alteração**, e não afirmar que ela já ocorreu. O produtor externo/simulador não escolhe a nova versão do banco.

Campos adicionais obrigatórios: `status` exatamente ACTIVE/INACTIVE; `expectedVersion` inteiro de 0 até 9223372036854775806. A versão esperada é obtida por leitura do cliente antes de solicitar a mudança. Somente o `customer-service` modifica o cliente.

Os exemplos conceituais do enunciado não são apresentados como mensagens v1 completas: o projeto escolhe `occurredAt` em lugar de `createdAt` e acrescenta `schemaVersion`/`expectedVersion`. Essa diferença foi aprovada; não haverá preenchimento silencioso de campos ausentes pelo consumidor.

## 3. Concorrência, ordem e exclusão lógica

Foi aprovada uma versão de agregado `version` BIGINT, inicial 0, com migration posterior. A API deverá expor a versão nos DTOs de leitura e escrita para que o publicador da solicitação possa obtê-la. O campo ainda **não existe** no banco nem nas respostas atuais; sua implementação é trabalho futuro da T07/T08.

O consumidor executará, em uma transação:

1. Validar o contrato/metadados e consultar a deduplicação do seu consumidor.
2. Serializar a alteração do cliente com a mesma disciplina de locks usada por PUT/DELETE, sem acessar registros logicamente excluídos.
3. Comparar `expectedVersion` com a versão corrente. Apenas igualdade autoriza a alteração.
4. Atualizar status e `updated_at`, incrementar a versão e registrar o resultado idempotente na mesma transação.
5. Confirmar a mensagem somente após o commit.

Uma solicitação válida com o mesmo status atual também consome a versão e avança em 1. Isso dá uma decisão única a duas solicitações concorrentes com a mesma versão esperada. Alterações efetivas por PUT e DELETE também devem incrementar `version`; um PUT sem mudança efetiva pode manter a versão. Não usar `occurredAt`, ordem de chegada ou apenas o flag `redelivered` para decidir qual alteração prevalece.

| Exemplo | Decisão aprovada |
|---|---|
| Cliente v0; A solicita INACTIVE/expectedVersion 0 | Aplica, cliente v1 |
| A retorna com mesmo eventId e mesmo conteúdo | ACK sem nova alteração, mesmo que o cliente já esteja em outra versão |
| B tem outro eventId, ACTIVE/expectedVersion 0, cliente já v1 | `VERSION_CONFLICT`, sem alteração; DLQ |
| B espera v1, mas chega quando o cliente ainda está v0 | Também `VERSION_CONFLICT`; não aguarda uma mensagem anterior |
| PUT alterou o cliente após a leitura do publicador | Versão diverge; solicitação antiga vai para DLQ |
| Cliente ausente ou logicamente excluído | `CUSTOMER_NOT_FOUND`; DLQ sem recriar/restaurar registro |

Esta política rejeita conflitos em vez de tentar reordenar comandos. Não garante que duas intenções concorrentes sejam ambas aplicadas. Após analisar um conflito, o responsável lê o estado atual e, se ainda desejar a mudança, publica **nova solicitação com novo eventId e versão atual**. Reenviar o mesmo corpo conflitante não corrige a versão. A política não altera silenciosamente o contrato do PUT atual para exigir If-Match; um PUT posterior continua sendo uma nova escrita explícita.

## 4. Topologia aprovada

Manifesto aprovado: [topology.v1.json](topology.v1.json). É uma descrição dos recursos planejados, **não** um arquivo de definitions para importar no RabbitMQ. Nenhum comando desta etapa cria recursos no broker.

Vhost `datum`. Todos os exchanges duráveis, sem auto-delete. Todas as filas quorum, duráveis, não exclusivas e sem auto-delete. Ambiente local poderá usar um nó; isso não oferece alta disponibilidade nem proteção contra perda permanente desse nó/disco. Versão exata da imagem e provisionamento serão validados na T07; o desenho usa recursos documentados de quorum/TTL/DLX, sem depender de plugins de delayed exchange.

| Produtor | Exchange | Routing key | Fila | Consumidor |
|---|---|---|---|---|
| customer-service/outbox | `datum.customer.events` (topic) | `customer.created.v1` | `customer-notification-service.created.v1` | customer-notification-service |
| status-publisher externo/simulador | `datum.customer.events` (topic) | `customer.status-change.v1` | `customer-service.status-change.v1` | customer-service |

Os bindings usam essas chaves exatas na v1. Cada consumidor tem fila própria; duas aplicações independentes não competem na mesma fila. Novas réplicas de um mesmo consumidor podem competir em sua fila, protegidas por idempotência e controle de versão no banco.

Para cada identificador de consumidor `C` da tabela (igual ao nome da fila principal):

| Origem/destino | Exchange | Routing/binding key | Fila e comportamento |
|---|---|---|---|
| Falha transitória, retry 1 | `datum.customer.retry` (direct) | `C.retry.1` | `C.retry.1`, TTL de 5000 ms, sem consumidor |
| Retry 2 | `datum.customer.retry` | `C.retry.2` | `C.retry.2`, TTL de 30000 ms, sem consumidor |
| Retry 3 | `datum.customer.retry` | `C.retry.3` | `C.retry.3`, TTL de 120000 ms, sem consumidor |
| Expiração de cada retry | `datum.customer.redelivery` (direct) | `C` | Binding apenas para a fila principal `C` |
| Rejeição definitiva/exaustão da principal | `datum.customer.dead` (direct) | `C` | `C.dlq`, sem consumidor automático |

São duas filas principais, seis de retry e duas DLQs. As filas de retry usam TTL de fila para as mensagens (`message-ttl`), não expiração da própria fila. TTL e dead-letter routing key são declarados por fila/policy na implementação. O retorno de retry usa exchange próprio para atingir apenas o consumidor que falhou, sem redistribuir o evento ao exchange de domínio.

Filas principais e de retry terão DLX com `dead-letter-strategy=at-least-once` e `overflow=reject-publish`, incluindo as filas de origem dos TTLs. DLQs não terão TTL, DLX ou reenvio automático. Todas as filas terão limites iniciais de 10000 mensagens e 64 MiB por fila com rejeição de excesso, em vez de `drop-head`. São limites de demonstração, ajustáveis por política. Com destino cheio/indisponível, a transferência pode ficar pendente e exercer contrapressão; não é motivo para descartar ou confirmar silenciosamente a mensagem de origem.

`delivery-limit=5` nas filas principais é uma proteção adicional contra ciclos de redelivery por falhas de conexão/processo. Seu comportamento será testado na versão escolhida do RabbitMQ; ele não substitui a contagem dos retries planejados da aplicação. Prefetch inicial 10 e um consumidor por instância são parâmetros de demonstração aprovados, ajustáveis depois de testes.

## 5. Publicação, ACK, retries e DLQ

### Propriedades AMQP

AMQP 0-9-1; corpo JSON UTF-8; `content_type=application/json`, `content_encoding=UTF-8`, `delivery_mode=2`, `message_id=eventId`, `type=eventType`, `correlation_id=eventId`. Tipo, versão e routing key devem corresponder. Sem serialização Java, nomes de classes, JWT, senha, CPF ou e-mail em headers. A identidade do produtor vem da credencial/permissão do broker, não de um campo confiado do JSON.

Publicações usam `mandatory=true` e publisher confirms, com espera inicial máxima de 5000 ms por confirmação. `basic.return`, NACK e confirmação ausente/ambígua são falhas de envio. Receber ACK do broker não prova processamento pelo consumidor; e um confirm isolado não prova roteamento quando a publicação não é mandatory. A topologia precisa existir antes do primeiro envio. Mandatory detecta ausência de qualquer destino, não ausência de cada assinante pretendido; bindings esperados também precisam ser conferidos.

No outbox, só marcar como publicado após confirmação positiva e ausência de return. Reutilizar o mesmo ID em ambiguidades. Backoff aprovado para publicação: 1, 2, 4, 8, 16 e depois 30 segundos entre tentativas, mantendo o registro pendente enquanto a infraestrutura não aceitar. Não aplicar o limite de três retries de consumidor ao outbox nem apagar o cadastro/outbox por indisponibilidade do broker. Erro estrutural de um registro de outbox deve ser sinalizado como bloqueado para análise, sem descarte.

### Consumo e deduplicação

Entrega **at-least-once**, sem promessa de exactly-once. Cada serviço mantém sua própria inbox/deduplicação no seu banco, com chave única `(consumerId, eventId)`. No mesmo commit: alteração de status + resultado de processamento, ou registro de notificação fictícia + resultado. Uma falha anterior ao commit faz rollback de ambos; falha após commit e antes de ACK permite reentrega sem repetir o efeito local.

Guardar também uma representação normalizada/fingerprint dos campos validados: inteiros em base 10 sem perda de precisão, instante normalizado em UTC e demais valores do contrato. Comparar significado dos campos, não hash dos bytes JSON crus. Whitespace/ordem das propriedades não constituem conflito. O algoritmo exato dessa normalização será testado junto da inbox; não usar conversão dos IDs para double.

Duplicata com conteúdo igual e resultado APPLIED/NOTIFICATION_REGISTERED recebe ACK sem repetir efeitos. Mesmo eventId com conteúdo diferente é `EVENT_ID_CONFLICT`, rejeitado para DLQ, preservando o registro original. Se uma solicitação válida foi definitivamente rejeitada por regra de negócio, persistir REJECTED com motivo; uma reentrega igual continua sendo encaminhada à DLQ, sem alterar o cliente. Essa distinção evita que um crash entre registrar a rejeição e rejeitar no broker resulte em ACK silencioso na reentrega.

Não expirar automaticamente as chaves de deduplicação nesta versão. Dois UUIDs diferentes não são automaticamente a mesma ocorrência; cabe ao outbox preservar o ID do cadastro. O registro fictício da notificação é o efeito local auditável, sem envio externo a deduplicar.

### Classificação de falhas

| Condição | Tratamento aprovado |
|---|---|
| Sucesso/duplicata já aplicada | Commit/consulta durável do resultado, depois ACK |
| JSON/schema/versão/metadados inválidos | Rejeitar sem requeue para DLQ; não tentar desserialização em classes arbitrárias |
| Cliente ausente/excluído, versão divergente, mesmo eventId com outro conteúdo | Falha definitiva: nenhuma alteração do cliente; DLQ |
| Banco temporariamente indisponível, deadlock/transação abortada transitória | Rollback; publicar na próxima fila de retry |
| Falha transitória após retry 3 | Rejeitar sem requeue para DLQ |
| Transferência para retry não confirmada/returned | Não dar ACK na origem; suspender consumo/recuperar conexão com intervalo inicial de 5 s, sem laço de NACK imediato |

No caminho de retry, manter o corpo e as propriedades de identidade. Header interno `x-retry-attempt` indica 1, 2 ou 3. Publicar persistentemente no retry correspondente, aguardar confirm sem return e só então ACK da entrega original. Um crash entre essas duas ações pode duplicar a mensagem, por isso a inbox continua obrigatória. Nas expirações de TTL e na rejeição à DLQ, o DLX deve usar a transferência at-least-once configurada, e não o default at-most-once.

Na entrada pelo exchange de domínio, headers internos de retry/death fornecidos pelo publicador externo são inválidos. No retorno pelo exchange interno de redelivery, exigir contador interno de retry no intervalo 1..3. Broker/user permissions devem impedir que o publicador externo escreva em exchanges de retry/redelivery/dead-letter. `x-death` é informação de diagnóstico do broker, não um contador arbitrário confiado do produtor.

O caminho nominal tem uma tentativa inicial e três rodadas de retry. Crashes e reentregas físicas podem aumentar o número de execuções; não se promete um teto global de quatro chamadas por eventId. Todo caminho de retry incrementa a rodada, e redeliveries sem progresso têm a proteção adicional do delivery-limit. Não usar `requeue=true` em loop apertado.

DLQ é quarentena para análise. Manter a mensagem original e metadados técnicos de correlação; não imprimir corpo, headers arbitrários ou exceções potencialmente sensíveis nos logs. Acesso às DLQs deve ser restrito, pois mensagens inválidas podem conter campos indevidos. Não executar replay automático. Após corrigir uma falha de infraestrutura, um replay do mesmo evento preserva o ID e reinicia conscientemente a rodada de retry; uma nova intenção de negócio ou correção de payload exige novo ID. Rejeições persistidas por versão/conteúdo não se tornam válidas apenas por replay.

### Permissões a implementar

Credenciais separadas: customer-service publica criação e gerencia seu consumo de status; notification consome criação e publica apenas seus retries; status-publisher escreve somente no exchange de domínio. Provisionamento administra exchanges, filas e policies. Publicadores externos não têm acesso aos bancos. O broker não é autenticado com access tokens das APIs; o script de demonstração futuro terá credencial própria de escopo limitado. Limitar também quais routing keys/tipos cada papel pode publicar por validação e, quando suportado, permissões de tópico.

## 6. Compatibilidade

Schemas v1 estão congelados com esta aprovação. Como são fechados, adicionar/remover campos, alterar enums/tipos/obrigatoriedade ou semântica exige **v2**, novo schema e nova routing key. Implantar suporte do consumidor antes de mudar o produtor; migrar bindings/filas de forma coordenada. Durante coexistência, não assumir deduplicação entre v1 e v2 sem uma estratégia específica. Mensagens v1 não são reinterpretadas como v2.

UUID, occurredAt e conteúdo são imutáveis em reenvios. `schemaVersion` é do contrato; `expectedVersion` é pré-condição de concorrência do cliente; `x-retry-attempt` é metadado de transporte. Nenhum desses três números substitui os demais.

## 7. Validar localmente, sem broker

Python 3.10+; ambiente isolado ignorado pelo Git. Na raiz do projeto:

```sh
python3 -m venv contracts/events/.venv
contracts/events/.venv/bin/python -m pip install -r contracts/events/requirements.txt
contracts/events/.venv/bin/python contracts/events/verify.py
```

Instalação inicial requer PyPI; depois a verificação é offline, sem consultar os endereços `$schema`/`$id`. Não é necessário Docker, Keycloak, PostgreSQL ou reiniciar a API. O validador verifica os dois schemas, exemplos positivos e negativos, limites, formatos de datas/UUID, rejeição de dados pessoais adicionais, JSON ambíguo/excessivo e coerência estática das filas/rotas do manifesto. O resultado deve terminar em verificações aprovadas e **Contratos APROVADOS em 2026-11-10**.

Os exemplos negativos estão em [examples/invalid](examples/invalid). Devem falhar: e-mail adicional em criação, ausência de expectedVersion e status BLOCKED. Para uma inspeção manual, compare-os com os dois [exemplos válidos](examples).

### Evidências que ainda serão exigidas

| Etapa | Testes com código/broker reais ainda necessários |
|---|---|
| T07 | Cliente + outbox atômicos; broker fora; restart; confirms ausentes/ambíguos; mandatory return; mesmo ID em todas as tentativas |
| T08 | Mesma mensagem repetida/concorrente; ID com conteúdo divergente; duas mensagens diferentes com mesma expectedVersion; mensagem de versão futura; PUT/DELETE concorrentes; rollback antes de commit; crash entre commit e ACK |
| T09 | Notificação + inbox atômicas no banco próprio; duplicatas/restart; sem acesso ao banco de clientes; sem envio real de e-mail |
| T11 | Filas/policies efetivas; três TTLs; retorno só ao consumidor que falhou; DLQ; falha de destino da transferência; overflow; delivery-limit; falha entre confirm do retry e ACK original; recuperação sem perda e com duplicatas toleradas |

Esses testes não são substituídos pela validação estática da T06. A aprovação permite planejar os próximos incrementos com este contrato; cada implementação continua sujeita à validação local antes de commit e push.

## 8. Referências técnicas

- [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-validation): tipos, limites e validação de formatos.
- [Validação com jsonschema](https://python-jsonschema.readthedocs.io/en/stable/validate/): habilitação explícita do FormatChecker; dependências adicionais para formatos.
- [RabbitMQ: ACK e publisher confirms](https://www.rabbitmq.com/docs/4.2/confirms): confirmações do produtor e do consumidor têm responsabilidades distintas; mandatory/returns para mensagens sem rota.
- [RabbitMQ: quorum queues](https://www.rabbitmq.com/docs/4.2/quorum-queues): transferência dead-letter at-least-once depende de configuração explícita e reject-publish; pode haver duplicatas/contrapressão.
- [RabbitMQ: DLX](https://www.rabbitmq.com/docs/4.2/dlx): DLX padrão pode perder mensagens na transferência; políticas e routing key de dead-letter precisam ser explícitas.
