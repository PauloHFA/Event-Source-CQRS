# Próximos passos

Este documento organiza melhorias posteriores às fases 1–9. As fases anteriores
continuam sendo entregas do projeto; os itens abaixo são trabalho futuro. Vamos
executá-los sequencialmente, atualizando o estado e os critérios de aceite a
cada entrega.

## Ordem de implementação

### 1. Autenticação e autorização da API — validada

**Objetivo:** deixar de tratar `X-User-Id` enviado pelo cliente como identidade.

**Escopo:**
- Validar tokens de acesso emitidos por um provedor de identidade confiável.
- Aplicar autorização nos comandos e consultas, sem confiar em identidade
  recebida em headers arbitrários.
- Definir como a identidade autenticada se relaciona com as contas.
- Manter configuração de desenvolvimento segura e documentada; não adicionar
  credenciais reais ao repositório.

**Critérios de aceite:**
- Requisições sem credencial válida são rejeitadas nas rotas protegidas.
- Testes cobrem token ausente/inválido e acesso autorizado/não autorizado.
- `X-User-Id` não consegue representar ou substituir a identidade autenticada.
- README descreve configuração e execução local.

**Decisão registrada:** usar validação OIDC/JWT independente de fornecedor e
autorizar pelo `sub` do token. Nenhum provedor está configurado ainda; `OIDC_ISSUER_URI`
será obrigatório na execução. O vínculo usa o `titularRef` do evento de abertura.

**Implementação:** validação JWT stateless, autorização por titular nas rotas de
conta/histórico/transferência, identidade obtida do `sub` e chaves idempotentes
escopadas por usuário e operação. Os testes HTTP com PostgreSQL via Testcontainers
passaram no CI hospedado (execução `41962134754`, commit `b48c5be`): os sete
testes de `ContaCommandApiIT` passaram, incluindo token ausente, identidade
derivada do token, rejeição de acesso a outra conta e idempotência.

### 2. Completar o lado de consultas do CQRS — implementação e validação concluídas

**Objetivo:** tornar os read models utilizáveis pela aplicação.

**Escopo:**
- Conectar a execução contínua das projeções, com configuração de lote e
  intervalo.
- Expor consultas de saldo e extrato, com paginação e validação de acesso.
- Documentar a consistência eventual e o comportamento de rebuild.
- Configurar o processamento periódico e um índice compatível com paginação
  estável do extrato.

**Critérios de aceite:**
- Projeções avançam automaticamente e retomam após reinício.
- Endpoints retornam os dados projetados e não leem o Event Store como atalho.
- Testes verificam paginação, retomada, idempotência e consistência eventual.

**Implementação:** runner agendado e configurável, consultas
autenticadas de saldo/extrato e paginação limitada com ordenação estável. Testes
de controller e PostgreSQL foram adicionados. No CI hospedado (execução
`41962134754`, commit `b48c5be`), `ProjectionIT` passou com seis testes,
cobrindo consultas SQL, paginação estável, idempotência, reconstrução e
concorrência. Na execução `41980601989` do GitHub Actions, `ProjectionIT` passou
com sete testes, incluindo retomada por runner recriado a partir do checkpoint
persistido; `ContaCommandApiIT` passou com oito testes, incluindo convergência
do read model pelo agendador sem acionamento manual.

### 3. Tornar os testes de integração reproduzíveis no CI — concluída

**Objetivo:** validar o comportamento com PostgreSQL e, quando aplicável, Kafka
em ambiente automatizado.

**Escopo e aceite:**
- Configurar pipeline para executar testes unitários e de integração com
  dependências efêmeras.
- Cobrir migrations, concorrência, projeções, saga e reinício/reprocessamento do
  publisher.
- Publicar o resultado dos testes e falhar o pipeline em caso de regressão.

**Implementação:** `.github/workflows/ci.yml` executa `mvn verify` com Java 21
em pull requests para `main`, pushes para `main` e execução manual. O runner
Ubuntu usa Docker para PostgreSQL, Kafka e Schema Registry via Testcontainers;
Surefire/Failsafe reports são publicados mesmo em caso de falha.

