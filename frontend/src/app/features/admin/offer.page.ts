import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe } from '../../core/format';
import { Block, Page, Problem } from '../../core/models';
import { WEEKDAYS } from '../../core/rules';
import { ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface PeriodView {
  id: string;
  code: string;
  name: string;
  type: string;
  ordinaryEnrollmentFrom: string;
  ordinaryEnrollmentTo: string;
  extraordinaryEnrollmentTo: string;
  specialEnrollmentTo: string;
  classesStart: string;
  classesEnd: string;
  gradesCloseDeadline: string;
  status: string;
}

interface SectionView {
  id: string;
  subjectCode: string;
  subjectName: string;
  code: string;
  capacity: number;
  enrolledCount: number;
  seatsLeft: number;
  teacherName: string | null;
  status: string;
  blocks: Block[];
}

interface Career {
  id: string;
  code: string;
  name: string;
  degreeTitle: string;
  modality: string;
  durationPeriods: number;
  status: string;
}

interface Curriculum {
  id: string;
  versionCode: string;
  name: string;
  validFrom: string;
  status: string;
}

interface Tree {
  curriculum: Curriculum;
  levels: { id: string; number: number; name: string; subjects: { id: string; code: string; name: string; totalHours: number; credits: number; prerequisites: string[] }[] }[];
}

const PERIOD_STATUS: Record<string, { label: string; chip: string; next?: string }> = {
  PLANNED: { label: 'Planificado', chip: 'chip-muted', next: 'ENROLLMENT' },
  ENROLLMENT: { label: 'En matrícula', chip: 'chip-gold', next: 'IN_PROGRESS' },
  IN_PROGRESS: { label: 'En clases', chip: 'chip-blue', next: 'CLOSED' },
  CLOSED: { label: 'Cerrado', chip: 'chip-green' },
};

/** Oferta académica: períodos con su calendario, paralelos con ocupación y mallas por carrera. */
@Component({
  selector: 'app-admin-offer',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Académico" title="Oferta académica" subtitle="Calendario de cada período, paralelos con su ocupación y las mallas vigentes." >
      <div class="seg">
        <button [class.on]="tab() === 'periods'" (click)="tab.set('periods')"><app-icon name="calendar" [size]="15" /> Períodos</button>
        <button [class.on]="tab() === 'curricula'" (click)="tab.set('curricula')"><app-icon name="book" [size]="15" /> Mallas</button>
      </div>
    </app-page-header>

    @if (tab() === 'periods') {
      <div class="grid gap-5 xl:grid-cols-[340px_1fr]">
        <section class="space-y-2">
          @for (p of periods.value()?.items ?? []; track p.id; let i = $index) {
            <button class="period tile w-full p-4 text-left" [class.sel]="periodId() === p.id" (click)="periodId.set(p.id)" [style.--i]="i">
              <div class="flex items-center justify-between">
                <span class="mono font-bold">{{ p.code }}</span>
                <span class="chip {{ ps(p.status).chip }}">{{ ps(p.status).label }}</span>
              </div>
              <p class="muted mt-1 text-xs">Clases {{ p.classesStart | ecDate }} → {{ p.classesEnd | ecDate }}</p>
            </button>
          }
        </section>
        <section>
          @if (period(); as p) {
            <div class="tile p-5">
              <div class="flex flex-wrap items-start justify-between gap-3">
                <div><p class="kicker">{{ p.type === 'ORDINARY' ? 'Período ordinario' : 'Período extraordinario' }}</p><h2 class="text-2xl">{{ p.name }}</h2></div>
                @if (auth.has('CATALOG_WRITE') && ps(p.status).next; as next) {
                  <button class="btn btn-primary" (click)="advance(p, next)">Pasar a «{{ ps(next).label }}»</button>
                }
              </div>
              <ol class="calendar mt-5">
                @for (m of milestones(p); track m.label) {
                  <li [class.past]="m.date < today"><span class="dot"></span><p class="text-xs font-bold">{{ m.label }}</p><p class="mono muted text-[11px]">{{ m.date | ecDate }}</p></li>
                }
              </ol>
            </div>
            <div class="tile mt-4 overflow-x-auto">
              <table class="table">
                <thead><tr><th>Asignatura</th><th>Paralelo</th><th>Docente</th><th>Horario</th><th>Ocupación</th></tr></thead>
                <tbody>
                  @for (s of sections.value()?.items ?? []; track s.id) {
                    <tr>
                      <td><span class="mono muted text-xs">{{ s.subjectCode }}</span><br /><span class="font-semibold">{{ s.subjectName }}</span></td>
                      <td class="mono">{{ s.code }}</td>
                      <td class="text-sm">{{ s.teacherName ?? '—' }}</td>
                      <td class="text-xs">@for (b of s.blocks; track $index) { {{ day(b.weekday) }} {{ b.start.slice(0, 5) }}–{{ b.end.slice(0, 5) }}<br /> }</td>
                      <td class="min-w-[140px]">
                        <div class="flex justify-between text-xs font-bold"><span>{{ s.enrolledCount }}/{{ s.capacity }}</span><span class="muted">{{ s.seatsLeft }} libres</span></div>
                        <div class="bar"><span [style.width.%]="(s.enrolledCount / s.capacity) * 100" [class.full]="s.seatsLeft === 0"></span></div>
                      </td>
                    </tr>
                  } @empty {
                    <tr><td colspan="5" class="muted py-8 text-center">Este período aún no tiene paralelos.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <app-empty icon="calendar" title="Elige un período" text="Verás su calendario y la ocupación de cada paralelo." />
          }
        </section>
      </div>
    } @else {
      <div class="grid gap-5 xl:grid-cols-[320px_1fr]">
        <section class="space-y-2">
          @for (c of careers.value()?.items ?? []; track c.id) {
            <button class="period tile w-full p-4 text-left" [class.sel]="careerId() === c.id" (click)="careerId.set(c.id)">
              <span class="mono muted text-xs font-bold">{{ c.code }}</span>
              <p class="font-semibold leading-snug">{{ c.name }}</p>
              <p class="muted text-xs">{{ c.modality }} · {{ c.durationPeriods }} períodos</p>
            </button>
          }
        </section>
        <section>
          @if (careerId()) {
            <div class="mb-3 flex flex-wrap gap-2">
              @for (cu of curricula.value() ?? []; track cu.id) {
                <button class="chip !py-1.5" [class.chip-blue]="curriculumId() === cu.id" [class.chip-muted]="curriculumId() !== cu.id" (click)="curriculumId.set(cu.id)">
                  Malla {{ cu.versionCode }} · {{ cu.status === 'ACTIVE' ? 'vigente' : cu.status === 'DRAFT' ? 'borrador' : 'cerrada' }}
                </button>
              }
            </div>
            @if (tree.value(); as t) {
              <div class="levels">
                @for (l of t.levels; track l.id) {
                  <div class="tile p-3">
                    <p class="label">{{ l.name }}</p>
                    <ul class="space-y-2">
                      @for (s of l.subjects; track s.id) {
                        <li class="subject"><span class="mono muted text-[10px] font-bold">{{ s.code }}</span><span class="block text-sm font-semibold leading-snug">{{ s.name }}</span>
                          <span class="muted text-[11px]">{{ s.totalHours }} h · {{ s.credits }} cr @if (s.prerequisites.length) { · {{ s.prerequisites.length }} prerreq. }</span></li>
                      }
                    </ul>
                  </div>
                }
              </div>
            } @else if (curriculumId()) {
              <app-skeleton [count]="1" [height]="300" />
            }
          } @else {
            <app-empty icon="book" title="Elige una carrera" text="Verás sus mallas, niveles y asignaturas con prerrequisitos." />
          }
        </section>
      </div>
    }
  `,
  styles: `
    .seg { display: inline-flex; padding: 4px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--line); }
    .seg button { display: inline-flex; gap: .35rem; align-items: center; padding: .45rem .9rem; border-radius: 999px; font-weight: 700; font-size: .85rem; color: var(--muted); }
    .seg button.on { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-tile); }
    .period { transition: transform .2s, box-shadow .2s, border-color .2s; animation: var(--animate-rise); animation-delay: calc(var(--i, 0) * 40ms); }
    .period:hover { transform: translateX(3px); }
    .period.sel { border-color: var(--color-cupula-400); box-shadow: 0 0 0 3px var(--brand-soft); }
    .calendar { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: .5rem; position: relative; }
    .calendar li { position: relative; padding-top: 1.1rem; }
    .calendar .dot { position: absolute; top: 0; left: 0; width: .8rem; height: .8rem; border-radius: 999px; background: var(--color-cupula-400); box-shadow: 0 0 0 4px var(--brand-soft); }
    .calendar li.past .dot { background: var(--color-rio-500); box-shadow: 0 0 0 4px var(--color-rio-100); }
    .bar { height: 6px; border-radius: 999px; background: var(--surface-2); overflow: hidden; margin-top: 4px; }
    .bar span { display: block; height: 100%; background: var(--color-rio-500); transition: width .8s; }
    .bar span.full { background: var(--color-teja-500); }
    .levels { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: .8rem; }
    .subject { padding: .55rem .65rem; border-radius: 10px; background: var(--surface-2); border-left: 3px solid var(--color-cupula-400); }
  `,
})
export class AdminOfferPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  protected readonly auth = inject(AuthStore);
  protected readonly today = new Date().toISOString().slice(0, 10);
  protected readonly tab = signal<'periods' | 'curricula'>('periods');
  protected readonly periodId = signal<string | null>(null);
  protected readonly careerId = signal<string | null>(null);
  protected readonly curriculumId = signal<string | null>(null);

  protected readonly periods = rxResource({ stream: () => this.api.get<Page<PeriodView>>('/catalog/periods', { size: 30 }) });
  protected readonly sections = rxResource({
    params: () => this.periodId() ?? undefined,
    stream: ({ params }) => this.api.get<Page<SectionView>>(`/catalog/periods/${params}/sections`, { size: 100 }),
  });
  protected readonly careers = rxResource({ stream: () => this.api.get<Page<Career>>('/catalog/careers', { size: 100 }) });
  protected readonly curricula = rxResource({
    params: () => this.careerId() ?? undefined,
    stream: ({ params }) => this.api.get<Curriculum[]>(`/catalog/careers/${params}/curricula`),
  });
  protected readonly tree = rxResource({
    params: () => this.curriculumId() ?? undefined,
    stream: ({ params }) => this.api.get<Tree>(`/catalog/curricula/${params}`),
  });
  protected readonly period = computed(() => (this.periods.value()?.items ?? []).find((p) => p.id === this.periodId()) ?? null);

  protected ps(status: string) {
    return PERIOD_STATUS[status] ?? { label: status, chip: 'chip-muted' };
  }

  protected milestones(p: PeriodView) {
    return [
      { label: 'Abre matrícula', date: p.ordinaryEnrollmentFrom },
      { label: 'Fin ordinaria', date: p.ordinaryEnrollmentTo },
      { label: 'Fin extraordinaria', date: p.extraordinaryEnrollmentTo },
      { label: 'Fin especial', date: p.specialEnrollmentTo },
      { label: 'Inicio de clases', date: p.classesStart },
      { label: 'Fin de clases', date: p.classesEnd },
      { label: 'Cierre de actas', date: p.gradesCloseDeadline },
    ];
  }

  protected day(d: number): string {
    return WEEKDAYS[d].slice(0, 3);
  }

  protected advance(p: PeriodView, next: string): void {
    this.api.post(`/admin/catalog/periods/${p.id}/status/${next}`).subscribe({
      next: () => {
        this.toasts.ok('Período actualizado');
        this.periods.reload();
      },
      error: (e: Problem) => this.toasts.problem(e),
    });
  }
}
