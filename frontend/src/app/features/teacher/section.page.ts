import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { GradePipe, RESULT_LABEL } from '../../core/format';
import { ActView, Problem, Roster, RosterRow } from '../../core/models';
import { CelebrationService, HelpService, SettingsStore, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { PageHeaderComponent, RingComponent, SkeletonComponent } from '../../shared/widgets';

interface ActPreview {
  sectionId: string;
  students: number;
  missingGrades: number;
  approved: number;
  failed: number;
  failedByAttendance: number;
  lines: { detailId: string; studentCode: string; fullName: string; finalGrade: number; attendancePct: number; result: string }[];
}

type Mark = 'PRESENT' | 'ABSENT' | 'LATE' | 'JUSTIFIED';
const MARKS: { value: Mark; short: string; label: string }[] = [
  { value: 'PRESENT', short: 'P', label: 'Presente' },
  { value: 'LATE', short: 'T', label: 'Tarde' },
  { value: 'JUSTIFIED', short: 'J', label: 'Justificada' },
  { value: 'ABSENT', short: 'F', label: 'Falta' },
];

/** Paralelo: hoja de notas tipo planilla, toma de asistencia y cierre ceremonial del acta (RN-13, RN-14). */
@Component({
  selector: 'app-teacher-section',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent, RingComponent, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (roster.value(); as r) {
      <a routerLink="/docente" class="muted mb-3 inline-flex items-center gap-1 text-sm font-semibold hover:underline"><app-icon name="back" [size]="15" /> Mis paralelos</a>
      <app-page-header [kicker]="'Paralelo ' + r.sectionCode + ' · ' + r.rows.length + ' estudiantes'" [title]="r.subjectName">
        <span class="chip" [class.chip-blue]="open()" [class.chip-green]="!open()">{{ open() ? 'Acta abierta' : 'Acta cerrada' }}</span>
      </app-page-header>

      <div class="tabs mb-5" role="tablist">
        @for (t of tabs; track t.key) {
          <button role="tab" [class.on]="tab() === t.key" [attr.aria-selected]="tab() === t.key" (click)="openTab(t.key)">
            <app-icon [name]="t.icon" [size]="16" /> {{ t.label }}
            @if (t.key === 'grades' && pendingCount() > 0) { <span class="badge">{{ pendingCount() }}</span> }
          </button>
        }
      </div>

      @switch (tab()) {
        @case ('grades') {
          <section class="tile overflow-hidden">
            <div class="flex flex-wrap items-center justify-between gap-3 border-b p-3" style="border-color: var(--line)">
              <p class="muted text-sm">Escala {{ r.scheme.scale.min }}–{{ r.scheme.scale.max }} · aprueba con {{ r.scheme.scale.passing }} y {{ r.scheme.scale.minAttendance }}% de asistencia.
                Usa <kbd>Tab</kbd>/<kbd>Enter</kbd> para avanzar.</p>
              <div class="flex gap-2">
                @if (pendingCount() > 0) { <button class="btn btn-ghost" (click)="discard()">Descartar</button> }
                <button class="btn btn-primary" [disabled]="pendingCount() === 0 || saving() || invalidCount() > 0 || !open()" (click)="save(r)">
                  <app-icon name="check" [size]="16" /> {{ saving() ? 'Guardando…' : 'Guardar ' + (pendingCount() || '') }}
                </button>
              </div>
            </div>
            <div class="overflow-x-auto">
              <table class="sheet">
                <thead>
                  <tr>
                    <th class="sticky-col">Estudiante</th>
                    @for (c of r.scheme.components; track c.id) { <th class="num">{{ c.name }}<span class="muted block text-[10px] font-semibold">{{ c.weight }}%</span></th> }
                    <th class="num">Acumulado</th><th class="num">Asistencia</th><th>Estado</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of r.rows; track row.detailId; let ri = $index) {
                    <tr [style.--i]="ri">
                      <td class="sticky-col">
                        <p class="font-semibold">{{ row.fullName }}</p>
                        <p class="mono muted text-[11px]">{{ row.studentCode }} @if (row.attempt > 1) { · {{ row.attempt }}.ª mat. }</p>
                      </td>
                      @for (c of r.scheme.components; track c.id; let ci = $index) {
                        <td class="num">
                          <input class="cell" inputmode="decimal" [attr.data-cell]="ri + '-' + ci" [disabled]="!open()"
                            [class.dirty]="isDirty(row.detailId, c.id)" [class.bad]="isInvalid(row.detailId, c.id, r)"
                            [value]="cellValue(row, c.id)" (input)="edit(row.detailId, c.id, $any($event.target).value)"
                            (keydown.enter)="$event.preventDefault(); move(ri + 1, ci)" [attr.aria-label]="c.name + ' de ' + row.fullName" />
                        </td>
                      }
                      <td class="num mono font-bold">{{ row.accumulated | grade }}</td>
                      <td class="num"><span class="mono" [style.color]="row.attendanceRisk ? 'var(--color-teja-500)' : ''">{{ row.attendancePct | grade }}%</span></td>
                      <td>
                        @if (row.provisionalResult) {
                          <span class="chip" [class.chip-green]="row.provisionalResult === 'APPROVED'" [class.chip-red]="row.provisionalResult !== 'APPROVED'">{{ result(row.provisionalResult) }}</span>
                        } @else { <span class="chip chip-muted">En curso</span> }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
        @case ('attendance') {
          <section class="tile p-5">
            @if (!session()) {
              <h2 class="text-xl">Nueva clase</h2>
              <div class="mt-3 grid gap-3 sm:grid-cols-[160px_130px_1fr_auto] sm:items-end">
                <div><label class="label" for="s-date">Fecha</label><input id="s-date" type="date" class="field" [value]="sDate()" (input)="sDate.set($any($event.target).value)" /></div>
                <div><label class="label" for="s-time">Hora</label><input id="s-time" type="time" class="field" [value]="sTime()" (input)="sTime.set($any($event.target).value)" /></div>
                <div><label class="label" for="s-topic">Tema</label><input id="s-topic" class="field" maxlength="200" [value]="sTopic()" (input)="sTopic.set($any($event.target).value)" placeholder="Ej.: Unidad 2 · Modelado" /></div>
                <button class="btn btn-primary" [disabled]="!sTopic().trim() || !open()" (click)="createSession(r)">Tomar asistencia</button>
              </div>
              <p class="muted mt-3 text-sm">Clases registradas: <strong>{{ r.sessions }}</strong>. La asistencia mínima para aprobar es {{ r.scheme.scale.minAttendance }}% (RN-15).</p>
            } @else {
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div><p class="kicker">{{ sDate() }} · {{ sTime() }}</p><h2 class="text-xl">{{ sTopic() }}</h2></div>
                <div class="flex gap-2">
                  <button class="btn btn-ghost" (click)="all('PRESENT', r)">Todos presentes</button>
                  <button class="btn btn-primary" [disabled]="saving()" (click)="saveAttendance()">Guardar asistencia</button>
                </div>
              </div>
              <ul class="mt-4 grid gap-2 md:grid-cols-2">
                @for (row of r.rows; track row.detailId; let i = $index) {
                  <li class="att flex items-center gap-3 rounded-xl p-2.5" [style.--i]="i">
                    <span class="min-w-0 flex-1 truncate text-sm font-semibold">{{ row.fullName }}</span>
                    <div class="marks" role="radiogroup" [attr.aria-label]="'Asistencia de ' + row.fullName">
                      @for (m of marks; track m.value) {
                        <button role="radio" [attr.aria-checked]="marked()[row.detailId] === m.value" [class]="'m-' + m.value" [class.on]="marked()[row.detailId] === m.value"
                          (click)="mark(row.detailId, m.value)" [title]="m.label">{{ m.short }}</button>
                      }
                    </div>
                  </li>
                }
              </ul>
            }
          </section>
        }
        @case ('act') {
          @if (preview.value(); as p) {
            @if (closedAct(); as act) {
              <section class="tile azulejo relative overflow-hidden p-8 text-center" style="animation: var(--animate-rise)">
                <div class="seal mx-auto">ACTA CERRADA</div>
                <p class="muted mt-4 text-sm">Firmada digitalmente · {{ act.closedAt }}</p>
                <p class="mono mt-2 break-all text-[11px]">SHA-256 {{ act.contentHash }}</p>
                <p class="muted mt-4 text-sm">Las notas ya están en el kardex de cada estudiante. Cualquier corrección requiere rectificación autorizada (RN-14).</p>
                <a routerLink="/docente/actas" class="btn btn-primary mt-5">Ver historial de actas</a>
              </section>
            } @else {
              <div class="grid gap-5 lg:grid-cols-[1fr_340px]">
                <section class="tile overflow-x-auto">
                  <table class="table">
                    <thead><tr><th>Estudiante</th><th>Nota final</th><th>Asistencia</th><th>Resultado</th></tr></thead>
                    <tbody>
                      @for (l of p.lines; track l.detailId) {
                        <tr><td>{{ l.fullName }}</td><td class="mono font-bold">{{ l.finalGrade | grade }}</td><td>{{ l.attendancePct | grade }}%</td>
                          <td><span class="chip" [class.chip-green]="l.result === 'APPROVED'" [class.chip-red]="l.result !== 'APPROVED'">{{ result(l.result) }}</span></td></tr>
                      }
                    </tbody>
                  </table>
                </section>
                <aside class="tile h-fit p-5">
                  <div class="flex justify-around">
                    <app-ring [value]="p.approved" [max]="p.students || 1" label="aprueban" [size]="96" />
                    <app-ring [value]="p.failed + p.failedByAttendance" [max]="p.students || 1" label="reprueban" [size]="96" />
                  </div>
                  @if (p.missingGrades > 0) {
                    <p class="mt-4 rounded-xl p-3 text-sm font-semibold" style="background: var(--color-teja-100); color: var(--color-teja-700)">
                      Faltan {{ p.missingGrades }} calificaciones. El acta solo se cierra completa (RN-13).</p>
                  } @else if (open()) {
                    <p class="mt-4 text-sm">Al cerrar, las notas se vuelven definitivas, se calcula su huella SHA-256 y se firman. Escribe <strong class="mono">{{ phrase() }}</strong> para confirmar.</p>
                    <input class="field mono mt-3 text-center uppercase tracking-widest" [value]="confirmText()" (input)="confirmText.set($any($event.target).value)"
                      [attr.aria-label]="'Escriba ' + phrase()" />
                    <button class="btn btn-teja mt-3 w-full !py-3" [disabled]="!phraseOk() || saving()" (click)="close()">
                      <app-icon name="seal" [size]="17" /> Cerrar y firmar acta
                    </button>
                  }
                  <button class="btn btn-ghost mt-3 w-full" (click)="help.show('docente-calificaciones-y-actas')">¿Qué pasa al cerrar?</button>
                </aside>
              </div>
            }
          } @else {
            <app-skeleton [count]="1" [height]="260" />
          }
        }
      }
    } @else {
      <app-skeleton [count]="2" [height]="200" />
    }
  `,
  styles: `
    .tabs { display: inline-flex; gap: 4px; padding: 4px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--line); }
    .tabs button { display: inline-flex; align-items: center; gap: .4rem; padding: .5rem 1rem; border-radius: 999px; font-weight: 700; font-size: .88rem; color: var(--muted); }
    .tabs button.on { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-tile); }
    .badge { min-width: 1.2rem; padding: 0 .35rem; border-radius: 999px; background: var(--color-teja-500); color: white; font-size: .7rem; }
    .sheet { width: 100%; border-collapse: separate; border-spacing: 0; font-size: .88rem; }
    .sheet th { text-align: left; padding: .6rem .7rem; font-size: .72rem; text-transform: uppercase; letter-spacing: .06em; color: var(--muted);
      background: var(--surface-2); border-bottom: 1px solid var(--line); white-space: nowrap; }
    .sheet td { padding: .35rem .7rem; border-bottom: 1px solid var(--line); background: var(--surface); }
    .sheet tbody tr { animation: var(--animate-rise); animation-delay: calc(var(--i) * 20ms); }
    .sheet tbody tr:hover td { background: var(--surface-2); }
    .num { text-align: center; }
    .sticky-col { position: sticky; left: 0; z-index: 1; min-width: 210px; }
    .cell { width: 4.6rem; text-align: center; font-family: var(--font-mono); font-weight: 600; padding: .4rem; border-radius: 8px;
      border: 1.5px solid transparent; background: var(--surface-2); transition: border-color .15s, background .15s; }
    .cell:focus { outline: none; border-color: var(--color-cupula-400); background: var(--surface); box-shadow: 0 0 0 3px var(--brand-soft); }
    .cell.dirty { border-color: var(--color-oro-400); background: color-mix(in srgb, var(--color-oro-100) 50%, var(--surface)); }
    .cell.bad { border-color: var(--color-alerta-500); animation: shake .4s; }
    kbd { font-family: var(--font-mono); font-size: .75em; padding: .05rem .35rem; border-radius: 5px; border: 1px solid var(--line); background: var(--surface); }
    .att { background: var(--surface-2); animation: var(--animate-rise); animation-delay: calc(var(--i) * 25ms); }
    .marks { display: flex; gap: 4px; }
    .marks button { width: 2rem; height: 2rem; border-radius: 10px; font-weight: 800; font-size: .8rem; border: 1.5px solid var(--line); background: var(--surface); color: var(--muted); transition: all .15s; }
    .marks button.on { color: white; transform: scale(1.08); border-color: transparent; }
    .marks .m-PRESENT.on { background: var(--color-rio-500); }
    .marks .m-LATE.on { background: var(--color-oro-500); }
    .marks .m-JUSTIFIED.on { background: var(--color-cupula-500); }
    .marks .m-ABSENT.on { background: var(--color-alerta-500); }
    .seal { display: inline-block; font-family: var(--font-display); font-size: 2rem; letter-spacing: .14em; color: var(--color-rio-600);
      border: 4px double var(--color-rio-500); border-radius: 14px; padding: .4rem 1.4rem; animation: var(--animate-stamp); }
  `,
})
export class TeacherSectionPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly celebration = inject(CelebrationService);
  private readonly settings = inject(SettingsStore);
  protected readonly help = inject(HelpService);
  readonly sectionId = input.required<string>();
  protected readonly tab = signal<'grades' | 'attendance' | 'act'>('grades');

  protected readonly roster = rxResource({ params: () => this.sectionId(), stream: ({ params }) => this.api.get<Roster>(`/teacher/sections/${params}/roster`) });
  protected readonly preview = rxResource({
    params: () => (this.tab() === 'act' ? this.sectionId() : undefined),
    stream: ({ params }) => this.api.get<ActPreview>(`/teacher/sections/${params}/act/preview`),
  });
  protected readonly tabs = [
    { key: 'grades' as const, label: 'Calificaciones', icon: 'grade' },
    { key: 'attendance' as const, label: 'Asistencia', icon: 'calendar' },
    { key: 'act' as const, label: 'Acta', icon: 'seal' },
  ];
  protected readonly marks = MARKS;
  private readonly pending = signal<Record<string, string>>({});
  protected readonly saving = signal(false);
  protected readonly session = signal<string | null>(null);
  protected readonly sDate = signal(new Date().toISOString().slice(0, 10));
  protected readonly sTime = signal('07:00');
  protected readonly sTopic = signal('');
  protected readonly marked = signal<Record<string, Mark>>({});
  protected readonly confirmText = signal('');
  protected readonly closedAct = signal<ActView | null>(null);

  protected readonly open = computed(() => this.roster.value()?.actStatus === 'OPEN' && !this.closedAct());
  protected readonly pendingCount = computed(() => Object.keys(this.pending()).length);
  protected readonly phrase = computed(() => this.settings.settings().closeConfirmationPhrase);
  protected readonly phraseOk = computed(() => !!this.phrase() && this.confirmText().trim().toUpperCase() === this.phrase().toUpperCase());
  protected readonly invalidCount = computed(() => {
    const r = this.roster.value();
    return r ? Object.keys(this.pending()).filter((k) => this.invalidValue(this.pending()[k], r)).length : 0;
  });

  protected openTab(key: 'grades' | 'attendance' | 'act'): void {
    this.tab.set(key);
    if (key === 'act') {
      this.preview.reload();
    }
  }

  protected cellValue(row: RosterRow, componentId: string): string {
    const key = row.detailId + '|' + componentId;
    if (key in this.pending()) {
      return this.pending()[key];
    }
    const g = row.grades.find((x) => x.componentId === componentId);
    return g ? String(g.value) : '';
  }

  protected edit(detailId: string, componentId: string, value: string): void {
    this.pending.update((p) => ({ ...p, [detailId + '|' + componentId]: value.replace(',', '.') }));
  }

  protected isDirty(detailId: string, componentId: string): boolean {
    return detailId + '|' + componentId in this.pending();
  }

  protected isInvalid(detailId: string, componentId: string, r: Roster): boolean {
    const v = this.pending()[detailId + '|' + componentId];
    return v !== undefined && this.invalidValue(v, r);
  }

  private invalidValue(v: string, r: Roster): boolean {
    if (v.trim() === '') {
      return true;
    }
    const n = Number(v);
    return Number.isNaN(n) || n < r.scheme.scale.min || n > r.scheme.scale.max;
  }

  protected move(row: number, col: number): void {
    (document.querySelector(`[data-cell="${row}-${col}"]`) as HTMLInputElement | null)?.focus();
  }

  protected discard(): void {
    this.pending.set({});
  }

  protected save(r: Roster): void {
    const grades = Object.entries(this.pending()).map(([key, value]) => {
      const [detailId, componentId] = key.split('|');
      const row = r.rows.find((x) => x.detailId === detailId);
      const version = row?.grades.find((g) => g.componentId === componentId)?.version ?? null;
      return { detailId, componentId, value: Number(value), version };
    });
    this.saving.set(true);
    this.api.post<{ saved: number }>(`/teacher/sections/${this.sectionId()}/grades`, { grades }).subscribe({
      next: (res) => {
        this.saving.set(false);
        this.pending.set({});
        this.toasts.ok(`${res.saved} calificaciones guardadas`, 'Los estudiantes ya las ven en su portal.');
        this.roster.reload();
      },
      error: (p: Problem) => {
        this.saving.set(false);
        this.toasts.problem(p);
        if (p.status === 409) {
          this.roster.reload();
        }
      },
    });
  }

  protected createSession(r: Roster): void {
    this.api.post<{ id: string }>(`/teacher/sections/${this.sectionId()}/sessions`, { date: this.sDate(), startsAt: this.sTime(), topic: this.sTopic().trim() }).subscribe({
      next: (s) => {
        this.session.set(s.id);
        this.all('PRESENT', r);
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }

  protected all(mark: Mark, r: Roster): void {
    this.marked.set(Object.fromEntries(r.rows.map((row) => [row.detailId, mark])));
  }

  protected mark(detailId: string, mark: Mark): void {
    this.marked.update((m) => ({ ...m, [detailId]: mark }));
  }

  protected saveAttendance(): void {
    const marks = Object.entries(this.marked()).map(([detailId, status]) => ({ detailId, status }));
    this.saving.set(true);
    this.api.post(`/teacher/sections/${this.sectionId()}/sessions/${this.session()}/attendance`, { marks }).subscribe({
      next: () => {
        this.saving.set(false);
        this.session.set(null);
        this.sTopic.set('');
        this.toasts.ok('Asistencia registrada');
        this.roster.reload();
      },
      error: (p: Problem) => {
        this.saving.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected close(): void {
    this.saving.set(true);
    this.api.post<ActView>(`/teacher/sections/${this.sectionId()}/act/close`, { confirmation: this.confirmText().trim() }).subscribe({
      next: (act) => {
        this.saving.set(false);
        this.closedAct.set(act);
        this.celebration.burst();
        this.roster.reload();
      },
      error: (p: Problem) => {
        this.saving.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected result(r: string): string {
    return RESULT_LABEL[r] ?? r;
  }
}
