import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore } from './auth.store';

/** La interfaz oculta lo que el rol no puede usar; la autorización real la decide siempre la API. */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthStore);
  return auth.isLoggedIn() ? true : inject(Router).createUrlTree(['/ingresar']);
};

export const permissionGuard = (...permissions: string[]): CanActivateFn => {
  return () => {
    const auth = inject(AuthStore);
    if (!auth.isLoggedIn()) {
      return inject(Router).createUrlTree(['/ingresar']);
    }
    return auth.hasAny(...permissions) ? true : inject(Router).createUrlTree([auth.home()]);
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
