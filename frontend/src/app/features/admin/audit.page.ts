import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { EcDatePipe } from '../../core/format';
import { Page } from '../../core/models';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface AuditEntry {
  id: string;
  occurredAt: string;
  tableName: string;
  recordId: string;
  action: string;
  oldValues: string | null;
  newValues: string | null;
  actorId: string | null;
  actorName: string | null;
  actorIp: string | null;
  requestId: string | null;
  detail: string | null;
}

const ACTIONS: Record<string, { label: string; chip: string }> = {
  INSERT: { label: 'Alta', chip: 'chip-green' },
  UPDATE: { label: 'Cambio', chip: 'chip-blue' },
  DENIED: { label: 'Intento denegado', chip: 'chip-red' },
  READ_SENSITIVE: { label: 'Lectura sensible', chip: 'chip-teja' },
  LOGIN: { label: 'Ingreso', chip: 'chip-muted' },
  EXPORT: { label: 'Exportación', chip: 'chip-gold' },
};

/** LEY-2: bitácora inmutable (solo inserciones) de cambios, intentos denegados, lecturas sensibles y exportaciones. */
@Component({
  selector: 'app-admin-audit',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="LEY-2 · nada se borra" title="Auditoría" subtitle="Bitácora de solo inserción particionada por año: quién, qué, cuándo, desde dónde y con qué solicitud." />
    <div class="tile mb-4 flex flex-wrap items-end gap-3 p-4">
      <div><label class="label" for="tb">Tabla</label><input id="tb" class="field mono" placeholder="p. ej. grade" [value]="table()" (change)="table.set($any($event.target).value); page.set(0)" /></div>
      <div><label class="label" for="ac">Acción</label>
        <select id="ac" class="field" (change)="action.set($any($event.target).value); page.set(0)">
          <option value="">Todas</option>@for (a of actions; track a[0]) { <option [value]="a[0]">{{ a[1].label }}</option> }</select></div>
      <div><label class="label" for="rid">Registro</label><input id="rid" class="field mono" placeholder="UUID" [value]="recordId()" (change)="recordId.set($any($event.target).value); page.set(0)" /></div>
    </div>
    @if (entries.value(); as p) {
      <ol class="log">
        @for (e of p.items; track e.id; let i = $index) {
          <li class="tile p-4" [style.--i]="i">
            <button class="flex w-full flex-wrap items-center gap-3 text-left" (click)="toggle(e.id)" [attr.aria-expanded]="open() === e.id">
              <span class="chip {{ act(e.action).chip }}">{{ act(e.action).label }}</span>
              <span class="mono text-sm font-bold">{{ e.tableName }}</span>
              <span class="muted min-w-0 flex-1 truncate text-sm">{{ e.detail ?? e.recordId }}</span>
              <span class="muted text-xs">{{ e.actorName ?? 'sistema' }} · {{ e.occurredAt | ecDate: 'datetime' }}</span>
              <app-icon [name]="open() === e.id ? 'x' : 'plus'" [size]="14" />
            </button>
            @if (open() === e.id) {
              <div class="mt-3 grid gap-3 md:grid-cols-2" style="animation: var(--animate-rise)">
                <div><p class="label">Antes</p><pre class="diff old">{{ pretty(e.oldValues) }}</pre></div>
                <div><p class="label">Después</p><pre class="diff new">{{ pretty(e.newValues) }}</pre></div>
                <p class="muted mono text-[11px] md:col-span-2">registro {{ e.recordId }} · IP {{ e.actorIp ?? '—' }} · solicitud {{ e.requestId ?? '—' }}</p>
              </div>
            }
          </li>
        } @empty {
          <li><app-empty icon="shield" title="Sin eventos con estos filtros" /></li>
        }
      </ol>
      <div class="mt-3 flex items-center justify-between text-sm">
        <span class="muted">{{ p.total }} eventos</span>
        <div class="flex gap-2">
          <button class="btn btn-ghost !py-1.5" [disabled]="page() === 0" (click)="page.set(page() - 1)">Anterior</button>
          <button class="btn btn-ghost !py-1.5" [disabled]="(page() + 1) * p.size >= p.total" (click)="page.set(page() + 1)">Siguiente</button>
        </div>
      </div>
    } @else {
      <app-skeleton [count]="6" [height]="56" />
    }
  `,
  styles: `
    .log { display: grid; gap: .5rem; }
    .log li { animation: var(--animate-rise); animation-delay: calc(var(--i) * 25ms); }
    .diff { font-family: var(--font-mono); font-size: .72rem; white-space: pre-wrap; word-break: break-all; padding: .7rem; border-radius: 10px; max-height: 260px; overflow: auto; }
    .diff.old { background: var(--color-alerta-100); color: var(--color-alerta-500); }
    .diff.new { background: var(--color-rio-100); color: var(--color-rio-700); }
    :host-context(.dark) .diff.old { background: #3b1d1d; color: #f3a6a6; }
    :host-context(.dark) .diff.new { background: #173a36; color: #8fd3c8; }
  `,
})
export class AdminAuditPage {
  private readonly api = inject(ApiService);
  protected readonly actions = Object.entries(ACTIONS);
  protected readonly table = signal('');
  protected readonly action = signal('');
  protected readonly recordId = signal('');
  protected readonly page = signal(0);
  protected readonly open = signal<string | null>(null);
  protected readonly entries = rxResource({
    params: () => ({ table: this.table().trim(), action: this.action(), recordId: this.recordId().trim(), page: this.page() }),
    stream: ({ params }) => this.api.get<Page<AuditEntry>>('/audit', { ...params, size: 25 }),
  });

  protected act(a: string) {
    return ACTIONS[a] ?? { label: a, chip: 'chip-muted' };
  }

  protected toggle(id: string): void {
    this.open.update((o) => (o === id ? null : id));
  }

  protected pretty(json: string | null): string {
    if (!json) {
      return '—';
    }
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }
}
