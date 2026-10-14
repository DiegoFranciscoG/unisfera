import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';
import { toProblem } from './api.service';
import { AuthResponse, UserSummary } from './models';

/**
 * Sesión del usuario. El token de acceso vive solo en memoria (no en localStorage: no lo alcanza
 * un XSS persistente); el de refresco es una cookie HttpOnly + SameSite=Strict que el navegador
 * envía únicamente a /api/auth.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly tokenSignal = signal<string | null>(null);
  readonly user = signal<UserSummary | null>(null);
  readonly challenge = signal<AuthResponse | null>(null);
  readonly isLoggedIn = computed(() => this.user() !== null);
  readonly portal = computed(() => portalOf(this.user()));
  private refreshing$: Observable<boolean> | null = null;

  token(): string | null {
    return this.tokenSignal();
  }

  has(permission: string): boolean {
    return this.user()?.permissions.includes(permission) ?? false;
  }

  hasAny(...permissions: string[]): boolean {
    return permissions.some((p) => this.has(p));
  }

  login(username: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>('/api/auth/login', { username, password }).pipe(catchError(toProblem), tap((r) => this.apply(r)));
  }

  changeRequiredPassword(newPassword: string): Observable<AuthResponse> {
    const challengeToken = this.challenge()?.challengeToken;
    return this.http
      .post<AuthResponse>('/api/auth/password/change-required', { challengeToken, newPassword })
      .pipe(catchError(toProblem), tap((r) => this.apply(r)));
  }

  verifyMfa(code: string): Observable<AuthResponse> {
    const challengeToken = this.challenge()?.challengeToken;
    return this.http.post<AuthResponse>('/api/auth/mfa/verify', { challengeToken, code }).pipe(catchError(toProblem), tap((r) => this.apply(r)));
  }

  /** Restaura la sesión al abrir la app (cookie de refresco) sin mostrar errores. */
  restore(): Promise<void> {
    return new Promise((resolve) => this.refresh().subscribe({ complete: () => resolve(), error: () => resolve() }));
  }

  /** Un solo refresco en curso aunque varias peticiones reciban 401 a la vez. */
  refresh(): Observable<boolean> {
    if (!this.refreshing$) {
      this.refreshing$ = this.http.post<AuthResponse>('/api/auth/refresh', {}).pipe(
        tap((r) => this.apply(r)),
        map((r) => r.status === 'AUTHENTICATED'),
        catchError(() => {
          this.clear();
          return of(false);
        }),
        finalize(() => (this.refreshing$ = null)),
        shareReplay(1),
      );
    }
    return this.refreshing$;
  }

  logout(everywhere = false): void {
    const url = everywhere ? '/api/auth/logout-all' : '/api/auth/logout';
    this.http.post(url, {}).subscribe({ complete: () => this.finishLogout(), error: () => this.finishLogout() });
  }

  private finishLogout(): void {
    this.clear();
    void this.router.navigate(['/ingresar']);
  }

  clear(): void {
    this.tokenSignal.set(null);
    this.user.set(null);
    this.challenge.set(null);
  }

  private apply(r: AuthResponse): void {
    if (r.status === 'AUTHENTICATED' && r.accessToken && r.user) {
      this.tokenSignal.set(r.accessToken);
      this.user.set(r.user);
      this.challenge.set(null);
    } else {
      this.challenge.set(r);
    }
  }

  home(): string {
    return '/' + this.portal();
  }
}

export function portalOf(user: UserSummary | null): 'estudiante' | 'docente' | 'admin' {
  if (!user) {
    return 'estudiante';
  }
  if (user.permissions.includes('SELF_SERVICE')) {
    return 'estudiante';
  }
  if (user.permissions.includes('TEACHING_OWN_SECTIONS') && user.roles.length === 1) {
    return 'docente';
  }
  return 'admin';
}
