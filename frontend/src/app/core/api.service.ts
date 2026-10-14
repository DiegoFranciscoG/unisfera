import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { Problem } from './models';

/** Cliente de la API: siempre bajo /api (el mismo origen; nginx o el proxy de desarrollo redirigen al backend). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api';

  get<T>(path: string, params?: Record<string, string | number | boolean | null | undefined>): Observable<T> {
    return this.http.get<T>(this.base + path, { params: this.params(params) }).pipe(catchError(toProblem));
  }

  post<T>(path: string, body: unknown = {}, headers?: Record<string, string>): Observable<T> {
    return this.http.post<T>(this.base + path, body, { headers: new HttpHeaders(headers ?? {}) }).pipe(catchError(toProblem));
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(this.base + path, body).pipe(catchError(toProblem));
  }

  patch<T>(path: string, body: unknown): Observable<T> {
    return this.http.patch<T>(this.base + path, body).pipe(catchError(toProblem));
  }

  upload<T>(path: string, form: FormData): Observable<T> {
    return this.http.post<T>(this.base + path, form).pipe(catchError(toProblem));
  }

  blob(path: string): Observable<Blob> {
    return this.http.get(this.base + path, { responseType: 'blob' }).pipe(catchError(toProblem));
  }

  private params(values?: Record<string, string | number | boolean | null | undefined>): HttpParams {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(values ?? {})) {
      if (v !== null && v !== undefined && v !== '') {
        p = p.set(k, String(v));
      }
    }
    return p;
  }
}

export function toProblem(error: HttpErrorResponse): Observable<never> {
  const body = error.error;
  const problem: Problem =
    body && typeof body === 'object' && 'code' in body
      ? { ...body, status: error.status }
      : {
          code: error.status === 0 ? 'OFFLINE' : 'INTERNAL',
          detail: error.status === 0 ? 'No hay conexión con el servidor. Revise su red e intente de nuevo.' : 'Ocurrió un error inesperado.',
          status: error.status,
        };
  return throwError(() => problem);
}

export function isProblem(value: unknown): value is Problem {
  return !!value && typeof value === 'object' && 'code' in value && 'detail' in value;
}

export function download(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
