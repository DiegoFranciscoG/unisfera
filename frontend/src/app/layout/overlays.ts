import { ChangeDetectionStrategy, Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService, download } from '../core/api.service';
import { Manual } from '../core/models';
import { HelpService, ToastService } from '../core/ui.services';
import { IconComponent } from '../shared/icon.component';
import { renderMarkdown } from '../shared/markdown';

/** Avisos apilados: cada error de regla ofrece "¿Qué significa?" con el manual en contexto. */
@Component({
  selector: 'app-toasts',
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed bottom-4 right-4 z-[60] flex w-[min(92vw,380px)] flex-col gap-3" aria-live="polite" aria-atomic="false">
      @for (t of toasts.toasts(); track t.id) {
        <div class="tile toast flex gap-3 p-4" [class.err]="t.kind === 'error'" [class.ok]="t.kind === 'ok'" role="status">
          <span class="icon grid h-9 w-9 shrink-0 place-items-center rounded-full">
            <app-icon [name]="t.kind === 'ok' ? 'check' : t.kind === 'error' ? 'alert' : 'sparkle'" [size]="18" />
          </span>
          <div class="min-w-0 flex-1">
            <p class="font-bold">{{ t.title }}</p>
            @if (t.detail) {
              <p class="muted mt-0.5 text-sm">{{ t.detail }}</p>
            }
            @if (t.helpUrl) {
              <button class="mt-2 text-sm font-bold underline decoration-2 underline-offset-4" style="color: var(--brand)"
                (click)="help.fromHelpUrl(t.helpUrl); toasts.dismiss(t.id)">¿Qué significa y qué hago?</button>
            }
          </div>
          <button class="muted self-start" (click)="toasts.dismiss(t.id)" aria-label="Cerrar aviso"><app-icon name="x" [size]="16" /></button>
        </div>
      }
    </div>
  `,
  styles: `
    .toast { animation: var(--animate-rise); }
    .toast .icon { background: var(--brand-soft); color: var(--brand); }
    .toast.err { border-color: var(--color-teja-300); }
    .toast.err .icon { background: var(--color-teja-100); color: var(--color-teja-700); }
    .toast.ok .icon { background: var(--color-rio-100); color: var(--color-rio-700); }
  `,
})
export class ToastsComponent {
  protected readonly toasts = inject(ToastService);
  protected readonly help = inject(HelpService);
}

/** Ayuda contextual: el manual de la pantalla actual, con buscador y descarga en PDF. */
@Component({
  selector: 'app-help-drawer',
  imports: [IconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (help.open()) {
      <div class="fixed inset-0 z-50 bg-black/30 backdrop-blur-[2px]" (click)="help.open.set(false)" aria-hidden="true"></div>
      <aside class="drawer fixed inset-y-0 right-0 z-50 flex w-[min(100vw,520px)] flex-col" role="dialog" aria-modal="true" aria-labelledby="help-title">
        <div class="azulejo flex items-center justify-between gap-3 border-b p-5" style="border-color: var(--line)">
          <div>
            <p class="kicker">Ayuda</p>
            <h2 id="help-title" class="text-2xl">{{ manual()?.title ?? 'Manuales' }}</h2>
          </div>
          <button class="btn btn-ghost !p-2" (click)="help.open.set(false)" aria-label="Cerrar ayuda"><app-icon name="x" /></button>
        </div>
        <div class="border-b p-4" style="border-color: var(--line)">
          <label class="sr-only" for="help-search">Buscar en los manuales</label>
          <div class="relative">
            <span class="muted absolute left-3 top-1/2 -translate-y-1/2"><app-icon name="search" [size]="18" /></span>
            <input id="help-search" class="field !pl-10" placeholder="¿Qué necesita? Ej.: tercera matrícula, pago, beca" [value]="query()"
              (input)="search($any($event.target).value)" />
          </div>
          @if (hits().length) {
            <ul class="mt-3 space-y-1">
              @for (h of hits(); track h.slug + h.heading) {
                <li>
                  <button class="w-full rounded-lg p-2 text-left text-sm hover:bg-[var(--surface-2)]" (click)="openManual(h.slug)">
                    <span class="font-bold">{{ h.title }}</span> · <span class="muted">{{ h.heading }}</span>
                  </button>
                </li>
              }
            </ul>
          }
        </div>
        <div #body class="prose-unisfera flex-1 overflow-y-auto p-6" [innerHTML]="html()"></div>
        <div class="flex justify-between gap-2 border-t p-4" style="border-color: var(--line)">
          <button class="btn btn-ghost" (click)="goAll()"><app-icon name="book" [size]="16" /> Todos los manuales</button>
          <button class="btn btn-primary" (click)="pdf()"><app-icon name="download" [size]="16" /> Descargar PDF</button>
        </div>
      </aside>
    }
  `,
  styles: `
    .drawer { background: var(--surface); box-shadow: -24px 0 60px -30px rgba(0,0,0,.45); animation: slide-in .35s cubic-bezier(.2,.8,.2,1); }
    @keyframes slide-in { from { transform: translateX(100%); } to { transform: none; } }
  `,
})
export class HelpDrawerComponent {
  protected readonly help = inject(HelpService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly body = viewChild<ElementRef<HTMLElement>>('body');
  protected readonly manual = signal<Manual | null>(null);
  protected readonly html = signal('');
  protected readonly query = signal('');
  protected readonly hits = signal<{ slug: string; title: string; heading: string }[]>([]);

  constructor() {
    effect(() => {
      if (this.help.open()) {
        this.openManual(this.help.slug());
      }
    });
  }

  openManual(slug: string): void {
    this.api.get<Manual>(`/public/manuals/${slug}`).subscribe({
      next: (m) => {
        this.manual.set(m);
        this.html.set(renderMarkdown(m.markdown ?? ''));
        this.hits.set([]);
        setTimeout(() => {
          const anchor = this.help.anchor();
          const el = anchor ? this.body()?.nativeElement.querySelector(`#${anchor}`) : null;
          if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'start' });
            el.classList.add('flash');
          } else {
            this.body()?.nativeElement.scrollTo({ top: 0 });
          }
        }, 60);
      },
      error: () => this.html.set('<p>Este manual no está disponible por ahora.</p>'),
    });
  }

  search(q: string): void {
    this.query.set(q);
    if (q.trim().length < 3) {
      this.hits.set([]);
      return;
    }
    this.api.get<{ slug: string; title: string; heading: string }[]>('/public/manuals/search', { q }).subscribe((h) => this.hits.set(h));
  }

  pdf(): void {
    const slug = this.manual()?.slug ?? this.help.slug();
    this.api.blob(`/public/manuals/${slug}/pdf`).subscribe({
      next: (b) => download(b, `unisfera-${slug}.pdf`),
      error: (e) => this.toasts.problem(e),
    });
  }

  goAll(): void {
    this.help.open.set(false);
    void this.router.navigate(['/ayuda']);
  }
}
