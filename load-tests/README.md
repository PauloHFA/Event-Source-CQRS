# Testes de Carga e Benchmark de Desempenho (RNF-03 a RNF-06)

Este diretório contém os scripts e cenários de teste de carga para comprovação das metas não funcionais do sistema:

| Meta | Descrição | Alvo |
|---|---|---|
| **RNF-03** | Latência p95 de comando (incluindo reidratação com snapshot) | `< 100 ms` |
| **RNF-04** | Atraso das projeções (*event lag*) p95 | `< 1 s` |
| **RNF-05** | Throughput de escrita em ambiente de teste | `≥ 1.000 eventos/s` |
| **RNF-06** | Reconstrução de conta com 10.000 eventos usando snapshot | `< 50 ms` |

---

## 1. Benchmarks Automatizados em Java (JUnit / Testcontainers)

Os testes automatizados de benchmark executam sem necessidade de ferramentas externas:

### A. Reconstrução com 10.000 eventos (RNF-06):
```powershell
mvn -pl domain test -Dtest=ContaReconstrucaoBenchmarkTest
```
* **Resultado medido:** Média de **0,026 ms** e máximo de **0,235 ms** para reidratação de 10.000 eventos com snapshot (meta: `< 50 ms`).

### B. Benchmark com PostgreSQL Real (RNF-03, RNF-05 e RNF-06):
```powershell
mvn -pl infrastructure -am test -Dtest=PerformanceBenchmarkIT
```
* Insere 10.000 eventos persistidos no banco de dados e snapshot na versão 9.900.
* Mede a reidratação end-to-end com PostgreSQL via JDBC.
* Simula 10 threads concorrentes de append contínuo medindo throughput e latência p95/p99.

---

## 2. Testes de Carga HTTP End-to-End com k6

Para executar o teste de estresse contra a API HTTP em execução:

### Pré-requisitos
* API em execução (`mvn -pl api-command -am spring-boot:run` ou container).
* [k6](https://k6.io/) instalado localmente ou Docker.

### Executando com k6 CLI
```powershell
k6 run --env API_URL=http://localhost:8080 --env JWT_TOKEN=<seu-token-jwt> load-tests/k6-load-test.js
```

### Executando via Docker
```powershell
docker run --rm -i --network=host `
  -e API_URL=http://localhost:8080 `
  -e JWT_TOKEN=<seu-token-jwt> `
  grafana/k6 run - < load-tests/k6-load-test.js
```

### Critérios de Aceite no Relatório k6
Ao final do teste, o k6 verifica automaticamente os limiares (*thresholds*):
* `http_req_duration`: percentil 95 abaixo de 100 ms (`p(95)<100`).
* `http_req_failed`: taxa de erros de sistema inferior a 1% (`rate<0.01`).
* `http_reqs`: taxa de requisições sustentada atingindo o patamar alvo.
