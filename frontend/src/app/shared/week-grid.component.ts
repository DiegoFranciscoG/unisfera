import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Block } from '../core/models';
import { WEEKDAYS, overlaps } from '../core/rules';

export interface GridItem {
  key: string;
  title: string;
  subtitle?: string | null;
  blocks: Block[];
  tone?: number;
}

const TONES = ['#2f69d1', '#c4532d', '#2e8c7e', '#b07c17', '#7a4fc4', '#1e4785', '#94391b', '#1f6157'];

/** Semana tipo agenda: bloques posicionados por hora; los cruces (RN-06) se marcan con rayas. */
@Component({
  selector: 'app-week-grid',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid-wrap" role="table" aria-label="Horario semanal">
      <div class="head" role="row">
        <span></span>
        @for (d of days(); track d) { <span role="columnheader" class="day">{{ weekdayName(d) }}</span> }
      </div>
      <div class="body" [style.height.px]="(endHour() - startHour()) * hourHeight">
        <div class="hours">
          @for (h of hours(); track h) { <span [style.top.px]="(h - startHour()) * hourHeight">{{ h }}:00</span> }
        </div>
        @for (d of days(); track d; let col = $index) {
          <div class="col">
            @for (h of hours(); track h) { <span class="line" [style.top.px]="(h - startHour()) * hourHeight"></span> }
            @for (b of blocksOf(d); track b.key + b.start; let i = $index) {
              <div class="block" [class.clash]="b.clash" [style.top.px]="offset(b.start)" [style.height.px]="offset(b.end) - offset(b.start)"
                [style.--tone]="b.color" [style.--i]="i + col" role="cell" [attr.aria-label]="b.title + ' ' + b.start + '–' + b.end">
                <strong>{{ b.title }}</strong>
                <span>{{ b.start.slice(0, 5) }}–{{ b.end.slice(0, 5) }}@if (b.classroom) { · {{ b.classroom }} }</span>
                @if (b.subtitle) { <span class="sub">{{ b.subtitle }}</span> }
              </div>
            }
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: block; overflow-x: auto; }
    .grid-wrap { min-width: 640px; }
    .head, .body { display: grid; grid-template-columns: 52px repeat(var(--cols, 6), 1fr); }
    .head { position: sticky; top: 0; z-index: 1; }
    .day { text-align: center; font-size: .72rem; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; color: var(--muted); padding: .5rem 0; }
    .body { position: relative; }
    .hours { position: relative; }
    .hours span { position: absolute; right: 8px; transform: translateY(-50%); font-family: var(--font-mono); font-size: 10px; color: var(--muted); }
    .col { position: relative; border-left: 1px dashed var(--line); }
    .line { position: absolute; left: 0; right: 0; border-top: 1px dashed var(--line); opacity: .7; }
    .block { position: absolute; left: 4px; right: 4px; border-radius: 10px; padding: 6px 8px; overflow: hidden; font-size: .72rem; line-height: 1.25;
      background: color-mix(in srgb, var(--tone) 14%, var(--surface)); border-left: 4px solid var(--tone); color: var(--text);
      display: flex; flex-direction: column; gap: 2px; animation: var(--animate-tile-in); animation-delay: calc(var(--i) * 40ms);
      box-shadow: 0 6px 16px -10px var(--tone); }
    .block strong { font-size: .76rem; }
    .block .sub { color: var(--muted); }
    .block.clash { background: repeating-linear-gradient(-45deg, var(--color-alerta-100), var(--color-alerta-100) 6px, var(--surface) 6px, var(--surface) 12px);
      border-left-color: var(--color-alerta-500); animation: shake .45s; z-index: 2; }
  `,
  host: { '[style.--cols]': 'days().length' },
})
export class WeekGridComponent {
  readonly items = input<GridItem[]>([]);
  protected readonly hourHeight = 46;

  private readonly flat = computed(() =>
    this.items().flatMap((item, idx) =>
      item.blocks.map((b) => ({
        ...b,
        key: item.key,
        title: item.title,
        subtitle: item.subtitle ?? null,
        color: TONES[(item.tone ?? idx) % TONES.length],
        clash: false,
      })),
    ),
  );

  private readonly withClashes = computed(() => {
    const list = this.flat().map((b) => ({ ...b }));
    for (let i = 0; i < list.length; i++) {
      for (let j = i + 1; j < list.length; j++) {
        if (list[i].key !== list[j].key && overlaps(list[i], list[j])) {
          list[i].clash = true;
          list[j].clash = true;
        }
      }
    }
    return list;
  });

  protected readonly days = computed(() => {
    const used = new Set(this.flat().map((b) => b.weekday));
    const base = [1, 2, 3, 4, 5];
    if (used.has(6)) base.push(6);
    if (used.has(7)) base.push(7);
    return base;
  });
  protected readonly startHour = computed(() => Math.min(7, ...this.flat().map((b) => Number(b.start.slice(0, 2)))));
  protected readonly endHour = computed(() => Math.max(13, ...this.flat().map((b) => Math.ceil(minutes(b.end) / 60))));
  protected readonly hours = computed(() => Array.from({ length: this.endHour() - this.startHour() }, (_, i) => this.startHour() + i));

  protected blocksOf(day: number) {
    return this.withClashes().filter((b) => b.weekday === day);
  }

  protected offset(time: string): number {
    return ((minutes(time) - this.startHour() * 60) / 60) * this.hourHeight;
  }

  protected weekdayName(d: number): string {
    return WEEKDAYS[d].slice(0, 3);
  }
}

function minutes(time: string): number {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + (m || 0);
}
