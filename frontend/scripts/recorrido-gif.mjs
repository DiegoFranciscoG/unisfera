// Captura los cuadros del recorrido animado del README (docs/img/recorrido.gif): ingreso, órbita,
// malla, matrícula en tres pasos y confirmación. Luego `python scripts/armar-gif.py` arma el GIF.
// Uso (desde frontend/): E2E_PASSWORD=... E2E_NEW_STUDENT=<aspirante sin matrícula> node scripts/recorrido-gif.mjs
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:8081';
const USER = process.env.E2E_NEW_STUDENT;
const OUT = resolve(process.cwd(), 'test-results', 'recorrido');
mkdirSync(OUT, { recursive: true });
let n = 0;

const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL ?? 'chrome' });
const page = await (await browser.newContext({ viewport: { width: 1280, height: 800 }, colorScheme: 'light', locale: 'es-EC' })).newPage();
const frame = async (wait = 900) => {
  await page.waitForTimeout(wait);
  await page.screenshot({ path: resolve(OUT, `${String(n++).padStart(2, '0')}.png`) });
};

await page.goto(`${BASE}/ingresar`);
await frame(1200);
// LOPDP: en el GIF publicado la cédula aparece incompleta.
await page.locator('#username').fill(USER.slice(0, 5));
await frame(700);
await page.locator('#username').fill(USER);
await page.locator('#password').fill(process.env.E2E_PASSWORD);
await page.getByRole('button', { name: /Ingresar/ }).click();
await page.waitForURL((u) => u.pathname.startsWith('/estudiante'));
await frame(2200);
await page.getByRole('link', { name: 'Mi malla', exact: true }).click();
await frame(2600);
await page.locator('g.planet').first().click();
await frame(1200);
await page.getByRole('link', { name: 'Matrícula', exact: true }).click();
await frame(2200);
const cards = page.locator('article.subject');
for (let i = 0; i < Math.min(3, await cards.count()); i++) {
  const free = cards.nth(i).locator('button.section:not([disabled])');
  if (await free.count()) {
    await free.first().click();
    await frame(900);
  }
}
await page.getByRole('button', { name: /Continuar/ }).click();
await frame(1500);
await page.getByRole('button', { name: /Continuar/ }).click();
await frame(800);
await page.getByRole('checkbox').check();
await frame(500);
await page.getByRole('button', { name: /Confirmar matrícula/ }).click();
await frame(1800);
await frame(1200);
console.log(`${n} cuadros en ${OUT}`);
await Promise.race([browser.close().catch(() => undefined), new Promise((r) => setTimeout(r, 5000))]);
process.exit(0);
