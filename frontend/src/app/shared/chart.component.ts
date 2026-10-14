import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, effect, inject, input, viewChild } from '@angular/core';
import { BarController, BarElement, CategoryScale, Chart, Filler, Legend, LineController, LineElement, LinearScale, PointElement, Tooltip,
  DoughnutController, ArcElement } from 'chart.js';
import { ThemeService } from '../core/ui.services';

Chart.register(BarController, BarElement, LineController, LineElement, PointElement, CategoryScale, LinearScale, Tooltip, Legend, Filler,
  DoughnutController, ArcElement);

export interface ChartPoint {
  label: string;
  value: number;
}

const PALETTE = ['#2f69d1', '#c4532d', '#2e8c7e', '#e2a93b', '#7a4fc4', '#84acee', '#da7652', '#7cc4b8'];

/** Gráfico con la paleta Cúpula; se redibuja al cambiar de tema y respeta "reducir movimiento". */
@Component({
  selector: 'app-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="relative" [style.height.px]="height()"><canvas #canvas [attr.aria-label]="label()" role="img"></canvas></div>`,
})
export class ChartComponent implements OnDestroy {
  readonly type = input<'bar' | 'line' | 'doughnut'>('bar');
  readonly points = input<ChartPoint[]>([]);
  readonly label = input('');
  readonly height = input(260);
  readonly unit = input('');
  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private readonly theme = inject(ThemeService);
  private chart: Chart | null = null;

  constructor() {
    effect(() => {
      const points = this.points();
      const type = this.type();
      this.theme.dark();
      this.render(type, points);
    });
  }

  private render(type: 'bar' | 'line' | 'doughnut', points: ChartPoint[]): void {
    this.chart?.destroy();
    const css = getComputedStyle(document.documentElement);
    const text = css.getPropertyValue('--muted').trim() || '#5d6178';
    const grid = css.getPropertyValue('--line').trim() || '#e7dfd0';
    const reduce = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    const ctx = this.canvas().nativeElement.getContext('2d');
    if (!ctx) {
      return;
    }
    const gradient = ctx.createLinearGradient(0, 0, 0, this.height());
    gradient.addColorStop(0, 'rgba(47, 105, 209, 0.35)');
    gradient.addColorStop(1, 'rgba(47, 105, 209, 0)');
    const unit = this.unit();
    this.chart = new Chart(ctx, {
      type,
      data: {
        labels: points.map((p) => p.label),
        datasets: [
          {
            label: this.label(),
            data: points.map((p) => Number(p.value)),
            backgroundColor: type === 'line' ? gradient : type === 'doughnut' ? PALETTE : points.map((_, i) => PALETTE[i % PALETTE.length]),
            borderColor: type === 'line' ? '#2f69d1' : type === 'doughnut' ? css.getPropertyValue('--surface').trim() : 'transparent',
            borderWidth: type === 'line' ? 2.5 : type === 'doughnut' ? 3 : 0,
            borderRadius: type === 'bar' ? 8 : 0,
            fill: type === 'line',
            tension: 0.35,
            pointRadius: 3,
            pointBackgroundColor: '#c4532d',
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: reduce ? false : { duration: 900, easing: 'easeOutQuart' },
        plugins: {
          legend: { display: type === 'doughnut', position: 'bottom', labels: { color: text, font: { family: 'Manrope Variable', weight: 600 } } },
          tooltip: { callbacks: { label: (c) => ` ${c.formattedValue}${unit ? ' ' + unit : ''}` } },
        },
        scales:
          type === 'doughnut'
            ? {}
            : {
                x: { ticks: { color: text, font: { family: 'Manrope Variable', size: 11 } }, grid: { display: false } },
                y: { ticks: { color: text, font: { family: 'JetBrains Mono', size: 10 } }, grid: { color: grid }, beginAtZero: true },
              },
      },
    });
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }
}
