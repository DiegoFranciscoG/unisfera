// Auditoría de adaptabilidad: recorre cada pantalla de los tres portales en anchos de teléfono,
// tableta y escritorio, y reporta cualquier elemento que se salga del ancho visible (scroll horizontal).
// Uso (desde frontend/): E2E_PASSWORD=... E2E_STUDENT=... E2E_TEACHER=... [E2E_ADMIN=... E2E_TOTP_FILE=...] node scripts/auditar-responsive.mjs
import { chromium } from '@playwright/test';
import { createHmac } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:8081';
const PASSWORD = process.env.E2E_PASSWORD;
const WIDTHS = [360, 768, 1280];
const PORTALS = [
  { user: process.env.E2E_STUDENT, routes: ['/estudiante', '/estudiante/malla', '/estudiante/matricula', '/estudiante/horario', '/estudiante/notas',
    '/estudiante/kardex', '/estudiante/cuenta', '/estudiante/becas', '/estudiante/solicitudes', '/estudiante/ficha', '/ayuda', '/ayuda/como-me-matriculo'] },
  { user: process.env.E2E_TEACHER, routes: ['/docente', '/docente/actas'] },
  { user: process.env.E2E_ADMIN, mfa: true, routes: ['/admin', '/admin/estudiantes', '/admin/oferta', '/admin/parametros', '/admin/finanzas', '/admin/bienestar',
    '/admin/reportes', '/admin/solicitudes', '/admin/auditoria'] },
];

const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL ?? 'chrome' });
let problems = 0;

for (const portal of PORTALS.filter((p) => p.user)) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 860 }, locale: 'es-EC' });
  const page = await context.newPage();
  await login(page, portal);
  for (const route of portal.routes) {
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 860 });
      await page.goto(`${BASE}${route}`);
      await page.waitForTimeout(1800);
      const report = await page.evaluate(() => {
        const vw = document.documentElement.clientWidth;
        const offenders = [];
        for (const el of document.querySelectorAll('body *')) {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.right <= vw + 1) continue;
          // Contenido dentro de un contenedor con desplazamiento propio (tablas, horario) es intencional.
          let p = el.parentElement;
          let scrolls = false;
          while (p && p !== document.body) {
            const ox = getComputedStyle(p).overflowX;
            if (ox === 'auto' || ox === 'scroll' || ox === 'hidden' || ox === 'clip') { scrolls = true; break; }
            p = p.parentElement;
          }
          if (!scrolls && getComputedStyle(el).position !== 'fixed') {
            offenders.push(`${el.tagName.toLowerCase()}.${String(el.className).split(' ').slice(0, 3).join('.')} → ${Math.round(r.right)}px`);
          }
        }
        return { pageScroll: document.documentElement.scrollWidth > vw + 1, offenders: offenders.slice(0, 5) };
      });
      if (report.pageScroll || report.offenders.length) {
        problems++;
        console.log(`✗ ${route} @${width}px ${report.pageScroll ? '(scroll horizontal)' : ''}\n   ${report.offenders.join('\n   ')}`);
      } else {
        console.log(`✓ ${route} @${width}px`);
      }
    }
  }
  await closeQuietly(context);
}
await closeQuietly(browser);
console.log(problems ? `\n${problems} pantallas con desbordes` : '\nSin desbordes en ningún ancho');
process.exit(problems ? 1 : 0);

/** Cerrar el navegador del sistema a veces no responde en Windows: no debe bloquear el resultado. */
async function closeQuietly(target) {
  await Promise.race([target.close().catch(() => undefined), new Promise((r) => setTimeout(r, 5000))]);
}

async function login(page, portal) {
  await page.goto(`${BASE}/ingresar`);
  await page.locator('#username').fill(portal.user);
  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: /Ingresar/ }).click();
  if (!portal.mfa) {
    await page.waitForURL((u) => !u.pathname.startsWith('/ingresar'));
    return;
  }
  await page.locator('#code').waitFor();
  const file = process.env.E2E_TOTP_FILE;
  let secret = (await page.locator('p.mono').allTextContents()).map((t) => t.trim()).find((t) => /^[A-Z2-7]{16,}$/.test(t));
  if (secret && file) {
    writeFileSync(file, secret, { mode: 0o600 });
  } else if (!secret && file && existsSync(file)) {
    secret = readFileSync(file, 'utf8').trim();
  }
  if (!secret) {
    throw new Error('La cuenta administrativa ya tiene segundo factor y no hay E2E_TOTP_FILE con su clave');
  }
  await page.locator('#code').fill(totp(secret));
  await page.waitForURL((u) => u.pathname.startsWith('/admin'));
}

function totp(base32) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bits = [...base32.replace(/=+$/, '')].map((c) => alphabet.indexOf(c).toString(2).padStart(5, '0')).join('');
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = createHmac('sha1', key).update(counter).digest();
  return String((h.readUInt32BE(h[19] & 15) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}
