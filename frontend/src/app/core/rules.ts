/**
 * Reglas espejo del backend para la validación en vivo (Ley 3: interfaz, dominio y base aplican
 * la misma regla; si difieren, manda la base). Los valores de negocio llegan de /api/public/settings.
 */

/** SEC-01 · Cédula ecuatoriana: provincia 01–24 o 30, tercer dígito < 6 y verificador módulo 10. */
export function cedulaSteps(value: string): { valid: boolean; province: boolean; third: boolean; check: number | null } {
  if (!/^\d{10}$/.test(value)) {
    return { valid: false, province: false, third: false, check: null };
  }
  const province = Number(value.slice(0, 2));
  const provinceOk = (province >= 1 && province <= 24) || province === 30;
  const thirdOk = province === 30 || Number(value[2]) < 6;
  const check = checkDigit(value.slice(0, 9));
  return { valid: provinceOk && thirdOk && check === Number(value[9]), province: provinceOk, third: thirdOk, check };
}

export function checkDigit(firstNine: string): number {
  const coefficients = [2, 1, 2, 1, 2, 1, 2, 1, 2];
  let sum = 0;
  for (let i = 0; i < 9; i++) {
    const product = Number(firstNine[i]) * coefficients[i];
    sum += product >= 10 ? product - 9 : product;
  }
  return (10 - (sum % 10)) % 10;
}

export function isValidCedula(value: string): boolean {
  return cedulaSteps(value).valid;
}

/** Usuario: cédula de 10 dígitos o pasaporte alfanumérico. */
export function isValidUsername(value: string): boolean {
  return /^\d{10}$/.test(value) ? isValidCedula(value) : /^[A-Za-z0-9]{5,20}$/.test(value);
}

/** SEC-01 · La contraseña nunca contiene la cédula ni un fragmento de 6 dígitos de ella. */
export function passwordIssues(password: string, idNumber: string, minLength: number): string[] {
  const issues: string[] = [];
  if (password.length < minLength) {
    issues.push(`Al menos ${minLength} caracteres`);
  }
  for (let i = 0; i + 6 <= idNumber.length; i++) {
    if (password.includes(idNumber.slice(i, i + 6))) {
      issues.push('No puede contener su cédula ni parte de ella');
      break;
    }
  }
  if (new Set(password).size <= 2 && password.length > 0) {
    issues.push('Demasiado repetitiva');
  }
  return issues;
}

/** RN-06 · Solapamiento real de bloques horarios. */
export function overlaps(a: { weekday: number; start: string; end: string }, b: { weekday: number; start: string; end: string }): boolean {
  return a.weekday === b.weekday && a.start < b.end && b.start < a.end;
}

export const WEEKDAYS = ['', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado', 'Domingo'];
