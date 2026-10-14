import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { EcDatePipe, GradePipe, RESULT_LABEL } from '../../core/format';
import { Kardex, KardexEntry } from '../../core/models';
import { HelpService, SettingsStore } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent, StatComponent } from '../../shared/widgets';

/** Kardex: historial inmutable (LEY-2) agrupado por período, con promedio ponderado por horas (RN-16). */
@Component({
  selector: 'app-kardex-page',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, StatComponent, GradePipe, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Kardex" title="Tu historial académico" subtitle="Cada registro proviene de un acta cerrada u homologación aprobada y no se modifica: solo se rectifica con resolución.">
      <button class="btn btn-ghost" (click)="help.show('kardex-y-promedio')"><app-icon name="help" [size]="16" /> ¿Cómo se calcula el promedio?</button>
      <button class="btn btn-ghost" (click)="print()"><app-icon name="download" [size]="16" /> Imprimir</button>
    </app-page-header>

    @if (kardex.value(); as k) {
      <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
        <app-stat label="Promedio ponderado" [value]="k.weightedAverage" [decimals]="2" icon="grade" tone="blue" hint="Ponderado por horas (RN-16)" />
        <app-stat label="Créditos aprobados" [value]="k.approvedCredits" [decimals]="1" icon="seal" tone="rio" />
        <app-stat label="Asignaturas aprobadas" [value]="k.approvedSubjects" icon="check" tone="oro" />
        <app-stat label="Intentos reprobados" [value]="k.failedAttempts" icon="refresh" tone="teja" />
      </div>

      <div class="mt-6 flex flex-wrap gap-2">
        @for (f of filters; track f.key) {
          <button class="chip !py-1.5 transition" [class.chip-blue]="filter() === f.key" [class.chip-muted]="filter() !== f.key" (click)="filter.set(f.key)">{{ f.label }}</button>
        }
      </div>

      @for (group of groups(); track group.period; let gi = $index) {
        <section class="mt-5" style="animation: var(--animate-rise)" [style.animation-delay.ms]="gi * 60">
          <h2 class="mb-2 flex items-center gap-3 text-xl">
            <span class="period">{{ group.period }}</span>
            <span class="muted text-sm font-normal">{{ group.entries.length }} registros</span>
          </h2>
          <div class="tile overflow-x-auto">
            <table class="table">
              <thead><tr><th>Código</th><th>Asignatura</th><th>Nivel</th><th>Horas</th><th>Matrícula</th><th>Nota</th><th>Asistencia</th><th>Resultado</th></tr></thead>
              <tbody>
                @for (e of group.entries; track e.subjectId + e.recordedAt) {
                  <tr>
                    <td class="mono text-xs">{{ e.subjectCode }}</td>
                    <td class="font-semibold">{{ e.subjectName }} @if (e.source === 'HOMOLOGATION') { <span class="chip chip-gold ml-1">Homologada</span> }</td>
                    <td>{{ e.level }}</td>
                    <td>{{ e.totalHours }}</td>
                    <td>{{ e.attempt ?? '—' }}</td>
                    <td class="mono font-bold">{{ e.finalGrade | grade }}</td>
                    <td>{{ e.attendancePct === null ? '—' : (e.attendancePct | grade) + '%' }}</td>
                    <td><span class="chip" [class.chip-green]="e.result === 'APPROVED'" [class.chip-red]="e.result !== 'APPROVED'">{{ resultLabel(e.result) }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      } @empty {
        <div class="mt-6"><app-empty icon="book" title="Tu kardex está vacío" text="Cuando se cierre el acta de tu primer período, tus notas aparecerán aquí." /></div>
      }
      <p class="muted mt-6 text-xs">Consultado el {{ now | ecDate: 'datetime' }} · Escala {{ settings.settings().scaleMin }}–{{ settings.settings().scaleMax }}</p>
    } @else {
      <app-skeleton [count]="4" [height]="90" />
    }
  `,
  styles: `
    .period { font-family: var(--font-mono); font-size: .95rem; padding: .2rem .7rem; border-radius: 8px; background: var(--brand-soft); color: var(--brand); }
  `,
})
export class KardexPage {
  private readonly api = inject(ApiService);
  protected readonly settings = inject(SettingsStore);
  protected readonly help = inject(HelpService);
  protected readonly kardex = rxResource({ stream: () => this.api.get<Kardex>('/me/kardex') });
  protected readonly filter = signal<'ALL' | 'APPROVED' | 'FAILED'>('ALL');
  protected readonly now = new Date().toISOString();
  protected readonly filters = [
    { key: 'ALL' as const, label: 'Todo' },
    { key: 'APPROVED' as const, label: 'Aprobadas' },
    { key: 'FAILED' as const, label: 'Reprobadas' },
  ];

  protected readonly groups = computed(() => {
    const entries = (this.kardex.value()?.entries ?? []).filter((e) =>
      this.filter() === 'ALL' ? true : this.filter() === 'APPROVED' ? e.result === 'APPROVED' : e.result !== 'APPROVED',
    );
    const map = new Map<string, KardexEntry[]>();
    for (const e of entries) {
      const key = e.periodCode ?? 'Homologaciones';
      map.set(key, [...(map.get(key) ?? []), e]);
    }
    return [...map.entries()].sort((a, b) => b[0].localeCompare(a[0])).map(([period, list]) => ({ period, entries: list }));
  });

  protected resultLabel(r: string): string {
    return RESULT_LABEL[r] ?? r;
  }

  protected print(): void {
    window.print();
  }
}
