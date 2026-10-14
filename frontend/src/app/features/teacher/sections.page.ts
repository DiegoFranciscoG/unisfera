import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { SectionCard } from '../../core/models';
import { IconComponent } from '../../shared/icon.component';
import { EmptyComponent, PageHeaderComponent, SkeletonComponent, StatComponent } from '../../shared/widgets';

export const ACT_LABEL: Record<string, { label: string; chip: string }> = {
  OPEN: { label: 'Acta abierta', chip: 'chip-blue' },
  DRAFT: { label: 'Acta abierta', chip: 'chip-blue' },
  NONE: { label: 'Acta abierta', chip: 'chip-blue' },
  CLOSED: { label: 'Acta cerrada', chip: 'chip-green' },
  RECTIFIED: { label: 'Rectificada', chip: 'chip-gold' },
};

/** Portal docente: sus paralelos del período con el estado del acta. */
@Component({
  selector: 'app-teacher-sections',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent, EmptyComponent, StatComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Portal docente" [title]="'Hola, ' + firstName()" subtitle="Registra notas y asistencia; cierra el acta cuando esté completa. Todo queda firmado y auditado." />

    @if (sections.value(); as list) {
      <div class="mb-6 grid grid-cols-2 gap-3 md:grid-cols-4">
        <app-stat label="Paralelos" [value]="list.length" icon="users" />
        <app-stat label="Estudiantes" [value]="students()" icon="user" tone="rio" />
        <app-stat label="Clases dictadas" [value]="sessions()" icon="calendar" tone="oro" />
        <app-stat label="Actas cerradas" [value]="closed()" icon="seal" tone="teja" />
      </div>
      <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        @for (s of list; track s.id; let i = $index) {
          <a [routerLink]="['/docente/paralelos', s.id]" class="card tile tile-hover p-5" [style.animation-delay.ms]="i * 60">
            <div class="flex items-start justify-between gap-2">
              <p class="mono muted text-[11px] font-bold">{{ s.subjectCode }} · {{ s.periodCode }}</p>
              <span class="chip {{ act(s.actStatus).chip }}">{{ act(s.actStatus).label }}</span>
            </div>
            <h2 class="mt-2 text-xl leading-snug">{{ s.subjectName }}</h2>
            <p class="muted text-sm">Paralelo {{ s.sectionCode }}</p>
            <div class="mt-4">
              <div class="flex justify-between text-xs font-bold"><span>{{ s.enrolled }} estudiantes</span><span class="muted">cupo {{ s.capacity }}</span></div>
              <div class="bar"><span [style.width.%]="(s.enrolled / s.capacity) * 100"></span></div>
            </div>
            <p class="mt-4 flex items-center gap-2 text-sm font-bold" style="color: var(--brand)">Abrir paralelo <app-icon name="arrow" [size]="16" /></p>
          </a>
        } @empty {
          <div class="md:col-span-3"><app-empty icon="users" title="Sin paralelos asignados" text="Cuando la coordinación te asigne paralelos, aparecerán aquí." /></div>
        }
      </div>
    } @else {
      <app-skeleton [count]="3" [height]="150" />
    }
  `,
  styles: `
    .card { animation: var(--animate-rise); display: block; }
    .bar { height: 6px; border-radius: 999px; background: var(--surface-2); margin-top: 6px; overflow: hidden; }
    .bar span { display: block; height: 100%; background: linear-gradient(90deg, var(--color-rio-500), var(--color-cupula-500)); border-radius: inherit; transition: width 1s; }
  `,
})
export class TeacherSectionsPage {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthStore);
  protected readonly sections = rxResource({ stream: () => this.api.get<SectionCard[]>('/teacher/sections') });
  protected readonly firstName = computed(() => (this.auth.user()?.displayName ?? '').split(' ')[0]);
  protected readonly students = computed(() => (this.sections.value() ?? []).reduce((a, s) => a + s.enrolled, 0));
  protected readonly sessions = computed(() => (this.sections.value() ?? []).reduce((a, s) => a + s.sessions, 0));
  protected readonly closed = computed(() => (this.sections.value() ?? []).filter((s) => s.actStatus === 'CLOSED').length);

  protected act(status: string) {
    return ACT_LABEL[status] ?? { label: status, chip: 'chip-muted' };
  }
}
