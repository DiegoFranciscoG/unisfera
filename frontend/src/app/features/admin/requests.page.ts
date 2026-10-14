import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { EcDatePipe } from '../../core/format';
import { Page, Problem } from '../../core/models';
import { ToastService } from '../../core/ui.services';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';
import { REQUEST_STATUS, REQUEST_TYPES, RequestView } from '../student/requests.page';

/** Bandeja de solicitudes: tablero por estado con plazo de respuesta y resolución. */
@Component({
  selector: 'app-admin-requests',
  imports: [PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Secretaría" title="Bandeja de solicitudes" subtitle="Las vencidas aparecen primero. Cada cambio de estado notifica al estudiante." />
    <div class="mb-4 flex flex-wrap gap-2">
      @for (s of filters; track s.key) {
        <button class="chip !py-1.5" [class.chip-blue]="status() === s.key" [class.chip-muted]="status() !== s.key" (click)="status.set(s.key); page.set(0)">{{ s.label }}</button>
      }
    </div>
    @if (queue.value(); as q) {
      <div class="grid gap-3 lg:grid-cols-2">
        @for (r of q.items; track r.id; let i = $index) {
          <article class="tile p-4" [class.late]="r.overdue" style="animation: var(--animate-rise)" [style.animation-delay.ms]="i * 40">
            <div class="flex flex-wrap items-start justify-between gap-2">
              <div><p class="kicker">{{ typeLabel(r.type) }}</p><h2 class="text-lg leading-snug">{{ r.subject }}</h2>
                <p class="muted text-xs">{{ r.studentName }} · {{ r.createdAt | ecDate: 'datetime' }}</p></div>
              <div class="flex flex-col items-end gap-1">
                <span class="chip {{ st(r.status).chip }}">{{ st(r.status).label }}</span>
                @if (r.dueAt) { <span class="text-xs font-bold" [style.color]="r.overdue ? 'var(--color-alerta-500)' : 'var(--muted)'">{{ r.overdue ? 'Vencida' : 'Vence' }} {{ r.dueAt | ecDate }}</span> }
              </div>
            </div>
            <p class="mt-2 text-sm">{{ r.detail }}</p>
            @if (r.status === 'SUBMITTED' || r.status === 'IN_REVIEW') {
              @if (open() === r.id) {
                <textarea class="field mt-3" rows="2" maxlength="2000" placeholder="Respuesta para el estudiante" [value]="resolution()" (input)="resolution.set($any($event.target).value)"></textarea>
                <div class="mt-2 flex flex-wrap justify-end gap-2">
                  @if (r.status === 'SUBMITTED') { <button class="btn btn-ghost" (click)="move(r, 'IN_REVIEW')">Tomar en revisión</button> }
                  <button class="btn btn-ghost" style="color: var(--color-alerta-500)" [disabled]="!resolution().trim()" (click)="move(r, 'REJECTED')">Negar</button>
                  <button class="btn btn-primary" [disabled]="!resolution().trim()" (click)="move(r, 'APPROVED')">Aprobar</button>
                </div>
              } @else {
                <button class="mt-3 text-sm font-bold" style="color: var(--brand)" (click)="open.set(r.id); resolution.set('')">Atender →</button>
              }
            } @else if (r.resolution) {
              <p class="muted mt-2 text-sm"><strong>Respuesta:</strong> {{ r.resolution }}</p>
            }
          </article>
        } @empty {
          <div class="lg:col-span-2"><app-empty icon="inbox" title="Bandeja vacía" text="No hay solicitudes con este estado." /></div>
        }
      </div>
      <div class="mt-3 flex justify-end gap-2">
        <button class="btn btn-ghost !py-1.5" [disabled]="page() === 0" (click)="page.set(page() - 1)">Anterior</button>
        <button class="btn btn-ghost !py-1.5" [disabled]="(page() + 1) * q.size >= q.total" (click)="page.set(page() + 1)">Siguiente</button>
      </div>
    } @else {
      <app-skeleton [count]="4" [height]="120" />
    }
  `,
  styles: `.late { border-left: 4px solid var(--color-alerta-500); }`,
})
export class AdminRequestsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly filters = [
    { key: 'SUBMITTED', label: 'Recibidas' },
    { key: 'IN_REVIEW', label: 'En revisión' },
    { key: 'APPROVED', label: 'Aprobadas' },
    { key: 'REJECTED', label: 'Negadas' },
    { key: '', label: 'Todas' },
  ];
  protected readonly status = signal('SUBMITTED');
  protected readonly page = signal(0);
  protected readonly open = signal<string | null>(null);
  protected readonly resolution = signal('');
  protected readonly queue = rxResource({
    params: () => ({ status: this.status(), page: this.page() }),
    stream: ({ params }) => this.api.get<Page<RequestView>>('/admin/requests', { ...params, size: 20 }),
  });

  protected typeLabel(t: string): string {
    return REQUEST_TYPES.find((x) => x.value === t)?.label ?? t;
  }

  protected st(s: string) {
    return REQUEST_STATUS[s] ?? { label: s, chip: 'chip-muted' };
  }

  protected move(r: RequestView, status: string): void {
    this.api.post(`/admin/requests/${r.id}/status`, { status, resolution: this.resolution().trim() || null }).subscribe({
      next: () => {
        this.toasts.ok('Solicitud actualizada', 'El estudiante fue notificado.');
        this.open.set(null);
        this.queue.reload();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }
}
