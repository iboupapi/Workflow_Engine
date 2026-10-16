/*
 * Étape 4 — Benchmark de charge du moteur de workflow (k6).
 *
 *  Scénario A « barrage » : 50 VUs frappent SIMULTANÉMENT la même instance ACTIVE
 *    avec POST /transition. Attendu : exactement 1x HTTP 200, 49x HTTP 409, 0x 500,
 *    une seule ligne d'historique pour la transition (preuve SQL hors script).
 *
 *  Scénario B « avalanche » : 50 agents en flux continu pendant 30 s ; chacun crée
 *    un dossier (POST /start) puis le fait avancer (POST /transition avec condition
 *    SpEL). Attendu : 100 % de succès, mesure p95 et RPS.
 *
 * Usage :
 *   k6 run --env SCENARIO=A --env BASE_URL=http://localhost:8081 --env AGENT_JWT=<jeton> k6/benchmark.js
 *   k6 run --env SCENARIO=B --env BASE_URL=http://localhost:8081 --env AGENT_JWT=<jeton> k6/benchmark.js
 *
 *  Depuis le durcissement, tout /api/** exige un JETON ; l'action n'est permise qu'aux
 *  porteurs du rôle de l'étape (AGENT_JWT doit porter le rôle du premier maillon).
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const KEY = __ENV.DEFINITION_KEY || 'GIRAFE_ACTES';
const WHICH = (__ENV.SCENARIO || 'ALL').toUpperCase();
const AGENT_JWT = __ENV.AGENT_JWT || '';

const barrage200 = new Counter('barrage_res_200');
const barrage409 = new Counter('barrage_res_409');
const barrageOther = new Counter('barrage_res_other');

const JSON_HEADERS = {
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${AGENT_JWT}` },
};
const tagged = (name) => ({ headers: JSON_HEADERS.headers, tags: { name } });

export const options = { scenarios: {}, thresholds: {} };

if (WHICH === 'A' || WHICH === 'ALL') {
  options.scenarios.barrage = {
    executor: 'per-vu-iterations',
    exec: 'barrage',
    vus: 50,
    iterations: 1,
    maxDuration: '3m',
  };
  // Tous les tirs doivent répondre 200 ou 409 : aucun 500, aucune erreur réseau.
  options.thresholds.checks = ['rate==1.0'];
}

if (WHICH === 'B' || WHICH === 'ALL') {
  options.scenarios.avalanche = {
    executor: 'constant-vus',
    exec: 'avalanche',
    vus: 50,
    duration: '30s',
    gracefulStop: '30s',
    ...(WHICH === 'ALL' ? { startTime: '150s' } : {}),
  };
  options.thresholds.checks = ['rate==1.0'];
  options.thresholds.http_req_failed = ['rate==0'];
  // Report détaillé p95 par endpoint (bornes larges : mesure, pas sanction).
  options.thresholds['http_req_duration{name:avalanche-start}'] = ['p(95)<15000'];
  options.thresholds['http_req_duration{name:avalanche-transition}'] = ['p(95)<15000'];
}

export function setup() {
  if (WHICH === 'B') {
    return {};
  }
  const res = http.post(
    `${BASE}/api/workflows/${KEY}/start`,
    JSON.stringify({
      initiatorId: 'k6-barrage-setup',
      variables: { circuit: 'COURT', natureActe: 'ARRETE' },
    }),
    JSON_HEADERS,
  );
  if (res.status !== 201) {
    throw new Error(`setup: POST /start -> ${res.status} ${res.body}`);
  }
  const instanceId = res.json().id;
  console.log(`barrage: instance cible = ${instanceId}`);
  return { instanceId };
}

/** Scénario A — 50 validations concurrentes du MÊME dossier. */
export function barrage(data) {
  const res = http.post(
    `${BASE}/api/instances/${data.instanceId}/transition`,
    JSON.stringify({ action: 'VALIDER', actorId: `k6-vu-${__VU}`, variables: {} }),
    tagged('barrage-transition'),
  );
  if (res.status === 200) {
    barrage200.add(1);
  } else if (res.status === 409) {
    barrage409.add(1);
  } else {
    barrageOther.add(1);
  }
  check(res, {
    'barrage: 200 ou 409 uniquement (zero 500)': (r) => r.status === 200 || r.status === 409,
  });
}

/** Scénario B — chaque agent crée un dossier puis le fait avancer (condition SpEL). */
export function avalanche() {
  const startRes = http.post(
    `${BASE}/api/workflows/${KEY}/start`,
    JSON.stringify({
      initiatorId: `k6-agent-${__VU}`,
      variables: { circuit: 'COURT', natureActe: 'ARRETE', tir: `a-${__VU}-${__ITER}` },
    }),
    tagged('avalanche-start'),
  );
  if (!check(startRes, { 'avalanche: start -> 201': (r) => r.status === 201 })) {
    return;
  }
  const trRes = http.post(
    `${BASE}/api/instances/${startRes.json().id}/transition`,
    JSON.stringify({
      action: 'VALIDER',
      actorId: `k6-agent-${__VU}`,
      variables: { numeroOfficiel: `K6-${__VU}-${__ITER}` },
    }),
    tagged('avalanche-transition'),
  );
  check(trRes, { 'avalanche: transition -> 200': (r) => r.status === 200 });
}
