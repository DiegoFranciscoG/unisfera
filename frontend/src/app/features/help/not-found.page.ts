import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth.store';
import { SphereLogoComponent } from '../../shared/sphere-logo.component';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink, SphereLogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="azulejo grid min-h-screen place-items-center p-6 text-center">
      <div style="animation: var(--animate-rise)">
        <app-sphere [size]="160" [orbits]="true" />
        <p class="kicker mt-6">Error 404</p>
        <h1 class="mt-2 text-4xl">Esta página salió de órbita</h1>
        <p class="muted mx-auto mt-2 max-w-md">La dirección no existe o cambió. Volvamos a un lugar conocido.</p>
        <a [routerLink]="auth.isLoggedIn() ? auth.home() : '/ingresar'" class="btn btn-primary mt-6">Volver al inicio</a>
      </div>
    </div>
  `,
})
export class NotFoundPage {
  protected readonly auth = inject(AuthStore);
}
