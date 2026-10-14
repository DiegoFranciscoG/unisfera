import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, effect, inject, input, signal } from '@angular/core';
import { IconComponent } from './icon.component';

/** Anillo de progreso animado (notas, asistencia, avance de malla) con marca del umbral. */
@Component({
  selector: 'app-ring',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 120 120" role="img" [attr.aria-label]="label() + ': ' + display()">
      <circle cx="60" cy="60" r="50" fill="none" stroke="var(--line)" stroke-width="10" />
      <circle class="arc" cx="60" cy="60" r="50" fill="none" [attr.stroke]="color()" stroke-width="10" stroke-linecap="round"
        [attr.stroke-dasharray]="circumference" [attr.stroke-dashoffset]="offset()" transform="rotate(-90 60 60)" />
      @if (threshold() !== null) {
        <line [attr.x1]="tick().x1" [attr.y1]="tick().y1" [attr.x2]="tick().x2" [attr.y2]="tick().y2" stroke="var(--color-oro-400)" stroke-width="3"
          stroke-linecap="round" />
      }
      <text x="60" y="58" text-anchor="middle" class="value">{{ display() }}</text>
      <text x="60" y="78" text-anchor="middle" class="sub">{{ label() }}</text>
    </svg>
  `,
  styles: `
    :host { display: inline-flex; }
    .arc { transition: stroke-dashoffset 1.1s cubic-bezier(.2,.8,.2,1), stroke .4s; }
    .value { font-family: var(--font-display); font-size: 26px; font-weight: 600; fill: var(--text); }
    .sub { font-size: 10px; fill: var(--muted); letter-spacing: .08em; text-transform: uppercase; font-weight: 700; }
  `,
})
export class RingComponent {
  readonly value = input<number | null>(null);
  readonly max = input(100);
  readonly threshold = input<number | null>(null);
  readonly label = input('');
  readonly size = input(120);
  readonly suffix = input('');
  protected readonly circumference = 2 * Math.PI * 50;
  private readonly animated = signal(0);

  constructor() {
    effect(() => {
      const target = Math.max(0, Math.min(1, (this.value() ?? 0) / this.max()));
      requestAnimationFrame(() => this.animated.set(target));
    });
  }

  protected readonly offset = computed(() => this.circumference * (1 - this.animated()));
  protected readonly display = computed(() => (this.value() === null ? '—' : `${Math.round((this.value() ?? 0) * 10) / 10}${this.suffix()}`));
  protected readonly color = computed(() => {
    const v = this.value();
    const t = this.threshold();
    if (v === null) {
      return 'var(--line)';
    }
    if (t === null) {
      return 'var(--color-cupula-500)';
    }
    return v >= t ? 'var(--color-rio-500)' : v >= t * 0.85 ? 'var(--color-oro-400)' : 'var(--color-teja-500)';
  });
  protected readonly tick = computed(() => {
    const angle = ((this.threshold() ?? 0) / this.max()) * 2 * Math.PI - Math.PI / 2;
    return {
      x1: 60 + 42 * Math.cos(angle),
      y1: 60 + 42 * Math.sin(angle),
      x2: 60 + 58 * Math.cos(angle),
      y2: 60 + 58 * Math.sin(angle),
    };
  });
}

/** Número que cuenta hasta su valor (indicadores, saldos). */
@Component({
  selector: 'app-count-up',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `{{ text() }}`,
})
export class CountUpComponent implements OnDestroy {
  readonly value = input<number | null>(0);
  readonly decimals = input(0);
  readonly prefix = input('');
  readonly suffix = input('');
  private readonly current = signal(0);
  private frame = 0;

  constructor() {
    effect(() => {
      const target = Number(this.value() ?? 0);
      const start = performance.now();
      const from = this.current();
      cancelAnimationFrame(this.frame);
      const step = (now: number) => {
        const t = Math.min(1, (now - start) / 900);
        const eased = 1 - Math.pow(1 - t, 3);
        this.current.set(from + (target - from) * eased);
        if (t < 1) {
          this.frame = requestAnimationFrame(step);
        }
      };
      this.frame = requestAnimationFrame(step);
    });
  }

  protected readonly text = computed(
    () => this.prefix() + this.current().toLocaleString('es-EC', { minimumFractionDigits: this.decimals(), maximumFractionDigits: this.decimals() }) + this.suffix(),
  );

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }
}

/** Encabezado de página con antetítulo, título en Fraunces y acciones. */
@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-6 flex flex-wrap items-end justify-between gap-4" style="animation: var(--animate-rise)">
      <div class="min-w-0">
        @if (kicker()) {
          <p class="kicker">{{ kicker() }}</p>
        }
        <h1 class="mt-1 text-3xl md:text-[2.4rem]">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="muted mt-2 max-w-2xl">{{ subtitle() }}</p>
        }
      </div>
      <div class="flex flex-wrap gap-2"><ng-content /></div>
    </header>
  `,
})
export class PageHeaderComponent {
  readonly kicker = input('');
  readonly title = input.required<string>();
  readonly subtitle = input('');
}

