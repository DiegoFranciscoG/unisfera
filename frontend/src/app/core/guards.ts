import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore } from './auth.store';

/** La interfaz oculta lo que el rol no puede usar; la autorización real la decide siempre la API. */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthStore);
  return auth.isLoggedIn() ? true : inject(Router).createUrlTree(['/ingresar']);
};

export const permissionGuard = (...permissions: string[]): CanActivateFn => {
  return (_route, state) => {
    const auth = inject(AuthStore);
    if (!auth.isLoggedIn()) {
      return inject(Router).createUrlTree(['/ingresar']);
    }
    if (auth.hasAny(...permissions)) {
      return true;
    }
    // Nunca redirige a la misma ruta denegada (evita bucles con combinaciones de roles poco comunes).
    return inject(Router).createUrlTree([state.url === auth.home() ? '/ayuda' : auth.home()]);
  };
};

export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthStore);
  return auth.isLoggedIn() ? inject(Router).createUrlTree([auth.home()]) : true;
};

export const homeRedirect: CanActivateFn = () => {
  const auth = inject(AuthStore);
  return inject(Router).createUrlTree([auth.isLoggedIn() ? auth.home() : '/ingresar']);
};
