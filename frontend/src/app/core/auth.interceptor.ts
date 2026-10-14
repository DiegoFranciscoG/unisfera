import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthStore } from './auth.store';

/**
 * Añade el token y un identificador de correlación a cada petición. Ante un 401 intenta un único
 * refresco de sesión y reintenta; si no puede, la sesión termina.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthStore);
  const withHeaders = (r: HttpRequest<unknown>) => {
    const token = auth.token();
    let headers = r.headers.set('X-Request-Id', crypto.randomUUID());
    if (token && !r.url.includes('/api/auth/refresh')) {
      headers = headers.set('Authorization', `Bearer ${token}`);
    }
    return r.clone({ headers, withCredentials: r.url.startsWith('/api/auth') });
  };

  return next(withHeaders(req)).pipe(
    catchError((error: HttpErrorResponse) => {
      const isAuthCall = req.url.startsWith('/api/auth/');
      if (error.status === 401 && !isAuthCall && auth.token()) {
        return auth.refresh().pipe(
          switchMap((ok) => (ok ? next(withHeaders(req)) : throwError(() => error))),
        );
      }
      return throwError(() => error);
    }),
  );
};
