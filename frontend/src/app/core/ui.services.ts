import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { Problem, Settings } from './models';

export interface Toast {
  id: number;
  kind: 'ok' | 'error' | 'info';
  title: string;
  detail?: string;
  helpUrl?: string;
  code?: string;
}

/** Avisos efímeros. Cada error de regla enlaza al punto del manual que lo explica. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private seq = 0;

  ok(title: string, detail?: string): void {
    this.push({ kind: 'ok', title, detail });
  }

  info(title: string, detail?: string): void {
    this.push({ kind: 'info', title, detail });
  }

  problem(p: Problem | unknown, fallback = 'No se pudo completar la acción'): void {
    const problem = p as Problem;
    this.push({
      kind: 'error',
      title: problem?.code && /^(RN|SEC|LEY)-/.test(problem.code) ? `Regla ${problem.code}` : fallback,
      detail: problem?.detail,
      helpUrl: problem?.helpUrl,
      code: problem?.code,
    });
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }

  private push(t: Omit<Toast, 'id'>): void {
    const id = ++this.seq;
    this.toasts.update((list) => [...list.slice(-3), { ...t, id }]);
    setTimeout(() => this.dismiss(id), t.kind === 'error' ? 9000 : 4500);
  }
}

/** Valores de negocio para la interfaz: los mismos parámetros que usan la base y el dominio (LEY-1). */
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly api = inject(ApiService);
  readonly settings = signal<Settings>({
    scaleMin: 0,
    scaleMax: 100,
    passingGrade: 0,
    minAttendance: 0,
    attendanceWarningMargin: 0,
    maxSubjectsPerPeriod: 0,
    passwordMinLength: 0,
    currency: 'USD',
    zone: 'America/Guayaquil',
    closeConfirmationPhrase: '',
  });
  readonly loaded = signal(false);

  load(): Promise<void> {
    return new Promise((resolve) =>
      this.api.get<Settings>('/public/settings').subscribe({
        next: (s) => {
          this.settings.set(s);
          this.loaded.set(true);
          resolve();
        },
        error: () => resolve(),
      }),
    );
  }
}

/** Tema claro/oscuro: preferencia por visitante (localStorage protegido con try/catch). */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly dark = signal(false);

  init(): void {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem('unisfera.theme');
    } catch {
      stored = null;
    }
    const prefersDark = typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches;
    this.apply(stored ? stored === 'dark' : prefersDark);
  }

  toggle(): void {
    this.apply(!this.dark());
    try {
      localStorage.setItem('unisfera.theme', this.dark() ? 'dark' : 'light');
    } catch {
      /* sin almacenamiento: el tema se mantiene solo en esta sesión */
    }
  }

  private apply(dark: boolean): void {
    this.dark.set(dark);
    document.documentElement.classList.toggle('dark', dark);
  }
}

/** Panel de ayuda contextual: cada pantalla declara su manual. */
@Injectable({ providedIn: 'root' })
export class HelpService {
  readonly open = signal(false);
  readonly slug = signal<string>('preguntas-frecuentes');
  readonly anchor = signal<string | null>(null);

  show(slug?: string, anchor?: string): void {
    if (slug) {
      this.slug.set(slug);
    }
    this.anchor.set(anchor ?? null);
    this.open.set(true);
  }

  fromHelpUrl(url: string): void {
    const match = /\/ayuda\/([a-z0-9-]+)(?:#([a-z0-9-]+))?/.exec(url);
    if (match) {
      this.show(match[1], match[2]);
    }
  }
}

/** Confeti de azulejos para los momentos importantes (matrícula confirmada, pago aprobado). */
@Injectable({ providedIn: 'root' })
export class CelebrationService {
  burst(): void {
    if (typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return;
    }
    const colors = ['#2457a6', '#5287e2', '#c4532d', '#e2a93b', '#2e8c7e', '#b4cdf6'];
    const layer = document.createElement('div');
    layer.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:9999;overflow:hidden';
    for (let i = 0; i < 70; i++) {
      const tile = document.createElement('span');
      const size = 6 + Math.random() * 10;
      tile.style.cssText = `position:absolute;top:0;left:${Math.random() * 100}vw;width:${size}px;height:${size}px;` +
        `background:${colors[i % colors.length]};border-radius:${Math.random() > 0.5 ? '3px' : '50%'};` +
        `--drift:${(Math.random() - 0.5) * 240}px;--turn:${360 + Math.random() * 720}deg;` +
        `animation:confetti-fall ${1.6 + Math.random() * 1.6}s cubic-bezier(.2,.7,.3,1) ${Math.random() * 0.4}s both`;
      layer.appendChild(tile);
    }
    document.body.appendChild(layer);
    setTimeout(() => layer.remove(), 3800);
  }
}
