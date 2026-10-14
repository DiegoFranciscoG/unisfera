import { ChangeDetectionStrategy, Component, OnDestroy, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService, download } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe } from '../../core/format';
import { Page, Problem } from '../../core/models';
import { ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface Definition { code: string; title: string; description: string; needsPeriod: boolean; personalData: boolean }
interface ReportTable { code: string; title: string; cutoff: string; headers: string[]; rows: (string | number | null)[][]; notes: string[] }
interface Job { id: string; report: string; format: string; status: string; createdAt: string; finishedAt: string | null; fileId: string | null; error: string | null }
interface Period { id: string; code: string }

/** Reportes institucionales: vista previa con supresión de celdas pequeñas (RN-23) y exportación asíncrona. */
@Component({
  selector: 'app-admin-reports',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Reportes" title="Información para decidir" subtitle="Las cifras menores al umbral se suprimen para no identificar personas. Las exportaciones se generan en segundo plano." />
    <div class="grid gap-5 xl:grid-cols-[320px_1fr]">
      <section class="space-y-2">
        @for (d of catalog.value() ?? []; track d.code; let i = $index) {
          <button class="item tile w-full p-3 text-left" [class.sel]="selected()?.code === d.code" (click)="pick(d)" [style.--i]="i">
            <p class="font-semibold leading-snug">{{ d.title }}</p>
            <p class="muted text-xs">{{ d.description }}</p>
            @if (d.personalData) { <span class="chip chip-teja mt-2">Contiene datos personales</span> }
          </button>
        }
        @if (jobs().length) {
          <div class="tile mt-4 p-3">
            <p class="label">Exportaciones</p>
            <ul class="space-y-2">
              @for (j of jobs(); track j.id) {
                <li class="flex items-center gap-2 text-sm">
                  <span class="chip" [class.chip-green]="j.status === 'SUCCEEDED'" [class.chip-gold]="j.status === 'QUEUED' || j.status === 'RUNNING'" [class.chip-red]="j.status === 'FAILED'">{{ j.format }}</span>
                  <span class="min-w-0 flex-1 truncate">{{ j.report }} · {{ j.createdAt | ecDate: 'short' }}</span>
                  @if (j.status === 'SUCCEEDED') { <button class="font-bold" style="color: var(--brand)" (click)="fetch(j)"><app-icon name="download" [size]="15" /></button> }
                  @else if (j.status === 'FAILED') { <span class="text-xs" style="color: var(--color-alerta-500)">Error</span> }
                  @else { <span class="spinner"></span> }
                </li>
              }
            </ul>
          </div>
        }
      </section>
      <section>
        @if (selected(); as d) {
          <div class="tile flex flex-wrap items-end gap-3 p-4">
            @if (d.needsPeriod) {
              <div class="min-w-[180px]"><label class="label" for="rp">Período</label>
                <select id="rp" class="field" (change)="periodId.set($any($event.target).value || null)">
                  <option value="">Elegir…</option>@for (p of periods.value()?.items ?? []; track p.id) { <option [value]="p.id">{{ p.code }}</option> }</select></div>
            }
            <div><label class="label" for="co">Fecha de corte</label><input id="co" class="field" type="date" [value]="cutoff()" (input)="cutoff.set($any($event.target).value)" /></div>
            <button class="btn btn-primary" [disabled]="d.needsPeriod && !periodId()" (click)="run(d)"><app-icon name="chart" [size]="16" /> Ver reporte</button>
            @if (auth.has('REPORT_EXPORT')) {
              <button class="btn btn-ghost" [disabled]="d.needsPeriod && !periodId()" (click)="exportAs(d, 'XLSX')"><app-icon name="download" [size]="16" /> Excel</button>
              <button class="btn btn-ghost" [disabled]="d.needsPeriod && !periodId()" (click)="exportAs(d, 'PDF')"><app-icon name="file" [size]="16" /> PDF</button>
            }
          </div>
          @if (loading()) {
            <div class="mt-4"><app-skeleton [count]="5" [height]="36" /></div>
          } @else if (table(); as t) {
            <div class="tile mt-4 overflow-x-auto" style="animation: var(--animate-rise)">
              <div class="flex items-center justify-between border-b p-3" style="border-color: var(--line)">
                <h2 class="text-lg">{{ t.title }}</h2><span class="muted text-xs">Corte {{ t.cutoff }} · {{ t.rows.length }} filas</span>
              </div>
              <table class="table">
                <thead><tr>@for (h of t.headers; track $index) { <th>{{ h }}</th> }</tr></thead>
                <tbody>
                  @for (r of t.rows; track $index) {
                    <tr>@for (c of r; track $index) { <td [class.mono]="isNumber(c)" [class.suppressed]="isSuppressed(c)" [attr.title]="isSuppressed(c) ? 'Cifra suprimida para proteger la identidad (RN-23)' : null">{{ c ?? '—' }}</td> }</tr>
                  } @empty { <tr><td [attr.colspan]="t.headers.length" class="muted py-6 text-center">Sin datos para los filtros elegidos.</td></tr> }
                </tbody>
              </table>
              @if (t.notes.length) { <ul class="muted space-y-1 border-t p-3 text-xs" style="border-color: var(--line)">@for (n of t.notes; track $index) { <li>• {{ n }}</li> }</ul> }
            </div>
          }
        } @else {
          <app-empty icon="file" title="Elige un reporte" text="Matrícula, aprobación, deserción, cartera, recaudación, becas, ocupación, cohortes y organismos de control." />
        }
      </section>
    </div>
  `,
  styles: `
    .item { transition: transform .2s, border-color .2s; animation: var(--animate-rise); animation-delay: calc(var(--i) * 35ms); }
    .item:hover { transform: translateX(3px); }
    .item.sel { border-color: var(--color-cupula-400); box-shadow: 0 0 0 3px var(--brand-soft); }
    .suppressed { color: var(--muted); font-style: italic; font-size: .78rem; }
    .spinner { width: .9rem; height: .9rem; border-radius: 999px; border: 2px solid var(--line); border-top-color: var(--brand); animation: spin .7s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
  `,
})
export class AdminReportsPage implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly auth = inject(AuthStore);
  protected readonly catalog = rxResource({ stream: () => this.api.get<Definition[]>('/reports') });
  protected readonly periods = rxResource({ stream: () => this.api.get<Page<Period>>('/catalog/periods', { size: 30 }) });
  protected readonly selected = signal<Definition | null>(null);
  protected readonly periodId = signal<string | null>(null);
  protected readonly cutoff = signal(new Date().toISOString().slice(0, 10));
  protected readonly table = signal<ReportTable | null>(null);
  protected readonly loading = signal(false);
  protected readonly jobs = signal<Job[]>([]);
  private timer: ReturnType<typeof setInterval> | undefined;

  constructor() {
    if (this.auth.has('REPORT_EXPORT')) {
      this.refreshJobs();
    }
  }

  protected pick(d: Definition): void {
    this.selected.set(d);
    this.table.set(null);
  }

  protected run(d: Definition): void {
    this.loading.set(true);
    this.api.get<ReportTable>(`/reports/${d.code}`, { periodId: this.periodId(), cutoff: this.cutoff() }).subscribe({
      next: (t) => {
        this.loading.set(false);
        this.table.set(t);
      },
      error: (p: Problem) => {
        this.loading.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected exportAs(d: Definition, format: 'XLSX' | 'PDF'): void {
    const params = new URLSearchParams({ format, cutoff: this.cutoff() });
    if (this.periodId()) {
      params.set('periodId', this.periodId()!);
    }
    this.api.post<{ jobId: string }>(`/reports/${d.code}/jobs?${params}`).subscribe({
      next: () => {
        this.toasts.info('Exportación en cola', 'Te avisaremos cuando el archivo esté listo.');
        this.refreshJobs();
        this.poll();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }

  protected fetch(j: Job): void {
    this.api.blob(`/reports/jobs/${j.id}/file`).subscribe({
      next: (b) => download(b, `unisfera-${j.report}.${j.format === 'PDF' ? 'pdf' : 'xlsx'}`),
      error: (p) => this.toasts.problem(p),
    });
  }

  protected isSuppressed(v: unknown): boolean {
    return typeof v === 'string' && v.startsWith('<');
  }

  protected isNumber(v: unknown): boolean {
    return typeof v === 'number';
  }

  private refreshJobs(): void {
    this.api.get<Job[]>('/reports/jobs').subscribe((list) => {
      this.jobs.set(list.slice(0, 8));
      if (!list.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING')) {
        clearInterval(this.timer);
        this.timer = undefined;
      }
    });
  }

  private poll(): void {
    if (!this.timer) {
      this.timer = setInterval(() => this.refreshJobs(), 2000);
    }
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }
}
