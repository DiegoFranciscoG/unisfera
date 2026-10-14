import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { debounceTime } from 'rxjs';
import { ApiService, download } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe, MoneyPipe } from '../../core/format';
import { Page, PaymentView, Problem, Statement } from '../../core/models';
import { ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent } from '../../shared/widgets';

interface StudentHit {
  id: string;
  code: string;
  fullName: string;
  careerName: string;
}

interface TariffView {
  id: string;
  conceptCode: string;
  conceptName: string;
  careerName: string | null;
  levelNumber: number | null;
  periodCode: string;
  calcMode: string;
  amount: number;
  tariffVersion: number;
  status: string;
}

interface Reconciliation {
  id: string;
  totalRows: number;
  matched: number;
  discrepancies: number;
  notes: string[];
}

/** Financiero: cuenta de un estudiante, pagos presenciales, anulaciones con nota de crédito, tarifario y conciliación. */
@Component({
  selector: 'app-admin-finance',
  imports: [IconComponent, PageHeaderComponent, EmptyComponent, MoneyPipe, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Financiero" title="Cartera, pagos y facturación" subtitle="Todo movimiento es un asiento: los errores se corrigen con reversos y notas de crédito, nunca borrando.">
      @if (auth.has('PAYMENT_MANAGE')) {
        <button class="btn btn-ghost" (click)="runLateFees()"><app-icon name="clock" [size]="16" /> Calcular mora de hoy</button>
      }
    </app-page-header>

    <div class="tabs mb-5">
      <button [class.on]="tab() === 'students'" (click)="tab.set('students')"><app-icon name="user" [size]="15" /> Estudiante</button>
      <button [class.on]="tab() === 'tariffs'" (click)="tab.set('tariffs')"><app-icon name="coins" [size]="15" /> Tarifario</button>
      @if (auth.has('RECONCILIATION_MANAGE')) { <button [class.on]="tab() === 'reconcile'" (click)="tab.set('reconcile')"><app-icon name="refresh" [size]="15" /> Conciliación</button> }
    </div>

    @switch (tab()) {
      @case ('students') {
        <div class="grid gap-5 xl:grid-cols-[340px_1fr]">
          <section>
            <div class="relative">
              <span class="muted absolute left-3 top-1/2 -translate-y-1/2"><app-icon name="search" [size]="17" /></span>
              <input class="field !pl-10" placeholder="Buscar estudiante" [value]="query()" (input)="query.set($any($event.target).value)" aria-label="Buscar estudiante" />
            </div>
            <ul class="mt-3 space-y-2">
              @for (s of hits.value()?.items ?? []; track s.id) {
                <li><button class="hit tile w-full p-3 text-left" [class.sel]="student()?.id === s.id" (click)="student.set(s)">
                  <span class="mono muted text-[11px] font-bold">{{ s.code }}</span><span class="block font-semibold">{{ s.fullName }}</span><span class="muted text-xs">{{ s.careerName }}</span>
                </button></li>
              }
            </ul>
          </section>
          <section>
            @if (student(); as s) {
              @if (account.value(); as a) {
                <div class="grid gap-3 md:grid-cols-3">
                  <div class="tile p-4"><p class="label">Saldo</p><p class="font-display text-3xl">{{ a.summary.balance | money }}</p></div>
                  <div class="tile p-4"><p class="label">Pagado</p><p class="font-display text-3xl">{{ a.summary.payments | money }}</p></div>
                  <div class="tile p-4"><p class="label">Becas/descuentos</p><p class="font-display text-3xl">{{ a.summary.discounts | money }}</p></div>
                </div>
              }
              @if (auth.has('PAYMENT_MANAGE')) {
                <form class="tile mt-4 grid gap-3 p-4 md:grid-cols-[1fr_1fr_1.4fr_auto] md:items-end" (submit)="$event.preventDefault(); register(s)">
                  <div><label class="label" for="amt">Monto</label><input id="amt" class="field" type="number" min="0.01" step="0.01" [value]="amount() ?? ''" (input)="amount.set($any($event.target).valueAsNumber || null)" /></div>
                  <div><label class="label" for="method">Medio</label>
                    <select id="method" class="field" (change)="method.set($any($event.target).value)">
                      <option value="CASH">Efectivo</option><option value="TRANSFER">Transferencia</option><option value="CARD_POS">Tarjeta (POS)</option>
                    </select></div>
                  <div><label class="label" for="ref">Referencia</label><input id="ref" class="field" maxlength="120" [value]="reference()" (input)="reference.set($any($event.target).value)" placeholder="N.º de comprobante" /></div>
                  <button class="btn btn-primary" [disabled]="!amount() || !reference().trim() || busy()">Registrar pago</button>
                </form>
              }
              <div class="tile mt-4 overflow-x-auto">
                <table class="table">
                  <thead><tr><th>Fecha</th><th>Monto</th><th>Medio</th><th>Estado</th><th></th></tr></thead>
                  <tbody>
                    @for (p of payments.value() ?? []; track p.id) {
                      <tr>
                        <td class="text-xs">{{ p.createdAt | ecDate: 'datetime' }}</td>
                        <td class="mono font-bold">{{ p.amount | money }}</td>
                        <td>{{ p.method }}</td>
                        <td><span class="chip" [class.chip-green]="p.status === 'CONFIRMED'" [class.chip-gold]="p.status === 'PENDING'" [class.chip-red]="p.status === 'REJECTED' || p.status === 'ANNULLED'">{{ p.status }}</span></td>
                        <td class="whitespace-nowrap text-right">
                          @if (p.invoiceId) { <button class="btn btn-ghost !px-2.5 !py-1 text-xs" (click)="ride(p)"><app-icon name="file" [size]="13" /> RIDE</button> }
                          @if (p.status === 'CONFIRMED' && auth.has('PAYMENT_MANAGE')) { <button class="btn btn-ghost !px-2.5 !py-1 text-xs" style="color: var(--color-alerta-500)" (click)="annulling.set(p)">Anular</button> }
                        </td>
                      </tr>
                    } @empty {
                      <tr><td colspan="5" class="muted py-6 text-center">Sin pagos registrados.</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <app-empty icon="wallet" title="Busca un estudiante" text="Verás su saldo, pagos y facturas." />
            }
          </section>
        </div>
      }
      @case ('tariffs') {
        <div class="tile overflow-x-auto">
          <table class="table">
            <thead><tr><th>Concepto</th><th>Período</th><th>Carrera</th><th>Nivel</th><th>Cálculo</th><th>Valor</th><th>Versión</th></tr></thead>
            <tbody>
              @for (t of tariffs.value()?.items ?? []; track t.id) {
                <tr [class.opacity-50]="t.status !== 'ACTIVE'">
                  <td class="font-semibold">{{ t.conceptName }}</td><td class="mono text-xs">{{ t.periodCode }}</td><td>{{ t.careerName ?? 'Todas' }}</td>
                  <td>{{ t.levelNumber ?? 'Todos' }}</td><td>{{ calcLabel(t.calcMode) }}</td><td class="mono font-bold">{{ t.amount | money }}</td>
                  <td><span class="chip" [class.chip-green]="t.status === 'ACTIVE'" [class.chip-muted]="t.status !== 'ACTIVE'">v{{ t.tariffVersion }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="muted mt-3 text-sm">Las tarifas se versionan: publicar un nuevo valor reemplaza la versión vigente sin alterar lo ya facturado.</p>
      }
      @case ('reconcile') {
        <section class="tile p-5">
          <h2 class="text-xl">Conciliar extracto bancario o de pasarela</h2>
          <p class="muted text-sm">CSV con encabezado y columnas <span class="mono">referencia;monto</span>. Cada línea se compara con los pagos confirmados.</p>
          <div class="mt-4 flex flex-wrap items-end gap-3">
            <div><label class="label" for="src">Origen</label><select id="src" class="field" (change)="source.set($any($event.target).value)"><option value="BANK">Banco</option><option value="GATEWAY">Pasarela</option></select></div>
            <div><label class="label" for="sd">Fecha del extracto</label><input id="sd" type="date" class="field" [value]="statementDate()" (input)="statementDate.set($any($event.target).value)" /></div>
            <label class="btn btn-primary"><app-icon name="upload" [size]="16" /> Subir CSV<input type="file" class="sr-only" accept=".csv,text/csv" (change)="reconcile($event)" /></label>
          </div>
          @if (reconciliation(); as r) {
            <div class="mt-5 grid grid-cols-3 gap-3 text-center" style="animation: var(--animate-rise)">
              <div class="rounded-xl p-3" style="background: var(--surface-2)"><p class="label">Líneas</p><p class="font-display text-3xl">{{ r.totalRows }}</p></div>
              <div class="rounded-xl p-3" style="background: var(--color-rio-100); color: var(--color-rio-700)"><p class="label !text-current">Conciliadas</p><p class="font-display text-3xl">{{ r.matched }}</p></div>
              <div class="rounded-xl p-3" style="background: var(--color-teja-100); color: var(--color-teja-700)"><p class="label !text-current">Diferencias</p><p class="font-display text-3xl">{{ r.discrepancies }}</p></div>
            </div>
            <ul class="mt-3 space-y-1 text-sm">@for (n of r.notes; track $index) { <li>• {{ n }}</li> }</ul>
          }
        </section>
      }
    }

    @if (annulling(); as p) {
      <div class="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" (click)="annulling.set(null)">
        <div class="tile w-full max-w-md p-6" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" style="animation: var(--animate-rise)">
          <p class="kicker">Anular pago (RN-28)</p>
          <h2 class="mt-1 text-2xl">{{ p.amount | money }}</h2>
          <p class="muted mt-1 text-sm">Se emitirá una nota de crédito y un reverso en el libro. La operación queda auditada.</p>
          <textarea class="field mt-4" rows="3" maxlength="500" placeholder="Motivo (mínimo 10 caracteres)" [value]="annulReason()" (input)="annulReason.set($any($event.target).value)"></textarea>
          <div class="mt-4 flex justify-end gap-2">
            <button class="btn btn-ghost" (click)="annulling.set(null)">Cancelar</button>
            <button class="btn btn-teja" [disabled]="annulReason().trim().length < 10" (click)="annul(p)">Anular pago</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .tabs { display: inline-flex; gap: 4px; padding: 4px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--line); }
    .tabs button { display: inline-flex; gap: .35rem; align-items: center; padding: .45rem .9rem; border-radius: 999px; font-weight: 700; font-size: .85rem; color: var(--muted); }
    .tabs button.on { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-tile); }
    .hit { transition: border-color .2s, transform .2s; }
    .hit:hover { transform: translateX(3px); }
    .hit.sel { border-color: var(--color-cupula-400); box-shadow: 0 0 0 3px var(--brand-soft); }
  `,
})
export class AdminFinancePage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly auth = inject(AuthStore);
  protected readonly tab = signal<'students' | 'tariffs' | 'reconcile'>('students');
  protected readonly query = signal('');
  private readonly debounced = toSignal(toObservable(this.query).pipe(debounceTime(300)), { initialValue: '' });
  protected readonly student = signal<StudentHit | null>(null);
  protected readonly amount = signal<number | null>(null);
  protected readonly method = signal('CASH');
  protected readonly reference = signal('');
  protected readonly busy = signal(false);
  protected readonly annulling = signal<PaymentView | null>(null);
  protected readonly annulReason = signal('');
  protected readonly source = signal('BANK');
  protected readonly statementDate = signal(new Date().toISOString().slice(0, 10));
  protected readonly reconciliation = signal<Reconciliation | null>(null);
  private idempotencyKey = crypto.randomUUID();

  protected readonly hits = rxResource({
    params: () => (this.debounced().trim().length >= 2 ? this.debounced().trim() : undefined),
    stream: ({ params }) => this.api.get<Page<StudentHit>>('/admin/academic/students', { q: params, size: 8 }),
  });
  protected readonly account = rxResource({ params: () => this.student()?.id, stream: ({ params }) => this.api.get<Statement>(`/admin/finance/students/${params}/account`, { size: 1 }) });
  protected readonly payments = rxResource({ params: () => this.student()?.id, stream: ({ params }) => this.api.get<PaymentView[]>(`/admin/finance/students/${params}/payments`) });
  protected readonly tariffs = rxResource({
    params: () => (this.tab() === 'tariffs' ? 'load' : undefined),
    stream: () => this.api.get<Page<TariffView>>('/admin/finance/tariffs', { size: 100 }),
  });

  protected calcLabel(mode: string): string {
    return { FLAT: 'Valor fijo', PER_CREDIT: 'Por crédito', PER_SUBJECT: 'Por asignatura' }[mode] ?? mode;
  }

  protected register(s: StudentHit): void {
    this.busy.set(true);
    this.api
      .post<PaymentView>('/admin/finance/payments', { studentId: s.id, amount: this.amount(), method: this.method(), reference: this.reference().trim() },
        { 'Idempotency-Key': this.idempotencyKey })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.idempotencyKey = crypto.randomUUID();
          this.amount.set(null);
          this.reference.set('');
          this.toasts.ok('Pago registrado', 'Se emitió la factura electrónica.');
          this.account.reload();
          this.payments.reload();
        },
        error: (p: Problem) => {
          this.busy.set(false);
          this.toasts.problem(p);
        },
      });
  }

  protected annul(p: PaymentView): void {
    this.api.post(`/admin/finance/payments/${p.id}/annul`, { reason: this.annulReason().trim() }).subscribe({
      next: () => {
        this.annulling.set(null);
        this.annulReason.set('');
        this.toasts.ok('Pago anulado', 'Nota de crédito emitida.');
        this.account.reload();
        this.payments.reload();
      },
      error: (e: Problem) => this.toasts.problem(e),
    });
  }

  protected ride(p: PaymentView): void {
    this.api.blob(`/admin/finance/invoices/${p.invoiceId}/ride`).subscribe({ next: (b) => download(b, `ride-${p.reference}.pdf`), error: (e) => this.toasts.problem(e) });
  }

  protected runLateFees(): void {
    this.api.post<{ installments: number }>('/admin/finance/late-fees/run').subscribe({
      next: (r) => this.toasts.ok('Mora calculada', `${r.installments} cuotas actualizadas.`),
      error: (e: Problem) => this.toasts.problem(e),
    });
  }

  protected reconcile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    const form = new FormData();
    form.append('file', file);
    this.api.upload<Reconciliation>(`/admin/finance/reconciliations?source=${this.source()}&statementDate=${this.statementDate()}`, form).subscribe({
      next: (r) => this.reconciliation.set(r),
      error: (e: Problem) => this.toasts.problem(e),
    });
  }
}
