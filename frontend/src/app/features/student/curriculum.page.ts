import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { GradePipe } from '../../core/format';
import { Progress, SubjectProgress, SubjectStatus } from '../../core/models';
import { HelpService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface Planet {
  subject: SubjectProgress;
  x: number;
  y: number;
}

export const SUBJECT_STATUS: Record<SubjectStatus, { label: string; chip: string; color: string; help: string }> = {
  APPROVED: { label: 'Aprobada', chip: 'chip-green', color: 'var(--color-rio-500)', help: 'Ya forma parte de tu kardex.' },
  IN_PROGRESS: { label: 'Cursando', chip: 'chip-blue', color: 'var(--color-cupula-500)', help: 'Estás matriculado/a en este período.' },
  AVAILABLE: { label: 'Disponible', chip: 'chip-gold', color: 'var(--color-oro-400)', help: 'Cumples los prerrequisitos: puedes tomarla.' },
  NEEDS_AUTHORIZATION: { label: 'Requiere autorización', chip: 'chip-teja', color: 'var(--color-teja-500)', help: 'Sería tu tercera matrícula: necesita autorización (RN-07).' },
  BLOCKED: { label: 'Bloqueada', chip: 'chip-muted', color: 'var(--line)', help: 'Te falta aprobar uno o más prerrequisitos (RN-02).' },
  EXHAUSTED: { label: 'Sin matrículas', chip: 'chip-red', color: 'var(--color-alerta-500)', help: 'Agotaste las tres matrículas permitidas (RN-07).' },
};

/** Malla curricular en órbitas: cada nivel es un anillo y cada asignatura un planeta coloreado por su estado. */
@Component({
  selector: 'app-curriculum-page',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Mi malla" title="Tu carrera, en órbitas" subtitle="Cada anillo es un nivel. Toca una asignatura para ver qué necesitas para tomarla.">
      <div class="seg" role="tablist" aria-label="Vista">
        <button role="tab" [attr.aria-selected]="view() === 'orbit'" [class.on]="view() === 'orbit'" (click)="view.set('orbit')"><app-icon name="orbit" [size]="16" /> Órbitas</button>
        <button role="tab" [attr.aria-selected]="view() === 'grid'" [class.on]="view() === 'grid'" (click)="view.set('grid')"><app-icon name="menu" [size]="16" /> Por niveles</button>
      </div>
    </app-page-header>

    <ul class="mb-5 flex flex-wrap gap-2 text-xs">
      @for (s of legend; track s.key) {
        <li>
          <button class="chip {{ s.chip }} transition" [class.opacity-40]="filter() && filter() !== s.key" (click)="toggleFilter(s.key)">
            <span class="h-2 w-2 rounded-full" [style.background]="s.color"></span>{{ s.label }} · {{ count(s.key) }}
          </button>
        </li>
      }
    </ul>

    @if (progress.value(); as p) {
      <div class="grid gap-5 xl:grid-cols-[1fr_340px]">
        @if (view() === 'orbit') {
          <section class="tile azulejo relative overflow-hidden p-2">
            <svg viewBox="0 0 800 800" class="orbits w-full" role="img" aria-label="Mapa orbital de la malla">
              <defs>
                <radialGradient id="core" cx="35%" cy="30%">
                  <stop offset="0%" stop-color="#84acee" /><stop offset="60%" stop-color="#2457a6" /><stop offset="100%" stop-color="#172f52" />
                </radialGradient>
              </defs>
              @for (ring of rings(); track ring.level) {
                <circle cx="400" cy="400" [attr.r]="ring.radius" class="ring" [class.done]="ring.done" />
                <text [attr.x]="ring.labelX" [attr.y]="ring.labelY" text-anchor="middle" dominant-baseline="middle" class="ring-label">Nivel {{ ring.level }}</text>
              }
              @if (selected(); as sel) {
                @for (line of links(); track line.id) {
                  <line [attr.x1]="line.x1" [attr.y1]="line.y1" [attr.x2]="line.x2" [attr.y2]="line.y2" class="link" [class.ok]="line.ok" />
                }
              }
              <circle cx="400" cy="400" r="54" fill="url(#core)" class="core" />
              <text x="400" y="396" text-anchor="middle" class="core-value">{{ pct() }}%</text>
              <text x="400" y="416" text-anchor="middle" class="core-label">avance</text>
              @for (pl of planets(); track pl.subject.id; let i = $index) {
                <g class="planet" [class.dim]="isDimmed(pl.subject)" [class.sel]="selected()?.id === pl.subject.id" [style.--i]="i"
                  [attr.transform]="'translate(' + pl.x + ' ' + pl.y + ')'" (click)="select(pl.subject)" (keydown.enter)="select(pl.subject)"
                  tabindex="0" role="button" [attr.aria-label]="pl.subject.name + ', ' + statusOf(pl.subject).label">
                  <circle r="21" class="halo" [attr.fill]="statusOf(pl.subject).color" />
                  <circle r="15" [attr.fill]="statusOf(pl.subject).color" class="body" [class.blocked]="pl.subject.status === 'BLOCKED'" />
                  @if (pl.subject.status === 'APPROVED') {
                    <path d="M-6 0l4 4 8-8" class="tick" />
                  }
                  <text y="34" text-anchor="middle" class="planet-label">{{ pl.subject.code }}</text>
                </g>
              }
            </svg>
          </section>
        } @else {
          <section class="grid gap-4" [style.grid-template-columns]="'repeat(auto-fit, minmax(220px, 1fr))'">
            @for (ring of rings(); track ring.level) {
              <div class="tile p-3">
                <p class="label">Nivel {{ ring.level }}</p>
                <ul class="space-y-2">
                  @for (s of ring.subjects; track s.id; let i = $index) {
                    <li>
                      <button class="subject-card w-full text-left" [class.dim]="isDimmed(s)" [class.sel]="selected()?.id === s.id" [style.--c]="statusOf(s).color"
                        [style.--i]="i" (click)="select(s)">
                        <span class="mono text-[11px] font-bold opacity-70">{{ s.code }}</span>
                        <span class="block text-sm font-bold leading-snug">{{ s.name }}</span>
                        <span class="mt-1 flex items-center justify-between text-xs">
                          <span class="chip {{ statusOf(s).chip }}">{{ statusOf(s).label }}</span>
                          @if (s.grade !== null) { <span class="mono font-bold">{{ s.grade | grade }}</span> }
                        </span>
                      </button>
                    </li>
                  }
                </ul>
              </div>
            }
          </section>
        }

        <aside class="tile h-fit p-5 xl:sticky xl:top-20">
          @if (selected(); as s) {
            <div class="detail" [attr.data-key]="s.id">
              <p class="mono muted text-xs font-bold">{{ s.code }} · Nivel {{ s.level }}</p>
              <h2 class="mt-1 text-2xl">{{ s.name }}</h2>
              <span class="chip {{ statusOf(s).chip }} mt-2">{{ statusOf(s).label }}</span>
              <p class="muted mt-3 text-sm">{{ statusOf(s).help }}</p>
              <dl class="mt-4 grid grid-cols-3 gap-2 text-center">
                <div class="rounded-xl p-2" style="background: var(--surface-2)"><dt class="label !mb-0 !text-[10px]">Créditos</dt><dd class="font-display text-xl">{{ s.credits }}</dd></div>
                <div class="rounded-xl p-2" style="background: var(--surface-2)"><dt class="label !mb-0 !text-[10px]">Horas</dt><dd class="font-display text-xl">{{ s.totalHours }}</dd></div>
                <div class="rounded-xl p-2" style="background: var(--surface-2)"><dt class="label !mb-0 !text-[10px]">Matrículas</dt><dd class="font-display text-xl">{{ s.attemptsUsed }}</dd></div>
              </dl>
              @if (s.missingPrerequisites.length) {
                <p class="label mt-5">Te falta aprobar</p>
                <ul class="space-y-1.5">
                  @for (m of s.missingPrerequisites; track m) {
                    <li class="flex items-center gap-2 text-sm"><app-icon name="lock" [size]="15" /> {{ m }}</li>
                  }
                </ul>
              }
              @if (s.corequisites.length) {
                <p class="label mt-5">Se toma junto con</p>
                <ul class="space-y-1.5 text-sm">
                  @for (c of s.corequisites; track c) { <li>{{ nameOf(c) }}</li> }
                </ul>
              }
              <div class="mt-5 flex flex-wrap gap-2">
                @if (s.status === 'AVAILABLE' || s.status === 'NEEDS_AUTHORIZATION') {
                  <a routerLink="/estudiante/matricula" class="btn btn-teja">Tomarla en la matrícula</a>
                }
                @if (s.status === 'BLOCKED') {
                  <button class="btn btn-ghost" (click)="help.show('prerrequisitos', 'rn-02')">¿Por qué está bloqueada?</button>
                }
                @if (s.status === 'NEEDS_AUTHORIZATION' || s.status === 'EXHAUSTED') {
                  <button class="btn btn-ghost" (click)="help.show('si-repruebo', 'rn-07')">¿Qué hago?</button>
                }
              </div>
            </div>
          } @else {
            <div class="py-6 text-center">
              <span class="mx-auto grid h-14 w-14 place-items-center rounded-full" style="background: var(--brand-soft); color: var(--brand)"><app-icon name="orbit" [size]="26" /></span>
              <p class="font-display mt-3 text-xl">Elige una asignatura</p>
              <p class="muted mt-1 text-sm">Verás sus prerrequisitos, créditos y cuántas matrículas has usado.</p>
              <p class="mt-4 text-sm"><strong>{{ p.approvedCredits }}</strong> de <strong>{{ p.totalCredits }}</strong> créditos aprobados</p>
            </div>
          }
        </aside>
      </div>
    } @else {
      <app-skeleton [count]="1" [height]="520" />
    }
  `,
  styles: `
    .seg { display: inline-flex; padding: 4px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--line); }
    .seg button { display: inline-flex; align-items: center; gap: .4rem; padding: .45rem .9rem; border-radius: 999px; font-weight: 700; font-size: .85rem; color: var(--muted); transition: all .2s; }
    .seg button.on { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-tile); }
    .orbits { max-height: 78vh; }
    .ring { fill: none; stroke: var(--muted); stroke-opacity: .45; stroke-width: 1.5; stroke-dasharray: 3 6; }
    .ring.done { stroke: var(--color-rio-500); stroke-opacity: .5; stroke-dasharray: none; }
    .ring-label { font-size: 10px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; fill: var(--muted); paint-order: stroke; stroke: var(--surface); stroke-width: 4px; }
    .core { filter: drop-shadow(0 10px 20px rgba(36,87,166,.45)); animation: float 6s ease-in-out infinite; }
    .core-value { font-family: var(--font-display); font-size: 26px; fill: white; font-weight: 600; }
    .core-label { font-size: 10px; fill: rgba(255,255,255,.8); text-transform: uppercase; letter-spacing: .14em; font-weight: 800; }
    .planet { cursor: pointer; outline: none; animation: pop .6s cubic-bezier(.2,.9,.3,1.4) both; animation-delay: calc(var(--i) * 35ms); }
    .planet .halo { opacity: 0; transition: opacity .25s, transform .25s; }
    .planet:hover .halo, .planet:focus-visible .halo, .planet.sel .halo { opacity: .25; }
    .planet .body { transition: transform .25s cubic-bezier(.2,.9,.3,1.4); stroke: var(--surface); stroke-width: 3; }
    .planet .body.blocked { stroke: var(--muted); stroke-dasharray: 3 3; stroke-width: 2; fill: var(--surface); }
    .planet:hover .body, .planet.sel .body { transform: scale(1.18); }
    .planet.dim { opacity: .28; }
    .planet-label { font-family: var(--font-mono); font-size: 10px; font-weight: 600; fill: var(--text); paint-order: stroke; stroke: var(--bg); stroke-width: 3px; }
    .tick { fill: none; stroke: white; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round; }
    .link { stroke: var(--color-teja-500); stroke-width: 2; stroke-dasharray: 5 5; animation: dash 1s linear infinite; }
    .link.ok { stroke: var(--color-rio-500); }
    @keyframes dash { to { stroke-dashoffset: -20; } }
    @keyframes pop { from { opacity: 0; transform: scale(.2); } }
    .subject-card { display: block; padding: .7rem .8rem; border-radius: 12px; background: var(--surface); border: 1px solid var(--line);
      border-left: 4px solid var(--c); transition: transform .2s, box-shadow .2s, opacity .2s; animation: var(--animate-rise); animation-delay: calc(var(--i) * 40ms); }
    .subject-card:hover, .subject-card.sel { transform: translateY(-2px); box-shadow: var(--shadow-lift); }
    .subject-card.dim { opacity: .3; }
    .detail { animation: var(--animate-rise); }
  `,
})
export class CurriculumPage {
  private readonly api = inject(ApiService);
  protected readonly help = inject(HelpService);
  protected readonly progress = rxResource({ stream: () => this.api.get<Progress>('/me/curriculum') });
  protected readonly view = signal<'orbit' | 'grid'>('orbit');
  protected readonly filter = signal<SubjectStatus | null>(null);
  protected readonly selected = signal<SubjectProgress | null>(null);
  protected readonly legend = (Object.keys(SUBJECT_STATUS) as SubjectStatus[]).map((key) => ({ key, ...SUBJECT_STATUS[key] }));

  protected readonly rings = computed(() => {
    const subjects = this.progress.value()?.subjects ?? [];
    const levels = [...new Set(subjects.map((s) => s.level))].sort((a, b) => a - b);
    const step = levels.length ? 300 / levels.length : 0;
    return levels.map((level, idx) => {
      const list = subjects.filter((s) => s.level === level);
      const radius = 92 + step * (idx + 0.5);
      // La etiqueta va en el hueco entre planetas más cercano a la parte superior del anillo.
      const angle = list.length ? this.gapAngle(list.length, idx) : -Math.PI / 2;
      return { level, radius, subjects: list, done: list.every((s) => s.status === 'APPROVED'),
        labelX: 400 + radius * Math.cos(angle), labelY: 400 + radius * Math.sin(angle) };
    });
  });

  protected readonly planets = computed<Planet[]>(() =>
    this.rings().flatMap((ring, r) =>
      ring.subjects.map((subject, i) => {
        const angle = this.planetAngle(i, ring.subjects.length, r);
        return { subject, x: 400 + ring.radius * Math.cos(angle), y: 400 + ring.radius * Math.sin(angle) };
      }),
    ),
  );

  protected readonly pct = computed(() => {
    const p = this.progress.value();
    return p && p.totalCredits > 0 ? Math.round((p.approvedCredits / p.totalCredits) * 100) : 0;
  });

  protected readonly links = computed(() => {
    const sel = this.selected();
    if (!sel) {
      return [];
    }
    const byId = new Map(this.planets().map((p) => [p.subject.id, p]));
    const from = byId.get(sel.id);
    if (!from) {
      return [];
    }
    return [...sel.prerequisites, ...sel.corequisites]
      .map((id) => byId.get(id))
      .filter((p): p is Planet => !!p)
      .map((p) => ({ id: p.subject.id, x1: from.x, y1: from.y, x2: p.x, y2: p.y, ok: p.subject.status === 'APPROVED' }));
  });

  /** Ángulo del planeta i en el anillo r (misma fórmula para planetas y etiquetas). */
  private planetAngle(i: number, n: number, r: number): number {
    return (i / n) * Math.PI * 2 - Math.PI / 2 + r * 0.55;
  }

  private gapAngle(n: number, r: number): number {
    const top = -Math.PI / 2;
    let best = top;
    let bestDistance = Infinity;
    for (let i = 0; i < n; i++) {
      const mid = this.planetAngle(i, n, r) + Math.PI / n;
      const d = Math.abs(Math.atan2(Math.sin(mid - top), Math.cos(mid - top)));
      if (d < bestDistance) {
        bestDistance = d;
        best = mid;
      }
    }
    return best;
  }

  protected statusOf(s: SubjectProgress) {
    return SUBJECT_STATUS[s.status];
  }

  protected count(status: SubjectStatus): number {
    return (this.progress.value()?.subjects ?? []).filter((s) => s.status === status).length;
  }

  protected toggleFilter(status: SubjectStatus): void {
    this.filter.update((f) => (f === status ? null : status));
  }

  protected isDimmed(s: SubjectProgress): boolean {
    const sel = this.selected();
    if (this.filter() && s.status !== this.filter()) {
      return true;
    }
    if (sel && this.view() === 'orbit') {
      return s.id !== sel.id && !sel.prerequisites.includes(s.id) && !sel.corequisites.includes(s.id);
    }
    return false;
  }

  protected select(s: SubjectProgress): void {
    this.selected.update((cur) => (cur?.id === s.id ? null : s));
  }

  protected nameOf(id: string): string {
    return this.progress.value()?.subjects.find((s) => s.id === id)?.name ?? '—';
  }
}
