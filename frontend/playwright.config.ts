import { defineConfig, devices } from '@playwright/test';

/**
 * Flujos críticos sobre el sistema completo levantado con docker compose (ver .github/workflows/ci.yml).
 * Se ejecutan en los tres motores de navegador (Chromium, Firefox y WebKit/Safari) y en teléfonos.
 * Las pruebas marcadas @unico modifican datos de forma irrepetible (matricular a un aspirante) y corren
 * solo una vez, en Chromium de escritorio.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:8081',
    locale: 'es-EC',
    timezoneId: 'America/Guayaquil',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], channel: process.env['PW_CHANNEL'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] }, grepInvert: /@unico/ },
    { name: 'safari', use: { ...devices['Desktop Safari'] }, grepInvert: /@unico/ },
    { name: 'android', use: { ...devices['Pixel 7'], channel: process.env['PW_CHANNEL'] }, grep: /@movil/ },
    { name: 'iphone', use: { ...devices['iPhone 15'] }, grep: /@movil/ },
  ],
});
