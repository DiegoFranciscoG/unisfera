import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe, GradePipe, MoneyPipe, STATUS_LABEL } from '../../core/format';
import { CurrentSubject, Offer, Progress, ScheduleEntry, Statement } from '../../core/models';
import { WEEKDAYS } from '../../core/rules';
import { SettingsStore } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { CountUpComponent, RingComponent, SkeletonComponent } from '../../shared/widgets';

/** "Mi órbita": el estado del estudiante de un vistazo y la siguiente acción que le conviene. */
@Component({
  selector: 'app-student-home',
  imports: [RouterLink, IconComponent, RingComponent, CountUpComponent, SkeletonComponent, MoneyPipe, EcDatePipe, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (progress.value(); as p) {
      <!-- Héroe -->
      <section class="hero tile relative overflow-hidden p-6 md:p-8" style="animation: var(--animate-rise)">
        <div class="relative z-10 grid items-center gap-6 md:grid-cols-[1fr_auto]">
          <div>
            <p class="kicker !text-[var(--color-oro-300)]">{{ p.careerName }}</p>
            <h1 class="mt-2 text-[clamp(2rem,4vw,3rem)] text-white">Hola, {{ firstName() }}</h1>
            <p class="mt-2 max-w-xl text-white/75">
              Llevas <strong class="text-white">{{ p.approvedSubjects }} de {{ p.totalSubjects }}</strong> asignaturas aprobadas.
              @if (remainingLevels() > 0) { Te faltan unos <strong class="text-white">{{ remainingLevels() }} niveles</strong> para culminar tu malla. }
            </p>
            <div class="mt-5 flex flex-wrap gap-2">
              <span class="chip bg-white/15 text-white"><app-icon name="user" [size]="14" /> {{ p.studentCode }}</span>
              <span class="chip bg-white/15 text-white">{{ statusLabel(p.status) }}</span>
              @if (p.weightedAverage !== null) {
                <span class="chip bg-white/15 text-white"><app-icon name="grade" [size]="14" /> Promedio {{ p.weightedAverage | grade }}</span>
              }
            </div>
          </div>
          <div class="ring-wrap grid place-items-center">
            <app-ring [value]="creditsPct()" [max]="100" label="de avance" suffix="%" [size]="168" />
          </div>
        </div>
        <svg class="orbit-deco hidden sm:block" viewBox="0 0 400 400" aria-hidden="true">
          <ellipse cx="200" cy="200" rx="190" ry="70" /><ellipse cx="200" cy="200" rx="150" ry="150" /><circle class="sat" r="6" />
        </svg>
      </section>
    } @else if (progress.isLoading()) {
      <app-skeleton [count]="1" [height]="220" />
    }

    <div class="mt-6 grid gap-5 lg:grid-cols-3">
      <!-- Matrícula -->
      <section class="tile tile-hover p-5 lg:col-span-2" style="animation: var(--animate-rise); animation-delay: 80ms">
        @if (offer.value(); as o) {
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p class="kicker">Matrícula · {{ o.periodName }}</p>
              @if (o.alreadyEnrolled) {
                <h2 class="mt-1 text-2xl">Ya estás matriculado/a</h2>
                <p class="muted mt-1">Revisa tu horario y tus notas parciales cuando inicien las clases.</p>
              } @else if (o.typeToday) {
                <h2 class="mt-1 text-2xl">Tu matrícula está abierta</h2>
                <p class="muted mt-1">
                  {{ typeLabel(o.typeToday) }} hasta el <strong>{{ deadline(o) | ecDate }}</strong>.
                  Tienes {{ availableCount(o) }} asignaturas disponibles.
                </p>
              } @else {
                <h2 class="mt-1 text-2xl">Matrícula cerrada</h2>
                <p class="muted mt-1">La matrícula ordinaria abre el {{ o.ordinaryFrom | ecDate }}.</p>
              }
            </div>
            @if (!o.alreadyEnrolled && o.typeToday) {
              <div class="countdown text-center">
                <p class="font-display text-5xl leading-none"><app-count-up [value]="daysLeft(o)" /></p>
                <p class="muted text-xs font-bold uppercase tracking-widest">días</p>
              </div>
            }
          </div>
          <div class="mt-5 flex flex-wrap gap-2">
            @if (!o.alreadyEnrolled && o.typeToday) {
              <a routerLink="/estudiante/matricula" class="btn btn-teja">Matricularme ahora <app-icon name="arrow" [size]="16" /></a>
            } @else {
              <a routerLink="/estudiante/horario" class="btn btn-primary">Ver mi horario <app-icon name="calendar" [size]="16" /></a>
            }
            <a routerLink="/estudiante/malla" class="btn btn-ghost">Explorar mi malla</a>
          </div>
        } @else if (offer.isLoading()) {
          <app-skeleton [count]="2" [height]="40" />
        } @else {
          <p class="kicker">Matrícula</p>
          <h2 class="mt-1 text-2xl">No hay un período en matrícula</h2>
          <p class="muted mt-1">Te avisaremos en tus notificaciones cuando abra el próximo.</p>
        }
      </section>

      <!-- Cuenta -->
      <section class="tile tile-hover p-5" style="animation: var(--animate-rise); animation-delay: 140ms">
        <p class="kicker">Mi cuenta</p>
        @if (account.value(); as a) {
          <p class="mt-2 font-display text-4xl">{{ a.summary.balance | money }}</p>
          <p class="muted text-sm">{{ a.summary.balance > 0 ? 'Saldo pendiente' : 'Estás al día' }}</p>
          @if (nextInstallment(); as i) {
            <div class="mt-4 rounded-xl p-3" [class.overdue]="i.overdue" style="background: var(--surface-2)">
              <p class="text-sm font-bold">Cuota {{ i.number }} · {{ i.outstanding | money }}</p>
              <p class="muted text-xs">{{ i.overdue ? 'Vencida desde' : 'Vence el' }} {{ i.dueDate | ecDate }}</p>
            </div>
          }
          <a routerLink="/estudiante/cuenta" class="btn btn-primary mt-4 w-full">{{ a.summary.balance > 0 ? 'Pagar en línea' : 'Ver estado de cuenta' }}</a>
        } @else {
          <app-skeleton [count]="2" [height]="36" />
        }
      </section>

      <!-- Hoy -->
      <section class="tile p-5" style="animation: var(--animate-rise); animation-delay: 200ms">
        <div class="flex items-center justify-between">
          <p class="kicker">Hoy · {{ todayName }}</p>
          <a routerLink="/estudiante/horario" class="muted text-xs font-bold hover:underline">Semana</a>
        </div>
        <ul class="mt-3 space-y-2">
          @for (c of todayClasses(); track c.subjectCode + c.start) {
            <li class="flex items-center gap-3 rounded-xl p-2.5" style="background: var(--surface-2)">
              <span class="mono text-xs font-bold">{{ c.start }}</span>
              <span class="min-w-0 flex-1 truncate text-sm font-semibold">{{ c.subjectName }}</span>
              @if (c.classroom) { <span class="chip chip-muted">{{ c.classroom }}</span> }
            </li>
          } @empty {
            <li class="muted py-6 text-center text-sm">Sin clases hoy. ¡Aprovecha para adelantar tareas!</li>
          }
        </ul>
      </section>

      <!-- Notas -->
      <section class="tile p-5 lg:col-span-2" style="animation: var(--animate-rise); animation-delay: 260ms">
        <div class="flex items-center justify-between">
          <p class="kicker">Mis notas del período</p>
          <a routerLink="/estudiante/notas" class="muted text-xs font-bold hover:underline">Ver detalle</a>
        </div>
        <div class="mt-3 grid gap-3 sm:grid-cols-2">
          @for (g of grades.value() ?? []; track g.detailId) {
            <a routerLink="/estudiante/notas" class="flex items-center gap-3 rounded-xl p-2 hover:bg-[var(--surface-2)]">
              <app-ring [value]="graded(g) ? g.accumulated : null" [max]="settings.settings().scaleMax" [threshold]="settings.settings().passingGrade" [size]="64" />
              <div class="min-w-0">
                <p class="truncate text-sm font-bold">{{ g.subjectName }}</p>
                @if (g.attendanceWarning) {
                  <p class="text-xs font-bold" style="color: var(--color-teja-500)">⚠ Asistencia {{ g.attendancePct | grade }}%</p>
                } @else if (g.needed.neededAverage !== null && !g.needed.alreadyPassed) {
                  <p class="muted text-xs">Necesitas {{ g.needed.neededAverage | grade }} en lo que falta</p>
                } @else if (g.needed.alreadyPassed) {
                  <p class="text-xs font-bold" style="color: var(--color-rio-500)">✓ Ya alcanzaste la nota mínima</p>
                }
              </div>
            </a>
          } @empty {
            <p class="muted col-span-full py-6 text-center text-sm">Aún no hay calificaciones registradas en este período.</p>
          }
        </div>
      </section>
    </div>
  `,
  styles: `
    .hero { background: radial-gradient(120% 140% at 0% 0%, var(--color-cupula-500), var(--color-cupula-900) 75%); border: none; color: white; }
    .hero :is(h1) { color: white; }
    .ring-wrap { filter: drop-shadow(0 10px 30px rgba(0,0,0,.25)); --text: #fff; --muted: rgba(255,255,255,.7); --line: rgba(255,255,255,.18); }
    .orbit-deco { position: absolute; right: -60px; top: -80px; width: 420px; opacity: .22; fill: none; stroke: white; stroke-width: 1; }
    .orbit-deco .sat { fill: var(--color-oro-300); stroke: none; offset-path: path('M10,200 a190,70 0 1,0 380,0 a190,70 0 1,0 -380,0'); animation: orbit 14s linear infinite; }
    @keyframes orbit { to { offset-distance: 100%; } }
    .countdown { padding: .6rem 1rem; border-radius: 16px; background: var(--color-teja-100); color: var(--color-teja-700); }
    :host-context(.dark) .countdown { background: #4a2518; color: #f2b29b; }
    .overdue { box-shadow: inset 3px 0 0 var(--color-teja-500); }
  `,
})
export class StudentHomePage {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly todayName = WEEKDAYS[isoWeekday(this.settings.settings().zone)];

  protected readonly progress = rxResource({ stream: () => this.api.get<Progress>('/me/curriculum') });
  protected readonly offer = rxResource({ stream: () => this.api.get<Offer>('/me/enrollment/offer') });
  protected readonly account = rxResource({ stream: () => this.api.get<Statement>('/me/account', { size: 5 }) });
  protected readonly schedule = rxResource({ stream: () => this.api.get<ScheduleEntry[]>('/me/schedule') });
  protected readonly grades = rxResource({ stream: () => this.api.get<CurrentSubject[]>('/me/grades') });

  protected readonly firstName = computed(() => (this.auth.user()?.displayName ?? '').split(' ')[0]);
  protected readonly creditsPct = computed(() => {
    const p = this.progress.value();
    return p && p.totalCredits > 0 ? (p.approvedCredits / p.totalCredits) * 100 : 0;
  });
  protected readonly remainingLevels = computed(() => {
    const p = this.progress.value();
    if (!p) {
      return 0;
    }
    const pending = new Set(p.subjects.filter((s) => s.status !== 'APPROVED').map((s) => s.level));
    return pending.size;
  });
  protected readonly nextInstallment = computed(() =>
    (this.account.value()?.installments ?? []).filter((i) => i.outstanding > 0).sort((a, b) => a.dueDate.localeCompare(b.dueDate))[0] ?? null,
  );
  protected readonly todayClasses = computed(() => {
    const day = isoWeekday(this.settings.settings().zone);
    return (this.schedule.value() ?? [])
      .flatMap((e) => e.blocks.filter((b) => b.weekday === day).map((b) => ({ ...b, subjectName: e.subjectName, subjectCode: e.subjectCode })))
      .sort((a, b) => a.start.localeCompare(b.start));
  });

  protected graded(g: CurrentSubject): boolean {
    return g.components.some((c) => c.value !== null);
  }

  protected statusLabel(status: string): string {
    return STATUS_LABEL[status] ?? status;
  }

  protected typeLabel(type: string): string {
    return { ORDINARY: 'Matrícula ordinaria', EXTRAORDINARY: 'Matrícula extraordinaria', SPECIAL: 'Matrícula especial' }[type] ?? type;
  }

  protected deadline(o: Offer): string {
    return o.typeToday === 'ORDINARY' ? o.ordinaryTo : o.typeToday === 'EXTRAORDINARY' ? o.extraordinaryTo : o.specialTo;
  }

  protected daysLeft(o: Offer): number {
    const end = new Date(this.deadline(o) + 'T23:59:59-05:00').getTime();
    return Math.max(0, Math.ceil((end - Date.now()) / 86_400_000));
  }

  protected availableCount(o: Offer): number {
    return o.subjects.filter((s) => s.sections.length > 0 && (s.subject.status === 'AVAILABLE' || s.subject.status === 'NEEDS_AUTHORIZATION')).length;
  }
}

/** Día ISO (1 = lunes … 7 = domingo) en la zona de Ecuador. */
export function isoWeekday(zone: string): number {
  const name = new Intl.DateTimeFormat('en-US', { weekday: 'short', timeZone: zone }).format(new Date());
  return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(name) + 1;
}
