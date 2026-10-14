import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe } from '../../core/format';
import { Page, Problem } from '../../core/models';
import { SettingsStore, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface ParameterVersion {
  id: string;
  key: string;
  value: string;
  type: string;
  unit: string | null;
  description: string;
  validFrom: string;
  validTo: string | null;
  changeReason: string | null;
}

const GROUPS: Record<string, { label: string; icon: string }> = {
  grading: { label: 'Calificaciones', icon: 'grade' },
  evaluation: { label: 'Evaluación y actas', icon: 'seal' },
  attendance: { label: 'Asistencia', icon: 'calendar' },
  enrollment: { label: 'Matrícula', icon: 'sparkle' },
  homologation: { label: 'Homologación', icon: 'refresh' },
  finance: { label: 'Finanzas', icon: 'wallet' },
  tax: { label: 'Tributario', icon: 'file' },
  security: { label: 'Seguridad', icon: 'shield' },
  welfare: { label: 'Bienestar y becas', icon: 'heart' },
  reports: { label: 'Reportes', icon: 'chart' },
  dropout: { label: 'Deserción', icon: 'flag' },
  uploads: { label: 'Archivos', icon: 'upload' },
};

/** LEY-1 «nada quemado»: cada valor de negocio es un parámetro versionado con vigencia y motivo. */
@Component({
  selector: 'app-admin-parameters',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="LEY-1 · nada quemado" title="Parámetros institucionales"
      subtitle="Cambiar un valor no edita el anterior: publica una nueva versión con fecha de vigencia y motivo. La base, el dominio y la interfaz leen la misma fuente." />

    <div class="mb-4 flex flex-wrap items-center gap-2">
      <div class="relative min-w-[240px] flex-1">
        <span class="muted absolute left-3 top-1/2 -translate-y-1/2"><app-icon name="search" [size]="17" /></span>
        <input class="field !pl-10" placeholder="Buscar parámetro o descripción" [value]="query()" (input)="query.set($any($event.target).value)" />
      </div>
    </div>

    @if (params.value(); as p) {
      <div class="grid gap-5 xl:grid-cols-[1fr_400px]">
        <div class="space-y-5">
          @for (g of groups(); track g.key) {
            <section style="animation: var(--animate-rise)">
              <h2 class="mb-2 flex items-center gap-2 text-lg"><app-icon [name]="g.icon" [size]="18" /> {{ g.label }}</h2>
              <div class="grid gap-2 md:grid-cols-2">
                @for (v of g.items; track v.key) {
                  <button class="param tile p-3 text-left" [class.sel]="selected()?.key === v.key" (click)="select(v)">
                    <p class="mono muted truncate text-[11px] font-bold">{{ v.key }}</p>
                    <p class="font-display text-2xl leading-tight">{{ display(v) }} <span class="muted text-sm font-sans">{{ v.unit }}</span></p>
                    <p class="muted line-clamp-2 text-xs">{{ v.description }}</p>
                  </button>
                }
              </div>
            </section>
          }
        </div>
        <aside class="tile h-fit p-5 xl:sticky xl:top-20">
          @if (selected(); as v) {
            <p class="mono muted text-xs font-bold">{{ v.key }}</p>
            <h2 class="mt-1 text-xl">{{ v.description }}</h2>
            <p class="mt-3 font-display text-4xl">{{ display(v) }} <span class="muted text-base">{{ v.unit }}</span></p>
            <p class="muted text-xs">Vigente desde {{ v.validFrom | ecDate: 'datetime' }} · tipo {{ v.type }}</p>

            <p class="label mt-5">Línea de tiempo</p>
            <ol class="timeline">
              @for (h of history.value()?.versions ?? []; track h.id) {
                <li [class.now]="!h.validTo || h.validTo > nowIso">
                  <span class="dot"></span>
                  <p class="mono text-sm font-bold">{{ h.value }}</p>
                  <p class="muted text-[11px]">{{ h.validFrom | ecDate: 'datetime' }} → {{ h.validTo ? (h.validTo | ecDate: 'datetime') : 'sin fin' }}</p>
                  @if (h.changeReason) { <p class="text-xs">«{{ h.changeReason }}»</p> }
                </li>
              }
            </ol>

            @if (auth.has('PARAMETER_WRITE')) {
              <form class="mt-5 space-y-3 border-t pt-4" style="border-color: var(--line)" (submit)="$event.preventDefault(); publish(v)">
                <p class="label">Publicar nueva versión</p>
                <input class="field mono" [value]="newValue()" (input)="newValue.set($any($event.target).value)" placeholder="Nuevo valor" aria-label="Nuevo valor" />
                <div>
                  <label class="label" for="from">Vigente desde</label>
                  <input id="from" type="datetime-local" class="field" [value]="validFrom()" (input)="validFrom.set($any($event.target).value)" />
                </div>
                <textarea class="field" rows="2" maxlength="500" placeholder="Motivo (resolución, memorando…), mínimo 10 caracteres" [value]="reason()"
                  (input)="reason.set($any($event.target).value)" aria-label="Motivo"></textarea>
                <button class="btn btn-primary w-full" [disabled]="!newValue().trim() || reason().trim().length < 10 || busy()">Publicar versión</button>
              </form>
            }
          } @else {
            <div class="py-8 text-center">
              <span class="mx-auto grid h-14 w-14 place-items-center rounded-full" style="background: var(--brand-soft); color: var(--brand)"><app-icon name="sliders" [size]="26" /></span>
              <p class="font-display mt-3 text-xl">{{ p.total }} parámetros vigentes</p>
              <p class="muted mt-1 text-sm">Elige uno para ver su historia y publicar cambios.</p>
            </div>
          }
        </aside>
      </div>
    } @else {
      <app-skeleton [count]="4" [height]="110" />
    }
  `,
  styles: `
    .param { transition: transform .2s, box-shadow .2s, border-color .2s; }
    .param:hover { transform: translateY(-2px); box-shadow: var(--shadow-lift); }
    .param.sel { border-color: var(--color-cupula-400); box-shadow: 0 0 0 3px var(--brand-soft); }
    .timeline { border-left: 2px solid var(--line); margin-left: .4rem; padding-left: 1rem; display: grid; gap: .8rem; }
    .timeline li { position: relative; animation: var(--animate-rise); }
    .timeline .dot { position: absolute; left: -1.43rem; top: .35rem; width: .75rem; height: .75rem; border-radius: 999px; background: var(--line); }
    .timeline li.now .dot { background: var(--color-rio-500); box-shadow: 0 0 0 4px var(--color-rio-100); }
  `,
})
export class AdminParametersPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly settings = inject(SettingsStore);
  protected readonly auth = inject(AuthStore);
  protected readonly nowIso = new Date().toISOString();
  protected readonly params = rxResource({ stream: () => this.api.get<Page<ParameterVersion>>('/parameters', { size: 100 }) });
  protected readonly query = signal('');
  protected readonly selected = signal<ParameterVersion | null>(null);
  protected readonly history = rxResource({
    params: () => this.selected()?.key,
    stream: ({ params }) => this.api.get<{ key: string; versions: ParameterVersion[] }>(`/parameters/${params}/history`),
  });
  protected readonly newValue = signal('');
  protected readonly validFrom = signal(localNow());
  protected readonly reason = signal('');
  protected readonly busy = signal(false);

  protected readonly groups = computed(() => {
    const q = this.query().trim().toLowerCase();
    const items = (this.params.value()?.items ?? []).filter((v) => !q || v.key.includes(q) || v.description.toLowerCase().includes(q));
    const map = new Map<string, ParameterVersion[]>();
    for (const v of items) {
      const prefix = v.key.split('.')[0];
      map.set(prefix, [...(map.get(prefix) ?? []), v]);
    }
    return [...map.entries()].map(([key, list]) => ({ key, label: GROUPS[key]?.label ?? key, icon: GROUPS[key]?.icon ?? 'sliders', items: list }));
  });

  protected display(v: ParameterVersion): string {
    return v.type === 'BOOLEAN' ? (v.value === 'true' ? 'Sí' : 'No') : v.value;
  }

  protected select(v: ParameterVersion): void {
    this.selected.set(v);
    this.newValue.set(v.value);
    this.reason.set('');
    this.validFrom.set(localNow());
  }

  protected publish(v: ParameterVersion): void {
    this.busy.set(true);
    this.api
      .post(`/parameters/${v.key}/versions`, { value: this.newValue().trim(), validFrom: new Date(this.validFrom()).toISOString(), reason: this.reason().trim() })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.toasts.ok('Versión publicada', 'Todos los servidores la aplican al llegar su vigencia.');
          this.params.reload();
          this.history.reload();
          void this.settings.load();
        },
        error: (p: Problem) => {
          this.busy.set(false);
          this.toasts.problem(p);
        },
      });
  }
}

function localNow(): string {
  const d = new Date(Date.now() + 60_000);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}
