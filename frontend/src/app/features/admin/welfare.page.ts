import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { debounceTime } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe, GradePipe } from '../../core/format';
import { Page, Problem } from '../../core/models';
import { CelebrationService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent } from '../../shared/widgets';
import { APPLICATION_LABEL, ApplicationView, CallView } from '../student/scholarships.page';

interface TypeView { id: string; code: string; name: string; mode: string; value: number }
interface Criterion { id: string; name: string; maxScore: number; weight: number }
interface Period { id: string; code: string; name: string; status: string }
interface Alert { id: string; studentId: string; studentCode: string; studentName: string; careerName: string; risk: string; reasons: string; status: string; detectedAt: string }
interface Attention { id: string; category: string; attendedAt: string; summary: string; details: string | null; followUpRequired: boolean; professional: string }
interface StudentHit { id: string; code: string; fullName: string }

const RISK: Record<string, string> = { HIGH: 'chip-red', MEDIUM: 'chip-gold', LOW: 'chip-blue' };
const CATEGORY: Record<string, string> = { HEALTH: 'Salud', PSYCHOLOGY: 'Psicología', ORIENTATION: 'Orientación', SOCIAL_WORK: 'Trabajo social' };

/** Bienestar: convocatorias y comité de becas, alertas tempranas de deserción y atenciones cifradas. */
@Component({
  selector: 'app-admin-welfare',
  imports: [IconComponent, PageHeaderComponent, EmptyComponent, EcDatePipe, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Bienestar universitario" title="Que nadie se quede fuera de órbita" subtitle="Becas con criterios publicados y comité, alertas tempranas y atención con datos sensibles protegidos." />

    <div class="tabs mb-5">
      @if (auth.has('SCHOLARSHIP_READ')) { <button [class.on]="tab() === 'calls'" (click)="tab.set('calls')"><app-icon name="gift" [size]="15" /> Becas</button> }
      @if (auth.has('DROPOUT_MANAGE')) { <button [class.on]="tab() === 'alerts'" (click)="tab.set('alerts')"><app-icon name="flag" [size]="15" /> Alertas</button> }
      @if (auth.has('WELFARE_SENSITIVE')) { <button [class.on]="tab() === 'care'" (click)="tab.set('care')"><app-icon name="heart" [size]="15" /> Atención</button> }
    </div>

    @switch (tab()) {
      @case ('calls') {
        <div class="grid gap-5 xl:grid-cols-[360px_1fr]">
          <section class="space-y-2">
            @if (auth.has('SCHOLARSHIP_MANAGE')) {
              <button class="btn btn-primary w-full" (click)="creating.set(!creating())"><app-icon name="plus" [size]="16" /> Nueva convocatoria</button>
            }
            @for (c of calls.value() ?? []; track c.id) {
              <button class="item tile w-full p-3 text-left" [class.sel]="callId() === c.id" (click)="callId.set(c.id); creating.set(false)">
                <div class="flex items-center justify-between"><span class="kicker">{{ c.typeName }}</span><span class="chip chip-muted">{{ c.status }}</span></div>
                <p class="font-semibold">{{ c.name }}</p>
                <p class="muted text-xs">{{ c.periodCode }} · {{ c.applications }} postulaciones · {{ c.quota }} cupos</p>
              </button>
            }
          </section>
          <section>
            @if (creating()) {
              <form class="tile space-y-3 p-5" (submit)="$event.preventDefault(); createCall()" style="animation: var(--animate-rise)">
                <h2 class="text-xl">Nueva convocatoria</h2>
                <div class="grid gap-3 md:grid-cols-2">
                  <div><label class="label" for="ty">Tipo de beca</label><select id="ty" class="field" (change)="form.typeId = $any($event.target).value">
                    <option value="">Elegir…</option>@for (t of types.value() ?? []; track t.id) { <option [value]="t.id">{{ t.name }} ({{ t.mode === 'PERCENT' ? t.value + '%' : '$' + t.value }})</option> }</select></div>
                  <div><label class="label" for="pe">Período</label><select id="pe" class="field" (change)="form.periodId = $any($event.target).value">
                    <option value="">Elegir…</option>@for (p of periods.value()?.items ?? []; track p.id) { <option [value]="p.id">{{ p.code }} · {{ p.name }}</option> }</select></div>
                  <div class="md:col-span-2"><label class="label" for="nm">Nombre</label><input id="nm" class="field" maxlength="160" (input)="form.name = $any($event.target).value" /></div>
                  <div><label class="label" for="qt">Cupos</label><input id="qt" class="field" type="number" min="1" (input)="form.quota = $any($event.target).valueAsNumber" /></div>
                  <div><label class="label" for="rb">Resultados hasta</label><input id="rb" class="field" type="date" (input)="form.resultsBy = $any($event.target).value" /></div>
                  <div><label class="label" for="op">Abre</label><input id="op" class="field" type="datetime-local" (input)="form.opensAt = $any($event.target).value" /></div>
                  <div><label class="label" for="cl">Cierra</label><input id="cl" class="field" type="datetime-local" (input)="form.closesAt = $any($event.target).value" /></div>
                </div>
                <p class="label">Documentos requeridos (código;nombre, uno por línea)</p>
                <textarea class="field mono text-sm" rows="3" (input)="form.documents = $any($event.target).value">CERT_INGRESOS;Certificado de ingresos familiares&#10;CEDULA_REPRESENTANTE;Copia de cédula del representante</textarea>
                <p class="label">Criterios (nombre;puntaje máximo;peso %, uno por línea; los pesos suman 100)</p>
                <textarea class="field mono text-sm" rows="3" (input)="form.criteria = $any($event.target).value">Situación socioeconómica;100;60&#10;Rendimiento académico;100;40</textarea>
                <button class="btn btn-primary">Publicar convocatoria</button>
              </form>
            } @else if (call(); as c) {
              <div class="tile p-5">
                <p class="kicker">{{ c.typeName }} · {{ c.mode === 'PERCENT' ? c.value + '%' : '$' + c.value }}</p>
                <h2 class="text-2xl">{{ c.name }}</h2>
                <p class="muted text-sm">Cierra {{ c.closesAt | ecDate: 'datetime' }} · resultados hasta {{ c.resultsBy | ecDate }}</p>
                <div class="mt-3 flex flex-wrap gap-1.5">@for (cr of criteria.value() ?? []; track cr.id) { <span class="chip chip-blue">{{ cr.name }} · {{ cr.weight }}%</span> }</div>
              </div>
              <div class="tile mt-4 overflow-x-auto">
                <table class="table">
                  <thead><tr><th></th><th>Estudiante</th><th>Estado</th><th>Documentos</th><th>Puntaje</th><th></th></tr></thead>
                  <tbody>
                    @for (a of applications.value() ?? []; track a.id) {
                      <tr>
                        <td>@if (a.status === 'SUBMITTED' || a.status === 'UNDER_REVIEW') {
                          <input type="checkbox" [checked]="approvedIds().includes(a.id)" (change)="toggleApproved(a.id)" [attr.aria-label]="'Aprobar a ' + a.studentName" /> }</td>
                        <td class="font-semibold">{{ a.studentName }}</td>
                        <td><span class="chip chip-blue">{{ appLabel(a.status) }}</span></td>
                        <td class="text-xs">{{ a.documents.length }} adjuntos @if (a.missingDocuments.length) { <span style="color: var(--color-alerta-500)">· faltan {{ a.missingDocuments.length }}</span> }</td>
                        <td class="mono font-bold">{{ a.totalScore === null ? '—' : (a.totalScore | grade) }}</td>
                        <td>@if (auth.has('SCHOLARSHIP_MANAGE') && (a.status === 'SUBMITTED' || a.status === 'UNDER_REVIEW')) {
                          <button class="btn btn-ghost !px-2.5 !py-1 text-xs" (click)="scoring.set(a)">Calificar</button> }</td>
                      </tr>
                    } @empty { <tr><td colspan="6" class="muted py-6 text-center">Aún no hay postulaciones.</td></tr> }
                  </tbody>
                </table>
              </div>
              @if (auth.has('COMMITTEE_RESOLVE')) {
                <form class="tile mt-4 grid gap-3 p-5 md:grid-cols-3" (submit)="$event.preventDefault(); resolve(c)">
                  <h3 class="text-lg md:col-span-3">Resolución del comité (RN-20)</h3>
                  <input class="field" placeholder="N.º de acta" maxlength="40" [value]="act()" (input)="act.set($any($event.target).value)" aria-label="Número de acta" />
                  <input class="field" type="date" [value]="sessionDate()" (input)="sessionDate.set($any($event.target).value)" aria-label="Fecha de sesión" />
                  <input class="field" placeholder="Miembros (separados por coma, mínimo según quórum)" [value]="members()" (input)="members.set($any($event.target).value)" aria-label="Miembros" />
                  <textarea class="field md:col-span-3" rows="2" placeholder="Resumen de la sesión" [value]="summary()" (input)="summary.set($any($event.target).value)" aria-label="Resumen"></textarea>
                  <p class="muted text-sm md:col-span-2">Se aprobarán {{ approvedIds().length }} postulaciones marcadas; las demás evaluadas se niegan.</p>
                  <button class="btn btn-teja" [disabled]="!act() || !summary()">Registrar resolución</button>
                </form>
              }
            } @else {
              <app-empty icon="gift" title="Elige una convocatoria" text="Verás sus postulaciones, puntajes y podrás registrar la resolución del comité." />
            }
          </section>
        </div>
      }
      @case ('alerts') {
        <div class="tile mb-4 flex flex-wrap items-end gap-3 p-4">
          <div class="min-w-[220px]"><label class="label" for="ap">Período</label>
            <select id="ap" class="field" (change)="alertPeriod.set($any($event.target).value || null)">
              <option value="">Elegir…</option>@for (p of periods.value()?.items ?? []; track p.id) { <option [value]="p.id">{{ p.code }}</option> }</select></div>
          <button class="btn btn-primary" [disabled]="!alertPeriod()" (click)="detect()"><app-icon name="sparkle" [size]="16" /> Detectar riesgo ahora</button>
        </div>
        <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          @for (a of alerts.value() ?? []; track a.id; let i = $index) {
            <article class="tile p-4" style="animation: var(--animate-rise)" [style.animation-delay.ms]="i * 40">
              <div class="flex items-center justify-between"><span class="chip {{ riskChip(a.risk) }}">Riesgo {{ riskLabel(a.risk) }}</span><span class="muted text-xs">{{ a.detectedAt | ecDate }}</span></div>
              <p class="mt-2 font-semibold">{{ a.studentName }}</p><p class="muted text-xs">{{ a.studentCode }} · {{ a.careerName }}</p>
              <p class="mt-2 text-sm">{{ a.reasons }}</p>
              <div class="mt-3 flex gap-2">
                @for (s of ['OPEN', 'IN_FOLLOW_UP', 'CLOSED']; track s) {
                  <button class="chip !py-1" [class.chip-blue]="a.status === s" [class.chip-muted]="a.status !== s" (click)="updateAlert(a, s)">{{ alertLabel(s) }}</button>
                }
              </div>
            </article>
          } @empty {
            <div class="md:col-span-3"><app-empty icon="flag" title="Sin alertas para el período" text="Ejecuta la detección para evaluar asistencia, notas y deudas." /></div>
          }
        </div>
      }
      @case ('care') {
        <div class="sensitive mb-4 flex items-start gap-3 rounded-2xl p-4 text-sm"><app-icon name="shield" [size]="20" />
          <span>Datos sensibles (LOPDP art. 26): se guardan cifrados con AES-256-GCM, solo con consentimiento vigente del estudiante y <strong>cada lectura queda registrada en auditoría</strong>.</span></div>
        <div class="grid gap-5 xl:grid-cols-[320px_1fr]">
          <section>
            <input class="field" placeholder="Buscar estudiante" [value]="careQuery()" (input)="careQuery.set($any($event.target).value)" aria-label="Buscar estudiante" />
            <ul class="mt-3 space-y-2">
              @for (s of careHits.value()?.items ?? []; track s.id) {
                <li><button class="item tile w-full p-3 text-left" [class.sel]="careStudent()?.id === s.id" (click)="careStudent.set(s)"><span class="mono muted text-[11px]">{{ s.code }}</span><span class="block font-semibold">{{ s.fullName }}</span></button></li>
              }
            </ul>
          </section>
          <section>
            @if (careStudent(); as s) {
              <form class="tile grid gap-3 p-5 md:grid-cols-2" (submit)="$event.preventDefault(); record(s)">
                <h2 class="text-xl md:col-span-2">Nueva atención · {{ s.fullName }}</h2>
                <select class="field" (change)="cCategory.set($any($event.target).value)" aria-label="Categoría">@for (c of categories; track c[0]) { <option [value]="c[0]">{{ c[1] }}</option> }</select>
                <label class="flex items-center gap-2 text-sm"><input type="checkbox" (change)="cFollow.set(!cFollow())" /> Requiere seguimiento</label>
                <input class="field md:col-span-2" placeholder="Resumen" maxlength="2000" [value]="cSummary()" (input)="cSummary.set($any($event.target).value)" aria-label="Resumen" />
                <textarea class="field md:col-span-2" rows="3" placeholder="Detalle (se cifra)" maxlength="8000" [value]="cDetails()" (input)="cDetails.set($any($event.target).value)" aria-label="Detalle"></textarea>
                <button class="btn btn-primary md:col-span-2" [disabled]="!cSummary().trim()">Registrar atención</button>
              </form>
              <ul class="mt-4 space-y-3">
                @for (a of attentions.value() ?? []; track a.id) {
                  <li class="tile p-4"><div class="flex justify-between"><span class="chip chip-teja">{{ category(a.category) }}</span><span class="muted text-xs">{{ a.attendedAt | ecDate: 'datetime' }}</span></div>
                    <p class="mt-2 font-semibold">{{ a.summary }}</p>@if (a.details) { <p class="muted mt-1 text-sm">{{ a.details }}</p> }
                    <p class="muted mt-1 text-xs">{{ a.professional }} @if (a.followUpRequired) { · <strong>con seguimiento</strong> }</p></li>
                } @empty { <li class="muted text-sm">Sin atenciones registradas o sin consentimiento vigente.</li> }
              </ul>
            } @else {
              <app-empty icon="heart" title="Elige un estudiante" />
            }
          </section>
        </div>
      }
    }

    @if (scoring(); as a) {
      <div class="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" (click)="scoring.set(null)">
        <div class="tile w-full max-w-md p-6" (click)="$event.stopPropagation()" role="dialog" aria-modal="true" style="animation: var(--animate-rise)">
          <p class="kicker">Calificar postulación</p><h2 class="text-2xl">{{ a.studentName }}</h2>
          @for (cr of criteria.value() ?? []; track cr.id) {
            <label class="label mt-4" [attr.for]="'cr-' + cr.id">{{ cr.name }} (máx. {{ cr.maxScore }}, peso {{ cr.weight }}%)</label>
            <input [id]="'cr-' + cr.id" class="field" type="number" min="0" [max]="cr.maxScore" (input)="setScore(cr.id, $any($event.target).valueAsNumber)" />
          }
          <div class="mt-5 flex justify-end gap-2"><button class="btn btn-ghost" (click)="scoring.set(null)">Cancelar</button><button class="btn btn-primary" (click)="score(a)">Guardar puntaje</button></div>
        </div>
      </div>
    }
  `,
  styles: `
    .tabs { display: inline-flex; gap: 4px; padding: 4px; border-radius: 999px; background: var(--surface-2); border: 1px solid var(--line); }
    .tabs button { display: inline-flex; gap: .35rem; align-items: center; padding: .45rem .9rem; border-radius: 999px; font-weight: 700; font-size: .85rem; color: var(--muted); }
    .tabs button.on { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-tile); }
    .item { transition: transform .2s, border-color .2s; }
    .item:hover { transform: translateX(3px); }
    .item.sel { border-color: var(--color-cupula-400); box-shadow: 0 0 0 3px var(--brand-soft); }
    .sensitive { background: var(--color-teja-100); color: var(--color-teja-700); }
    :host-context(.dark) .sensitive { background: #4a2518; color: #f2b29b; }
  `,
})
export class AdminWelfarePage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly celebration = inject(CelebrationService);
  protected readonly auth = inject(AuthStore);
  protected readonly tab = signal<'calls' | 'alerts' | 'care'>(this.auth.has('SCHOLARSHIP_READ') ? 'calls' : this.auth.has('DROPOUT_MANAGE') ? 'alerts' : 'care');
  protected readonly categories = Object.entries(CATEGORY);

  protected readonly calls = rxResource({ params: () => (this.auth.has('SCHOLARSHIP_READ') ? 1 : undefined), stream: () => this.api.get<CallView[]>('/admin/welfare/calls') });
  protected readonly types = rxResource({ params: () => (this.creating() ? 1 : undefined), stream: () => this.api.get<TypeView[]>('/admin/welfare/types') });
  protected readonly periods = rxResource({ stream: () => this.api.get<Page<Period>>('/catalog/periods', { size: 30 }) });
  protected readonly callId = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly call = computed(() => (this.calls.value() ?? []).find((c) => c.id === this.callId()) ?? null);
  protected readonly applications = rxResource({ params: () => this.callId() ?? undefined, stream: ({ params }) => this.api.get<ApplicationView[]>(`/admin/welfare/calls/${params}/applications`) });
  protected readonly criteria = rxResource({ params: () => this.callId() ?? undefined, stream: ({ params }) => this.api.get<Criterion[]>(`/admin/welfare/calls/${params}/criteria`) });
  protected readonly scoring = signal<ApplicationView | null>(null);
  private scores: Record<string, number> = {};
  protected readonly approvedIds = signal<string[]>([]);
  protected readonly act = signal('');
  protected readonly sessionDate = signal(new Date().toISOString().slice(0, 10));
  protected readonly members = signal('Presidencia del comité, Dirección de Bienestar, Representación estudiantil');
  protected readonly summary = signal('');
  protected form = { typeId: '', periodId: '', name: '', quota: 0, resultsBy: '', opensAt: '', closesAt: '',
    documents: 'CERT_INGRESOS;Certificado de ingresos familiares\nCEDULA_REPRESENTANTE;Copia de cédula del representante',
    criteria: 'Situación socioeconómica;100;60\nRendimiento académico;100;40' };

  protected readonly alertPeriod = signal<string | null>(null);
  protected readonly alerts = rxResource({ params: () => this.alertPeriod() ?? undefined, stream: ({ params }) => this.api.get<Alert[]>(`/admin/welfare/periods/${params}/dropout-alerts`) });

  protected readonly careQuery = signal('');
  private readonly careDebounced = toSignal(toObservable(this.careQuery).pipe(debounceTime(300)), { initialValue: '' });
  protected readonly careHits = rxResource({
    params: () => (this.careDebounced().trim().length >= 2 ? this.careDebounced().trim() : undefined),
    stream: ({ params }) => this.api.get<Page<StudentHit>>('/admin/academic/students', { q: params, size: 8 }),
  });
  protected readonly careStudent = signal<StudentHit | null>(null);
  protected readonly attentions = rxResource({ params: () => this.careStudent()?.id, stream: ({ params }) => this.api.get<Attention[]>(`/admin/welfare/students/${params}/attentions`) });
  protected readonly cCategory = signal('ORIENTATION');
  protected readonly cSummary = signal('');
  protected readonly cDetails = signal('');
  protected readonly cFollow = signal(false);

  protected appLabel(s: string): string {
    return APPLICATION_LABEL[s] ?? s;
  }

  protected riskChip(r: string): string {
    return RISK[r] ?? 'chip-muted';
  }

  protected riskLabel(r: string): string {
    return { HIGH: 'alto', MEDIUM: 'medio', LOW: 'bajo' }[r] ?? r;
  }

  protected alertLabel(s: string): string {
    return { OPEN: 'Abierta', IN_FOLLOW_UP: 'En seguimiento', CLOSED: 'Cerrada' }[s] ?? s;
  }

  protected category(c: string): string {
    return CATEGORY[c] ?? c;
  }

  protected toggleApproved(id: string): void {
    this.approvedIds.update((list) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]));
  }

  protected setScore(id: string, value: number): void {
    this.scores[id] = value;
  }

  protected score(a: ApplicationView): void {
    this.api.post(`/admin/welfare/applications/${a.id}/score`, { scores: this.scores }).subscribe({
      next: () => {
        this.scoring.set(null);
        this.scores = {};
        this.toasts.ok('Puntaje registrado');
        this.applications.reload();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }

  protected resolve(c: CallView): void {
    const evaluated = (this.applications.value() ?? []).filter((a) => a.status === 'SUBMITTED' || a.status === 'UNDER_REVIEW').map((a) => a.id);
    const approved = this.approvedIds();
    const body = {
      actNumber: this.act().trim(),
      sessionDate: this.sessionDate(),
      summary: this.summary().trim(),
      members: this.members().split(',').map((m) => m.trim()).filter(Boolean).map((name, i) => ({ name, role: i === 0 ? 'PRESIDENTE' : 'VOCAL', userId: null })),
      approved,
      rejected: evaluated.filter((id) => !approved.includes(id)),
    };
    this.api.post(`/admin/welfare/calls/${c.id}/resolution`, body).subscribe({
      next: () => {
        this.celebration.burst();
        this.toasts.ok('Resolución registrada', 'Se notificó a cada postulante y se aplicaron los descuentos.');
        this.approvedIds.set([]);
        this.applications.reload();
        this.calls.reload();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }

  protected createCall(): void {
    const f = this.form;
    const documents = f.documents.split('\n').map((l) => l.split(';')).filter((p) => p.length >= 2).map(([code, name]) => ({ code: code.trim(), name: name.trim(), mandatory: true }));
    const criteria = f.criteria.split('\n').map((l) => l.split(';')).filter((p) => p.length >= 3).map(([name, max, weight]) => ({ name: name.trim(), maxScore: Number(max), weight: Number(weight) }));
    this.api
      .post<{ id: string }>('/admin/welfare/calls', { typeId: f.typeId, periodId: f.periodId, name: f.name, quota: f.quota, opensAt: new Date(f.opensAt).toISOString(),
        closesAt: new Date(f.closesAt).toISOString(), resultsBy: f.resultsBy, documents, criteria })
      .subscribe({
        next: (r) => {
          this.creating.set(false);
          this.toasts.ok('Convocatoria publicada');
          this.calls.reload();
          this.callId.set(r.id);
        },
        error: (p: Problem) => this.toasts.problem(p),
      });
  }

  protected detect(): void {
    this.api.post<{ alerts: number }>(`/admin/welfare/periods/${this.alertPeriod()}/dropout-alerts/detect`).subscribe({
      next: (r) => {
        this.toasts.ok('Detección completada', `${r.alerts} alertas nuevas.`);
        this.alerts.reload();
      },
      error: (p: Problem) => this.toasts.problem(p),
    });
  }

  protected updateAlert(a: Alert, status: string): void {
    this.api.post(`/admin/welfare/dropout-alerts/${a.id}`, { status, note: null }).subscribe({ next: () => this.alerts.reload(), error: (p) => this.toasts.problem(p) });
  }

  protected record(s: StudentHit): void {
    this.api
      .post(`/admin/welfare/students/${s.id}/attentions`, { category: this.cCategory(), attendedAt: new Date().toISOString(), summary: this.cSummary().trim(),
        details: this.cDetails().trim() || null, followUpRequired: this.cFollow() })
      .subscribe({
        next: () => {
          this.cSummary.set('');
          this.cDetails.set('');
          this.toasts.ok('Atención registrada (cifrada)');
          this.attentions.reload();
        },
        error: (p: Problem) => this.toasts.problem(p),
      });
  }
}
