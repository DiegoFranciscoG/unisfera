import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Problem } from '../../core/models';
import { isValidUsername, passwordIssues } from '../../core/rules';
import { SettingsStore, ToastService } from '../../core/ui.services';
import { CedulaTilesComponent } from '../../shared/cedula-tiles.component';
import { IconComponent } from '../../shared/icon.component';
import { SphereLogoComponent } from '../../shared/sphere-logo.component';

/**
 * Recuperación de acceso. La respuesta a "olvidé mi contraseña" es siempre la misma exista o no
 * la cuenta (no se revela qué cédulas están registradas). El enlace del correo trae ?token=.
 */
@Component({
  selector: 'app-recover-page',
  imports: [RouterLink, CedulaTilesComponent, IconComponent, SphereLogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="azulejo grid min-h-screen place-items-center px-4 py-10">
      <div class="tile w-full max-w-[460px] p-7 sm:p-9" style="animation: var(--animate-rise)">
        <a routerLink="/ingresar" class="flex items-center gap-3"><app-sphere [size]="36" /><span class="font-display text-xl">UNISFERA</span></a>
        @if (!token()) {
          @if (!sent()) {
            <h1 class="mt-6 text-3xl">Recuperar acceso</h1>
            <p class="muted mt-1">Le enviaremos un enlace de un solo uso a su correo institucional.</p>
            <form class="mt-6 space-y-5" (submit)="$event.preventDefault(); forgot()">
              <div>
                <label class="label" for="username">Cédula o pasaporte</label>
                <app-cedula-tiles [(value)]="username" />
              </div>
              <button class="btn btn-primary w-full !py-3" [disabled]="busy() || !validUser()">Enviar enlace</button>
            </form>
          } @else {
            <div class="mt-8 text-center">
              <span class="sent mx-auto grid h-16 w-16 place-items-center rounded-full"><app-icon name="check" [size]="30" /></span>
              <h1 class="mt-4 text-2xl">Revise su correo</h1>
              <p class="muted mt-2">Si la cuenta existe, recibirá un enlace válido por tiempo limitado. Revise también la carpeta de spam.</p>
            </div>
          }
        } @else {
          <h1 class="mt-6 text-3xl">Nueva contraseña</h1>
          <form class="mt-6 space-y-4" (submit)="$event.preventDefault(); reset()">
            <div>
              <label class="label" for="np">Nueva contraseña</label>
              <input id="np" class="field" type="password" autocomplete="new-password" maxlength="128" [value]="password()"
                (input)="password.set($any($event.target).value)" />
            </div>
            <div>
              <label class="label" for="np2">Repítala</label>
              <input id="np2" class="field" type="password" autocomplete="new-password" maxlength="128" [value]="confirm()"
                (input)="confirm.set($any($event.target).value)" />
            </div>
            @for (issue of issues(); track issue) {
              <p class="text-sm" style="color: var(--color-alerta-500)">• {{ issue }}</p>
            }
            <button class="btn btn-primary w-full !py-3" [disabled]="busy() || issues().length > 0 || password() !== confirm()">Guardar</button>
          </form>
        }
        <a routerLink="/ingresar" class="muted mt-6 inline-block text-sm font-semibold hover:underline">← Volver a ingresar</a>
      </div>
    </div>
  `,
  styles: `.sent { background: var(--color-rio-100); color: var(--color-rio-700); animation: var(--animate-stamp); }`,
})
export class RecoverPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly settings = inject(SettingsStore);
  readonly token = input<string>();
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly confirm = signal('');
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly validUser = computed(() => isValidUsername(this.username()));
  protected readonly issues = computed(() =>
    this.password() ? passwordIssues(this.password(), '', this.settings.settings().passwordMinLength || 12) : [],
  );

  protected forgot(): void {
    this.busy.set(true);
    this.api.post('/auth/password/forgot', { username: this.username() }).subscribe({
      next: () => {
        this.busy.set(false);
        this.sent.set(true);
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    });
  }

  protected reset(): void {
    this.busy.set(true);
    this.api.post('/auth/password/reset', { token: this.token(), newPassword: this.password() }).subscribe({
      next: () => {
        this.toasts.ok('Contraseña actualizada', 'Ya puede ingresar con su nueva contraseña.');
        void this.router.navigate(['/ingresar']);
      },
      error: (p: Problem) => {
        this.busy.set(false);
        this.toasts.problem(p);
      },
    });
  }
}