**Validação:** a execução `41962134754` do GitHub Actions concluiu `mvn verify`
com sucesso no Java 21. Passaram 64 testes unitários, 23 testes de integração
da infraestrutura e 7 testes de integração da API, todos sem falhas ou skips.
O ambiente hospedado executou os testes PostgreSQL com Testcontainers; os
relatórios Surefire/Failsafe foram publicados.

### 4. Operacionalizar privacidade e integridade — validada

**Objetivo:** transformar os serviços internos existentes em procedimentos
controlados e auditáveis.

**Escopo e aceite:**
- Definir rotação, backup e restauração da chave mestra, sem perda de acesso aos
  dados cifrados.
- Definir autorização, confirmação, auditoria e verificação posterior para
  crypto-shredding.
- Disponibilizar verificação da cadeia de hashes como operação restrita, com
  resultado e falha claramente reportados.
- Documentar limites para dados antigos, backups e cópias exportadas.

**Implementação:** endpoints administrativos protegidos pelo scope OAuth2
`privacy:manage` para crypto-shredding, rotação de chave mestra e verificação da
cadeia. Shredding e auditoria ocorrem na mesma transação; a verificação roda em
snapshot repeatable-read e registra o resultado. A tabela de auditoria é
append-only. A rotação reprotege chaves de dados por conta com a chave destino externa,
exige modo de manutenção e confirmação explícita, e deve ser executada com
apenas uma instância e sem gravações.

**Limitações documentadas:** a rotação não reescreve backups; a chave antiga
precisa ser mantida enquanto existirem backups que a exijam. Crypto-shredding
não remove referências `userId` já gravadas em metadados históricos, nem apaga
backups ou exportações, então não equivale a anonimização completa.

**Validação:** concluída na execução `41962134754` do GitHub Actions, commit
`b48c5be`. Os três testes PostgreSQL de `PersonalDataKeyOperationsIT` passaram,
cobrindo crypto-shredding com auditoria, rejeição de solicitações inválidas sem
perda de acesso aos dados e rotação/reproteção legível com a nova chave. Os
testes MVC passaram, cobrindo autorização, confirmação e condições
operacionais. `JdbcEventStoreIT` também confirmou a verificação de cadeia válida
e a detecção de adulteração.

### 5. Endurecer a publicação e o consumo Kafka — implementação e validação CI concluídas

**Objetivo:** tornar explícitos e testáveis os cenários de repetição e falha.

**Escopo e aceite:**
- Definir política de retry, backoff, alertas e tratamento de eventos inválidos.
- Confirmar avanço seguro do checkpoint e documentar a entrega ao menos uma vez.
- Definir deduplicação pelo `eventId` no contrato de consumidores.
- Testar indisponibilidade, reinício, reenvio e compatibilidade de schema.

**Implementação:** retries Kafka com `acks=all`, idempotência e limite de
entrega de 120 segundos; falha de lote mantém o checkpoint e a próxima tentativa
ocorre no ciclo agendado seguinte. O `eventId` estável no envelope/header é a
chave de deduplicação documentada para consumers; eventos sem mapeamento falham
sem descarte e deixam o checkpoint bloqueado. Métricas expõem lotes falhos,
posição que bloqueou o processamento e lag, com orientação de alertas no README.

