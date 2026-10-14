import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService, download } from '../../core/api.service';
import { EcDatePipe, MoneyPipe } from '../../core/format';
import { PaymentView, Problem, Statement } from '../../core/models';
import { HelpService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { CountUpComponent, EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

const ENTRY_LABEL: Record<string, string> = {
  CHARGE: 'Cargo',
  LATE_FEE: 'Interés por mora',
  DISCOUNT: 'Descuento / beca',
  PAYMENT: 'Pago',
  CREDIT_NOTE: 'Nota de crédito',
  REVERSAL: 'Reverso',
  ADJUSTMENT: 'Ajuste',
};

/** Estado de cuenta: saldo, cuotas, movimientos del libro mayor y pago en línea idempotente. */
@Component({
  selector: 'app-account-page',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, CountUpComponent, MoneyPipe, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Cuenta y pagos" title="Tu estado de cuenta" subtitle="Cada valor viene del libro contable: nada se borra, todo se compensa con un movimiento.">
      <button class="btn btn-ghost" (click)="help.show('como-pago-en-linea')"><app-icon name="help" [size]="16" /> Cómo pagar</button>
    </app-page-header>

    @if (account.value(); as a) {
      <div class="grid gap-5 lg:grid-cols-[1.2fr_1fr]">
        <section class="balance tile relative overflow-hidden p-6" style="animation: var(--animate-rise)">
          <p class="kicker !text-[var(--color-oro-300)]">Saldo pendiente</p>
          <p class="font-display mt-2 text-5xl text-white"><app-count-up [value]="a.summary.balance" [decimals]="2" prefix="$" /></p>
          @if (a.summary.balance > 0) {
            <div class="mt-5 flex flex-wrap items-end gap-3">
              <div class="min-w-[160px] flex-1">
                <label class="label !text-white/70" for="amount">Monto a pagar</label>
                <input id="amount" class="field !bg-white/10 !text-white" type="number" step="0.01" min="0.01" [max]="a.summary.balance"
                  [value]="amount() ?? a.summary.balance" (input)="amount.set($any($event.target).valueAsNumber || null)" />
              </div>
              <button class="btn btn-teja !py-3" [disabled]="paying()" (click)="pay(a.summary.balance)">
                <app-icon name="card" [size]="17" /> {{ paying() ? 'Abriendo pasarela…' : 'Pagar en línea' }}
              </button>
            </div>
            <p class="mt-3 text-xs text-white/60">El pago se aplica primero a intereses y luego a la cuota más antigua (RN-26). Recibirás tu factura electrónica.</p>
          } @else {
            <p class="mt-3 flex items-center gap-2 text-white/80"><app-icon name="check" [size]="18" /> Estás al día. ¡Gracias!</p>
          }
          <div class="coins" aria-hidden="true"><app-icon name="coins" [size]="180" [stroke]="0.8" /></div>
        </section>

        <section class="tile p-5" style="animation: var(--animate-rise); animation-delay: 80ms">
          <p class="kicker">Resumen</p>
          <dl class="mt-3 space-y-2 text-sm">
            <div class="flex justify-between"><dt class="muted">Cargos</dt><dd class="mono">{{ a.summary.charges | money }}</dd></div>
            <div class="flex justify-between"><dt class="muted">Intereses por mora</dt><dd class="mono">{{ a.summary.lateFees | money }}</dd></div>
            <div class="flex justify-between"><dt class="muted">Descuentos y becas</dt><dd class="mono" style="color: var(--color-rio-600)">−{{ a.summary.discounts | money }}</dd></div>
            <div class="flex justify-between"><dt class="muted">Pagos</dt><dd class="mono" style="color: var(--color-rio-600)">−{{ a.summary.payments | money }}</dd></div>
            <div class="flex justify-between"><dt class="muted">Notas de crédito</dt><dd class="mono">−{{ a.summary.creditNotes | money }}</dd></div>
            <div class="flex justify-between"><dt class="muted">Reversos</dt><dd class="mono">{{ a.summary.reversals | money }}</dd></div>
            <div class="flex justify-between border-t pt-2 font-bold" style="border-color: var(--line)"><dt>Saldo</dt><dd class="mono">{{ a.summary.balance | money }}</dd></div>
          </dl>
          <p class="mt-3 flex items-center gap-2 text-xs" [style.color]="a.summary.balances ? 'var(--color-rio-600)' : 'var(--color-alerta-500)'">
            <app-icon [name]="a.summary.balances ? 'check' : 'alert'" [size]="14" /> {{ a.summary.balances ? 'El libro cuadra con tus cuotas' : 'Descuadre detectado: contacta a Financiero' }}
          </p>
        </section>
      </div>

      <!-- Cuotas -->
      <section class="mt-6">
        <h2 class="mb-3 text-xl">Cuotas</h2>
        <ol class="timeline">
          @for (i of a.installments; track i.id; let idx = $index) {
            <li class="tile" [class.paid]="i.outstanding <= 0" [class.overdue]="i.overdue" [style.--i]="idx">
              <span class="dot"><app-icon [name]="i.outstanding <= 0 ? 'check' : i.overdue ? 'alert' : 'clock'" [size]="14" /></span>
              <div class="flex flex-1 flex-wrap items-center justify-between gap-2">
                <div>
                  <p class="font-bold">{{ i.periodCode }} · Cuota {{ i.number }}</p>
                  <p class="muted text-xs">Vence {{ i.dueDate | ecDate }} @if (i.lateFee > 0) { · interés {{ i.lateFee | money }} }</p>
                </div>
                <div class="text-right">
                  <p class="mono font-bold">{{ i.outstanding > 0 ? (i.outstanding | money) : 'Pagada' }}</p>
                  <p class="muted text-xs">de {{ i.amountDue | money }}</p>
                </div>
              </div>
            </li>
          } @empty {
            <li class="muted">No tienes cuotas registradas.</li>
          }
        </ol>
      </section>

      <div class="mt-6 grid gap-5 lg:grid-cols-2">
        <section class="tile p-5">
          <h2 class="text-xl">Pagos y facturas</h2>
          <ul class="mt-3 divide-y" style="border-color: var(--line)">
            @for (p of payments.value() ?? []; track p.id) {
              <li class="flex flex-wrap items-center gap-3 py-3">
                <span class="chip" [class.chip-green]="p.status === 'CONFIRMED'" [class.chip-gold]="p.status === 'PENDING'" [class.chip-red]="p.status === 'REJECTED' || p.status === 'ANNULLED'">{{ paymentLabel(p.status) }}</span>
                <div class="min-w-0 flex-1">
                  <p class="mono font-bold">{{ p.amount | money }}</p>
                  <p class="muted truncate text-xs">{{ p.createdAt | ecDate: 'datetime' }} · {{ methodLabel(p.method) }}</p>
                </div>
                @if (p.invoiceId) {
                  <button class="btn btn-ghost !px-3 !py-1.5 text-xs" (click)="ride(p)"><app-icon name="file" [size]="14" /> Factura</button>
                } @else if (p.status === 'PENDING' && p.checkoutUrl) {
                  <a class="btn btn-ghost !px-3 !py-1.5 text-xs" [href]="p.checkoutUrl">Continuar pago</a>
                }
              </li>
            } @empty {
              <li class="muted py-4 text-sm">Aún no registras pagos.</li>
            }
          </ul>
        </section>
        <section class="tile p-5">
          <h2 class="text-xl">Movimientos</h2>
          <ul class="mt-3 space-y-2">
            @for (e of a.entries.items; track e.id) {
              <li class="flex items-center gap-3 text-sm">
                <span class="grid h-8 w-8 shrink-0 place-items-center rounded-full" [style.background]="e.direction > 0 ? 'var(--color-teja-100)' : 'var(--color-rio-100)'"
                  [style.color]="e.direction > 0 ? 'var(--color-teja-700)' : 'var(--color-rio-700)'"><app-icon [name]="e.direction > 0 ? 'plus' : 'check'" [size]="14" /></span>
                <div class="min-w-0 flex-1">
                  <p class="truncate font-semibold">{{ e.description }}</p>
                  <p class="muted text-xs">{{ entryLabel(e.type) }} · {{ e.occurredAt | ecDate }}</p>
                </div>
                <span class="mono font-bold">{{ e.direction > 0 ? '+' : '−' }}{{ e.amount | money }}</span>
              </li>
            }
          </ul>
          @if (a.entries.total > a.entries.items.length) {
            <button class="btn btn-ghost mt-3 w-full" (click)="more()">Ver más movimientos</button>
          }
        </section>
      </div>
    } @else if (account.error()) {
      <app-empty icon="wallet" title="No pudimos cargar tu cuenta" text="Intenta de nuevo en unos segundos." />
    } @else {
      <app-skeleton [count]="3" [height]="140" />
    }
  `,
  styles: `
    .balance { background: radial-gradient(130% 140% at 100% 0%, var(--color-cupula-500), var(--color-cupula-900) 70%); border: none; color: white; }
    .coins { position: absolute; right: -30px; bottom: -40px; color: white; opacity: .08; }
    .timeline { display: grid; gap: .6rem; }
    .timeline li { display: flex; gap: .8rem; align-items: center; padding: .8rem 1rem; animation: var(--animate-rise); animation-delay: calc(var(--i) * 50ms); border-left: 4px solid var(--color-cupula-300); }
    .timeline li.paid { border-left-color: var(--color-rio-500); opacity: .75; }
    .timeline li.overdue { border-left-color: var(--color-teja-500); }
    .dot { display: grid; place-items: center; width: 1.8rem; height: 1.8rem; border-radius: 999px; background: var(--surface-2); flex-shrink: 0; }
    .paid .dot { background: var(--color-rio-500); color: white; }
    .overdue .dot { background: var(--color-teja-500); color: white; }
  `,
})
export class AccountPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly help = inject(HelpService);
  private readonly pageSize = signal(10);
  protected readonly account = rxResource({ params: () => this.pageSize(), stream: ({ params }) => this.api.get<Statement>('/me/account', { size: params }) });
  protected readonly payments = rxResource({ stream: () => this.api.get<PaymentView[]>('/me/payments') });
  protected readonly amount = signal<number | null>(null);
  protected readonly paying = signal(false);
  /** La misma clave viaja en reintentos: si el usuario hace doble clic, el servidor devuelve el mismo intento (RN-24). */
  private idempotencyKey = crypto.randomUUID();

  protected readonly hasPending = computed(() => (this.payments.value() ?? []).some((p) => p.status === 'PENDING'));

  protected pay(balance: number): void {
    const amount = Math.min(this.amount() ?? balance, balance);
    this.paying.set(true);
    this.api.post<PaymentView>('/me/payments/intents', { amount }, { 'Idempotency-Key': this.idempotencyKey }).subscribe({
      next: (p) => {
        this.idempotencyKey = crypto.randomUUID();
        if (p.checkoutUrl) {
          window.location.assign(p.checkoutUrl);
        } else {
          this.paying.set(false);
          this.payments.reload();
        }
      },
      error: (e: Problem) => {
        this.paying.set(false);
        this.toasts.problem(e);
      },
    });
  }

  protected ride(p: PaymentView): void {
    this.api.blob(`/me/invoices/${p.invoiceId}/ride`).subscribe({
      next: (b) => download(b, `factura-${p.reference}.pdf`),
      error: (e) => this.toasts.problem(e),
    });
  }

  protected more(): void {
    this.pageSize.update((s) => s + 20);
  }

  protected entryLabel(type: string): string {
    return ENTRY_LABEL[type] ?? type;
  }

  protected paymentLabel(status: string): string {
    return { CONFIRMED: 'Confirmado', PENDING: 'Pendiente', REJECTED: 'Rechazado', ANNULLED: 'Anulado' }[status] ?? status;
  }

  protected methodLabel(method: string): string {
    return { GATEWAY: 'En línea', TRANSFER: 'Transferencia', CASH: 'Efectivo', CARD_POS: 'Tarjeta (POS)' }[method] ?? method;
  }
}
