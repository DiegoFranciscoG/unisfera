import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { GradePipe } from '../../core/format';
import { CurrentSubject } from '../../core/models';
import { HelpService, SettingsStore } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, RingComponent, SkeletonComponent } from '../../shared/widgets';

/** Notas del período: aporte de cada componente, asistencia y "lo que te falta" con un simulador. */
@Component({
  selector: 'app-grades-page',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, RingComponent, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Mis notas" title="Cómo vas este período"
      [subtitle]="'Nota mínima para aprobar: ' + s().passingGrade + ' sobre ' + s().scaleMax + ' · asistencia mínima: ' + s().minAttendance + '%.'">
      <button class="btn btn-ghost" (click)="help.show('como-leo-mis-notas')"><app-icon name="help" [size]="16" /> ¿Cómo se calcula?</button>
    </app-page-header>

    @if (grades.value(); as list) {
      <div class="grid gap-5 lg:grid-cols-2">
        @for (g of list; track g.detailId; let i = $index) {
          <article class="tile p-5" [style.animation-delay.ms]="i * 70" style="animation: var(--animate-rise)">
            <header class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <p class="mono muted text-[11px] font-bold">{{ g.subjectCode }} · Paralelo {{ g.sectionCode }}</p>
                <h2 class="mt-1 text-xl leading-snug">{{ g.subjectName }}</h2>
                <div class="mt-2 flex flex-wrap gap-1.5">
                  @if (g.provisional) { <span class="chip chip-gold">Provisional</span> } @else { <span class="chip chip-green">Acta cerrada</span> }
                  @if (g.attempt > 1) { <span class="chip chip-teja">{{ g.attempt }}.ª matrícula</span> }
                </div>
              </div>
              <div class="flex gap-1">
                <app-ring [value]="g.accumulated" [max]="s().scaleMax" [threshold]="s().passingGrade" label="nota" [size]="92" />
                <app-ring [value]="g.attendancePct" [max]="100" [threshold]="g.minAttendance" label="asist." suffix="%" [size]="92" />
              </div>
            </header>

            <ul class="mt-4 space-y-2.5">
              @for (c of g.components; track c.componentId) {
                <li>
                  <div class="flex justify-between text-sm">
                    <span class="font-semibold">{{ c.name }} <span class="muted font-normal">· {{ c.weight }}%</span></span>
                    <span class="mono font-bold">{{ c.value === null ? 'pendiente' : (c.value | grade) }}</span>
                  </div>
                  <div class="bar"><span [style.width.%]="c.value === null ? 0 : (c.value / s().scaleMax) * 100" [class.pending]="c.value === null"></span></div>
                </li>
              }
            </ul>

            @if (g.attendanceWarning) {
              <div class="note warn mt-4"><app-icon name="alert" [size]="16" />
                <span>Tu asistencia ({{ g.attendancePct | grade }}%) está cerca o por debajo del mínimo ({{ g.minAttendance }}%). Si cae por debajo, la asignatura se reprueba por asistencia (RN-15).</span>
              </div>
            }
            @if (g.needed.alreadyPassed) {
              <div class="note ok mt-4"><app-icon name="check" [size]="16" /><span>Ya alcanzaste la nota mínima con lo acumulado. ¡Sigue así!</span></div>
            } @else if (g.needed.pendingWeight > 0) {
              <div class="note mt-4">
                <app-icon name="sparkle" [size]="16" />
                <span>
                  @if (g.needed.reachable) {
                    Te falta el {{ g.needed.pendingWeight | grade }}% de la nota. Necesitas un promedio de <strong>{{ g.needed.neededAverage | grade }}</strong> en lo que queda.
                  } @else {
                    Con lo que queda ({{ g.needed.pendingWeight | grade }}%) ya no alcanzas la nota mínima. Habla con tu docente o revisa
                    <button class="font-bold underline" (click)="help.show('si-repruebo')">qué pasa si repruebo</button>.
                  }
                </span>
              </div>
              <div class="sim mt-3">
                <label class="label" [attr.for]="'sim-' + g.detailId">Simulador · si saco en lo que falta…</label>
                <div class="flex items-center gap-3">
                  <input type="range" [id]="'sim-' + g.detailId" [min]="s().scaleMin" [max]="s().scaleMax" step="1" [value]="simValue(g)"
                    (input)="setSim(g.detailId, $any($event.target).valueAsNumber)" class="flex-1" />
                  <span class="mono w-12 text-right font-bold">{{ simValue(g) }}</span>
                </div>
                <p class="mt-1 text-sm">Terminarías con <strong class="font-display text-lg" [style.color]="projected(g) >= s().passingGrade ? 'var(--color-rio-500)' : 'var(--color-teja-500)'">{{ projected(g) | grade }}</strong>
                  <span class="muted"> (estimación; la nota oficial la calcula el acta)</span></p>
              </div>
            }
          </article>
        } @empty {
          <div class="lg:col-span-2"><app-empty icon="grade" title="Aún no hay notas" text="Cuando tus docentes registren calificaciones, las verás aquí al instante." /></div>
        }
      </div>
    } @else {
      <app-skeleton [count]="2" [height]="260" />
    }
  `,
  styles: `
    .bar { height: 8px; border-radius: 999px; background: var(--surface-2); overflow: hidden; margin-top: 4px; }
    .bar span { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--color-cupula-400), var(--color-cupula-600));
      transition: width 1s cubic-bezier(.2,.8,.2,1); }
    .bar span.pending { background: transparent; }
    .note { display: flex; gap: .6rem; align-items: flex-start; padding: .75rem .9rem; border-radius: 12px; font-size: .9rem; background: var(--brand-soft); }
    .note.warn { background: var(--color-teja-100); color: var(--color-teja-700); }
    .note.ok { background: var(--color-rio-100); color: var(--color-rio-700); }
    :host-context(.dark) .note.warn { background: #4a2518; color: #f2b29b; }
    :host-context(.dark) .note.ok { background: #173a36; color: #8fd3c8; }
    .sim { padding: .8rem; border-radius: 12px; border: 1px dashed var(--line); }
    input[type='range'] { accent-color: var(--color-cupula-500); }
  `,
})
export class GradesPage {
  private readonly api = inject(ApiService);
  private readonly settings = inject(SettingsStore);
  protected readonly help = inject(HelpService);
  protected readonly s = this.settings.settings;
  protected readonly grades = rxResource({ stream: () => this.api.get<CurrentSubject[]>('/me/grades') });
  private readonly sims = signal<Record<string, number>>({});

  protected simValue(g: CurrentSubject): number {
    return this.sims()[g.detailId] ?? Math.round(g.needed.neededAverage ?? this.s().passingGrade);
  }

  protected setSim(id: string, value: number): void {
    this.sims.update((m) => ({ ...m, [id]: value }));
  }

  protected projected(g: CurrentSubject): number {
    return g.accumulated + (this.simValue(g) * g.needed.pendingWeight) / 100;
  }
}
