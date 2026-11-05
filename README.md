# Event Sourcing + CQRS — Sistema Bancário

Implementação didática de um núcleo bancário em Java, construído para demonstrar
Event Sourcing e CQRS sem um framework de Event Store. O histórico de domínio é
a fonte da verdade: comandos produzem eventos append-only, e o estado de uma
conta pode ser reconstituído a partir desses eventos.

> **Estado do projeto:** as fases 1 a 9 foram desenvolvidas. A autenticação
> JWT/OIDC está implementada, mas depende de um issuer configurado e de
> validação de integração; a Query API está em implementação no roadmap. O
> sistema é uma referência de arquitetura e não está pronto para operar como
> banco real. Não inclui integração com PIX/SPB, KYC, operação multi-região,
> nem comprovação de metas de desempenho de produção.

## Índice

- [O que foi implementado](#o-que-foi-implementado)
- [Arquitetura e módulos](#arquitetura-e-módulos)
- [Fluxos principais](#fluxos-principais)
- [Tecnologias e pré-requisitos](#tecnologias-e-pré-requisitos)
- [Executar localmente](#executar-localmente)
- [Configuração](#configuração)
- [API HTTP](#api-http)
- [Eventos de integração](#eventos-de-integração)
- [Persistência e migrations](#persistência-e-migrations)
- [Privacidade e integridade](#privacidade-e-integridade)
- [Observabilidade](#observabilidade)
- [Testes](#testes)
- [Limitações e próximos passos](#limitações-e-próximos-passos)
- [Documentação complementar](#documentação-complementar)

## O que foi implementado

| Fase | Entrega |
|---|---|
| 1 | Domínio de contas e transferências com eventos imutáveis e regras de negócio. |
| 2 | Event Store PostgreSQL, streams versionados, concorrência otimista e proteção append-only. |
| 3 | Command API REST, validação de entrada, tratamento de erros e idempotência. |
| 4 | Modelos de leitura de saldo/extrato, projeção idempotente, checkpoints e execução agendada. |
| 5 | Snapshots descartáveis para reidratação mais rápida e consultas históricas por data ou versão. |
| 6 | Saga de transferência entre contas, com eventos de progresso, retry idempotente e compensação. |
| 7 | Publisher Kafka, eventos de integração, JSON Schema e registro no Schema Registry. |
| 8 | Upcasting, criptografia AES-GCM com chave por conta, crypto-shredding e cadeia de hashes por stream. |
| 9 | Logs JSON, métricas e tracing configurável, propagação de correlation ID e documentação operacional. |

As fases representam funcionalidades implementadas no código; isso não significa
que todas as metas não funcionais de latência, throughput ou disponibilidade
tenham sido medidas ou comprovadas.

## Arquitetura e módulos

```text
Cliente
  |
  v
api-command (Spring Boot / REST)
  |
  v
application (handlers, idempotência, saga, portas)
  |
  v
domain (Conta, Transferencia, eventos e regras)
  |
  v
infrastructure
  ├── Event Store e snapshots (PostgreSQL)
  ├── projeções de saldo/extrato (PostgreSQL)
  ├── publicação de eventos (Kafka + Schema Registry)
  ├── criptografia de dados pessoais
  └── verificação da cadeia de integridade
```

- **`domain/`** — agregados e regras de negócio em Java, sem dependência de
  Spring, JDBC, Jackson ou Kafka.
- **`application/`** — comandos, handlers, saga, serviços de histórico e
  interfaces (portas) para persistência e unidade de trabalho.
- **`infrastructure/`** — adapters PostgreSQL/JDBC, migrations Flyway, projeções,
  snapshots, publisher, serialização e criptografia.
- **`api-command/`** — aplicação Spring Boot com endpoints de comando,
  consulta, transferência e reconstrução histórica.

O read model de saldo e extrato é atualizado continuamente pelo agendador da
projeção e consultado pela API. As consultas usam somente as tabelas projetadas;
o endpoint de estado histórico continua reconstruindo o agregado do Event Store.

## Fluxos principais

### Comando de conta

1. A API valida o formato dos dados recebidos.
2. O handler verifica uma eventual `Idempotency-Key` e carrega o stream (ou
   snapshot mais eventos posteriores).
3. O agregado decide se a operação é permitida e produz eventos.
4. O Event Store grava todos os eventos do comando em uma transação, com versões
   sequenciais e metadados.
5. A confirmação só é devolvida depois do commit.

Conflitos de versão causam nova tentativa limitada no handler. As regras do
domínio, como valor positivo, saldo disponível e estado da conta, são aplicadas
antes de gravar eventos.

### Projeções e snapshots

O projetor mantém saldo/status em `rm_conta_saldo` e movimentações em
`rm_extrato`. Checkpoint e atualizações do read model são gravados na mesma
transação; o projetor evita reaplicar versões já projetadas. `ProjectionRunner`
tem operações de lote, catch-up e rebuild. Os snapshots são auxiliares e podem
ser descartados; o histórico permanece no Event Store.
Na aplicação, o runner executa automaticamente em lotes e retoma do checkpoint
após reiniciar. Como comandos e projeções são assíncronos, as consultas são
eventualmente consistentes: logo após um comando, saldo e extrato podem ainda
não refletir o evento. Enquanto o read model da conta ainda não existir, os
endpoints de consulta respondem `503 Service Unavailable`; tente novamente.

### Transferência

A saga grava seu próprio stream `transfer-{uuid}` e coordena o débito na origem
e o crédito no destino:

- se a origem rejeitar o débito, a transferência é rejeitada;
- se o destino rejeitar o crédito, a saga tenta estornar a origem;
- se a compensação também não puder ser feita, registra a falha para
  intervenção.

As etapas usam chaves idempotentes derivadas do identificador da transferência.
Há suporte a retomar sagas não terminais paradas, mas a política operacional de
recuperação deve ser configurada e monitorada no ambiente de execução.

### Publicação para Kafka

O publisher lê eventos confirmados do Event Store a partir de um checkpoint,
converte apenas os eventos destinados ao contrato público e avança o checkpoint
após a publicação. O publisher usa Kafka com `acks=all` e idempotência do
produtor. O cliente tenta novamente durante até 120 segundos por entrega, com
timeout de requisição de 30 segundos e backoff fixo de 100 ms. Se o broker ou
o Schema Registry continuar indisponível, a execução agendada falha
com log e métricas; uma nova tentativa ocorre no próximo ciclo configurado.

O checkpoint PostgreSQL só avança depois que todos os eventos publicáveis do
lote recebem confirmação. Como a transação do banco não pode ser atômica com a
transação Kafka, uma falha pode reenviar mensagens que já foram confirmadas.
Portanto, a garantia é **ao menos uma vez**, não exatamente uma vez. `eventId`
é estável entre tentativas e está no envelope e no header Kafka; consumidores
devem deduplicar por esse valor em uma inbox durável, na mesma transação do
efeito de negócio. Exemplo de chave:

```sql
CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
```

O consumidor insere a chave na inbox e aplica seu efeito de negócio na mesma
transação; conflito na chave significa que o evento já foi processado e pode
ser confirmado sem repetir o efeito.

Etapas internas conhecidas da saga são omitidas intencionalmente. Um tipo de
evento sem mapeamento falha explicitamente e não permite avançar o checkpoint:
não há descarte nem dead-letter automático, pois isso poderia perder um evento
de negócio. Corrija o mapeamento/contrato e retome o publisher; não avance o
checkpoint manualmente sem procedimento de recuperação aprovado.

O envelope v1 usa os subjects `<topic>-value`. Configure o Schema Registry com
compatibilidade `BACKWARD_TRANSITIVE`; mudanças compatíveis devem ser aditivas
e opcionais. Mudanças incompatíveis exigem novo tópico/subject versionado e
migração coordenada dos consumidores. Como `data` é aberto no JSON Schema v1,
o Registry valida o envelope, mas não garante compatibilidade dos campos
específicos de cada tipo de evento; mantenha testes de contrato por tipo.

Monitore `integration.publisher.batches.failed`,
`integration.publisher.failure.event.position` e
`integration.publisher.event.lag`, além do timer de duração. Configure alertas
para qualquer aumento de lotes falhos, posição de falha diferente de zero e lag
persistente. O limite de lag e a janela de alerta devem ser definidos conforme
a capacidade e o objetivo operacional do ambiente; não há SLO de lag medido
neste repositório.

## Tecnologias e pré-requisitos

- JDK **21** (definido em `pom.xml`).
- Maven 3.9 ou compatível.
- PostgreSQL 16.
- Um provedor OIDC que emita JWTs com `sub` estável. O projeto valida tokens,
  mas não instala nem provisiona um provedor de identidade.
- Docker Desktop ou Docker Engine para os testes de integração com Testcontainers.
- Kafka e Confluent-compatible Schema Registry somente para habilitar a
  publicação de integração.
- Um coletor OpenTelemetry somente se tracing exportado for desejado.

## Executar localmente

### 1. Iniciar PostgreSQL

Com Docker, um banco local para desenvolvimento pode ser iniciado assim:

```powershell
docker run --name banco-es-postgres `
  -e POSTGRES_DB=banco `
  -e POSTGRES_USER=banco `
  -e POSTGRES_PASSWORD=banco `
  -p 5432:5432 `
  -d postgres:16-alpine
```

O usuário configurado precisa ter permissão para executar as migrations Flyway.
As migrations também criam o papel `app_user` (`NOLOGIN`) e concedem a ele
permissões restritas no Event Store e nas tabelas operacionais, incluindo
`UPDATE` das chaves envelopadas para permitir a rotação. Elas não configuram
automaticamente a aplicação para conectar como esse papel. O exemplo local
usa `banco`, que é o usuário proprietário criado pelo container. Em produção,
separe o usuário que aplica migrations do usuário runtime e configure
autenticação/menor privilégio.

### 2. Definir a chave mestra

O serviço exige uma chave AES-256 Base64. Gere uma chave única e mantenha-a em
um gerenciador de segredos; não use o exemplo ou uma chave versionada no Git.
Em PowerShell, pode-se gerar uma chave temporária para desenvolvimento:

```powershell
$bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$env:BANCO_CRYPTO_MASTER_KEY = [Convert]::ToBase64String($bytes)
```

Use o valor gerado na sessão do terminal para iniciar a aplicação. A chave não
deve ser regenerada entre reinicializações se o banco contiver dados cifrados:
uma chave diferente impedirá a abertura das chaves de dados existentes.

### 3. Executar a API

Na raiz do repositório:

```powershell
mvn -pl api-command -am spring-boot:run
```

A aplicação usa `http://localhost:8080` por padrão. O Flyway aplica as migrations
ao iniciar. A conexão PostgreSQL padrão é `jdbc:postgresql://localhost:5432/banco`
com usuário e senha `banco`, configuráveis pelas variáveis descritas abaixo.
Configure `OIDC_ISSUER_URI` com o issuer do seu provedor antes de iniciar; sem
essa configuração a autenticação JWT não pode ser validada.

## Configuração

| Variável / propriedade | Padrão | Descrição |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/banco` | JDBC URL do PostgreSQL. |
| `DB_USER` | `banco` | Usuário do banco. |
| `DB_PASSWORD` | `banco` | Senha do banco; substitua fora do desenvolvimento local. |
| `BANCO_CRYPTO_MASTER_KEY` | — | Chave Base64 AES-256 obrigatória para iniciar o serviço. |
| `BANCO_CRYPTO_ROTATION_NEW_MASTER_KEY` | vazio | Chave Base64 AES-256 de destino; necessária apenas durante uma rotação controlada. |
| `BANCO_MAINTENANCE_ENABLED` | `false` | Quando `true`, bloqueia rotas não administrativas para permitir uma rotação em janela de manutenção. |
| `OIDC_ISSUER_URI` | — | Issuer OIDC usado para validar assinatura, emissor e validade do JWT. |
| `banco.snapshot.interval` | `100` | Intervalo de versões para criação de snapshot. |
| `BANCO_PROJECTIONS_ENABLED` | `true` | Ativa o processamento agendado das projeções. |
| `BANCO_PROJECTIONS_BATCH_SIZE` | `200` | Máximo de eventos lidos por lote de projeção. |
| `BANCO_PROJECTIONS_POLL_INTERVAL` | `1000` ms | Atraso fixo entre lotes de projeção. |
| `banco.messaging.enabled` | `false` | Ativa o publisher e o agendador Kafka quando `true`. |
| `banco.messaging.bootstrap-servers` | `localhost:9092` | Endereço dos brokers Kafka. |
| `banco.messaging.client-id` | `banco-event-publisher` | Client ID do produtor Kafka. |
| `banco.messaging.batch-size` | `100` | Máximo de eventos lidos por lote. |
| `banco.messaging.poll-interval` | `1000` ms | Atraso fixo entre lotes publicados. |
| `banco.messaging.schema-registry-url` | `http://localhost:8081` | URL base do Schema Registry. |
| `banco.messaging.schema-registry-username` | vazio | Usuário opcional do Schema Registry. |
| `banco.messaging.schema-registry-password` | vazio | Senha opcional; informe junto com o usuário. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | Endpoint OTLP HTTP para traces. |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Probabilidade de amostragem de traces. |

Para habilitar Kafka, configure `banco.messaging.enabled=true` e forneça os
endereços do broker e do Schema Registry. As credenciais do Schema Registry
devem ser fornecidas juntas, por configuração externa; nunca as grave no código.
Operações em `/admin/**` exigem um JWT cujo claim `scope` inclua
`privacy:manage`. Registre essa permissão somente para operadores autorizados.

## API HTTP

Todos os endpoints, exceto `GET /actuator/health`, exigem um bearer token JWT
válido do issuer configurado. O `sub` do token identifica o titular; o cliente
não escolhe o titular ao abrir uma conta. Em contas existentes, somente o
titular pode executar comandos, consultar saldo, extrato e histórico ou
consultar transferência iniciada a partir da própria conta. Contas criadas
antes desta regra só poderão ser acessadas se o `titularRef` armazenado
corresponder ao `sub` emitido pelo provedor.

Valores monetários são enviados como decimais JSON, por exemplo `25.50`.
`Idempotency-Key` evita repetir efeitos para o mesmo usuário e operação.
`X-Correlation-Id` aceita um UUID; se omitido, a API gera um. `X-User-Id` não é
usado como identidade nem concede acesso.

### Abrir conta

```http
POST /contas
Content-Type: application/json
Authorization: Bearer <access-token>
Idempotency-Key: abrir-conta-001
X-Correlation-Id: 5e1d97b0-1c3e-4d3e-8ee8-2e6e3aa2f1e0

{
  "limite": 500.00
}
```

Retorna `201 Created` com o identificador da conta e a versão resultante.
O `titularRef` persistido é obtido do claim `sub` autenticado, nunca do corpo ou
de `X-User-Id`.

### Depositar

```http
POST /contas/{id}/depositos
Authorization: Bearer <access-token>
Idempotency-Key: deposito-001
Content-Type: application/json

{
  "valor": 100.00,
  "origem": "CAIXA"
}
```

Retorna `202 Accepted`.

### Sacar

```http
POST /contas/{id}/saques
Idempotency-Key: saque-001
Content-Type: application/json

{
  "valor": 25.00
}
```

Retorna `202 Accepted` quando o comando é aceito.

### Bloquear, desbloquear e encerrar

- `POST /contas/{id}/bloqueio` com `{"motivo":"solicitação"}` bloqueia.
- `DELETE /contas/{id}/bloqueio` desbloqueia.
- `POST /contas/{id}/encerramento` encerra se o saldo estiver zerado.

Estas operações aceitam `Idempotency-Key` e `X-Correlation-Id` quando aplicável.
Todas exigem o bearer token do titular.

### Transferir e consultar transferência

```http
POST /transferencias
Authorization: Bearer <access-token>
Idempotency-Key: transferencia-001
Content-Type: application/json

{
  "origem": "UUID-DA-CONTA-ORIGEM",
  "destino": "UUID-DA-CONTA-DESTINO",
  "valor": 42.50
}
```

A resposta é `201 Created`, com o identificador e estado da saga. Consulte com
`GET /transferencias/{id}`.

### Consultar estado histórico

Informe exatamente um critério:

```http
GET /contas/{id}/historico/estado?versao=3
GET /contas/{id}/historico/estado?em=2026-01-15T10:00:00Z
```

O endpoint reconstitui o agregado dos eventos e devolve um snapshot do estado
daquela versão ou instante.

### Consultar saldo e extrato projetados

```http
GET /contas/{id}/saldo
GET /contas/{id}/extrato?pagina=0&tamanho=20
```

O extrato usa páginas começando em zero, tamanho padrão 20 e máximo 100. A
ordenação é da movimentação mais recente para a mais antiga, com desempate
estável por identificador interno. Saldo e extrato são lidos dos read models
PostgreSQL e podem apresentar atraso em relação aos comandos.

### Erros

As respostas seguem o formato `ProblemDetail` do Spring:

- `400 Bad Request`: formato ou validação inválida;
- `409 Conflict`: conta já existente ou conflito de concorrência persistente;
- `422 Unprocessable Entity`: regra de negócio rejeitou o comando.

## Eventos de integração

Quando o publisher está ativado, os tópicos padrão são:

- `banco.conta.eventos.v1`
- `banco.transferencia.eventos.v1`

O valor Kafka segue o envelope wire do Schema Registry (magic byte, schema ID
e payload JSON). O produtor registra o JSON Schema no subject `<topic>-value`.
`eventId`, tipo e, quando disponíveis, `correlationId` também seguem em headers.
Referências pessoais não são incluídas no contrato de integração. Eventos
internos de progresso da saga são omitidos.

## Persistência e migrations

As migrations Flyway estão em
`infrastructure/src/main/resources/db/migration/`:

1. `V1` — Event Store, snapshots, idempotência, checkpoints de projeção e
   imutabilidade dos eventos.
2. `V2` — tabelas de saldo e extrato projetados.
3. `V3` — checkpoint do publisher de integração.
4. `V4` — chaves de dados pessoais envelopadas.
5. `V5` — heads e registros da cadeia de integridade.

O Event Store tem posição global, identificador e versão por stream, tipo de
evento, versão de schema, payload JSONB, metadados e horário. Triggers e
permissões no banco impedem `UPDATE`/`DELETE` dos eventos; correções de negócio
são novos eventos compensatórios.

## Privacidade e integridade

### Criptografia e crypto-shredding

`titularRef` em novos eventos de abertura é cifrado com AES-GCM usando uma chave
de dados por conta. A chave de dados é armazenada envelopada por uma chave mestra
externa. Destruir a chave da conta torna o valor cifrado irrecuperável, mantendo
os eventos financeiros.

**Limite importante:** eventos anteriores à ativação da criptografia continuam
em texto claro. O upcaster preserva a leitura dos eventos antigos, mas não os
cifra retroativamente. Portanto, o crypto-shredding não apaga nem anonimiza
automaticamente os registros legados, backups ou cópias exportadas. Um processo
de migração/retention precisa ser projetado e aprovado antes de alegar
conformidade retroativa.

Proteja e faça backup da chave mestra. Perder ou trocar essa chave sem um
procedimento de rotação de chaves torna as chaves de dados existentes
irrecuperáveis.

#### Crypto-shredding, auditoria e rotação

As operações administrativas exigem a autoridade `SCOPE_privacy:manage`, obtida
do scope OAuth2 `privacy:manage`, e registram operador, justificativa, resultado
e instante em `privacy_integrity_audit`, tabela append-only:

- `POST /admin/privacy/accounts/{id}/shred` remove a chave de dados da conta.
  O corpo exige `reason` e a confirmação literal `DESTROY_PERSONAL_DATA_KEY`.
  A remoção e o registro de auditoria são atômicos. Se a chave já não existir,
  a operação retorna `KEY_NOT_FOUND` e também audita o resultado.
- `POST /admin/integrity/verify` verifica a cadeia em snapshot consistente e
  audita resultado, quantidade validada e primeira posição inválida, se houver.
- `POST /admin/privacy/master-key/rotate` reprotege todas as chaves de dados
  com `BANCO_CRYPTO_ROTATION_NEW_MASTER_KEY`; exige confirmação literal
  `ROTATE_MASTER_KEY` e `BANCO_MAINTENANCE_ENABLED=true`.

Uma rotação exige janela de manutenção: pare gravações, reduza a aplicação a
uma única instância, habilite `BANCO_MAINTENANCE_ENABLED=true`, mantenha
`BANCO_CRYPTO_MASTER_KEY` com a chave atual e configure a chave destino somente
no secret manager como `BANCO_CRYPTO_ROTATION_NEW_MASTER_KEY`. Com um operador
autorizado, invoque o endpoint de rotação. Após sucesso, atualize
`BANCO_CRYPTO_MASTER_KEY` para a chave destino, remova a variável temporária,
desabilite a manutenção e só então restaure as instâncias e o tráfego. A
aplicação ativa a nova chave em memória depois do commit do re-envelopamento.
Não execute rotação simultânea em réplicas.

Antes de qualquer alteração, faça backup consistente do banco e preserve a
chave mestra correspondente no gerenciador de segredos. Teste a restauração.
Backups do banco anteriores à rotação ainda requerem a chave antiga; retenha-a
protegida até esses backups expirarem ou serem tratados. Crypto-shredding não
altera backups, réplicas ou exportações. Além disso, `userId` em metadados
históricos dos eventos permanece fora do payload cifrado; logo, apagar a chave
não remove todas as referências ao titular nem constitui, por si só, prova de
anonimização ou conformidade legal. Avalie esse limite antes de usar a operação.

### Upcasting

O `EventUpcasterRegistry` converte `ContaAberta` v1 para o formato atual v2 em
memória durante a leitura, sem reescrever a linha persistida. Versões futuras
precisam de upcasters explícitos para cada transição de schema.

### Cadeia de integridade

Eventos adicionados após a migration V5 recebem hashes SHA-256 encadeados por
stream. `JdbcEventIntegrityVerifier` verifica os eventos e o head da cadeia em
uma leitura consistente. A migration define o início da cadeia após o último
evento preexistente de cada stream: a cadeia não autentica retroativamente esse
histórico.

A cadeia detecta alterações/omissões nos registros protegidos, mas não é uma
assinatura externa e não impede um administrador com privilégios de reescrever
eventos e hashes conjuntamente. O endpoint operacional de verificação exige o
scope `privacy:manage`; mesmo assim, o resultado é evidência operacional e não
uma assinatura externa.

## Observabilidade

- Logs de console estruturados em JSON, com MDC contendo `correlationId`.
- O filtro valida `X-Correlation-Id` como UUID, cria um quando ausente, devolve-o
  na resposta e o propaga aos metadados dos eventos.
- Spring Boot Actuator expõe `health`, `info`, `metrics` e `prometheus`.
  Apenas `health` é público; os demais endpoints exigem autenticação.
- Configuração de Micrometer tracing e exportação OTLP.
- Componentes de projeção e publisher registram timers, contadores de eventos,
  lotes com falha do publisher, posição do evento que bloqueou o checkpoint e
  gauges de lag quando executados. Para a política de retry e os alertas Kafka,
  consulte [Publicação para Kafka](#publicação-para-kafka).

Restrinja endpoints de métricas e health pela rede/ingress. Configure um coletor
OTLP para receber traces. O sampling padrão de 10% é apenas uma configuração
inicial, não uma recomendação universal.

## Testes

Executar todos os testes unitários de todos os módulos:

```powershell
mvn test
```

Executar testes unitários e de integração com Testcontainers:

```powershell
mvn verify
```

`mvn verify` é o comando canônico do CI. O workflow do GitHub Actions executa-o
em cada pull request para `main`, em cada push para `main` e manualmente por
`workflow_dispatch`. O runner Ubuntu fornece Docker para os testes PostgreSQL
via Testcontainers; os relatórios Surefire e Failsafe são publicados como
artefato, inclusive quando a execução falha. Veja o
[workflow de CI](./.github/workflows/ci.yml).

O Maven precisa estar instalado localmente. Os testes `*IT` usam PostgreSQL
Testcontainers e falham quando Docker não está acessível — não são ignorados
silenciosamente. A suíte cobre regras de domínio, handlers, saga,
snapshots/time travel, serialização e upcasting, cifra AES-GCM, publicação e
mapping, filtro de correlation ID, migrations, projeções, concorrência e fluxo
HTTP da API. O teste de publisher simula falha e repetição com um publisher de
teste; o broker Kafka real permanece fora desta etapa.

Ainda não há benchmark automatizado ou teste de caos que demonstre as metas
documentadas de p95, lag, throughput ou rebuild de 10.000 eventos. Elas devem
ser medidas em ambiente representativo antes de serem declaradas atendidas.
O CI também não inicia um broker Kafka/Schema Registry real; a falha/repetição
é testada com um publisher controlado, e a compatibilidade efetiva dos subjects
deve ser validada no ambiente que hospeda o Registry.

## Limitações e próximos passos

- O projeto valida JWTs OIDC, mas não provisiona um provedor de identidade nem
  usuários; configure `OIDC_ISSUER_URI` e integre um provedor antes de executar.
- Contas antigas cujo `titularRef` não corresponde ao claim `sub` não ficam
  automaticamente vinculadas; é necessária uma migração de identidade aprovada.
- Os read models e endpoints de consulta estão na API principal, não em um
  serviço separado. A projeção é eventualmente consistente.
- Operações de crypto-shredding e rotação exigem procedimento de manutenção,
  backup restaurável e operador autorizado. O teste de rotação usa PostgreSQL
  Testcontainers; não foi executado em um ambiente sem Docker reconhecido pelo
  Testcontainers.
- Kafka/Schema Registry são opcionais e exigem infraestrutura externa.
- Garantia de entrega Kafka é, na prática, ao menos uma vez; consumidores precisam
  deduplicar usando `eventId`.
- O `titularRef` legado permanece em texto claro; planejar migração e retenção.
- Criar procedimento operacional de rotação/restauração da chave mestra.
- Executar e automatizar benchmarks de carga, latência, lag e rebuild.
- Revisar permissões e controles de acesso, endpoints de observabilidade,
  backups, disaster recovery e hardening antes de qualquer implantação real.

## Documentação complementar

- [Requisitos e guia de referência (`doc.txt`)](./doc.txt)
- [Documento de referência do projeto (PDF)](./Event%20Sourcing%20%2B%20CQRS%20em%20Java%20%E2%80%93%20Sistema%20Banc%C3%A1rio.pdf)
