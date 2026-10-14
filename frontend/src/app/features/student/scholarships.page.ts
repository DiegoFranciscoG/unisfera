import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { EcDatePipe, GradePipe } from '../../core/format';
import { Problem } from '../../core/models';
import { CelebrationService, HelpService, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

export interface CallView {
  id: string;
  typeCode: string;
  typeName: string;
  mode: 'PERCENT' | 'AMOUNT';
  value: number;
  periodCode: string;
  name: string;
  quota: number;
  opensAt: string;
  closesAt: string;
  resultsBy: string;
  status: string;
  documents: { code: string; name: string; mandatory: boolean }[];
  applications: number;
}

export interface ApplicationView {
  id: string;
  callId: string;
  callName: string;
  typeName: string;
  studentId: string;
  studentName: string;
  status: 'DRAFT' | 'SUBMITTED' | 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  statement: string | null;
  submittedAt: string | null;
  totalScore: number | null;
  documents: { requiredDocumentId: string; code: string; name: string; fileId: string; fileName: string }[];
  missingDocuments: string[];
}

interface AwardView {
  id: string;
  typeName: string;
  mode: string;
  value: number;
  startPeriod: string;
  status: string;
  conditions: string;
  followups: { id: string; periodCode: string; average: number; attendance: number; outcome: string; reason: string; appealStatus: string | null }[];
}

const FLOW: ApplicationView['status'][] = ['DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'APPROVED'];
export const APPLICATION_LABEL: Record<string, string> = {
  DRAFT: 'Borrador',
  SUBMITTED: 'Enviada',
  UNDER_REVIEW: 'En evaluación',
  APPROVED: 'Aprobada',
  REJECTED: 'Negada',
  WITHDRAWN: 'Retirada',
};

/** Becas: convocatorias abiertas, postulación con documentos, seguimiento y apelación (RN-18 a RN-22). */
@Component({
  selector: 'app-scholarships-page',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, EcDatePipe, GradePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Becas y ayudas" title="Apoyo para que sigas en órbita" subtitle="Postula en línea, adjunta tus documentos y sigue el estado de tu solicitud.">
      <button class="btn btn-ghost" (click)="help.show('como-postulo-a-una-beca')"><app-icon name="help" [size]="16" /> Cómo postular</button>
    </app-page-header>

    <!-- Becas vigentes -->
    @for (a of awards.value() ?? []; track a.id) {
      <section class="award tile relative mb-5 overflow-hidden p-5" style="animation: var(--animate-rise)">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p class="kicker !text-[var(--color-oro-600)]">Tu beca · desde {{ a.startPeriod }}</p>
            <h2 class="text-2xl">{{ a.typeName }}</h2>
            <p class="muted text-sm">{{ a.conditions }}</p>
          </div>
          <div class="text-right">
            <p class="font-display text-4xl">{{ a.mode === 'PERCENT' ? a.value + '%' : '$' + a.value }}</p>
            <span class="chip" [class.chip-green]="a.status === 'ACTIVE'" [class.chip-red]="a.status !== 'ACTIVE'">{{ awardLabel(a.status) }}</span>
          </div>
        </div>
        @if (a.followups.length) {
          <ul class="mt-4 grid gap-2 sm:grid-cols-2">
            @for (f of a.followups; track f.id) {
              <li class="rounded-xl p-3 text-sm" style="background: var(--surface-2)">
                <div class="flex items-center justify-between">
                  <strong>{{ f.periodCode }}</strong>
                  <span class="chip" [class.chip-green]="f.outcome === 'MAINTAINED'" [class.chip-gold]="f.outcome === 'REDUCED'" [class.chip-red]="f.outcome === 'SUSPENDED'">{{ outcomeLabel(f.outcome) }}</span>
                </div>
                <p class="muted mt-1 text-xs">Promedio {{ f.average | grade }} · asistencia {{ f.attendance | grade }}%</p>
                @if (f.outcome !== 'MAINTAINED') {
                  @if (f.appealStatus) {
                    <p class="mt-1 text-xs font-bold">Apelación: {{ appealLabel(f.appealStatus) }}</p>
                  } @else {
                    <button class="mt-2 text-xs font-bold underline" style="color: var(--brand)" (click)="appealing.set(f.id)">Apelar esta decisión</button>
                  }
                }
              </li>
            }
          </ul>
        }
        <div class="medal" aria-hidden="true"><app-icon name="seal" [size]="150" [stroke]="0.9" /></div>
      </section>
    }

    <div class="grid gap-6 xl:grid-cols-[1fr_1fr]">
      <section>
        <h2 class="mb-3 text-xl">Convocatorias abiertas</h2>
        @if (calls.value(); as list) {
          <div class="space-y-3">
            @for (c of list; track c.id; let i = $index) {
              <article class="tile tile-hover p-5" [style.animation-delay.ms]="i * 60" style="animation: var(--animate-rise)">
                <div class="flex items-start justify-between gap-3">
                  <div>
                    <p class="kicker">{{ c.typeName }}</p>
                    <h3 class="mt-1 text-lg">{{ c.name }}</h3>
                    <p class="muted mt-1 text-sm">Cierra el {{ c.closesAt | ecDate: 'datetime' }} · resultados hasta el {{ c.resultsBy | ecDate }}</p>
                  </div>
                  <span class="value">{{ c.mode === 'PERCENT' ? c.value + '%' : '$' + c.value }}</span>
                </div>
                <p class="label mt-3">Documentos</p>
                <ul class="flex flex-wrap gap-1.5">
                  @for (d of c.documents; track d.code) { <li class="chip chip-muted">{{ d.name }}@if (d.mandatory) {<span style="color: var(--color-teja-500)">*</span>}</li> }
                </ul>
                <div class="mt-4 flex items-center justify-between">
                  <span class="muted text-xs">{{ c.quota }} cupos · {{ c.applications }} postulaciones</span>
                  @if (applicationFor(c.id); as app) {
                    <span class="chip chip-blue">Ya postulaste · {{ label(app.status) }}</span>
                  } @else {
                    <button class="btn btn-primary" [disabled]="busy()" (click)="draft(c)">Postular</button>
                  }
                </div>
              </article>
            } @empty {
              <app-empty icon="gift" title="No hay convocatorias abiertas" text="Te avisaremos cuando Bienestar publique una nueva." />
            }
          </div>
        } @else {
          <app-skeleton [count]="2" [height]="160" />
        }
      </section>

      <section>
        <h2 class="mb-3 text-xl">Mis postulaciones</h2>
        <div class="space-y-3">
          @for (a of applications.value() ?? []; track a.id) {
            <article class="tile p-5" style="animation: var(--animate-rise)">
              <div class="flex items-start justify-between gap-2">
                <div><p class="kicker">{{ a.typeName }}</p><h3 class="text-lg">{{ a.callName }}</h3></div>
                @if (a.totalScore !== null) { <span class="chip chip-gold">Puntaje {{ a.totalScore | grade }}</span> }
              </div>
              <ol class="flow mt-4" [class.rejected]="a.status === 'REJECTED' || a.status === 'WITHDRAWN'">
                @for (st of flow; track st; let i = $index) {
                  <li [class.on]="reached(a, i)"><span></span>{{ label(st) }}</li>
                }
              </ol>
              @if (a.status === 'REJECTED' || a.status === 'WITHDRAWN') {
                <p class="mt-2 text-sm font-bold" style="color: var(--color-alerta-500)">{{ label(a.status) }}</p>
              }
              @if (a.status === 'DRAFT') {
                <label class="label mt-4" [attr.for]="'st-' + a.id">Carta de motivación</label>
                <textarea [id]="'st-' + a.id" class="field" rows="3" maxlength="4000" [value]="a.statement ?? ''" (blur)="saveStatement(a, $any($event.target).value)"></textarea>
                <p class="label mt-4">Documentos (PDF, JPG o PNG)</p>
                <ul class="space-y-2">
                  @for (d of docsFor(a); track d.code) {
                    <li class="doc flex items-center gap-3 rounded-xl p-2.5" [class.done]="d.uploaded">
                      <span class="grid h-8 w-8 place-items-center rounded-full"><app-icon [name]="d.uploaded ? 'check' : 'file'" [size]="15" /></span>
                      <span class="min-w-0 flex-1 text-sm"><strong>{{ d.name }}</strong>@if (d.mandatory) {<span style="color: var(--color-teja-500)"> *</span>}
                        @if (d.fileName) { <span class="muted block truncate text-xs">{{ d.fileName }}</span> }</span>
                      <label class="btn btn-ghost !px-3 !py-1.5 text-xs">
                        <app-icon name="upload" [size]="14" /> {{ d.uploaded ? 'Reemplazar' : 'Subir' }}
                        <input type="file" class="sr-only" accept="application/pdf,image/jpeg,image/png" (change)="upload(a, d.code, $event)" />
                      </label>
                    </li>
                  }
                </ul>
                <div class="mt-4 flex flex-wrap justify-end gap-2">
                  <button class="btn btn-ghost" (click)="withdraw(a)">Retirar</button>
                  <button class="btn btn-teja" [disabled]="a.missingDocuments.length > 0 || busy()" (click)="submit(a)">
                    Enviar postulación
                  </button>
                </div>
                @if (a.missingDocuments.length) {
                  <p class="muted mt-2 text-right text-xs">Falta: {{ a.missingDocuments.join(', ') }}</p>
                }
              }
            </article>
          } @empty {
            <app-empty icon="inbox" title="Sin postulaciones" text="Elige una convocatoria y postula en minutos." />
          }
        </div>
      </section>
    </div>

    @if (appealing(); as followupId) {
      <div class="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" (click)="appealing.set(null)">
        <div class="tile w-full max-w-lg p-6" style="animation: var(--animate-rise)" (click)="$event.stopPropagation()" role="dialog" aria-modal="true">
          <p class="kicker">Apelación (RN-22)</p>
          <h2 class="mt-1 text-2xl">Cuéntale al comité tu situación</h2>
          <textarea class="field mt-4" rows="5" maxlength="4000" [value]="appealText()" (input)="appealText.set($any($event.target).value)"
            placeholder="Explica qué pasó durante el período (mínimo 20 caracteres)."></textarea>
          <div class="mt-4 flex justify-end gap-2">
            <button class="btn btn-ghost" (click)="appealing.set(null)">Cancelar</button>
            <button class="btn btn-primary" [disabled]="appealText().trim().length < 20 || busy()" (click)="appeal(followupId)">Enviar apelación</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .award { border: 1.5px solid var(--color-oro-400); background: linear-gradient(140deg, var(--surface), color-mix(in srgb, var(--color-oro-100) 45%, var(--surface))); }
    .medal { position: absolute; right: -20px; bottom: -30px; color: var(--color-oro-400); opacity: .14; animation: var(--animate-spin-slow); }
    .value { font-family: var(--font-display); font-size: 1.8rem; color: var(--color-teja-500); }
    .flow { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; font-size: .7rem; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
    .flow li span { display: block; height: 6px; border-radius: 999px; background: var(--surface-2); margin-bottom: 6px; transition: background .5s; }
    .flow li.on { color: var(--brand); }
    .flow li.on span { background: linear-gradient(90deg, var(--color-cupula-400), var(--color-cupula-600)); }
    .flow.rejected li.on span { background: var(--color-alerta-500); }
    .doc { background: var(--surface-2); }
    .doc span:first-child { background: var(--surface); }
    .doc.done span:first-child { background: var(--color-rio-500); color: white; }
  `,
})
export class ScholarshipsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly celebration = inject(CelebrationService);
  protected readonly help = inject(HelpService);
  protected readonly calls = rxResource({ stream: () => this.api.get<CallView[]>('/scholarships/calls/open') });
  protected readonly applications = rxResource({ stream: () => this.api.get<ApplicationView[]>('/me/scholarships/applications') });
  protected readonly awards = rxResource({ stream: () => this.api.get<AwardView[]>('/me/scholarships/awards') });
  protected readonly busy = signal(false);
  protected readonly appealing = signal<string | null>(null);
  protected readonly appealText = signal('');
  protected readonly flow = FLOW;

  private readonly callById = computed(() => new Map((this.calls.value() ?? []).map((c) => [c.id, c])));

  protected applicationFor(callId: string): ApplicationView | null {
    return (this.applications.value() ?? []).find((a) => a.callId === callId && a.status !== 'WITHDRAWN') ?? null;
  }

  protected docsFor(a: ApplicationView) {
    const call = this.callById().get(a.callId);
    const required = call?.documents ?? a.documents.map((d) => ({ code: d.code, name: d.name, mandatory: true }));
    return required.map((r) => {
      const up = a.documents.find((d) => d.code === r.code);
      return { ...r, uploaded: !!up, fileName: up?.fileName ?? null };
    });
  }

  protected reached(a: ApplicationView, index: number): boolean {
    const position = FLOW.indexOf(a.status);
    return position >= 0 ? index <= position : index <= 1;
  }

  protected label(status: string): string {
    return APPLICATION_LABEL[status] ?? status;
  }

  protected awardLabel(status: string): string {
    return { ACTIVE: 'Vigente', SUSPENDED: 'Suspendida', FINISHED: 'Finalizada', REVOKED: 'Revocada' }[status] ?? status;
  }

  protected outcomeLabel(outcome: string): string {
    return { MAINTAINED: 'Se mantiene', REDUCED: 'Reducida', SUSPENDED: 'Suspendida' }[outcome] ?? outcome;
  }

  protected appealLabel(status: string): string {
    return { FILED: 'en revisión', ACCEPTED: 'aceptada', REJECTED: 'negada' }[status] ?? status;
  }

  protected draft(c: CallView): void {
    this.busy.set(true);
    this.api.post('/me/scholarships/applications', { callId: c.id, statement: '' }).subscribe(this.after('Borrador creado', 'Completa tu carta y sube los documentos.'));
  }

  protected saveStatement(a: ApplicationView, text: string): void {
    if ((a.statement ?? '') === text) {
      return;
    }
    this.api.put(`/me/scholarships/applications/${a.id}`, { statement: text }).subscribe({
      next: () => this.toasts.ok('Carta guardada'),
      error: (p: Problem) => this.toasts.problem(p),
    });
  }

  protected upload(a: ApplicationView, code: string, event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) {
      return;
    }
    const form = new FormData();
    form.append('file', file);
    this.busy.set(true);
    this.api.upload(`/me/scholarships/applications/${a.id}/documents/${code}`, form).subscribe(this.after('Documento recibido'));
  }

  protected submit(a: ApplicationView): void {
    this.busy.set(true);
    this.api.post(`/me/scholarships/applications/${a.id}/submit`).subscribe({
      next: () => {
        this.busy.set(false);
        this.celebration.burst();
        this.toasts.ok('¡Postulación enviada!', 'El comité la evaluará con los criterios publicados.');
        this.applications.reload();
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected withdraw(a: ApplicationView): void {
    this.busy.set(true);
    this.api.post(`/me/scholarships/applications/${a.id}/withdraw`, { reason: 'Retirada por el estudiante desde el portal' }).subscribe(this.after('Postulación retirada'));
  }

  protected appeal(followupId: string): void {
    this.busy.set(true);
    this.api.post(`/me/scholarships/followups/${followupId}/appeal`, { statement: this.appealText().trim() }).subscribe({
      next: () => {
        this.busy.set(false);
        this.appealing.set(null);
        this.appealText.set('');
        this.toasts.ok('Apelación registrada', 'El comité responderá dentro del plazo reglamentario.');
        this.awards.reload();
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    });
  }

  private after(title: string, detail?: string) {
    return {
      next: () => {
        this.busy.set(false);
        this.toasts.ok(title, detail);
        this.applications.reload();
        this.calls.reload();
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    };
  }
}
