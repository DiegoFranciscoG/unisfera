import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { EcDatePipe, GradePipe, RESULT_LABEL } from '../../core/format';
import { ActView } from '../../core/models';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

/** Historial de actas firmadas: originales y rectificaciones encadenadas, con su huella SHA-256. */
@Component({
  selector: 'app-teacher-acts',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Actas" title="Historial de actas" subtitle="Un acta cerrada no se edita: se rectifica con autorización y ambas quedan enlazadas (LEY-2)." />
    @if (acts.value(); as list) {
      <div class="space-y-3">
        @for (a of list; track a.id; let i = $index) {
          <article class="tile p-5" [style.animation-delay.ms]="i * 50" style="animation: var(--animate-rise)">
            <button class="flex w-full flex-wrap items-center justify-between gap-3 text-left" (click)="toggle(a.id)" [attr.aria-expanded]="open() === a.id">
              <div>
                <p class="mono muted text-[11px] font-bold">{{ a.periodCode }} · Paralelo {{ a.sectionCode }}</p>
                <h2 class="text-lg">{{ a.subjectName }}</h2>
                <p class="muted text-xs">Cerrada el {{ a.closedAt | ecDate: 'datetime' }}</p>
              </div>
              <div class="flex items-center gap-2">
                <span class="chip" [class.chip-gold]="a.type !== 'ORIGINAL'" [class.chip-blue]="a.type === 'ORIGINAL'">{{ a.type === 'ORIGINAL' ? 'Original' : 'Rectificación' }}</span>
                <span class="chip" [class.chip-green]="a.status === 'CLOSED'" [class.chip-muted]="a.status !== 'CLOSED'">{{ a.status === 'CLOSED' ? 'Vigente' : 'Reemplazada' }}</span>
                <app-icon [name]="open() === a.id ? 'x' : 'plus'" [size]="16" />
              </div>
            </button>
            @if (open() === a.id) {
              <div class="mt-4" style="animation: var(--animate-rise)">
                <p class="mono break-all rounded-lg p-2 text-[11px]" style="background: var(--surface-2)">SHA-256 {{ a.contentHash }}</p>
                @if (a.reason) { <p class="mt-2 text-sm"><strong>Motivo:</strong> {{ a.reason }}</p> }
                <div class="mt-3 overflow-x-auto">
                  <table class="table">
                    <thead><tr><th>Código</th><th>Estudiante</th><th>Nota</th><th>Asistencia</th><th>Resultado</th></tr></thead>
                    <tbody>
                      @for (l of a.lines; track l.detailId) {
                        <tr><td class="mono text-xs">{{ l.studentCode }}</td><td>{{ l.fullName }}</td><td class="mono font-bold">{{ l.finalGrade | grade }}</td>
                          <td>{{ l.attendancePct | grade }}%</td><td>{{ label(l.result) }}</td></tr>
                      }
                    </tbody>
                  </table>
                </div>
              </div>
            }
          </article>
        } @empty {
          <app-empty icon="seal" title="Aún no has cerrado actas" text="Cuando cierres un acta aparecerá aquí con su firma." />
        }
      </div>
    } @else {
      <app-skeleton [count]="3" [height]="90" />
    }
  `,
})
export class TeacherActsPage {
  private readonly api = inject(ApiService);
  protected readonly acts = rxResource({ stream: () => this.api.get<ActView[]>('/teacher/acts') });
  protected readonly open = signal<string | null>(null);

  protected toggle(id: string): void {
    this.open.update((o) => (o === id ? null : id));
  }

  protected label(r: string): string {
    return RESULT_LABEL[r] ?? r;
  }
}
