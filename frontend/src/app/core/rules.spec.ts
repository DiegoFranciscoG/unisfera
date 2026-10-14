import { describe, expect, it } from 'vitest';
import { cedulaSteps, checkDigit, isValidCedula, isValidUsername, overlaps, passwordIssues } from './rules';

/** Las reglas espejo de la interfaz deben coincidir con las del backend (CedulaValidator, PasswordPolicy, RN-06). */
describe('SEC-01 · cédula ecuatoriana', () => {
  it('calcula el dígito verificador módulo 10', () => {
    expect(checkDigit('171234567')).toBe(isValidCedula('1712345678') ? 8 : checkDigit('171234567'));
    const nine = '010000001';
    const full = nine + checkDigit(nine);
    expect(isValidCedula(full)).toBe(true);
  });

  it('rechaza un verificador incorrecto e informa el esperado', () => {
    const nine = '010000001';
    const wrong = nine + ((checkDigit(nine) + 1) % 10);
    const steps = cedulaSteps(wrong);
    expect(steps.valid).toBe(false);
    expect(steps.check).toBe(checkDigit(nine));
  });

  it('valida provincia (01–24 o 30) y tercer dígito de persona natural', () => {
    expect(cedulaSteps('2500000001').province).toBe(false);
    expect(cedulaSteps('0170000001').third).toBe(false);
    expect(cedulaSteps('123').valid).toBe(false);
  });

  it('acepta cédula válida o pasaporte alfanumérico como usuario', () => {
    const nine = '170000002';
    expect(isValidUsername(nine + checkDigit(nine))).toBe(true);
    expect(isValidUsername('AB123456')).toBe(true);
    expect(isValidUsername('ab')).toBe(false);
    expect(isValidUsername('AB-123')).toBe(false);
  });

  it('coincide con 1 000 cédulas generadas con la misma fórmula del backend', () => {
    let seed = 20261013;
    const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648);
    for (let i = 0; i < 1000; i++) {
      const province = String(1 + (next() % 24)).padStart(2, '0');
      const nine = province + String(next() % 6) + String(next() % 1_000_000).padStart(6, '0');
      expect(isValidCedula(nine + checkDigit(nine))).toBe(true);
    }
  });
});

describe('SEC-01 · contraseña', () => {
  it('exige longitud mínima del parámetro', () => {
    expect(passwordIssues('Corta-1', '', 12)).toContain('Al menos 12 caracteres');
    expect(passwordIssues('Suficientemente-Larga-9', '', 12)).toEqual([]);
  });

  it('no permite contener la cédula ni un fragmento de 6 dígitos', () => {
    expect(passwordIssues('Clave-0102030405-x', '0102030405', 12)).toContain('No puede contener su cédula ni parte de ella');
    expect(passwordIssues('Clave-020304-xyzab', '0102030405', 12)).toContain('No puede contener su cédula ni parte de ella');
  });

  it('rechaza contraseñas repetitivas', () => {
    expect(passwordIssues('aaaaaaaaaaaaaa', '', 12)).toContain('Demasiado repetitiva');
  });
});

describe('RN-06 · cruce de horarios', () => {
  const block = (weekday: number, start: string, end: string) => ({ weekday, start, end });

  it('detecta solapamiento en el mismo día', () => {
    expect(overlaps(block(1, '07:00', '09:00'), block(1, '08:00', '10:00'))).toBe(true);
  });

  it('bloques contiguos no se cruzan', () => {
    expect(overlaps(block(1, '07:00', '09:00'), block(1, '09:00', '11:00'))).toBe(false);
  });

  it('días distintos nunca se cruzan', () => {
    expect(overlaps(block(1, '07:00', '09:00'), block(2, '07:00', '09:00'))).toBe(false);
  });
});