/** Estado vacío ilustrado. */
@Component({
  selector: 'app-empty',
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tile azulejo flex flex-col items-center gap-3 px-6 py-12 text-center">
      <span class="grid h-14 w-14 place-items-center rounded-full" style="background: var(--brand-soft); color: var(--brand)">
        <app-icon [name]="icon()" [size]="26" />
      </span>
      <p class="font-display text-xl">{{ title() }}</p>
      @if (text()) {
        <p class="muted max-w-md text-sm">{{ text() }}</p>
      }
      <ng-content />
    </div>
  `,
})
export class EmptyComponent {
  readonly icon = input('sparkle');
  readonly title = input.required<string>();
  readonly text = input('');
}

/** Tarjeta de indicador con contador animado. */
@Component({
  selector: 'app-stat',
  imports: [CountUpComponent, IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tile tile-hover h-full p-4">
      <div class="flex items-center justify-between">
        <p class="label !mb-0">{{ label() }}</p>
        <span class="grid h-8 w-8 place-items-center rounded-full" [style.background]="tint()" [style.color]="ink()">
          <app-icon [name]="icon()" [size]="16" />
        </span>
      </div>
      <p class="font-display mt-3 text-3xl">
        <app-count-up [value]="value()" [decimals]="decimals()" [prefix]="prefix()" [suffix]="suffix()" />
      </p>
      @if (hint()) {
        <p class="muted mt-1 text-xs">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatComponent {
  readonly label = input.required<string>();
  readonly value = input<number | null>(0);
  readonly icon = input('chart');
  readonly decimals = input(0);
  readonly prefix = input('');
  readonly suffix = input('');
  readonly hint = input('');
  readonly tone = input<'blue' | 'teja' | 'oro' | 'rio'>('blue');
  protected readonly tint = computed(
    () => ({ blue: 'var(--brand-soft)', teja: 'var(--color-teja-100)', oro: 'var(--color-oro-100)', rio: 'var(--color-rio-100)' })[this.tone()],
  );
  protected readonly ink = computed(
    () => ({ blue: 'var(--brand)', teja: 'var(--color-teja-700)', oro: 'var(--color-oro-600)', rio: 'var(--color-rio-700)' })[this.tone()],
  );
}

/** Esqueleto de carga. */
@Component({
  selector: 'app-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@for (row of rows(); track $index) {
      <div class="skeleton mb-3" [style.height.px]="height()"></div>
    }`,
})
export class SkeletonComponent {
  readonly count = input(3);
  readonly height = input(64);
  protected readonly rows = computed(() => Array.from({ length: this.count() }));
}

/** Revela su contenido con animación al entrar en pantalla. */
@Component({
  selector: 'app-reveal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<ng-content />`,
  host: { '[class.revealed]': 'visible()', class: 'reveal' },
  styles: `
    :host.reveal { display: block; opacity: 0; transform: translateY(18px); transition: opacity .6s ease, transform .6s cubic-bezier(.2,.8,.2,1); }
    :host.revealed { opacity: 1; transform: none; }
  `,
})
export class RevealComponent implements OnDestroy {
  protected readonly visible = signal(false);
  private readonly observer: IntersectionObserver | null;

  constructor() {
    const host = inject(ElementRef).nativeElement as HTMLElement;
    this.observer = typeof IntersectionObserver === 'function'
      ? new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && this.visible.set(true), { threshold: 0.12 })
      : null;
    if (this.observer) {
      this.observer.observe(host);
    } else {
      this.visible.set(true);
    }
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }
}
