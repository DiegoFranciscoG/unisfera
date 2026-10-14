import { Pipe, PipeTransform, inject } from '@angular/core';
import { SettingsStore } from './ui.services';

/** Dinero siempre formateado desde números exactos que llegan de la API (nunca se calcula con float aquí). */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  private readonly settings = inject(SettingsStore);

  transform(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }
    return new Intl.NumberFormat('es-EC', { style: 'currency', currency: this.settings.settings().currency }).format(Number(value));
  }
}

/** Fechas mostradas en la zona horaria de Ecuador aunque se guarden en UTC. */
@Pipe({ name: 'ecDate' })
export class EcDatePipe implements PipeTransform {
  private readonly settings = inject(SettingsStore);

  transform(value: string | null | undefined, mode: 'date' | 'datetime' | 'short' = 'date'): string {
    if (!value) {
      return '—';
    }
    const date = value.length === 10 ? new Date(value + 'T12:00:00Z') : new Date(value);
    const options: Intl.DateTimeFormatOptions =
      mode === 'datetime'
        ? { dateStyle: 'medium', timeStyle: 'short' }
        : mode === 'short'
          ? { day: '2-digit', month: 'short' }
          : { dateStyle: 'medium' };
    return new Intl.DateTimeFormat('es-EC', { ...options, timeZone: this.settings.settings().zone }).format(date);
  }
}

@Pipe({ name: 'grade' })
export class GradePipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : Number(value).toLocaleString('es-EC', { maximumFractionDigits: 2 });
  }
}

export const RESULT_LABEL: Record<string, string> = {
  APPROVED: 'Aprobada',
  FAILED: 'Reprobada',
  FAILED_ATTENDANCE: 'Reprobada por asistencia',
};

export const STATUS_LABEL: Record<string, string> = {
  ADMITTED: 'Admitido',
  ENROLLED: 'Matriculado',
  REGULAR: 'Regular',
  WITHDRAWN: 'Retirado',
  SUSPENDED: 'Suspendido',
  COMPLETED: 'Egresado',
  GRADUATED: 'Graduado',
};
