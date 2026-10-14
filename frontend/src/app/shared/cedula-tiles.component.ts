import { ChangeDetectionStrategy, Component, ElementRef, computed, input, model, viewChild } from '@angular/core';
import { cedulaSteps } from '../core/rules';

/**
 * Campo de cédula como una fila de azulejos: cada dígito "voltea" al escribirse y, al completar
 * los diez, se ve la verificación módulo 10 (provincia, tercer dígito y dígito verificador).
 * Acepta también pasaporte (alfanumérico) para estudiantes extranjeros.
 */
@Component({
  selector: 'app-cedula-tiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap" (click)="focus()">
      <input #field class="sr-only-input" [id]="inputId()" [value]="value()" (input)="onInput($event)" inputmode="text"
        autocomplete="username" maxlength="20" [attr.aria-describedby]="inputId() + '-hint'" aria-label="Cédula o pasaporte" />
      <div class="tiles" [class.done]="isCedula() && state().valid" [class.bad]="isCedula() && value().length === 10 && !state().valid"
        aria-hidden="true">
        @for (slot of slots(); track $index) {
          <span class="tile-digit" [class.filled]="slot !== ''" [class.province]="$index < 2" [class.check]="$index === 9"
            [class.cursor]="$index === value().length" [style.--d]="$index">{{ slot }}</span>
        }
      </div>
    </div>
    <p class="hint" [id]="inputId() + '-hint'" aria-live="polite">
      @if (!value()) {
        Escriba su cédula (10 dígitos) o su pasaporte.
      } @else if (!isCedula()) {
        Pasaporte: letras y números, de 5 a 20 caracteres.
      } @else if (value().length < 10) {
        Faltan {{ 10 - value().length }} dígitos.
      } @else if (state().valid) {
        <span class="ok">✓ Cédula válida · verificador {{ state().check }}</span>
      } @else if (!state().province) {
        <span class="err">La provincia (dos primeros dígitos) debe ser 01–24 o 30.</span>
      } @else if (!state().third) {
        <span class="err">El tercer dígito de una persona natural es menor a 6.</span>
      } @else {
        <span class="err">El dígito verificador debería ser {{ state().check }}. Revise los números.</span>
      }
    </p>
  `,
  styles: `
    :host { display: block; }
    .wrap { position: relative; cursor: text; }
    .sr-only-input { position: absolute; inset: 0; opacity: 0; width: 100%; height: 100%; font-size: 16px; cursor: text; }
    .tiles { display: grid; grid-template-columns: repeat(10, 1fr); gap: 6px; perspective: 600px; }
    .tile-digit {
      aspect-ratio: 3 / 4; display: grid; place-items: center; border-radius: 10px;
      font-family: var(--font-mono); font-size: clamp(1rem, 3.8vw, 1.45rem); font-weight: 600;
      background: var(--surface); border: 1.5px dashed var(--line); color: var(--text);
      transition: background .3s, border-color .3s, transform .3s, color .3s;
    }
    .tile-digit.filled { border-style: solid; border-color: var(--color-cupula-300); background: var(--brand-soft); animation: var(--animate-tile-in); }
    .tile-digit.province.filled { border-color: var(--color-oro-400); }
    .tile-digit.cursor { border-color: var(--color-cupula-500); animation: pulse-ring 1.4s infinite; }
    .done .tile-digit { background: var(--color-rio-100); border-color: var(--color-rio-500); color: var(--color-rio-700);
      animation: flipok .6s both; animation-delay: calc(var(--d) * 45ms); }
    :host-context(.dark) .done .tile-digit { background: #173a36; color: #8fd3c8; }
    .bad .tile-digit.check { background: var(--color-alerta-100); border-color: var(--color-alerta-500); color: var(--color-alerta-500); }
    .bad { animation: shake .45s; }
    .hint { margin-top: .55rem; font-size: .83rem; color: var(--muted); min-height: 1.3em; }
    .ok { color: var(--color-rio-500); font-weight: 700; }
    .err { color: var(--color-alerta-500); font-weight: 600; }
    @keyframes flipok { 0% { transform: rotateY(0); } 50% { transform: rotateY(90deg); } 100% { transform: rotateY(0); } }
  `,
})
export class CedulaTilesComponent {
  readonly value = model('');
  readonly inputId = input('username');
  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  protected readonly isCedula = computed(() => /^\d*$/.test(this.value()) && this.value().length <= 10);
  protected readonly state = computed(() => cedulaSteps(this.value()));
  protected readonly slots = computed(() => {
    const v = this.isCedula() ? this.value() : '';
    return Array.from({ length: 10 }, (_, i) => v[i] ?? '');
  });

  protected onInput(event: Event): void {
    const raw = (event.target as HTMLInputElement).value.toUpperCase().replace(/[^A-Z0-9]/g, '');
    this.value.set(raw);
  }

  focus(): void {
    this.field().nativeElement.focus();
  }
}
