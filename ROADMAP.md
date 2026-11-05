# Próximos passos

Este documento organiza melhorias posteriores às fases 1–9. As fases anteriores
continuam sendo entregas do projeto; os itens abaixo são trabalho futuro. Vamos
executá-los sequencialmente, atualizando o estado e os critérios de aceite a
cada entrega.

## Ordem de implementação

### 1. Autenticação e autorização da API — validação de integração pendente

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
escopadas por usuário e operação. A suíte unitária passou; falta executar os
testes HTTP com PostgreSQL via Testcontainers em um ambiente Docker funcional.

### 2. Completar o lado de consultas do CQRS — em implementação

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

**Implementação iniciada:** runner agendado e configurável, consultas
autenticadas de saldo/extrato e paginação limitada com ordenação estável. Testes
de controller foram adicionados; faltam validar as consultas SQL e execução
automática/reinício contra PostgreSQL em ambiente com Docker funcional.

### 3. Tornar os testes de integração reproduzíveis no CI — implementação pronta; primeira execução pendente

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
Ubuntu usa Docker para PostgreSQL Testcontainers; Surefire/Failsafe reports são
publicados mesmo em caso de falha. Não há serviço Kafka no pipeline: o teste de
publisher usa um double determinístico, enquanto o broker real fica para a
etapa 5.

**Validação pendente:** os testes unitários passaram localmente. A execução
local de `mvn -pl api-command -am verify` falhou porque Testcontainers não
reconheceu um ambiente Docker válido; `docker info` respondeu com sucesso, mas
isso não bastou para o runtime Java. A primeira execução no GitHub Actions ainda
precisa confirmar o ambiente hospedado e todos os testes de integração.

### 4. Operacionalizar privacidade e integridade — implementação com validação de integração pendente

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

**Validação pendente:** testes MVC cobrem autorização, confirmação, janela de
manutenção e chave destino; testes PostgreSQL cobrem shredding/auditoria,
rollback e rotação/reproteção. Os 64 testes unitários/MVC passam. A execução
de `mvn -pl api-command -am verify` termina com código 1 porque Testcontainers não
reconhece o endpoint Docker, mesmo com `docker info` bem-sucedido e `DOCKER_HOST`
definido explicitamente. A falha também ocorre no teste PostgreSQL isolado,
com cada named pipe do Docker Desktop e sem a configuração global
`~/.testcontainers.properties`; o CLI consegue acessar ambos os pipes. Assim,
os seis testes de integração ficam bloqueados antes de executar, sem evidência
de falha funcional da aplicação. Confirmar a suíte completa no CI hospedado ou
em um ambiente Linux com Docker funcional antes de concluir esta etapa.

### 5. Endurecer a publicação e o consumo Kafka — pendente

**Objetivo:** tornar explícitos e testáveis os cenários de repetição e falha.

**Escopo e aceite:**
- Definir política de retry, backoff, alertas e tratamento de eventos inválidos.
- Confirmar avanço seguro do checkpoint e documentar a entrega ao menos uma vez.
- Definir deduplicação pelo `eventId` no contrato de consumidores.
- Testar indisponibilidade, reinício, reenvio e compatibilidade de schema.

### 6. Padronizar o contrato de erros — pendente

**Objetivo:** não acoplar clientes a mensagens de exceção do domínio.

**Escopo e aceite:**
- Definir códigos de erro estáveis, separados das mensagens legíveis.
- Mapear erros de validação, regra de negócio, concorrência e infraestrutura
  para respostas HTTP consistentes.
- Testar status, código e formato das respostas públicas.

### 7. Medir desempenho e preparar operação — pendente

**Objetivo:** substituir metas não verificadas por evidências e requisitos
operacionais.

**Escopo e aceite:**
- Criar cenários de carga para comandos, projeções, publisher e rebuild.
- Medir e registrar p95/p99, throughput, lag e tempo de recuperação em ambiente
  documentado.
- Revisar alertas, retenção de logs, backups, restauração e disaster recovery.
- Não declarar metas atendidas sem resultados reproduzíveis.

## Como atualizar este roadmap

Ao concluir um item, registrar aqui o estado, decisões relevantes, evidências de
validação e limitações restantes. Não marcar como concluído somente por código
compilado: os critérios de aceite daquele item também precisam ser verificados.
