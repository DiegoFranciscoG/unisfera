// Prueba de carga del día de matrícula: 2.000 estudiantes ficticios ingresan y se matriculan en 10 minutos.
// Umbrales: p95 < 800 ms en las operaciones de matrícula y ningún error 5xx. Un 422 "sin cupos" (RN-05) es
// una respuesta correcta del sistema, no un error: con cupos limitados, muchos estudiantes deben recibirlo.
//
// Uso (desde la raíz del repo): docker compose --profile load run --rm k6
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PASSWORD = __ENV.DEMO_PASSWORD;
const USERS = new SharedArray('students', () =>
  open(__ENV.STUDENTS_CSV || '../../storage/seed/students.csv')
    .split('\n')
    .slice(1)
    .map((l) => l.trim())
    .filter(Boolean),
);
const TOTAL = Math.max(1, Math.min(Number(__ENV.USERS || 2000), USERS.length));

const enrolled = new Counter('matriculas_confirmadas');
const noSeats = new Counter('rechazos_sin_cupo_rn05');
const enrollTime = new Trend('tiempo_matricula', true);

export const options = {
  scenarios: {
    // 2.000 matrículas repartidas entre 250 usuarios virtuales concurrentes (cada uno atiende varios estudiantes).
    dia_de_matricula: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 250),
      iterations: TOTAL,
      maxDuration: '10m',
    },
  },
  thresholds: {
    'http_req_duration{op:offer}': ['p(95)<800'],
    'http_req_duration{op:preview}': ['p(95)<800'],
    'http_req_duration{op:enroll}': ['p(95)<800'],
    'http_req_failed{op:enroll}': ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (!PASSWORD) {
    throw new Error('Defina DEMO_PASSWORD');
  }
  if (USERS.length === 0) {
    throw new Error('No hay estudiantes en students.csv: arranque el backend con SEED_LOAD_TEST_STUDENTS > 0');
  }
}

export default function () {
  // Cada iteración es un estudiante distinto, que llega desde su propia conexión.
  const n = exec.scenario.iterationInTest;
  const username = USERS[n % USERS.length];
  const ip = `10.${(n >> 16) & 255}.${(n >> 8) & 255}.${n & 255}`;
  sleep(Math.random() * 20);
  const headers = { 'Content-Type': 'application/json', 'X-Forwarded-For': ip };

  const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username, password: PASSWORD }), { headers, tags: { op: 'login' } });
  if (!check(login, { 'ingreso 200': (r) => r.status === 200 && r.json('status') === 'AUTHENTICATED' })) {
    return;
  }
  const auth = { ...headers, Authorization: `Bearer ${login.json('accessToken')}` };

  const offer = http.get(`${BASE}/api/me/enrollment/offer`, { headers: auth, tags: { op: 'offer' } });
  check(offer, { 'oferta 200': (r) => r.status === 200 });
  if (offer.status !== 200) {
    return;
  }
  const subjects = offer.json('subjects') || [];
  const max = offer.json('maxSubjects') || 1;
  const sectionIds = subjects
    .filter((s) => s.subject.status === 'AVAILABLE' && s.sections.length > 0)
    .slice(0, max)
    .map((s) => {
      const open = s.sections.filter((x) => x.seatsLeft > 0);
      const pool = open.length ? open : s.sections;
      return pool[Math.floor(Math.random() * pool.length)].sectionId;
    });
  if (sectionIds.length === 0) {
    return;
  }

  sleep(1 + Math.random() * 3);
  const body = JSON.stringify({ sectionIds });
  const preview = http.post(`${BASE}/api/me/enrollment/preview`, body, { headers: auth, tags: { op: 'preview' } });
  check(preview, { 'vista previa 200': (r) => r.status === 200 });

  sleep(1 + Math.random() * 2);
  const started = Date.now();
  const enroll = http.post(`${BASE}/api/me/enrollment`, body, {
    headers: auth,
    tags: { op: 'enroll' },
    responseCallback: http.expectedStatuses(200, 422),
  });
  enrollTime.add(Date.now() - started);
  check(enroll, { 'matrícula respondida sin error del servidor': (r) => r.status === 200 || r.status === 422 });
  if (enroll.status === 200) {
    enrolled.add(1);
  } else if (enroll.status === 422 && String(enroll.json('code')) === 'RN-05') {
    noSeats.add(1);
  }
}
