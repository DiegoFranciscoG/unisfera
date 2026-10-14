import { ChangeDetectionStrategy, Component, OnDestroy, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { MoneyPipe } from '../../core/format';
import { PaymentView, Problem } from '../../core/models';
import { CelebrationService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';

interface SessionInfo {
  reference: string;
  amount: number;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
}

/**
 * Formulario alojado de la pasarela SIMULADA. No pide datos de tarjeta: el evaluador elige el
 * escenario (aprobar, rechazar, demorar la notificación o notificar dos veces) para comprobar la
 * idempotencia y la conciliación (RN-24, RN-25).
 */
@Component({
  selector: 'app-gateway-page',
  imports: [RouterLink, IconComponent, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="gw grid min-h-screen place-items-center px-4 py-10">
      <div class="card w-full max-w-md overflow-hidden rounded-3xl" style="animation: var(--animate-rise)">
        <header class="flex items-center justify-between px-6 py-4 text-white">
          <span class="flex items-center gap-2 font-bold"><app-icon name="lock" [size]="16" /> PagoSeguro · simulador</span>
          <span class="rounded-full bg-white/15 px-2 py-0.5 text-[11px] font-bold uppercase tracking-wider">Demo</span>
        </header>
        <div class="body p-6">
          @if (info.value(); as s) {
            <p class="muted text-sm">Pago a</p>
            <p class="text-lg font-bold">Universidad UNISFERA</p>
            <p class="font-display mt-4 text-5xl">{{ s.amount | money }}</p>
            <p class="mono muted mt-1 break-all text-xs">Ref. {{ s.reference }}</p>

            @switch (state()) {
              @case ('choose') {
                <p class="mt-6 text-sm font-semibold">Elija cómo responde la pasarela:</p>
                <div class="mt-3 grid grid-cols-2 gap-2">
                  <button class="opt ok" (click)="simulate('APPROVE')"><app-icon name="check" [size]="18" /> Aprobar</button>
                  <button class="opt bad" (click)="simulate('REJECT')"><app-icon name="x" [size]="18" /> Rechazar</button>
                  <button class="opt" (click)="simulate('DELAY')"><app-icon name="clock" [size]="18" /> Aprobar sin aviso</button>
                  <button class="opt" (click)="simulate('DOUBLE')"><app-icon name="refresh" [size]="18" /> Aviso duplicado</button>
                </div>
                <p class="muted mt-4 text-xs">«Aprobar sin aviso» simula una pasarela que no notifica: UNISFERA lo resuelve consultando el estado.
                  «Aviso duplicado» envía la misma notificación dos veces: el pago se aplica una sola vez.</p>
              }
              @case ('waiting') {
                <div class="mt-8 flex flex-col items-center gap-3 py-4">
                  <span class="loader"></span>
                  <p class="font-semibold">Procesando con el banco…</p>
                  <p class="muted text-xs">Esperando la confirmación de UNISFERA</p>
                </div>
              }
              @case ('done') {
                <div class="mt-6 text-center">
                  <span class="result mx-auto grid h-16 w-16 place-items-center rounded-full" [class.ok]="confirmed()" [class.bad]="!confirmed()">
                    <app-icon [name]="confirmed() ? 'check' : 'x'" [size]="30" />
                  </span>
                  <p class="font-display mt-3 text-2xl">{{ confirmed() ? 'Pago aprobado' : outcomeText() }}</p>
                  @if (invoiceReady()) { <p class="muted text-sm">Tu factura electrónica ya está disponible.</p> }
                  <a routerLink="/estudiante/cuenta" class="btn btn-primary mt-5 w-full">Volver a UNISFERA</a>
                </div>
              }
            }
          } @else if (info.error()) {
            <p class="font-bold">Sesión de pago no encontrada o vencida.</p>
            <a routerLink="/estudiante/cuenta" class="btn btn-ghost mt-4">Volver</a>
          } @else {
            <div class="skeleton h-40"></div>
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .gw { background: linear-gradient(160deg, #0f2a2a, #12162b); }
    .card { background: var(--surface); box-shadow: 0 40px 80px -30px rgba(0,0,0,.6); }
    .card header { background: linear-gradient(120deg, #1f6157, #2e8c7e); }
    .opt { display: flex; align-items: center; gap: .5rem; justify-content: center; padding: .8rem; border-radius: 14px; font-weight: 700; font-size: .88rem;
      border: 1.5px solid var(--line); background: var(--surface); transition: transform .15s, border-color .2s; }
    .opt:hover { transform: translateY(-2px); border-color: var(--color-rio-500); }
    .opt.ok { background: var(--color-rio-500); color: white; border-color: transparent; }
    .opt.bad { color: var(--color-alerta-500); }
    .loader { width: 44px; height: 44px; border-radius: 999px; border: 4px solid var(--surface-2); border-top-color: var(--color-rio-500); animation: spin .8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .result { animation: var(--animate-stamp); color: white; }
    .result.ok { background: var(--color-rio-500); }
    .result.bad { background: var(--color-alerta-500); }
  `,
})
export class GatewayPage implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthStore);
  private readonly toasts = inject(ToastService);
  private readonly celebration = inject(CelebrationService);
  readonly reference = input.required<string>();
  protected readonly info = rxResource({ params: () => this.reference(), stream: ({ params }) => this.api.get<SessionInfo>(`/public/gateway/simulated/${params}`) });
  protected readonly state = signal<'choose' | 'waiting' | 'done'>('choose');
  protected readonly confirmed = signal(false);
  protected readonly invoiceReady = signal(false);
  protected readonly outcomeText = signal('Pago rechazado');
  private timer: ReturnType<typeof setInterval> | undefined;
  private polls = 0;

  protected simulate(scenario: 'APPROVE' | 'REJECT' | 'DELAY' | 'DOUBLE'): void {
    this.state.set('waiting');
    this.api.post<{ outcome: string }>(`/public/gateway/simulated/${this.reference()}/pay`, { scenario }).subscribe({
      next: () => this.poll(),
      error: (p: Problem) => {
        this.state.set('choose');
        this.toasts.problem(p);
      },
    });
  }

  private poll(): void {
    this.polls = 0;
    this.timer = setInterval(() => {
      this.polls++;
      if (!this.auth.isLoggedIn()) {
        this.finish(null);
        return;
      }
      this.api.get<PaymentView[]>('/me/payments').subscribe((list) => {
        const payment = list.find((p) => p.reference === this.reference());
        if (payment && payment.status !== 'PENDING') {
          this.finish(payment);
        } else if (this.polls > 40) {
          this.outcomeText.set('Pago en verificación');
          this.finish(null);
        }
      });
    }, 1500);
  }

  private finish(payment: PaymentView | null): void {
    clearInterval(this.timer);
    this.confirmed.set(payment?.status === 'CONFIRMED');
    this.invoiceReady.set(!!payment?.invoiceId);
    this.state.set('done');
    if (payment?.status === 'CONFIRMED') {
      this.celebration.burst();
    }
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }
}
