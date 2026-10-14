// Genera las capturas del README (docs/img) recorriendo la app real con cuentas demo ficticias.
// Uso (desde frontend/): E2E_PASSWORD=... E2E_STUDENT=... E2E_TEACHER=... node scripts/capturas.mjs
// Usa el Chrome o Edge instalado en el equipo (canal del sistema): no descarga navegadores.
import { chromium } from '@playwright/test';
import { createHmac } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:8081';
const PASSWORD = process.env.E2E_PASSWORD;
const STUDENT = process.env.E2E_STUDENT;
const TEACHER = process.env.E2E_TEACHER;
const OUT = resolve(process.cwd(), '..', 'docs', 'img');
if (!PASSWORD || !STUDENT || !TEACHER) {
  console.error('Defina E2E_PASSWORD, E2E_STUDENT y E2E_TEACHER');
  process.exit(1);
}
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL ?? 'chrome' });

async function session(username, colorScheme = 'light') {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, colorScheme, locale: 'es-EC' });
  const page = await context.newPage();
  await page.goto(`${BASE}/ingresar`);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: /Ingresar/ }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/ingresar'));
  return { context, page };
}

async function shot(page, path, name, settle = 2500) {
  await page.goto(`${BASE}${path}`);
  await page.waitForTimeout(settle);
  await page.screenshot({ path: resolve(OUT, `${name}.png`) });
  console.log(`docs/img/${name}.png`);
}

// Ingreso (sin sesión)
{
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'es-EC' });
  const page = await context.newPage();
  await page.goto(`${BASE}/ingresar`);
  // LOPDP: una cédula aleatoria con dígito válido podría coincidir con la de una persona real; se publica incompleta.
  await page.locator('#username').fill(STUDENT.slice(0, 6));
  await page.waitForTimeout(1500);
  await page.screenshot({ path: resolve(OUT, 'ingreso.png') });
  console.log('docs/img/ingreso.png');
  await context.close();
}

// Estudiante
{
  const { context, page } = await session(STUDENT);
  await shot(page, '/estudiante', 'inicio-estudiante');
  await shot(page, '/estudiante/malla', 'malla', 3500);
  await shot(page, '/estudiante/notas', 'notas');
  await shot(page, '/estudiante/cuenta', 'cuenta');
  await context.close();
}

// Estudiante en modo oscuro: malla en órbitas
{
  const { context, page } = await session(STUDENT, 'dark');
  await shot(page, '/estudiante/malla', 'malla-oscuro', 3500);
  await context.close();
}

// Estudiante en teléfono (390×844): la misma app se adapta sin versión aparte
{
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, colorScheme: 'light', locale: 'es-EC' });
  const page = await context.newPage();
  await page.goto(`${BASE}/ingresar`);
  await page.locator('#username').fill(STUDENT);
  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: /Ingresar/ }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/ingresar'));
  await shot(page, '/estudiante', 'movil-inicio');
  await shot(page, '/estudiante/notas', 'movil-notas');
  await page.getByRole('button', { name: 'Abrir menú' }).click();
  await page.waitForTimeout(800);
  await page.screenshot({ path: resolve(OUT, 'movil-menu.png') });
  console.log('docs/img/movil-menu.png');
  await context.close();
}

// Docente
{
  const { context, page } = await session(TEACHER);
  await page.goto(`${BASE}/docente`);
  await page.waitForTimeout(1500);
  await page.getByText('Abrir paralelo').first().click();
  await page.waitForTimeout(2500);
  await page.screenshot({ path: resolve(OUT, 'docente-planilla.png') });
  console.log('docs/img/docente-planilla.png');
  await context.close();
}

// Matrícula: un aspirante admitido de la prueba de carga elige sus paralelos
if (process.env.E2E_NEW_STUDENT) {
  const { context, page } = await session(process.env.E2E_NEW_STUDENT);
  await page.goto(`${BASE}/estudiante/matricula`);
  await page.waitForTimeout(2500);
  const cards = page.locator('article.subject');
  for (let i = 0; i < Math.min(3, await cards.count()); i++) {
    await cards.nth(i).locator('button.section:not([disabled])').nth(i % 2).click();
    await page.waitForTimeout(300);
  }
  await page.waitForTimeout(1200);
  await page.screenshot({ path: resolve(OUT, 'matricula.png') });
  console.log('docs/img/matricula.png');
  await page.getByRole('button', { name: /Continuar/ }).click();
  await page.waitForTimeout(1500);
  await page.screenshot({ path: resolve(OUT, 'matricula-semana.png') });
  console.log('docs/img/matricula-semana.png');
  await context.close();
}

// Administración: la cuenta exige segundo factor; se enrola como lo haría una app de autenticación.
if (process.env.E2E_ADMIN) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light', locale: 'es-EC' });
  const page = await context.newPage();
  await page.goto(`${BASE}/ingresar`);
  await page.locator('#username').fill(process.env.E2E_ADMIN);
  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: /Ingresar/ }).click();
  await page.locator('#code').waitFor();
  let secret = (await page.locator('p.mono').allTextContents()).map((t) => t.trim()).find((t) => /^[A-Z2-7]{16,}$/.test(t));
  if (!secret && process.env.E2E_TOTP_FILE && existsSync(process.env.E2E_TOTP_FILE)) {
    secret = readFileSync(process.env.E2E_TOTP_FILE, 'utf8').trim();
  }
  if (secret) {
    await page.locator('#code').fill(totp(secret));
    await page.waitForURL((u) => u.pathname.startsWith('/admin'));
    await shot(page, '/admin', 'tablero', 3500);
    await shot(page, '/admin/parametros', 'parametros');
  } else {
    console.log('La cuenta administrativa ya tiene segundo factor enrolado: se omite su captura.');
  }
  await context.close();
}

await Promise.race([browser.close().catch(() => undefined), new Promise((r) => setTimeout(r, 5000))]);

function totp(base32) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bits = [...base32.replace(/=+$/, '')].map((c) => alphabet.indexOf(c).toString(2).padStart(5, '0')).join('');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[19] & 15;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}
