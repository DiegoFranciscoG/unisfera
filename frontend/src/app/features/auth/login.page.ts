import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import QRCode from 'qrcode';
import { AuthStore } from '../../core/auth.store';
import { AuthResponse, Problem } from '../../core/models';
import { isValidUsername, passwordIssues } from '../../core/rules';
import { HelpService, SettingsStore, ThemeService, ToastService } from '../../core/ui.services';
import { CedulaTilesComponent } from '../../shared/cedula-tiles.component';
import { IconComponent } from '../../shared/icon.component';
import { SphereLogoComponent } from '../../shared/sphere-logo.component';

type Step = 'credentials' | 'password' | 'mfa' | 'mfa-enroll';

/** Ingreso en pasos: cédula y contraseña → cambio obligatorio → segundo factor (con enrolamiento por QR). */
@Component({
  selector: 'app-login-page',
  imports: [RouterLink, CedulaTilesComponent, IconComponent, SphereLogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid min-h-screen lg:grid-cols-[1.05fr_1fr]">
      <!-- Panel de marca -->
      <section class="hero azulejo relative hidden overflow-hidden lg:flex lg:flex-col lg:justify-between lg:p-12">
        <div class="flex items-center gap-3">
          <app-sphere [size]="40" />
          <span class="font-display text-2xl tracking-tight">UNISFERA</span>
        </div>
        <div class="relative z-10 max-w-lg">
          <p class="kicker !text-[var(--color-oro-300)]">Sistema universitario</p>
          <h1 class="font-display mt-3 text-[clamp(2.6rem,4.6vw,4.1rem)] leading-[1.02] text-white">
            Toda tu vida universitaria,<br /><em class="not-italic text-[var(--color-oro-300)]">en una sola esfera.</em>
          </h1>
          <p class="mt-5 max-w-md text-[1.05rem] text-white/75">
            Matrícula, notas, pagos y becas con reglas claras: si algo no se puede, el sistema te dice por qué y qué hacer.
          </p>
          <ul class="mt-8 grid max-w-md grid-cols-3 gap-3 text-sm text-white/80">
            <li class="feature"><app-icon name="sparkle" [size]="18" /> Matrícula en 3 pasos</li>
            <li class="feature"><app-icon name="grade" [size]="18" /> Notas al instante</li>
            <li class="feature"><app-icon name="wallet" [size]="18" /> Pagos en línea</li>
          </ul>
        </div>
        <div class="orb" aria-hidden="true"><app-sphere [size]="520" [orbits]="true" /></div>
        <p class="relative z-10 text-xs text-white/50">Datos de demostración ficticios · Cumple la LOPDP</p>
      </section>

      <!-- Formulario -->
      <section class="relative flex flex-col justify-center px-5 py-10 sm:px-10">
        <div class="absolute right-4 top-4 flex gap-2">
          <button class="btn btn-ghost !p-2" (click)="help.show('primer-ingreso')" aria-label="Ayuda para ingresar"><app-icon name="help" [size]="18" /></button>
          <button class="btn btn-ghost !p-2" (click)="theme.toggle()" [attr.aria-label]="theme.dark() ? 'Tema claro' : 'Tema oscuro'">
            <app-icon [name]="theme.dark() ? 'sun' : 'moon'" [size]="18" />
          </button>
        </div>
        <div class="mx-auto w-full max-w-[440px]">
          <div class="mb-8 flex items-center gap-3 lg:hidden">
            <app-sphere [size]="38" />
            <span class="font-display text-2xl">UNISFERA</span>
          </div>

          <ol class="steps mb-7" aria-label="Pasos para ingresar">
            @for (s of stepList(); track s.id; let i = $index) {
              <li [class.on]="stepIndex() >= i" [class.now]="stepIndex() === i"><span>{{ i + 1 }}</span>{{ s.label }}</li>
            }
          </ol>

          @switch (step()) {
            @case ('credentials') {
              <div class="rise">
                <h2 class="text-[2.1rem] leading-tight">Hola de nuevo</h2>
                <p class="muted mt-1">Ingrese con su cédula o pasaporte.</p>
                <form class="mt-7 space-y-5" (submit)="$event.preventDefault(); submitCredentials()" [class.shake]="shake()">
                  <div>
                    <label class="label" for="username">Cédula o pasaporte</label>
                    <app-cedula-tiles [(value)]="username" inputId="username" />
                  </div>
                  <div>
                    <div class="flex items-baseline justify-between">
                      <label class="label" for="password">Contraseña</label>
                      <a routerLink="/recuperar" class="text-sm font-semibold" style="color: var(--brand)">¿La olvidó?</a>
                    </div>
                    <div class="relative">
                      <input #pw id="password" class="field !pr-12" [type]="showPassword() ? 'text' : 'password'" autocomplete="current-password"
                        [value]="password()" (input)="password.set($any($event.target).value)" (keyup)="checkCaps($event)" maxlength="128" required />
                      <button type="button" class="muted absolute right-3 top-1/2 -translate-y-1/2" (click)="showPassword.update((v) => !v)"
                        [attr.aria-label]="showPassword() ? 'Ocultar contraseña' : 'Mostrar contraseña'"><app-icon [name]="showPassword() ? 'eye-off' : 'eye'" [size]="18" /></button>
                    </div>
                    @if (capsLock()) {
                      <p class="mt-2 text-sm font-semibold" style="color: var(--color-oro-600)">Bloq Mayús está activado.</p>
                    }
                  </div>
                  @if (error()) {
                    <div class="alert" role="alert"><app-icon name="alert" [size]="18" /><span>{{ error() }}</span></div>
                  }
                  <button class="btn btn-primary w-full !py-3.5 text-base" [disabled]="busy() || !canSubmit()">
                    @if (busy()) { <span class="spinner"></span> Verificando… } @else { Ingresar <app-icon name="arrow" [size]="18" /> }
                  </button>
                </form>
                <details class="demo mt-8">
                  <summary>¿Es una demostración? Ver usuarios de prueba</summary>
                  <p class="muted mt-2 text-sm">Las cédulas de demostración se generan al cargar los datos de ejemplo y se listan en el README
                    (sección «Demo en vivo y usuario de prueba»). La contraseña es la variable <code>DEMO_PASSWORD</code> del despliegue.</p>
                </details>
              </div>
            }
            @case ('password') {
              <div class="rise">
                <h2 class="text-[2rem] leading-tight">Cree su contraseña</h2>
                <p class="muted mt-1">Por seguridad, la contraseña temporal o vencida debe cambiarse antes de continuar.</p>
                <form class="mt-6 space-y-4" (submit)="$event.preventDefault(); submitNewPassword()">
                  <div>
                    <label class="label" for="new-password">Nueva contraseña</label>
                    <input id="new-password" class="field" type="password" autocomplete="new-password" [value]="newPassword()"
                      (input)="newPassword.set($any($event.target).value)" maxlength="128" />
                  </div>
                  <div class="meter" aria-hidden="true"><span [style.width.%]="strength()" [attr.data-level]="strengthLevel()"></span></div>
                  <ul class="space-y-1.5 text-sm">
                    @for (rule of passwordChecks(); track rule.label) {
                      <li class="flex items-center gap-2" [class.ok-rule]="rule.ok">
                        <span class="dot"><app-icon [name]="rule.ok ? 'check' : 'x'" [size]="12" /></span>{{ rule.label }}
                      </li>
                    }
                  </ul>
                  <div>
                    <label class="label" for="confirm-password">Repítala</label>
                    <input id="confirm-password" class="field" type="password" autocomplete="new-password" [value]="confirmPassword()"
                      (input)="confirmPassword.set($any($event.target).value)" maxlength="128" />
                  </div>
                  @if (error()) {
                    <div class="alert" role="alert"><app-icon name="alert" [size]="18" /><span>{{ error() }}</span></div>
                  }
                  <button class="btn btn-primary w-full !py-3.5" [disabled]="busy() || !passwordReady()">Guardar y continuar</button>
                </form>
              </div>
            }
            @case ('mfa-enroll') {
              <div class="rise">
                <h2 class="text-[2rem] leading-tight">Active su segundo factor</h2>
                <p class="muted mt-1">Su rol maneja información sensible. Escanee el código con Google Authenticator, Microsoft Authenticator o similar.</p>
                <div class="mt-6 flex flex-col items-center gap-4 sm:flex-row sm:items-start">
                  <div class="qr tile p-3">
                    @if (qr()) { <img [src]="qr()" width="168" height="168" alt="Código QR para su aplicación de autenticación" /> }
                  </div>
                  <div class="text-sm">
                    <p class="muted">¿No puede escanear? Escriba esta clave:</p>
                    <p class="mono mt-1 break-all rounded-lg p-2 text-[0.82rem]" style="background: var(--surface-2)">{{ challenge()?.mfaSecret }}</p>
                  </div>
                </div>
                <form class="mt-6" (submit)="$event.preventDefault(); submitCode()">
                  <label class="label" for="code">Código de 6 dígitos</label>
                  <input #codeField id="code" class="field code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [value]="code()"
                    (input)="onCode($event)" />
                  @if (error()) {
                    <div class="alert mt-3" role="alert"><app-icon name="alert" [size]="18" /><span>{{ error() }}</span></div>
                  }
                  <button class="btn btn-primary mt-4 w-full !py-3.5" [disabled]="busy() || code().length !== 6">Activar e ingresar</button>
                </form>
              </div>
            }
            @case ('mfa') {
              <div class="rise">
                <h2 class="text-[2rem] leading-tight">Confirme que es usted</h2>
                <p class="muted mt-1">Escriba el código de 6 dígitos de su aplicación de autenticación.</p>
                <form class="mt-6" (submit)="$event.preventDefault(); submitCode()">
                  <label class="label" for="code">Código</label>
                  <input #codeField id="code" class="field code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [value]="code()"
                    (input)="onCode($event)" />
                  @if (error()) {
                    <div class="alert mt-3" role="alert"><app-icon name="alert" [size]="18" /><span>{{ error() }}</span></div>
                  }
                  <button class="btn btn-primary mt-4 w-full !py-3.5" [disabled]="busy() || code().length !== 6">Verificar</button>
                </form>
              </div>
            }
          }
          @if (step() !== 'credentials') {
            <button class="muted mt-6 text-sm font-semibold hover:underline" (click)="restart()">← Volver a empezar</button>
          }
        </div>
      </section>
    </div>
  `,
  styles: `
    .hero { background: radial-gradient(120% 90% at 0% 0%, var(--color-cupula-600), var(--color-cupula-900) 70%); color: white; }
    .hero.azulejo::before { opacity: .12; }
    .orb { position: absolute; right: -150px; bottom: -150px; opacity: .9; animation: float 9s ease-in-out infinite; }
    .feature { display: flex; flex-direction: column; gap: .4rem; padding: .8rem; border-radius: 14px; background: rgba(255,255,255,.07);
      border: 1px solid rgba(255,255,255,.12); backdrop-filter: blur(4px); }
    .steps { display: flex; gap: .5rem; flex-wrap: wrap; font-size: .78rem; font-weight: 700; color: var(--muted); }
    .steps li { display: flex; align-items: center; gap: .4rem; padding: .3rem .7rem .3rem .3rem; border-radius: 999px; background: var(--surface-2); transition: all .3s; }
    .steps li span { display: grid; place-items: center; width: 1.4rem; height: 1.4rem; border-radius: 999px; background: var(--line); color: var(--text); }
    .steps li.on span { background: var(--brand); color: white; }
    .steps li.now { background: var(--brand-soft); color: var(--brand); }
    .rise { animation: var(--animate-rise); }
    .shake { animation: shake .45s; }
    .alert { display: flex; gap: .6rem; align-items: flex-start; padding: .75rem .9rem; border-radius: 12px; font-size: .9rem;
      background: var(--color-alerta-100); color: var(--color-alerta-500); font-weight: 600; }
    :host-context(.dark) .alert { background: #3b1d1d; color: #f3a6a6; }
    .spinner { width: 1rem; height: 1rem; border-radius: 999px; border: 2px solid rgba(255,255,255,.4); border-top-color: white; animation: spin .7s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .meter { height: 6px; border-radius: 999px; background: var(--surface-2); overflow: hidden; }
    .meter span { display: block; height: 100%; border-radius: inherit; transition: width .35s, background .35s; background: var(--color-alerta-500); }
    .meter span[data-level='2'] { background: var(--color-oro-500); }
    .meter span[data-level='3'] { background: var(--color-rio-500); }
    .dot { display: grid; place-items: center; width: 1.1rem; height: 1.1rem; border-radius: 999px; background: var(--surface-2); color: var(--muted); }
    .ok-rule { color: var(--color-rio-600); font-weight: 600; }
    .ok-rule .dot { background: var(--color-rio-500); color: white; }
    .code { font-family: var(--font-mono); font-size: 1.6rem; letter-spacing: .6em; text-align: center; }
    .qr img { display: block; border-radius: 8px; background: white; }
    .demo summary { cursor: pointer; font-weight: 700; font-size: .9rem; color: var(--brand); }
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly toasts = inject(ToastService);
  private readonly settings = inject(SettingsStore);
  protected readonly help = inject(HelpService);
  protected readonly theme = inject(ThemeService);
  private readonly codeField = viewChild<ElementRef<HTMLInputElement>>('codeField');

  protected readonly step = signal<Step>('credentials');
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly newPassword = signal('');
  protected readonly confirmPassword = signal('');
  protected readonly code = signal('');
  protected readonly showPassword = signal(false);
  protected readonly capsLock = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly shake = signal(false);
  protected readonly qr = signal<string | null>(null);
  protected readonly challenge = this.auth.challenge;

  protected readonly canSubmit = computed(() => isValidUsername(this.username()) && this.password().length > 0);
  protected readonly stepList = computed(() => {
    const list: { id: Step; label: string }[] = [{ id: 'credentials', label: 'Identidad' }];
    if (this.step() === 'password') {
      list.push({ id: 'password', label: 'Nueva contraseña' });
    }
    if (this.step() === 'mfa' || this.step() === 'mfa-enroll') {
      list.push({ id: this.step(), label: 'Segundo factor' });
    }
    return list;
  });
  protected readonly stepIndex = computed(() => this.stepList().findIndex((s) => s.id === this.step()));

  protected readonly passwordChecks = computed(() => {
    const min = this.settings.settings().passwordMinLength || 12;
    const issues = passwordIssues(this.newPassword(), this.username(), min);
    const pw = this.newPassword();
    return [
      { label: `Al menos ${min} caracteres`, ok: pw.length >= min },
      { label: 'Mayúsculas y minúsculas', ok: /[a-z]/.test(pw) && /[A-Z]/.test(pw) },
      { label: 'Al menos un número', ok: /\d/.test(pw) },
      { label: 'Al menos un símbolo', ok: /[^A-Za-z0-9]/.test(pw) },
      { label: 'No contiene su cédula', ok: pw.length > 0 && !issues.some((i) => i.includes('cédula')) },
    ];
  });
  protected readonly strength = computed(() => (this.passwordChecks().filter((c) => c.ok).length / this.passwordChecks().length) * 100);
  protected readonly strengthLevel = computed(() => (this.strength() < 60 ? 1 : this.strength() < 100 ? 2 : 3));
  protected readonly passwordReady = computed(
    () => this.passwordChecks().every((c) => c.ok) && this.newPassword() === this.confirmPassword(),
  );

  protected checkCaps(event: KeyboardEvent): void {
    this.capsLock.set(event.getModifierState?.('CapsLock') ?? false);
  }

  protected onCode(event: Event): void {
    const value = (event.target as HTMLInputElement).value.replace(/\D/g, '').slice(0, 6);
    (event.target as HTMLInputElement).value = value;
    this.code.set(value);
    if (value.length === 6) {
      this.submitCode();
    }
  }

  protected submitCredentials(): void {
    this.run(this.auth.login(this.username(), this.password()));
  }

  protected submitNewPassword(): void {
    if (this.newPassword() !== this.confirmPassword()) {
      this.error.set('Las contraseñas no coinciden.');
      return;
    }
    this.run(this.auth.changeRequiredPassword(this.newPassword()));
  }

  protected submitCode(): void {
    if (this.busy()) {
      return;
    }
    this.run(this.auth.verifyMfa(this.code()));
  }

  protected restart(): void {
    this.auth.clear();
    this.step.set('credentials');
    this.password.set('');
    this.code.set('');
    this.error.set(null);
  }

  private run(request: ReturnType<AuthStore['login']>): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: (r) => this.advance(r),
      error: (p: Problem) => {
        this.busy.set(false);
        this.error.set(p.detail);
        this.code.set('');
        this.shake.set(true);
        setTimeout(() => this.shake.set(false), 500);
      },
    });
  }

  private advance(r: AuthResponse): void {
    this.busy.set(false);
    switch (r.status) {
      case 'AUTHENTICATED':
        this.toasts.ok(`Bienvenido/a, ${r.user?.displayName.split(' ')[0] ?? ''}`);
        void this.router.navigateByUrl(this.auth.home());
        break;
      case 'PASSWORD_CHANGE_REQUIRED':
        this.step.set('password');
        break;
      case 'MFA_ENROLLMENT_REQUIRED':
        this.step.set('mfa-enroll');
        if (r.otpauthUri) {
          void QRCode.toDataURL(r.otpauthUri, { margin: 1, width: 336, color: { dark: '#14284b', light: '#ffffff' } }).then((url) => this.qr.set(url));
        }
        setTimeout(() => this.codeField()?.nativeElement.focus(), 80);
        break;
      case 'MFA_REQUIRED':
        this.step.set('mfa');
        setTimeout(() => this.codeField()?.nativeElement.focus(), 80);
        break;
    }
  }
}
