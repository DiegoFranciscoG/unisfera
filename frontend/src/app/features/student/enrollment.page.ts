import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subject, debounceTime, switchMap, catchError, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { MoneyPipe } from '../../core/format';
import { Confirmation, Offer, Preview, Problem, SectionOffer, SubjectProgress } from '../../core/models';
import { WEEKDAYS, overlaps } from '../../core/rules';
import { CelebrationService, HelpService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { GridItem, WeekGridComponent } from '../../shared/week-grid.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';
import { SUBJECT_STATUS } from './curriculum.page';

interface Pick {
  subject: SubjectProgress;
  section: SectionOffer;
}

/**
 * Matrícula en tres pasos. La interfaz anticipa los cruces y el tope (RN-04, RN-06) para ayudar,
 * pero la vista previa del servidor es la que manda: allí se validan todas las reglas y el costo.
 */
@Component({
  selector: 'app-enrollment-page',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, WeekGridComponent, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (confirmation(); as c) {
      <!-- Éxito -->
      <section class="tile azulejo relative mx-auto max-w-2xl overflow-hidden p-8 text-center" style="animation: var(--animate-rise)">
        <div class="stamp mx-auto">MATRICULADO</div>
        <h1 class="mt-6 text-4xl">¡Listo, estás dentro!</h1>
        <p class="muted mt-2">Período {{ c.periodCode }} · matrícula {{ typeLabel(c.type) }}</p>
        <ul class="mx-auto mt-6 max-w-md space-y-2 text-left stagger">
          @for (d of c.details; track d.detailId; let i = $index) {
            <li class="flex items-center gap-3 rounded-xl p-3" style="background: var(--surface-2)" [style.--i]="i">
              <span class="grid h-7 w-7 place-items-center rounded-full text-white" style="background: var(--color-rio-500)"><app-icon name="check" [size]="15" /></span>
              <span class="flex-1 font-semibold">{{ d.subjectName }}</span>
              <span class="chip chip-muted">Paralelo {{ d.sectionCode }}</span>
              @if (d.attempt > 1) { <span class="chip chip-teja">{{ d.attempt }}.ª matrícula</span> }
            </li>
          }
        </ul>
        <div class="mt-8 flex flex-wrap justify-center gap-2">
          <a routerLink="/estudiante/horario" class="btn btn-primary"><app-icon name="calendar" [size]="16" /> Ver mi horario</a>
          <a routerLink="/estudiante/cuenta" class="btn btn-teja"><app-icon name="wallet" [size]="16" /> Pagar mi matrícula</a>
        </div>
      </section>
    } @else {
      <app-page-header kicker="Matrícula" [title]="offer.value()?.periodName ?? 'Matrícula'" subtitle="Elige tus asignaturas y paralelos; el costo y las reglas se calculan en vivo.">
        <button class="btn btn-ghost" (click)="help.show('como-me-matriculo')"><app-icon name="help" [size]="16" /> ¿Cómo funciona?</button>
      </app-page-header>

      @if (offer.value(); as o) {
        @if (o.alreadyEnrolled) {
          <app-empty icon="check" title="Ya tienes una matrícula en este período" text="Si necesitas cambiar una asignatura, solicita un retiro desde Solicitudes.">
            <a routerLink="/estudiante/horario" class="btn btn-primary mt-2">Ver mi horario</a>
          </app-empty>
        } @else if (!o.typeToday) {
          <app-empty icon="clock" title="La matrícula no está abierta" text="Revisa el calendario: las fechas se publican al inicio de cada período." />
        } @else {
          <!-- Pasos -->
          <ol class="wizard mb-6" aria-label="Pasos">
            @for (s of steps; track s.n) {
              <li [class.on]="step() >= s.n" [class.now]="step() === s.n">
                <button (click)="go(s.n)" [disabled]="s.n > 1 && picks().length === 0"><span>{{ s.n }}</span>{{ s.label }}</button>
              </li>
            }
          </ol>

          <div class="grid gap-5 xl:grid-cols-[1fr_360px]">
            <div class="min-w-0">
              @if (step() === 1) {
                <div class="mb-4 flex flex-wrap items-center gap-2">
                  <div class="relative min-w-[220px] flex-1">
                    <span class="muted absolute left-3 top-1/2 -translate-y-1/2"><app-icon name="search" [size]="17" /></span>
                    <input class="field !pl-10" placeholder="Buscar asignatura o código" [value]="query()" (input)="query.set($any($event.target).value)" />
                  </div>
                  <label class="chip chip-muted cursor-pointer !py-2"><input type="checkbox" [checked]="onlyAvailable()" (change)="onlyAvailable.set(!onlyAvailable())" /> Solo las que puedo tomar</label>
                </div>
                <div class="grid gap-3 md:grid-cols-2">
                  @for (item of visibleSubjects(); track item.subject.id; let i = $index) {
                    <article class="subject tile" [class.picked]="isPicked(item.subject.id)" [class.locked]="!canPick(item.subject)"
                      [class.shake]="shaking() === item.subject.id" [style.--i]="i">
                      <header class="flex items-start justify-between gap-2">
                        <div class="min-w-0">
                          <p class="mono muted text-[11px] font-bold">{{ item.subject.code }} · Nivel {{ item.subject.level }} · {{ item.subject.credits }} créditos</p>
                          <h3 class="text-lg leading-snug">{{ item.subject.name }}</h3>
                        </div>
                        <span class="chip {{ statusOf(item.subject).chip }}">{{ statusOf(item.subject).label }}</span>
                      </header>
                      @if (item.subject.missingPrerequisites.length) {
                        <p class="muted mt-2 text-xs"><app-icon name="lock" [size]="12" /> Requiere: {{ item.subject.missingPrerequisites.join(', ') }}</p>
                      }
                      @if (item.subject.status === 'NEEDS_AUTHORIZATION') {
                        <p class="mt-2 text-xs font-semibold" style="color: var(--color-teja-600)">Tercera matrícula: necesitas una autorización registrada por Secretaría (RN-07).</p>
                      }
                      @if (canPick(item.subject)) {
                        <ul class="mt-3 space-y-2">
                          @for (sec of item.sections; track sec.sectionId) {
                            <li>
                              <button class="section" [class.on]="pickedSection(item.subject.id) === sec.sectionId" [disabled]="sec.seatsLeft <= 0"
                                (click)="toggle(item.subject, sec)" [attr.aria-pressed]="pickedSection(item.subject.id) === sec.sectionId">
                                <span class="flex items-center justify-between gap-2">
                                  <strong>Paralelo {{ sec.code }}</strong>
                                  <span class="text-xs font-bold" [style.color]="seatColor(sec)">{{ sec.seatsLeft > 0 ? sec.seatsLeft + ' cupos' : 'Sin cupos' }}</span>
                                </span>
                                <span class="seats"><span [style.width.%]="((sec.capacity - sec.seatsLeft) / sec.capacity) * 100" [style.background]="seatColor(sec)"></span></span>
                                <span class="muted block text-xs">
                                  @for (b of sec.blocks; track $index) { {{ day(b.weekday) }} {{ b.start.slice(0, 5) }}–{{ b.end.slice(0, 5) }}&nbsp; }
                                  @if (sec.teacherName) { · {{ sec.teacherName }} }
                                </span>
                                @if (clashWith(item.subject.id, sec); as other) {
                                  <span class="mt-1 block text-xs font-bold" style="color: var(--color-alerta-500)">Se cruza con {{ other }}</span>
                                }
                              </button>
                            </li>
                          } @empty {
                            <li class="muted text-xs">No hay paralelos abiertos para esta asignatura.</li>
                          }
                        </ul>
                      }
                    </article>
                  } @empty {
                    <div class="md:col-span-2"><app-empty icon="search" title="Nada coincide con tu búsqueda" /></div>
                  }
                </div>
              } @else if (step() === 2) {
                <section class="tile p-4">
                  <p class="kicker mb-3">Tu semana</p>
                  <app-week-grid [items]="gridItems()" />
                </section>
                @if (preview()?.violations?.length) {
                  <section class="mt-4 space-y-2">
                    @for (v of preview()!.violations; track v.code + v.subjectId) {
                      <div class="violation tile flex items-start gap-3 p-3">
                        <span class="chip chip-red">{{ v.code }}</span>
                        <p class="flex-1 text-sm">{{ v.message }}</p>
                        <button class="text-sm font-bold underline" style="color: var(--brand)" (click)="helpFor(v.code)">¿Qué hago?</button>
                      </div>
                    }
                  </section>
                }
              } @else {
                <section class="tile p-6">
                  <p class="kicker">Último paso</p>
                  <h2 class="mt-1 text-2xl">Confirma tu matrícula</h2>
                  <ul class="mt-4 space-y-2">
                    @for (p of picks(); track p.subject.id) {
                      <li class="flex items-center justify-between rounded-xl p-3" style="background: var(--surface-2)">
                        <span class="font-semibold">{{ p.subject.name }}</span><span class="chip chip-muted">Paralelo {{ p.section.code }}</span>
                      </li>
                    }
                  </ul>
                  <label class="mt-5 flex items-start gap-3 text-sm">
                    <input type="checkbox" class="mt-1" [checked]="accepted()" (change)="accepted.set(!accepted())" />
                    <span>Revisé mi horario y acepto los valores calculados. Entiendo que retirarme después tiene plazos y efectos (RN-09).</span>
                  </label>
                  <button class="btn btn-teja mt-5 w-full !py-3.5 text-base" [disabled]="!accepted() || busy() || blocking()" (click)="confirm()">
                    @if (busy()) { Reservando cupos… } @else { Confirmar matrícula <app-icon name="check" [size]="18" /> }
                  </button>
                  @if (blocking()) {
                    <p class="mt-2 text-sm font-semibold" style="color: var(--color-alerta-500)">Corrige las observaciones del paso 2 antes de confirmar.</p>
                  }
                </section>
              }
            </div>

            <!-- Resumen -->
            <aside class="summary tile h-fit p-5 xl:sticky xl:top-20">
              <div class="flex items-center justify-between">
                <p class="kicker">Tu selección</p>
                <span class="counter" [class.full]="picks().length >= o.maxSubjects">{{ picks().length }}/{{ o.maxSubjects }}</span>
              </div>
              <ul class="mt-3 space-y-1.5">
                @for (p of picks(); track p.subject.id) {
                  <li class="pick flex items-center gap-2 text-sm">
                    <span class="min-w-0 flex-1 truncate">{{ p.subject.name }}</span>
                    <span class="mono muted text-xs">{{ p.section.code }}</span>
                    <button class="muted" (click)="remove(p.subject.id)" [attr.aria-label]="'Quitar ' + p.subject.name"><app-icon name="x" [size]="14" /></button>
                  </li>
                } @empty {
                  <li class="muted py-3 text-sm">Aún no eliges asignaturas.</li>
                }
              </ul>
              <div class="mt-4 border-t pt-4" style="border-color: var(--line)">
                <p class="label">Costo estimado</p>
                @if (previewing()) {
                  <div class="skeleton h-16"></div>
                } @else if (preview()?.quote; as q) {
                  <ul class="space-y-1 text-sm">
                    @for (l of q.lines; track l.concept + l.description) {
                      <li class="flex justify-between gap-2"><span class="muted">{{ l.description }}</span><span class="mono">{{ l.net | money }}</span></li>
                      @if (l.discount > 0) {
                        <li class="flex justify-between text-xs" style="color: var(--color-rio-600)"><span>Descuento aplicado</span><span class="mono">−{{ l.discount | money }}</span></li>
                      }
                    }
                  </ul>
                  <p class="mt-3 flex items-baseline justify-between"><span class="font-bold">Total</span><span class="font-display text-3xl">{{ q.total | money }}</span></p>
                  @if (q.installments > 1) {
                    <p class="muted text-xs">En {{ q.installments }} cuotas · primera de {{ q.firstInstallment | money }}</p>
                  }
                } @else {
                  <p class="muted text-sm">Elige al menos una asignatura.</p>
                }
              </div>
              <div class="mt-5 flex gap-2">
                @if (step() > 1) { <button class="btn btn-ghost flex-1" (click)="go(step() - 1)">Atrás</button> }
                @if (step() < 3) {
                  <button class="btn btn-primary flex-1" [disabled]="picks().length === 0" (click)="go(step() + 1)">Continuar <app-icon name="arrow" [size]="16" /></button>
                }
              </div>
            </aside>
          </div>
        }
      } @else if (offer.isLoading()) {
        <app-skeleton [count]="4" [height]="120" />
      } @else {
        <app-empty icon="calendar" title="No hay un período en matrícula" />
      }
    }
  `,
  styles: `
    .wizard { display: flex; gap: .5rem; flex-wrap: wrap; }
    .wizard button { display: flex; align-items: center; gap: .5rem; padding: .45rem .9rem .45rem .45rem; border-radius: 999px; font-weight: 700; font-size: .88rem;
      background: var(--surface); border: 1px solid var(--line); color: var(--muted); transition: all .25s; }
    .wizard button span { display: grid; place-items: center; width: 1.6rem; height: 1.6rem; border-radius: 999px; background: var(--surface-2); color: var(--text); }
    .wizard li.on button span { background: var(--brand); color: white; }
    .wizard li.now button { color: var(--brand); border-color: var(--color-cupula-300); box-shadow: 0 0 0 4px var(--brand-soft); }
    .subject { padding: 1rem; animation: var(--animate-rise); animation-delay: calc(var(--i) * 30ms); transition: box-shadow .25s, border-color .25s; }
    .subject.picked { border-color: var(--color-cupula-400); box-shadow: 0 0 0 3px var(--brand-soft), var(--shadow-lift); }
    .subject.locked { opacity: .62; }
    .section { width: 100%; text-align: left; display: flex; flex-direction: column; gap: .3rem; padding: .6rem .75rem; border-radius: 12px;
      border: 1.5px solid var(--line); background: var(--surface); transition: all .2s; }
    .section:hover:not(:disabled) { border-color: var(--color-cupula-300); }
    .section.on { border-color: var(--color-cupula-500); background: var(--brand-soft); }
    .section:disabled { opacity: .5; cursor: not-allowed; }
    .seats { display: block; height: 4px; border-radius: 999px; background: var(--surface-2); overflow: hidden; }
    .seats span { display: block; height: 100%; border-radius: inherit; transition: width .5s; }
    .counter { font-family: var(--font-mono); font-weight: 700; padding: .15rem .6rem; border-radius: 999px; background: var(--brand-soft); color: var(--brand); }
    .counter.full { background: var(--color-teja-100); color: var(--color-teja-700); }
    .pick { animation: var(--animate-tile-in); }
    .violation { border-left: 4px solid var(--color-alerta-500); animation: var(--animate-rise); }
    .stamp { display: inline-block; font-family: var(--font-display); font-size: 2.2rem; letter-spacing: .12em; color: var(--color-teja-500);
      border: 4px double var(--color-teja-500); border-radius: 12px; padding: .3rem 1.2rem; animation: var(--animate-stamp); }
  `,
})
export class EnrollmentPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly celebration = inject(CelebrationService);
  protected readonly help = inject(HelpService);

  protected readonly offer = rxResource({ stream: () => this.api.get<Offer>('/me/enrollment/offer') });
  protected readonly steps = [
    { n: 1, label: 'Elige' },
    { n: 2, label: 'Revisa' },
    { n: 3, label: 'Confirma' },
  ];
  protected readonly step = signal(1);
  protected readonly picks = signal<Pick[]>([]);
  protected readonly query = signal('');
  protected readonly onlyAvailable = signal(true);
  protected readonly shaking = signal<string | null>(null);
  protected readonly preview = signal<Preview | null>(null);
  protected readonly previewing = signal(false);
  protected readonly accepted = signal(false);
  protected readonly busy = signal(false);
  protected readonly confirmation = signal<Confirmation | null>(null);
  private readonly previewRequests = new Subject<string[]>();

  protected readonly visibleSubjects = computed(() => {
    const q = this.query().trim().toLowerCase();
    return (this.offer.value()?.subjects ?? [])
      .filter((s) => !this.onlyAvailable() || this.canPick(s.subject))
      .filter((s) => !q || s.subject.name.toLowerCase().includes(q) || s.subject.code.toLowerCase().includes(q))
      .sort((a, b) => a.subject.level - b.subject.level);
  });

  protected readonly gridItems = computed<GridItem[]>(() =>
    this.picks().map((p, i) => ({ key: p.subject.id, title: p.subject.name, subtitle: 'Paralelo ' + p.section.code, blocks: p.section.blocks, tone: i })),
  );

  protected readonly blocking = computed(() => (this.preview()?.violations?.length ?? 0) > 0);

  constructor() {
    this.previewRequests
      .pipe(
        debounceTime(350),
        switchMap((ids) => {
          if (ids.length === 0) {
            return of(null);
          }
          this.previewing.set(true);
          return this.api.post<Preview>('/me/enrollment/preview', { sectionIds: ids }).pipe(catchError((p: Problem) => {
            this.toasts.problem(p);
            return of(null);
          }));
        }),
      )
      .subscribe((p) => {
        this.previewing.set(false);
        this.preview.set(p);
      });
    effect(() => this.previewRequests.next(this.picks().map((p) => p.section.sectionId)));
  }

  protected statusOf(s: SubjectProgress) {
    return SUBJECT_STATUS[s.status];
  }

  protected canPick(s: SubjectProgress): boolean {
    return s.status === 'AVAILABLE' || s.status === 'NEEDS_AUTHORIZATION';
  }

  protected isPicked(subjectId: string): boolean {
    return this.picks().some((p) => p.subject.id === subjectId);
  }

  protected pickedSection(subjectId: string): string | null {
    return this.picks().find((p) => p.subject.id === subjectId)?.section.sectionId ?? null;
  }

  protected clashWith(subjectId: string, section: SectionOffer): string | null {
    for (const p of this.picks()) {
      if (p.subject.id === subjectId) {
        continue;
      }
      if (p.section.blocks.some((a) => section.blocks.some((b) => overlaps(a, b)))) {
        return p.subject.name;
      }
    }
    return null;
  }

  protected toggle(subject: SubjectProgress, section: SectionOffer): void {
    const current = this.pickedSection(subject.id);
    if (current === section.sectionId) {
      this.remove(subject.id);
      return;
    }
    const max = this.offer.value()?.maxSubjects ?? 0;
    if (!current && this.picks().length >= max) {
      this.bump(subject.id);
      this.toasts.problem({ code: 'RN-04', detail: `Puedes tomar hasta ${max} asignaturas por período.`, status: 422, helpUrl: '/ayuda/como-me-matriculo#rn-04' });
      return;
    }
    const clash = this.clashWith(subject.id, section);
    if (clash) {
      this.bump(subject.id);
      this.toasts.problem({ code: 'RN-06', detail: `Ese paralelo se cruza con ${clash}. Elige otro paralelo.`, status: 422, helpUrl: '/ayuda/como-me-matriculo#rn-06' });
      return;
    }
    this.picks.update((list) => [...list.filter((p) => p.subject.id !== subject.id), { subject, section }]);
  }

  protected remove(subjectId: string): void {
    this.picks.update((list) => list.filter((p) => p.subject.id !== subjectId));
  }

  protected go(n: number): void {
    if (n < 1 || n > 3 || (n > 1 && this.picks().length === 0)) {
      return;
    }
    this.step.set(n);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected confirm(): void {
    this.busy.set(true);
    this.api.post<Confirmation>('/me/enrollment', { sectionIds: this.picks().map((p) => p.section.sectionId) }).subscribe({
      next: (c) => {
        this.busy.set(false);
        this.confirmation.set(c);
        this.celebration.burst();
        window.scrollTo({ top: 0, behavior: 'smooth' });
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
        this.offer.reload();
        this.step.set(2);
      },
    });
  }

  protected helpFor(code: string): void {
    const manual = code === 'RN-02' ? 'prerrequisitos' : code === 'RN-07' ? 'si-repruebo' : 'como-me-matriculo';
    this.help.show(manual, code.toLowerCase());
  }

  protected day(weekday: number): string {
    return WEEKDAYS[weekday].slice(0, 3);
  }

  protected seatColor(sec: SectionOffer): string {
    const ratio = sec.seatsLeft / sec.capacity;
    return ratio <= 0 ? 'var(--color-alerta-500)' : ratio < 0.2 ? 'var(--color-teja-500)' : ratio < 0.5 ? 'var(--color-oro-500)' : 'var(--color-rio-500)';
  }

  protected typeLabel(type: string): string {
    return { ORDINARY: 'ordinaria', EXTRAORDINARY: 'extraordinaria', SPECIAL: 'especial' }[type] ?? type;
  }

  private bump(subjectId: string): void {
    this.shaking.set(subjectId);
    setTimeout(() => this.shaking.set(null), 500);
  }
}
