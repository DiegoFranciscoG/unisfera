import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { ChartComponent, ChartPoint } from '../../shared/chart.component';
import { IconComponent } from '../../shared/icon.component';
import { PageHeaderComponent, SkeletonComponent, StatComponent } from '../../shared/widgets';

interface Dashboard {
  periodCode: string | null;
  kpis: { key: string; label: string; value: number; unit: string }[];
  enrolledByCareer: { label: string; points: ChartPoint[] };
  collectionByMonth: { label: string; points: ChartPoint[] };
  approvalByLevel: { label: string; points: ChartPoint[] };
}

const KPI_STYLE: Record<string, { icon: string; tone: 'blue' | 'teja' | 'oro' | 'rio'; link?: string }> = {
  students: { icon: 'users', tone: 'blue', link: '/admin/estudiantes' },
  enrolled: { icon: 'sparkle', tone: 'rio' },
  occupancy: { icon: 'building', tone: 'oro', link: '/admin/oferta' },
  approval: { icon: 'grade', tone: 'rio' },
  overdue: { icon: 'alert', tone: 'teja', link: '/admin/finanzas' },
  collected: { icon: 'wallet', tone: 'blue', link: '/admin/finanzas' },
  scholars: { icon: 'gift', tone: 'oro', link: '/admin/bienestar' },
  alerts: { icon: 'flag', tone: 'teja', link: '/admin/bienestar' },
  requests: { icon: 'inbox', tone: 'blue', link: '/admin/solicitudes' },
};

/** Tablero institucional: indicadores en vivo y tendencias (los datos personales nunca salen aquí). */
@Component({
  selector: 'app-admin-dashboard',
  imports: [RouterLink, IconComponent, PageHeaderComponent, SkeletonComponent, StatComponent, ChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (data.value(); as d) {
      <app-page-header kicker="Tablero" [title]="'Período ' + (d.periodCode ?? 'sin abrir')" subtitle="Indicadores calculados en vivo desde la base; cada cifra enlaza a su módulo.">
        <button class="btn btn-ghost" (click)="data.reload()"><app-icon name="refresh" [size]="16" /> Actualizar</button>
        @if (auth.has('REPORT_EXPORT')) { <a routerLink="/admin/reportes" class="btn btn-primary"><app-icon name="file" [size]="16" /> Reportes</a> }
      </app-page-header>
      <div class="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
        @for (k of d.kpis; track k.key; let i = $index) {
          <a [routerLink]="style(k.key).link ?? null" class="block" style="animation: var(--animate-rise)" [style.animation-delay.ms]="i * 40">
            <app-stat [label]="k.label" [value]="k.value" [icon]="style(k.key).icon" [tone]="style(k.key).tone"
              [decimals]="k.unit === 'USD' ? 2 : k.unit === '%' ? 1 : 0" [prefix]="k.unit === 'USD' ? '$' : ''" [suffix]="k.unit === '%' ? '%' : ''" />
          </a>
        }
      </div>
      <div class="mt-6 grid gap-5 xl:grid-cols-2">
        <section class="tile p-5">
          <p class="kicker">Matrícula</p><h2 class="text-xl">{{ d.enrolledByCareer.label }}</h2>
          <app-chart type="bar" [points]="d.enrolledByCareer.points" [label]="d.enrolledByCareer.label" unit="estudiantes" />
        </section>
        <section class="tile p-5">
          <p class="kicker">Finanzas</p><h2 class="text-xl">{{ d.collectionByMonth.label }}</h2>
          <app-chart type="line" [points]="d.collectionByMonth.points" [label]="d.collectionByMonth.label" unit="USD" />
        </section>
        <section class="tile p-5 xl:col-span-2">
          <p class="kicker">Rendimiento</p><h2 class="text-xl">{{ d.approvalByLevel.label }} (%)</h2>
          <app-chart type="bar" [points]="d.approvalByLevel.points" [label]="d.approvalByLevel.label" unit="%" [height]="220" />
        </section>
      </div>
    } @else {
      <app-skeleton [count]="2" [height]="180" />
    }
  `,
})
export class AdminDashboardPage {
  private readonly api = inject(ApiService);
  protected readonly auth = inject(AuthStore);
  protected readonly data = rxResource({ stream: () => this.api.get<Dashboard>('/admin/dashboard') });

  protected style(key: string) {
    return KPI_STYLE[key] ?? { icon: 'chart', tone: 'blue' as const };
  }
}
