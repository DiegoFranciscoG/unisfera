import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { debounceTime } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe, GradePipe, MoneyPipe, RESULT_LABEL, STATUS_LABEL } from '../../core/format';
import { Kardex, Page, Problem, Progress, Statement } from '../../core/models';
import { ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, RingComponent, SkeletonComponent } from '../../shared/widgets';
import { SUBJECT_STATUS } from '../student/curriculum.page';

interface StudentSummary {
  id: string;
  code: string;
  fullName: string;
  idNumber: string;
  careerName: string;
  status: string;
  cohort: string;
  institutionalEmail: string;
}

interface Career {
  id: string;
  code: string;
  name: string;
}

/** Búsqueda de estudiantes y ficha 360° (malla, kardex, cuenta, historial de estados). */
@Component({
  selector: 'app-admin-students',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, RingComponent, GradePipe, MoneyPipe, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Secretaría" title="Estudiantes" subtitle="Busca por nombre, código o cédula. La identificación se muestra enmascarada (LOPDP)." />

    <div class="tile mb-4 flex flex-wrap items-end gap-3 p-4">
      <div class="relative min-w-[240px] flex-1">
        <label class="label" for="q">Buscar</label>
        <span class="muted absolute bottom-3 left-3"><app-icon name="search" [size]="17" /></span>
        <input id="q" class="field !pl-10" placeholder="Nombre, código o cédula" [value]="query()" (input)="query.set($any($event.target).value); page.set(0)" />
      </div>
      <div class="min-w-[200px]">
        <label class="label" for="career">Carrera</label>
        <select id="career" class="field" (change)="career.set($any($event.target).value || null); page.set(0)">
          <option value="">Todas</option>
          @for (c of careers.value()?.items ?? []; track c.id) { <option [value]="c.id">{{ c.name }}</option> }
        </select>
      </div>
      <div class="min-w-[160px]">
        <label class="label" for="status">Estado</label>
        <select id="status" class="field" (change)="status.set($any($event.target).value || null); page.set(0)">
          <option value="">Todos</option>
          @for (s of statuses; track s) { <option [value]="s">{{ statusLabel(s) }}</option> }
        </select>
      </div>
    </div>

    @if (students.value(); as p) {
      <div class="tile overflow-x-auto">
        <table class="table">
          <thead><tr><th>Código</th><th>Estudiante</th><th>Identificación</th><th>Carrera</th><th>Cohorte</th><th>Estado</th></tr></thead>
          <tbody>
            @for (s of p.items; track s.id) {
              <tr class="cursor-pointer" (click)="select(s)" [class.sel]="selected()?.id === s.id">
                <td class="mono text-xs">{{ s.code }}</td>
                <td class="font-semibold">{{ s.fullName }}</td>
                <td class="mono text-xs">{{ mask(s.idNumber) }}</td>
                <td>{{ s.careerName }}</td>
                <td class="mono text-xs">{{ s.cohort }}</td>
                <td><span class="chip chip-blue">{{ statusLabel(s.status) }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="6"><app-empty icon="search" title="Sin resultados" /></td></tr>
            }
          </tbody>
        </table>
      </div>
      <div class="mt-3 flex items-center justify-between text-sm">
        <span class="muted">{{ p.total }} estudiantes</span>
        <div class="flex gap-2">
          <button class="btn btn-ghost !py-1.5" [disabled]="page() === 0" (click)="page.set(page() - 1)">Anterior</button>
          <button class="btn btn-ghost !py-1.5" [disabled]="(page() + 1) * p.size >= p.total" (click)="page.set(page() + 1)">Siguiente</button>
        </div>
      </div>
    } @else {
      <app-skeleton [count]="6" [height]="44" />
    }

    @if (selected(); as s) {
      <div class="fixed inset-0 z-40 bg-black/30" (click)="selected.set(null)" aria-hidden="true"></div>
      <aside class="drawer fixed inset-y-0 right-0 z-50 flex w-[min(100vw,620px)] flex-col overflow-y-auto" role="dialog" aria-modal="true">
        <header class="azulejo flex items-start justify-between gap-3 border-b p-5" style="border-color: var(--line)">
          <div>
            <p class="kicker">{{ s.code }} · {{ s.careerName }}</p>
            <h2 class="text-2xl">{{ s.fullName }}</h2>
            <p class="muted text-sm">{{ s.institutionalEmail }}</p>
          </div>
          <button class="btn btn-ghost !p-2" (click)="selected.set(null)" aria-label="Cerrar"><app-icon name="x" /></button>
        </header>
        <div class="space-y-5 p-5">
          @if (progress.value(); as pr) {
            <section class="flex items-center gap-5">
              <app-ring [value]="pr.totalCredits ? (pr.approvedCredits / pr.totalCredits) * 100 : 0" suffix="%" label="avance" [size]="110" />
              <div class="flex-1">
                <p class="text-sm"><strong>{{ pr.approvedSubjects }}</strong> de {{ pr.totalSubjects }} asignaturas · promedio <strong>{{ pr.weightedAverage | grade }}</strong></p>
                <div class="mt-2 flex flex-wrap gap-1.5">
                  @for (st of statusCounts(); track st.key) { <span class="chip {{ st.chip }}">{{ st.label }} · {{ st.count }}</span> }
                </div>
              </div>
            </section>
          }
          @if (auth.has('FINANCE_READ') && account.value(); as a) {
            <section class="rounded-2xl p-4" style="background: var(--surface-2)">
              <p class="label">Estado de cuenta</p>
              <p class="font-display text-3xl">{{ a.summary.balance | money }}</p>
              <p class="muted text-xs">{{ a.installments.length }} cuotas · {{ overdue(a) }} vencidas</p>
            </section>
          }
          @if (kardex.value(); as k) {
            <section>
              <p class="label">Últimos registros del kardex</p>
              <ul class="space-y-1.5 text-sm">
                @for (e of k.entries.slice(0, 8); track e.subjectId + e.recordedAt) {
                  <li class="flex items-center justify-between gap-2"><span class="truncate">{{ e.subjectName }} <span class="muted mono text-xs">{{ e.periodCode }}</span></span>
                    <span class="flex items-center gap-2"><span class="mono font-bold">{{ e.finalGrade | grade }}</span>
                      <span class="chip" [class.chip-green]="e.result === 'APPROVED'" [class.chip-red]="e.result !== 'APPROVED'">{{ result(e.result) }}</span></span></li>
                }
              </ul>
            </section>
          }
          <section>
            <p class="label">Historial de estados</p>
            <ol class="space-y-1 text-sm">
              @for (h of history.value() ?? []; track h.changedAt) {
                <li><span class="mono muted text-xs">{{ h.changedAt | ecDate: 'datetime' }}</span> · {{ statusLabel(h.fromStatus) }} → <strong>{{ statusLabel(h.toStatus) }}</strong>
                  @if (h.process) { <span class="chip chip-muted ml-1">{{ h.process }}</span> }</li>
              }
            </ol>
          </section>
          @if (auth.has('STUDENT_WRITE')) {
            <section class="rounded-2xl border p-4" style="border-color: var(--line)">
              <p class="label">Cambiar estado</p>
              <div class="flex flex-wrap gap-2">
                <select class="field max-w-[220px]" (change)="newStatus.set($any($event.target).value)">
                  <option value="">Elegir…</option>
                  @for (st of statuses; track st) { <option [value]="st">{{ statusLabel(st) }}</option> }
                </select>
                <input class="field max-w-[200px]" placeholder="Proceso (si aplica)" maxlength="40" (input)="process.set($any($event.target).value)" />
                <button class="btn btn-primary" [disabled]="!newStatus()" (click)="changeStatus(s)">Aplicar</button>
              </div>
              <p class="muted mt-2 text-xs">Solo se permiten transiciones del catálogo; la base rechaza las demás.</p>
            </section>
          }
        </div>
      </aside>
    }
  `,
  styles: `
    .drawer { background: var(--surface); box-shadow: -24px 0 60px -30px rgba(0,0,0,.45); animation: slide .35s cubic-bezier(.2,.8,.2,1); }
    @keyframes slide { from { transform: translateX(100%); } }
    tr.sel td { background: var(--brand-soft); }
  `,
})
export class AdminStudentsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly auth = inject(AuthStore);
  protected readonly statuses = ['ADMITTED', 'ENROLLED', 'REGULAR', 'WITHDRAWN', 'SUSPENDED', 'COMPLETED', 'GRADUATED'];
  protected readonly query = signal('');
  private readonly debounced = toSignal(toObservable(this.query).pipe(debounceTime(300)), { initialValue: '' });
  protected readonly career = signal<string | null>(null);
  protected readonly status = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly selected = signal<StudentSummary | null>(null);
  protected readonly newStatus = signal('');
  protected readonly process = signal('');

  protected readonly careers = rxResource({ stream: () => this.api.get<Page<Career>>('/catalog/careers', { size: 100 }) });
  protected readonly students = rxResource({
    params: () => ({ q: this.debounced(), careerId: this.career(), status: this.status(), page: this.page() }),
    stream: ({ params }) => this.api.get<Page<StudentSummary>>('/admin/academic/students', { ...params, size: 20 }),
  });
  private readonly sid = computed(() => this.selected()?.id);
  protected readonly progress = rxResource({ params: () => this.sid(), stream: ({ params }) => this.api.get<Progress>(`/admin/academic/students/${params}/curriculum`) });
  protected readonly kardex = rxResource({ params: () => this.sid(), stream: ({ params }) => this.api.get<Kardex>(`/admin/students/${params}/kardex`) });
  protected readonly history = rxResource({
    params: () => this.sid(),
    stream: ({ params }) => this.api.get<{ fromStatus: string; toStatus: string; process: string | null; changedAt: string }[]>(`/admin/academic/students/${params}/status-history`),
  });
  protected readonly account = rxResource({
    params: () => (this.auth.has('FINANCE_READ') ? this.sid() : undefined),
    stream: ({ params }) => this.api.get<Statement>(`/admin/finance/students/${params}/account`, { size: 1 }),
  });

  protected readonly statusCounts = computed(() => {
    const subjects = this.progress.value()?.subjects ?? [];
    return (Object.keys(SUBJECT_STATUS) as (keyof typeof SUBJECT_STATUS)[])
      .map((key) => ({ key, ...SUBJECT_STATUS[key], count: subjects.filter((s) => s.status === key).length }))
      .filter((s) => s.count > 0);
  });

  protected select(s: StudentSummary): void {
    this.selected.set(s);
  }

  protected mask(id: string): string {
    return id && id.length > 4 ? '••••••' + id.slice(-4) : id;
  }

  protected statusLabel(s: string | null): string {
    return s ? (STATUS_LABEL[s] ?? s) : '—';
  }

  protected result(r: string): string {
    return RESULT_LABEL[r] ?? r;
  }

  protected overdue(a: Statement): number {
    return a.installments.filter((i) => i.overdue).length;
  }

  protected changeStatus(s: StudentSummary): void {
    this.api.post(`/admin/academic/students/${s.id}/status`, { toStatus: this.newStatus(), process: this.process() || null }).subscribe({
      next: () => {
        this.toasts.ok('Estado actualizado');
        this.history.reload();
        this.students.reload();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }
}
