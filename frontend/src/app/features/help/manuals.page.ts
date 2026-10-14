import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Manual } from '../../core/models';
import { IconComponent } from '../../shared/icon.component';
import { PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

const AUDIENCE: Record<string, { label: string; icon: string }> = {
  STUDENT: { label: 'Para estudiantes', icon: 'user' },
  TEACHER: { label: 'Para docentes', icon: 'users' },
  ADMIN: { label: 'Para administración', icon: 'building' },
};

interface Hit {
  slug: string;
  title: string;
  heading: string;
  snippet: string;
}

/** Centro de ayuda: manuales en lenguaje claro, con buscador y descarga en PDF. */
@Component({
  selector: 'app-manuals-page',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Centro de ayuda" title="¿En qué te ayudamos?" subtitle="Cada regla del sistema tiene su explicación aquí, con ejemplos y qué hacer si algo no se puede." />
    <div class="relative mb-6 max-w-2xl">
      <span class="muted absolute left-4 top-1/2 -translate-y-1/2"><app-icon name="search" [size]="20" /></span>
      <input class="field !py-3.5 !pl-12 text-base" placeholder="Busca: tercera matrícula, pagar, beca, retiro…" [value]="query()"
        (input)="search($any($event.target).value)" aria-label="Buscar en los manuales" />
      @if (hits().length) {
        <ul class="tile absolute inset-x-0 top-full z-10 mt-2 max-h-80 overflow-y-auto p-2">
          @for (h of hits(); track h.slug + h.heading) {
            <li>
              <a [routerLink]="['/ayuda', h.slug]" class="block rounded-lg p-2 text-sm hover:bg-[var(--surface-2)]">
                <strong>{{ h.title }}</strong> · <span class="muted">{{ h.heading }}</span>
                <span class="muted block truncate text-xs">{{ h.snippet }}</span>
              </a>
            </li>
          }
        </ul>
      }
    </div>
    @if (manuals.value()) {
      @for (g of groups(); track g.key) {
        <section class="mb-6">
          <h2 class="mb-3 flex items-center gap-2 text-xl"><app-icon [name]="g.icon" [size]="19" /> {{ g.label }}</h2>
          <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            @for (m of g.items; track m.slug; let i = $index) {
              <a [routerLink]="['/ayuda', m.slug]" class="card tile tile-hover flex items-center gap-3 p-4" [style.--i]="i">
                <span class="grid h-10 w-10 shrink-0 place-items-center rounded-xl" style="background: var(--brand-soft); color: var(--brand)"><app-icon name="book" [size]="18" /></span>
                <span class="font-semibold leading-snug">{{ m.title }}</span>
              </a>
            }
          </div>
        </section>
      }
    } @else {
      <app-skeleton [count]="3" [height]="70" />
    }
  `,
  styles: `.card { animation: var(--animate-rise); animation-delay: calc(var(--i) * 40ms); }`,
})
export class ManualsPage {
  private readonly api = inject(ApiService);
  protected readonly manuals = rxResource({ stream: () => this.api.get<Manual[]>('/public/manuals') });
  protected readonly query = signal('');
  protected readonly hits = signal<Hit[]>([]);
  protected readonly groups = computed(() =>
    Object.entries(AUDIENCE)
      .map(([key, a]) => ({ key, ...a, items: (this.manuals.value() ?? []).filter((m) => m.audience === key) }))
      .filter((g) => g.items.length > 0),
  );

  protected search(q: string): void {
    this.query.set(q);
    if (q.trim().length < 3) {
      this.hits.set([]);
      return;
    }
    this.api.get<Hit[]>('/public/manuals/search', { q }).subscribe((h) => this.hits.set(h));
  }
}
