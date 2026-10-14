import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Problem, ScheduleEntry } from '../../core/models';
import { HelpService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { GridItem, WeekGridComponent } from '../../shared/week-grid.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface WithdrawalView {
  id: string;
  detailId: string;
  subjectName: string;
  type: string;
  reason: string;
  status: string;
}

/** Horario semanal y retiro de asignaturas (RN-09) desde la misma pantalla. */
@Component({
  selector: 'app-schedule-page',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, WeekGridComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Horario" title="Tu semana" subtitle="Tus clases del período. Puedes imprimirlo o solicitar el retiro de una asignatura.">
      <button class="btn btn-ghost" (click)="print()"><app-icon name="download" [size]="16" /> Imprimir</button>
    </app-page-header>

    @if (schedule.value(); as list) {
      @if (list.length) {
        <section class="tile p-4" style="animation: var(--animate-rise)">
          <app-week-grid [items]="items()" />
        </section>
        <section class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-3 stagger">
          @for (e of list; track e.detailId; let i = $index) {
            <article class="tile p-4" [style.--i]="i">
              <p class="mono muted text-[11px] font-bold">{{ e.subjectCode }} · Paralelo {{ e.sectionCode }}</p>
              <h3 class="mt-1 text-lg leading-snug">{{ e.subjectName }}</h3>
              <p class="muted mt-1 text-sm">{{ e.teacherName ?? 'Docente por asignar' }}</p>
              <div class="mt-3 flex flex-wrap items-center gap-2">
                @if (e.attempt > 1) { <span class="chip chip-teja">{{ e.attempt }}.ª matrícula</span> }
                @if (withdrawalOf(e.detailId); as w) {
                  <span class="chip" [class.chip-gold]="w.status === 'REQUESTED'" [class.chip-green]="w.status === 'APPROVED'" [class.chip-red]="w.status === 'REJECTED'">
                    Retiro {{ withdrawalLabel(w.status) }}
                  </span>
                } @else {
                  <button class="ml-auto text-sm font-bold underline-offset-2 hover:underline" style="color: var(--color-teja-500)" (click)="askWithdraw(e)">Retirarme</button>
                }
              </div>
            </article>
          }
        </section>
      } @else {
        <app-empty icon="calendar" title="Aún no tienes clases" text="Cuando confirmes tu matrícula, aquí verás tu semana.">
          <a routerLink="/estudiante/matricula" class="btn btn-teja mt-2">Ir a matrícula</a>
        </app-empty>
      }
    } @else {
      <app-skeleton [count]="1" [height]="420" />
    }

    @if (withdrawing(); as e) {
      <div class="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" (click)="withdrawing.set(null)">
        <div class="tile w-full max-w-lg p-6" style="animation: var(--animate-rise)" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" aria-labelledby="wd-title">
          <p class="kicker">Retiro de asignatura</p>
          <h2 id="wd-title" class="mt-1 text-2xl">{{ e.subjectName }}</h2>
          <p class="muted mt-2 text-sm">El retiro voluntario tiene un plazo desde el inicio de clases; fuera de él solo procede por caso fortuito o fuerza mayor con documento.
            <button class="font-bold underline" style="color: var(--brand)" (click)="help.show('retiros-homologaciones-certificados', 'rn-09')">Ver reglas</button></p>
          <label class="label mt-4" for="wd-type">Tipo</label>
          <select id="wd-type" class="field" [value]="wType()" (change)="wType.set($any($event.target).value)">
            <option value="VOLUNTARY">Voluntario</option>
            <option value="FORCE_MAJEURE">Caso fortuito o fuerza mayor</option>
          </select>
          <label class="label mt-4" for="wd-reason">Motivo</label>
          <textarea id="wd-reason" class="field" rows="3" maxlength="1000" [value]="wReason()" (input)="wReason.set($any($event.target).value)"
            placeholder="Cuéntanos brevemente por qué (mínimo 10 caracteres)"></textarea>
          <div class="mt-5 flex justify-end gap-2">
            <button class="btn btn-ghost" (click)="withdrawing.set(null)">Cancelar</button>
            <button class="btn btn-teja" [disabled]="wReason().trim().length < 10 || busy()" (click)="withdraw(e)">Solicitar retiro</button>
          </div>
        </div>
      </div>
    }
  `,
})
export class SchedulePage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly help = inject(HelpService);
  protected readonly schedule = rxResource({ stream: () => this.api.get<ScheduleEntry[]>('/me/schedule') });
  protected readonly withdrawals = rxResource({ stream: () => this.api.get<WithdrawalView[]>('/me/withdrawals') });
  protected readonly withdrawing = signal<ScheduleEntry | null>(null);
  protected readonly wType = signal('VOLUNTARY');
  protected readonly wReason = signal('');
  protected readonly busy = signal(false);

  protected readonly items = computed<GridItem[]>(() =>
    (this.schedule.value() ?? []).map((e, i) => ({ key: e.detailId, title: e.subjectName, subtitle: e.teacherName, blocks: e.blocks, tone: i })),
  );

  protected withdrawalOf(detailId: string): WithdrawalView | null {
    return (this.withdrawals.value() ?? []).find((w) => w.detailId === detailId && w.status !== 'REJECTED') ?? null;
  }

  protected withdrawalLabel(status: string): string {
    return { REQUESTED: 'en trámite', APPROVED: 'aprobado', REJECTED: 'negado' }[status] ?? status;
  }

  protected askWithdraw(e: ScheduleEntry): void {
    this.wReason.set('');
    this.wType.set('VOLUNTARY');
    this.withdrawing.set(e);
  }

  protected withdraw(e: ScheduleEntry): void {
    this.busy.set(true);
    this.api.post('/me/withdrawals', { enrollmentDetailId: e.detailId, type: this.wType(), reason: this.wReason().trim() }).subscribe({
      next: () => {
        this.busy.set(false);
        this.withdrawing.set(null);
        this.toasts.ok('Solicitud de retiro enviada', 'Secretaría la revisará; te avisaremos en notificaciones.');
        this.withdrawals.reload();
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected print(): void {
    window.print();
  }
}
