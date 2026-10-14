import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { EcDatePipe } from '../../core/format';
import { Page, Problem } from '../../core/models';
import { HelpService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

export interface RequestView {
  id: string;
  studentId: string;
  studentName: string;
  type: string;
  subject: string;
  detail: string;
  status: string;
  createdAt: string;
  dueAt: string | null;
  overdue: boolean;
  resolution: string | null;
  events: { fromStatus: string | null; toStatus: string; note: string | null; occurredAt: string }[];
}

export const REQUEST_TYPES: { value: string; label: string; icon: string }[] = [
  { value: 'CERTIFICATE', label: 'Certificado', icon: 'file' },
  { value: 'WITHDRAWAL', label: 'Retiro', icon: 'logout' },
  { value: 'HOMOLOGATION', label: 'Homologación', icon: 'refresh' },
  { value: 'THIRD_ENROLLMENT', label: 'Tercera matrícula', icon: 'flag' },
  { value: 'SPECIAL_ENROLLMENT', label: 'Matrícula especial', icon: 'calendar' },
  { value: 'RECTIFICATION', label: 'Rectificación de nota', icon: 'edit' },
  { value: 'OTHER', label: 'Otro trámite', icon: 'inbox' },
];

export const REQUEST_STATUS: Record<string, { label: string; chip: string }> = {
  SUBMITTED: { label: 'Recibida', chip: 'chip-blue' },
  IN_REVIEW: { label: 'En revisión', chip: 'chip-gold' },
  APPROVED: { label: 'Aprobada', chip: 'chip-green' },
  REJECTED: { label: 'Negada', chip: 'chip-red' },
  CANCELLED: { label: 'Cancelada', chip: 'chip-muted' },
};

/** Trámites en línea con línea de tiempo y plazo de respuesta. */
@Component({
  selector: 'app-requests-page',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Solicitudes" title="Tus trámites, sin filas" subtitle="Cada solicitud tiene un plazo de respuesta y queda registrada con su historial.">
      <button class="btn btn-primary" (click)="creating.set(true)"><app-icon name="plus" [size]="16" /> Nueva solicitud</button>
    </app-page-header>

    @if (creating()) {
      <section class="tile mb-6 p-5" style="animation: var(--animate-rise)">
        <p class="label">¿Qué necesitas?</p>
        <div class="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
          @for (t of types; track t.value) {
            <button class="type" [class.on]="type() === t.value" (click)="type.set(t.value)">
              <app-icon [name]="t.icon" [size]="20" /><span>{{ t.label }}</span>
            </button>
          }
        </div>
        <div class="mt-4 grid gap-4 md:grid-cols-2">
          <div>
            <label class="label" for="rq-subject">Asunto</label>
            <input id="rq-subject" class="field" maxlength="160" [value]="subject()" (input)="subject.set($any($event.target).value)" />
          </div>
          <div class="md:row-span-2">
            <label class="label" for="rq-detail">Detalle</label>
            <textarea id="rq-detail" class="field" rows="5" maxlength="4000" [value]="detail()" (input)="detail.set($any($event.target).value)"
              placeholder="Describe tu pedido (mínimo 10 caracteres)."></textarea>
          </div>
          <p class="muted text-sm">
            @if (type() === 'WITHDRAWAL') { Para retirar una asignatura puedes hacerlo directamente desde tu <strong>Horario</strong>. }
            @if (type() === 'THIRD_ENROLLMENT') { La tercera matrícula requiere autorización (RN-07). <button class="font-bold underline" (click)="help.show('si-repruebo', 'rn-07')">Ver requisitos</button> }
          </p>
        </div>
        <div class="mt-4 flex justify-end gap-2">
          <button class="btn btn-ghost" (click)="creating.set(false)">Cancelar</button>
          <button class="btn btn-teja" [disabled]="!type() || subject().trim().length < 3 || detail().trim().length < 10 || busy()" (click)="create()">Enviar</button>
        </div>
      </section>
    }

    @if (requests.value(); as list) {
      <div class="space-y-3">
        @for (r of list; track r.id; let i = $index) {
          <article class="tile p-5" [style.animation-delay.ms]="i * 50" style="animation: var(--animate-rise)">
            <div class="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p class="kicker">{{ typeLabel(r.type) }}</p>
                <h3 class="text-lg">{{ r.subject }}</h3>
                <p class="muted text-xs">Creada el {{ r.createdAt | ecDate: 'datetime' }} @if (r.dueAt) { · respuesta hasta {{ r.dueAt | ecDate }} }</p>
              </div>
              <div class="flex items-center gap-2">
                @if (r.overdue) { <span class="chip chip-red">Plazo vencido</span> }
                <span class="chip {{ statusOf(r.status).chip }}">{{ statusOf(r.status).label }}</span>
              </div>
            </div>
            <p class="mt-2 text-sm">{{ r.detail }}</p>
            @if (r.resolution) {
              <p class="resolution mt-3 text-sm"><strong>Respuesta:</strong> {{ r.resolution }}</p>
            }
            <ol class="events mt-4">
              @for (e of r.events; track e.occurredAt) {
                <li><span class="dot"></span><strong>{{ statusOf(e.toStatus).label }}</strong> <span class="muted">· {{ e.occurredAt | ecDate: 'datetime' }}</span>
                  @if (e.note) { <span class="muted block text-xs">{{ e.note }}</span> }</li>
              }
            </ol>
            @if (r.status === 'SUBMITTED') {
              <button class="muted mt-3 text-sm font-semibold hover:underline" (click)="cancel(r)">Cancelar solicitud</button>
            }
          </article>
        } @empty {
          <app-empty icon="inbox" title="No tienes solicitudes" text="Pide certificados, rectificaciones u otros trámites sin acercarte a Secretaría." />
        }
      </div>
    } @else {
      <app-skeleton [count]="3" [height]="120" />
    }
  `,
  styles: `
    .type { display: flex; flex-direction: column; align-items: center; gap: .35rem; padding: .8rem .4rem; border-radius: 14px; border: 1.5px solid var(--line);
      font-size: .78rem; font-weight: 700; color: var(--muted); transition: all .2s; text-align: center; }
    .type:hover { border-color: var(--color-cupula-300); color: var(--text); }
    .type.on { border-color: var(--color-cupula-500); background: var(--brand-soft); color: var(--brand); transform: translateY(-2px); }
    .resolution { padding: .7rem .9rem; border-radius: 12px; background: var(--surface-2); border-left: 4px solid var(--color-rio-500); }
    .events { border-left: 2px dashed var(--line); margin-left: .35rem; padding-left: 1rem; font-size: .85rem; display: grid; gap: .5rem; }
    .events li { position: relative; }
    .events .dot { position: absolute; left: -1.42rem; top: .4rem; width: .7rem; height: .7rem; border-radius: 999px; background: var(--brand); box-shadow: 0 0 0 3px var(--surface); }
  `,
})
export class RequestsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly help = inject(HelpService);
  protected readonly requests = rxResource({ stream: () => this.api.get<RequestView[]>('/me/requests') });
  protected readonly types = REQUEST_TYPES;
  protected readonly creating = signal(false);
  protected readonly type = signal('');
  protected readonly subject = signal('');
  protected readonly detail = signal('');
  protected readonly busy = signal(false);

  protected typeLabel(t: string): string {
    return REQUEST_TYPES.find((x) => x.value === t)?.label ?? t;
  }

  protected statusOf(s: string) {
    return REQUEST_STATUS[s] ?? { label: s, chip: 'chip-muted' };
  }

  protected create(): void {
    this.busy.set(true);
    this.api.post('/me/requests', { type: this.type(), subject: this.subject().trim(), detail: this.detail().trim() }).subscribe({
      next: () => {
        this.busy.set(false);
        this.creating.set(false);
        this.subject.set('');
        this.detail.set('');
        this.toasts.ok('Solicitud enviada', 'Te notificaremos cada cambio de estado.');
        this.requests.reload();
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected cancel(r: RequestView): void {
    this.api.post(`/me/requests/${r.id}/cancel`).subscribe({
      next: () => {
        this.toasts.ok('Solicitud cancelada');
        this.requests.reload();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }
}

export type RequestPage = Page<RequestView>;
