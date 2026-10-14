import { Page, expect, test } from '@playwright/test';

const PASSWORD = process.env['E2E_PASSWORD'] ?? '';
const STUDENT = process.env['E2E_STUDENT'] ?? '';
const TEACHER = process.env['E2E_TEACHER'] ?? '';
const NEW_STUDENT = process.env['E2E_NEW_STUDENT'] ?? '';

/** Navega por el menú lateral; en teléfonos primero lo abre con el botón de hamburguesa. */
async function menu(page: Page, name: string): Promise<void> {
  const burger = page.getByRole('button', { name: 'Abrir menú' });
  if (await burger.isVisible()) {
    await burger.click();
  }
  await page.getByRole('link', { name, exact: true }).click();
}

async function login(page: Page, username: string): Promise<void> {
  await page.goto('/ingresar');
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(PASSWORD);
  await page.getByRole('button', { name: /Ingresar/ }).click();
}

test.describe('Ingreso', () => {
  test('una cédula con dígito verificador incorrecto se explica antes de enviar @movil', async ({ page }) => {
    await page.goto('/ingresar');
    await page.locator('#username').fill('0100000000');
    await expect(page.getByText(/dígito verificador debería ser/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Ingresar/ })).toBeDisabled();
  });

  test('credenciales erróneas muestran un mensaje genérico (SEC-02)', async ({ page }) => {
    test.skip(!STUDENT, 'Sin usuario demo');
    await page.goto('/ingresar');
    await page.locator('#username').fill(STUDENT);
    await page.locator('#password').fill('Clave-Equivocada-123');
    await page.getByRole('button', { name: /Ingresar/ }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(/ingresar/);
  });
});

test.describe('Estudiante', () => {
  test.skip(!STUDENT || !PASSWORD, 'Defina E2E_STUDENT y E2E_PASSWORD');

  test('ve su órbita, su malla y sus notas @movil', async ({ page }) => {
    await login(page, STUDENT);
    await expect(page.getByRole('heading', { name: /Hola,/ })).toBeVisible();
    await menu(page, 'Mi malla');
    await expect(page.getByRole('img', { name: 'Mapa orbital de la malla' })).toBeVisible();
    await menu(page, 'Mis notas');
    await expect(page.getByRole('heading', { name: 'Cómo vas este período' })).toBeVisible();
    await menu(page, 'Kardex');
    await expect(page.getByText('Promedio ponderado')).toBeVisible();
  });

  test('paga en línea con la pasarela simulada y obtiene su factura (RN-24)', async ({ page }) => {
    await login(page, STUDENT);
    await menu(page, 'Cuenta y pagos');
    await expect(page.getByText(/Saldo pendiente/).first()).toBeVisible();
    const pay = page.getByRole('button', { name: /Pagar en línea/ });
    test.skip(!(await pay.isVisible()), 'El estudiante demo no tiene saldo pendiente');
    await page.locator('#amount').fill('10');
    await pay.click();
    await expect(page).toHaveURL(/pagos\/pasarela/);
    await page.getByRole('button', { name: /^Aprobar$/ }).click();
    await expect(page.getByText('Pago aprobado')).toBeVisible({ timeout: 45_000 });
    await page.getByRole('link', { name: 'Volver a UNISFERA' }).click();
    await expect(page.getByRole('button', { name: /Factura/ }).first()).toBeVisible();
  });
});

test.describe('Matrícula', () => {
  test.skip(!NEW_STUDENT || !PASSWORD, 'Defina E2E_NEW_STUDENT');

  test('un aspirante admitido se matricula en tres pasos y ve su horario @unico', async ({ page }) => {
    await login(page, NEW_STUDENT);
    await menu(page, 'Matrícula');
    const section = page.locator('button.section:not([disabled])').first();
    await section.click();
    await expect(page.locator('.counter')).toContainText('1/');
    await page.getByRole('button', { name: /Continuar/ }).click();
    await expect(page.getByText('Tu semana')).toBeVisible();
    await expect(page.getByText('Total')).toBeVisible();
    await page.getByRole('button', { name: /Continuar/ }).click();
    await page.getByRole('checkbox').check();
    await page.getByRole('button', { name: /Confirmar matrícula/ }).click();
    await expect(page.getByText('MATRICULADO')).toBeVisible();
    await page.getByRole('link', { name: /Ver mi horario/ }).click();
    await expect(page.getByRole('table', { name: 'Horario semanal' })).toBeVisible();
  });
});

test.describe('Docente', () => {
  test.skip(!TEACHER || !PASSWORD, 'Defina E2E_TEACHER');

  test('abre su paralelo, ve la planilla y la vista previa del acta', async ({ page }) => {
    await login(page, TEACHER);
    await expect(page.getByRole('heading', { name: /Hola,/ })).toBeVisible();
    await page.getByText('Abrir paralelo').first().click();
    await expect(page.getByRole('tab', { name: /Calificaciones/ })).toBeVisible();
    await expect(page.locator('table.sheet')).toBeVisible();
    await page.getByRole('tab', { name: /Acta/ }).click();
    await expect(page.getByText(/aprueban/)).toBeVisible();
  });
});
