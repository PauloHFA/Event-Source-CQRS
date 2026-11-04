# Event Sourcing + CQRS — Sistema Bancário

Implementação didática de um núcleo bancário em Java, construído para demonstrar
Event Sourcing e CQRS sem um framework de Event Store. O histórico de domínio é
a fonte da verdade: comandos produzem eventos append-only, e o estado de uma
conta pode ser reconstituído a partir desses eventos.

> **Estado do projeto:** as fases 1 a 9 foram desenvolvidas. O sistema é uma
> referência de arquitetura e não está pronto para operar como banco real. Não
> inclui autenticação/autorização, integração com PIX/SPB, KYC, operação
> multi-região, nem comprovação de metas de desempenho de produção.

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
| 4 | Modelos de leitura para saldo e extrato, projeção idempotente e checkpoints reconstruíveis. |
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
  transferência e reconstrução histórica.

O read model de saldo e extrato está implementado na infraestrutura. Este
repositório não contém um módulo HTTP separado de Query API; o endpoint histórico
é reconstruído diretamente do Event Store.

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
produtor. Uma falha pode causar reenvio de mensagens do lote, portanto os
consumidores devem ser idempotentes.

## Tecnologias e pré-requisitos

- JDK **21** (definido em `pom.xml`).
- Maven 3.9 ou compatível.
- PostgreSQL 16.
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
permissões restritas no Event Store, mas não configuram automaticamente a
aplicação para conectar como esse papel. O exemplo local usa `banco`, que é o
usuário proprietário criado pelo container. Em produção, separe o usuário que
aplica migrations do usuário runtime e configure autenticação/menor privilégio.

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

## Configuração

| Variável / propriedade | Padrão | Descrição |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/banco` | JDBC URL do PostgreSQL. |
| `DB_USER` | `banco` | Usuário do banco. |
| `DB_PASSWORD` | `banco` | Senha do banco; substitua fora do desenvolvimento local. |
| `BANCO_CRYPTO_MASTER_KEY` | — | Chave Base64 AES-256 obrigatória para iniciar o serviço. |
| `banco.snapshot.interval` | `100` | Intervalo de versões para criação de snapshot. |
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

## API HTTP

Os endpoints de comando estão sob `/contas`. Valores monetários são enviados
como decimais JSON, por exemplo `25.50`. `Idempotency-Key` evita repetir efeitos
de uma mesma operação. Nos endpoints de conta, `X-Correlation-Id` aceita um UUID;
se omitido, a API gera um. A transferência usa o identificador da transferência
como correlation ID. `X-User-Id` é um identificador informativo, não substitui
autenticação.

### Abrir conta

```http
POST /contas
Content-Type: application/json
Idempotency-Key: abrir-conta-001
X-Correlation-Id: 5e1d97b0-1c3e-4d3e-8ee8-2e6e3aa2f1e0

{
  "titularRef": "referencia-interna",
  "limite": 500.00
}
```

Retorna `201 Created` com o identificador da conta e a versão resultante.
`titularRef` deve ser uma referência interna, não nome ou CPF.

### Depositar

```http
POST /contas/{id}/depositos
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

Estas operações aceitam `Idempotency-Key`, `X-Correlation-Id` e `X-User-Id`
como apropriado.

### Transferir e consultar transferência

```http
POST /transferencias
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
eventos e hashes conjuntamente. O verificador existe como componente interno;
não há endpoint público para executá-lo.

## Observabilidade

- Logs de console estruturados em JSON, com MDC contendo `correlationId`.
- O filtro valida `X-Correlation-Id` como UUID, cria um quando ausente, devolve-o
  na resposta e o propaga aos metadados dos eventos.
- Spring Boot Actuator expõe `health`, `info`, `metrics` e `prometheus`.
- Configuração de Micrometer tracing e exportação OTLP.
- Componentes de projeção e publisher registram timers, contadores de eventos,
  lotes com falha do publisher e gauges de lag quando executados.

Restrinja endpoints de métricas e health pela rede/ingress. Configure um coletor
OTLP para receber traces. O sampling padrão de 10% é apenas uma configuração
inicial, não uma recomendação universal.

## Testes

Executar os testes unitários dos módulos de infraestrutura e dependências:

```powershell
mvn -pl infrastructure -am test
```

Executar os testes unitários e compilar/testar a API e módulos dependentes:

```powershell
mvn -pl api-command -am test
```

Os testes de integração usam Testcontainers e requerem Docker:

```powershell
mvn -pl api-command -am verify
```

Os testes cobrem regras de domínio, handlers, saga, snapshots/time travel,
serialização e upcasting, cifra AES-GCM, publicação e mapping, filtro de
correlation ID, migrations e acesso PostgreSQL, projeções e concorrência.
Sem Docker, os testes `*IT` não conseguem iniciar os containers.

Ainda não há benchmark automatizado ou teste de caos que demonstre as metas
documentadas de p95, lag, throughput ou rebuild de 10.000 eventos. Elas devem
ser medidas em ambiente representativo antes de serem declaradas atendidas.

## Limitações e próximos passos

- Não há autenticação/autorização real; `X-User-Id` não é uma identidade
  autenticada.
- O read model existe, mas falta um serviço/API de consulta separado e o
  agendamento/wiring de execução contínua das projeções na aplicação.
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
