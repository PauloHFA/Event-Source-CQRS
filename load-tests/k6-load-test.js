import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// Metas não funcionais do projeto:
// RNF-03: Latência p95 < 100 ms
// RNF-05: Throughput >= 1.000 requisições/s
export const options = {
  stages: [
    { duration: '10s', target: 50 },  // Ramp-up
    { duration: '30s', target: 200 }, // Carga de estresse (alta concorrência)
    { duration: '10s', target: 0 },   // Ramp-down
  ],
  thresholds: {
    // RNF-03: Latência p95 de comandos HTTP < 100 ms
    http_req_duration: ['p(95)<100'],
    // Taxa de erros de sistema < 1%
    http_req_failed: ['rate<0.01'],
    // RNF-05: Throughput alvo >= 1000 req/s
    http_reqs: ['rate>=1000'],
  },
};

const BASE_URL = __ENV.API_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.JWT_TOKEN || 'test-token';

export default function () {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${AUTH_TOKEN}`,
    'X-Correlation-Id': uuidv4(),
    'Idempotency-Key': uuidv4(),
  };

  // 1. Abrir Conta
  const openPayload = JSON.stringify({
    limite: 1000.0,
  });

  const openRes = http.post(`${BASE_URL}/contas`, openPayload, { headers });
  const openOk = check(openRes, {
    'conta criada (201)': (r) => r.status === 201,
  });

  if (!openOk) return;

  const contaId = JSON.parse(openRes.body).contaId;

  // 2. Depósito
  const depPayload = JSON.stringify({
    valor: 150.0,
    origem: 'K6_LOAD_TEST',
  });

  const depRes = http.post(`${BASE_URL}/contas/${contaId}/depositos`, depPayload, {
    headers: Object.assign({}, headers, { 'Idempotency-Key': uuidv4() }),
  });

  check(depRes, {
    'depósito aceito (202)': (r) => r.status === 202,
  });

  // 3. Saque dentro do limite
  const saquePayload = JSON.stringify({
    valor: 50.0,
  });

  const saqueRes = http.post(`${BASE_URL}/contas/${contaId}/saques`, saquePayload, {
    headers: Object.assign({}, headers, { 'Idempotency-Key': uuidv4() }),
  });

  check(saqueRes, {
    'saque aceito (202)': (r) => r.status === 202,
  });

  // 4. Consulta de Saldo Projetado (Leitura CQRS)
  const saldoRes = http.get(`${BASE_URL}/contas/${contaId}/saldo`, { headers });
  check(saldoRes, {
    'saldo consultado (200 ou 503)': (r) => r.status === 200 || r.status === 503,
  });

  // 5. Consulta de Extrato Paginado por Período (RF-07)
  const agora = new Date().toISOString();
  const extratoRes = http.get(`${BASE_URL}/contas/${contaId}/extrato?pagina=0&tamanho=10&ate=${agora}`, { headers });
  check(extratoRes, {
    'extrato consultado (200 ou 503)': (r) => r.status === 200 || r.status === 503,
  });

  sleep(0.05);
}