**Testes adicionados:** falha transitória, checkpoint não avançado, duplicação
possível do primeiro evento após retry, retomada pelo checkpoint persistido
após recriar o runner, política de retry e resposta HTTP 409 do Schema Registry.
Além dos testes PostgreSQL anteriores, `KafkaSchemaRegistryIT` inicia Kafka
Confluent 7.6.1 e Schema Registry 7.6.1 via Testcontainers. O teste publica e
consome o wire format com schema ID e headers reais, e registra uma versão
incompatível sob `BACKWARD_TRANSITIVE` para confirmar a rejeição real do Registry.
`mvn -pl infrastructure -am test` compila os testes e passa os testes unitários;
Docker local está pausado, então os testes com containers foram executados na
CI hospedada (`mvn verify`, PR #7), com sucesso.

**Validação concluída:** a CI hospedada executou `mvn verify`, incluindo os
testes com Kafka/Schema Registry reais, com sucesso (PR #7). A recuperação de
indisponibilidade e o reenvio continuam cobertos pelo teste determinístico do
runner/checkpoint; o publisher Kafka real usa os retries e limite de entrega
configurados, mas não há ainda um teste de caos que derrube e restaure um broker
durante o envio.

### 6. Padronizar o contrato de erros — implementação e validação CI concluídas

**Objetivo:** não acoplar clientes a mensagens de exceção do domínio.

**Escopo e aceite:**
- Definir códigos de erro estáveis, separados das mensagens legíveis.
- Mapear erros de validação, regra de negócio, concorrência e infraestrutura
  para respostas HTTP consistentes.
- Testar status, código e formato das respostas públicas.

**Implementação:** respostas `ProblemDetail` incluem `code` estável para
validação, regras de domínio, conflito de concorrência e persistência. Falhas de
banco são registradas no servidor e retornam detalhe genérico ao cliente. Testes
unitários verificam códigos/status e a integração verifica o formato HTTP real.
Na execução `41980601989` do GitHub Actions, os testes unitários e de API
passaram sem falhas ou skips.

### 7. Medir desempenho e preparar operação — implementação concluída

**Objetivo:** substituir metas não verificadas por evidências e requisitos operacionais mensuráveis (RNF-03 a RNF-06).

**Implementação e validação:**
- `ContaReconstrucaoBenchmarkTest`: comprovação de RNF-06 com 10.000 eventos. Tempo medido: média de 0,026 ms e máximo de 0,235 ms (meta: < 50 ms).
- `PerformanceBenchmarkIT`: benchmark end-to-end com PostgreSQL via Testcontainers inserindo 10.000 eventos, validando reidratação com banco real (< 50 ms) e concorrência massiva de escrita (RNF-03 e RNF-05).
- `load-tests/k6-load-test.js`: script k6 de carga com ramp-up, estresse de 200 VUs e thresholds estritos de p95 < 100 ms (RNF-03) e throughput >= 1.000 req/s (RNF-05).

### 8. Limite diário de movimentação (RN-07), extrato por período (RF-07) e recuperação de sagas — concluída

**Objetivo:** suprir os requisitos de negócio, funcionais e operacionais pendentes da especificação.

**Implementação e validação:**
- [RN-07] Limite diário configurável por conta em `Conta`, acumulando débitos por dia civil de forma determinística, rejeitando excedentes com `LimiteDiarioExcedidoException` (`422 LIMITE_DIARIO_EXCEDIDO`).
- [RN-07] Endpoint `PUT /contas/{id}/limite-diario`, migração `V9__limite_diario.sql` e projeção em `ContaProjector`.
- [RF-07] Parâmetros `de` e `ate` (`Instant`) adicionados a `GET /contas/{id}/extrato` e ao `ContaReadModelQueries`.
- [RF-07] Endpoint de detalhes da conta em `GET /contas/{id}`.
- [Operação] Agendador automático de recuperação de transferências travadas `TransferenciaRecoveryConfiguration` com métricas e logs estruturados.

### 9. Separação física de API Query (Read Side CQRS) — concluída

**Objetivo:** isolar completamente leitura e escrita em serviços HTTP autônomos (CQRS físico estrito), conforme Seção 4.3 da especificação.

**Implementação e validação:**
- Módulo Maven independente `api-query` criado, rodando por padrão na porta `8081`.
- Endpoints de leitura transferidos exclusivamente para `api-query`:
  - `GET /contas/{id}` (detalhes da conta no modelo de leitura).
  - `GET /contas/{id}/saldo` (saldo atual projetado).
  - `GET /contas/{id}/extrato?pagina=0&tamanho=20&de=...&ate=...` (extrato com paginação e filtro por período).
- Segurança stateless via JWT/OIDC e autorização por titular (`sub`), além de filtro de correlação `X-Correlation-Id`.
- Módulo `api-command` (porta `8080`) mantido estritamente para comandos de escrita e histórico / time travel via Event Store.
- Testes unitários com MockMvc e Spring Security validados em `api-query` (`ContaQueryControllerTest`).

## Como atualizar este roadmap

Ao concluir um item, registrar aqui o estado, decisões relevantes, evidências de
validação e limitações restantes. Não marcar como concluído somente por código
compilado: os critérios de aceite daquele item também precisam ser verificados.
