import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ApiService, download } from '../../core/api.service';
import { Manual } from '../../core/models';
import { ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { renderMarkdown, slugify } from '../../shared/markdown';
import { EmptyComponent, SkeletonComponent } from '../../shared/widgets';

/** Manual completo con índice lateral; las anclas {#rn-xx} permiten llegar desde cada mensaje de error. */
@Component({
  selector: 'app-manual-page',
  imports: [RouterLink, IconComponent, SkeletonComponent, EmptyComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/ayuda" class="muted mb-4 inline-flex items-center gap-1 text-sm font-semibold hover:underline"><app-icon name="back" [size]="15" /> Todos los manuales</a>
    @if (manual.value(); as m) {
      <div class="grid gap-6 lg:grid-cols-[1fr_260px]">
        <article class="tile prose-unisfera p-6 md:p-10" style="animation: var(--animate-rise)" [innerHTML]="html()"></article>
        <aside class="h-fit lg:sticky lg:top-20">
          <button class="btn btn-primary w-full" (click)="pdf(m.slug)"><app-icon name="download" [size]="16" /> Descargar PDF</button>
          <p class="label mt-5">En este manual</p>
          <ul class="toc space-y-1 text-sm">
            @for (h of toc(); track h.id) {
              <li [class.sub]="h.level === 3"><a [href]="'#' + h.id" (click)="go($event, h.id)">{{ h.text }}</a></li>
            }
          </ul>
        </aside>
      </div>
    } @else if (manual.error()) {
      <app-empty icon="book" title="Manual no encontrado" text="Puede que el enlace haya cambiado." />
    } @else {
      <app-skeleton [count]="6" [height]="40" />
    }
  `,
  styles: `
    .toc a { display: block; padding: .3rem .6rem; border-radius: 8px; color: var(--muted); border-left: 2px solid var(--line); }
    .toc a:hover { color: var(--brand); border-left-color: var(--brand); background: var(--surface-2); }
    .toc .sub a { padding-left: 1.3rem; font-size: .82rem; }
  `,
})
export class ManualPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  readonly slug = input.required<string>();
  protected readonly manual = rxResource({ params: () => this.slug(), stream: ({ params }) => this.api.get<Manual>(`/public/manuals/${params}`) });
  protected readonly html = computed(() => renderMarkdown(this.manual.value()?.markdown ?? ''));
  protected readonly toc = computed(() =>
    (this.manual.value()?.markdown ?? '')
      .split('\n')
      .map((l) => /^(##|###)\s+(.*?)(?:\s*\{#([a-z0-9-]+)\})?$/.exec(l.trim()))
      .filter((m): m is RegExpExecArray => !!m)
      .map((m) => ({ level: m[1].length, text: m[2], id: m[3] ?? slugify(m[2]) })),
  );

  constructor() {
    queueMicrotask(() => this.scrollToHash(0));
  }

  protected go(event: Event, id: string): void {
    event.preventDefault();
    const el = document.getElementById(id);
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el?.classList.add('flash');
    history.replaceState(null, '', '#' + id);
  }

  protected pdf(slug: string): void {
    this.api.blob(`/public/manuals/${slug}/pdf`).subscribe({ next: (b) => download(b, `unisfera-${slug}.pdf`), error: (e) => this.toasts.problem(e) });
  }

  private scrollToHash(attempt: number): void {
    const id = location.hash.slice(1);
    if (!id) {
      return;
    }
    const el = document.getElementById(id);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      el.classList.add('flash');
    } else if (attempt < 20) {
      setTimeout(() => this.scrollToHash(attempt + 1), 150);
    }
  }
}
