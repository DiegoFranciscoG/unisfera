import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthStore } from '../core/auth.store';
import { EcDatePipe } from '../core/format';
import { NotificationItem, Page } from '../core/models';
import { HelpService, ThemeService } from '../core/ui.services';
import { IconComponent } from '../shared/icon.component';
import { SphereLogoComponent } from '../shared/sphere-logo.component';

interface NavItem {
  label: string;
  icon: string;
  link: string;
  permissions?: string[];
}

const NAV: Record<'estudiante' | 'docente' | 'admin', { title: string; items: NavItem[] }> = {
  estudiante: {
    title: 'Portal del estudiante',
    items: [
      { label: 'Inicio', icon: 'home', link: '/estudiante' },
      { label: 'Mi malla', icon: 'orbit', link: '/estudiante/malla' },
      { label: 'Matrícula', icon: 'sparkle', link: '/estudiante/matricula' },
      { label: 'Horario', icon: 'calendar', link: '/estudiante/horario' },
      { label: 'Mis notas', icon: 'grade', link: '/estudiante/notas' },
      { label: 'Kardex', icon: 'book', link: '/estudiante/kardex' },
      { label: 'Cuenta y pagos', icon: 'wallet', link: '/estudiante/cuenta' },
      { label: 'Becas', icon: 'gift', link: '/estudiante/becas' },
      { label: 'Solicitudes', icon: 'inbox', link: '/estudiante/solicitudes' },
      { label: 'Mi ficha', icon: 'user', link: '/estudiante/ficha' },
    ],
  },
  docente: {
    title: 'Portal docente',
    items: [
      { label: 'Mis paralelos', icon: 'users', link: '/docente' },
      { label: 'Historial de actas', icon: 'seal', link: '/docente/actas' },
    ],
  },
  admin: {
    title: 'Administración',
    items: [
      { label: 'Tablero', icon: 'chart', link: '/admin', permissions: ['REPORT_READ'] },
      { label: 'Estudiantes', icon: 'users', link: '/admin/estudiantes', permissions: ['STUDENT_READ'] },
      { label: 'Oferta académica', icon: 'building', link: '/admin/oferta', permissions: ['CATALOG_READ'] },
      { label: 'Parámetros', icon: 'sliders', link: '/admin/parametros', permissions: ['PARAMETER_READ'] },
      { label: 'Finanzas', icon: 'wallet', link: '/admin/finanzas', permissions: ['FINANCE_READ'] },
      { label: 'Bienestar', icon: 'heart', link: '/admin/bienestar', permissions: ['SCHOLARSHIP_READ', 'DROPOUT_MANAGE'] },
      { label: 'Reportes', icon: 'file', link: '/admin/reportes', permissions: ['REPORT_READ'] },
      { label: 'Solicitudes', icon: 'inbox', link: '/admin/solicitudes', permissions: ['REQUEST_MANAGE'] },
      { label: 'Auditoría', icon: 'shield', link: '/admin/auditoria', permissions: ['AUDIT_READ'] },
    ],
  },
};

/** Marco común de los tres portales: un solo frontend que cambia según los permisos del usuario. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent, SphereLogoComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a href="#main" class="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[70] btn btn-primary">Saltar al contenido</a>
    <div class="flex min-h-screen">
      <!-- Barra lateral -->
      <aside class="sidebar azulejo fixed inset-y-0 left-0 z-40 flex w-[264px] flex-col border-r transition-transform duration-300 lg:translate-x-0"
        [class.-translate-x-full]="!menuOpen()" style="border-color: var(--line)" aria-label="Navegación principal">
        <a routerLink="/" class="flex items-center gap-3 px-5 pt-5 pb-4">
          <app-sphere [size]="42" />
          <span>
            <span class="font-display block text-[1.55rem] leading-none tracking-tight">UNISFERA</span>
            <span class="muted text-[0.7rem] font-bold uppercase tracking-[0.18em]">{{ nav().title }}</span>
          </span>
        </a>
        <nav class="flex-1 overflow-y-auto px-3 pb-4">
          <ul class="space-y-1 stagger">
            @for (item of items(); track item.link; let i = $index) {
              <li [style.--i]="i">
                <a [routerLink]="item.link" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: isRoot(item.link) }"
                  class="nav-link flex items-center gap-3 rounded-xl px-3 py-2.5 font-semibold" (click)="menuOpen.set(false)">
                  <app-icon [name]="item.icon" [size]="19" />
                  <span>{{ item.label }}</span>
                </a>
              </li>
            }
            <li [style.--i]="items().length">
              <a routerLink="/ayuda" routerLinkActive="active" class="nav-link flex items-center gap-3 rounded-xl px-3 py-2.5 font-semibold"
                (click)="menuOpen.set(false)"><app-icon name="help" [size]="19" /><span>Manuales</span></a>
            </li>
          </ul>
        </nav>
        <div class="m-3 rounded-2xl p-3" style="background: var(--surface)">
          <div class="flex items-center gap-3">
            <span class="avatar grid h-10 w-10 shrink-0 place-items-center rounded-full font-bold">{{ initials() }}</span>
            <div class="min-w-0">
              <p class="truncate text-sm font-bold">{{ auth.user()?.displayName }}</p>
              <p class="muted mono truncate text-xs">{{ auth.user()?.username }}</p>
            </div>
          </div>
          <div class="mt-3 grid grid-cols-3 gap-2">
            <button class="btn btn-ghost !px-0 !py-2" (click)="theme.toggle()" [attr.aria-label]="theme.dark() ? 'Tema claro' : 'Tema oscuro'"
              [title]="theme.dark() ? 'Tema claro' : 'Tema oscuro'"><app-icon [name]="theme.dark() ? 'sun' : 'moon'" [size]="17" /></button>
            <button class="btn btn-ghost !px-0 !py-2" (click)="help.show()" aria-label="Ayuda" title="Ayuda"><app-icon name="help" [size]="17" /></button>
            <button class="btn btn-ghost !px-0 !py-2" (click)="auth.logout()" aria-label="Cerrar sesión" title="Cerrar sesión"><app-icon name="logout" [size]="17" /></button>
          </div>
          <button class="muted mt-2 w-full text-center text-xs font-semibold underline-offset-2 hover:underline" (click)="auth.logout(true)">
            Cerrar sesión en todos mis dispositivos
          </button>
        </div>
      </aside>
      @if (menuOpen()) {
        <div class="fixed inset-0 z-30 bg-black/30 lg:hidden" (click)="menuOpen.set(false)" aria-hidden="true"></div>
      }

      <!-- Contenido -->
      <div class="flex min-w-0 flex-1 flex-col lg:pl-[264px]">
        <header class="topbar sticky top-0 z-20 flex items-center gap-3 px-4 py-3 md:px-8">
          <button class="btn btn-ghost !p-2 lg:hidden" (click)="menuOpen.set(true)" aria-label="Abrir menú"><app-icon name="menu" /></button>
          <p class="muted hidden text-sm md:block">{{ greeting() }}, <strong style="color: var(--text)">{{ firstName() }}</strong></p>
          <div class="flex-1"></div>
          <button class="btn btn-ghost !px-3 !py-2" (click)="help.show(helpSlug())"><app-icon name="help" [size]="17" /> <span class="hidden sm:inline">¿Cómo funciona?</span></button>
          <div class="relative">
            <button class="btn btn-ghost relative !p-2" (click)="toggleNotifications()" aria-label="Notificaciones" [attr.aria-expanded]="notifOpen()">
              <app-icon name="bell" [size]="19" />
              @if (unread() > 0) {
                <span class="badge absolute -right-1 -top-1 grid h-5 min-w-5 place-items-center rounded-full px-1 text-[10px] font-bold text-white">{{ unread() }}</span>
              }
            </button>
            @if (notifOpen()) {
              <div class="tile absolute right-0 mt-2 w-[min(92vw,360px)] overflow-hidden" style="animation: var(--animate-rise)">
                <p class="border-b px-4 py-3 font-bold" style="border-color: var(--line)">Notificaciones</p>
                <ul class="max-h-96 overflow-y-auto">
                  @for (n of notifications(); track n.id) {
                    <li>
                      <button class="w-full px-4 py-3 text-left hover:bg-[var(--surface-2)]" [class.opacity-60]="n.readAt" (click)="openNotification(n)">
                        <p class="text-sm font-bold">{{ n.title }}</p>
                        <p class="muted text-xs">{{ n.body }}</p>
                        <p class="muted mt-1 text-[11px]">{{ n.createdAt | ecDate: 'datetime' }}</p>
                      </button>
                    </li>
                  } @empty {
                    <li class="muted px-4 py-6 text-center text-sm">Todo al día.</li>
                  }
                </ul>
              </div>
            }
          </div>
        </header>
        <main id="main" class="mx-auto w-full max-w-[1280px] flex-1 px-4 pb-16 pt-4 md:px-8" tabindex="-1">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
  styles: `
    .sidebar { background-color: var(--bg); }
    .nav-link { color: var(--muted); transition: background .2s, color .2s, transform .2s; }
    .nav-link:hover { background: var(--surface); color: var(--text); transform: translateX(2px); }
    .nav-link.active { background: var(--surface); color: var(--brand); box-shadow: var(--shadow-tile); }
    .nav-link.active app-icon { color: var(--color-teja-500); }
    .avatar { background: linear-gradient(140deg, var(--color-cupula-400), var(--color-cupula-700)); color: white; }
    .topbar { background: color-mix(in srgb, var(--bg) 82%, transparent); backdrop-filter: blur(10px); }
    .badge { background: var(--color-teja-500); animation: pulse-ring 1.8s infinite; }
  `,
})
export class ShellComponent implements OnInit, OnDestroy {
  protected readonly auth = inject(AuthStore);
  protected readonly theme = inject(ThemeService);
  protected readonly help = inject(HelpService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly menuOpen = signal(false);
  protected readonly notifOpen = signal(false);
  protected readonly notifications = signal<NotificationItem[]>([]);
  protected readonly unread = signal(0);
  protected readonly helpSlug = signal('preguntas-frecuentes');
  private timer: ReturnType<typeof setInterval> | undefined;

  protected readonly nav = computed(() => NAV[this.auth.portal()]);
  protected readonly items = computed(() =>
    this.nav().items.filter((i) => !i.permissions || this.auth.hasAny(...i.permissions)),
  );
  protected readonly firstName = computed(() => (this.auth.user()?.displayName ?? '').split(' ')[0]);
  protected readonly initials = computed(() =>
    (this.auth.user()?.displayName ?? '?').split(' ').filter(Boolean).slice(0, 2).map((p) => p[0]).join(''),
  );
  protected readonly greeting = computed(() => {
    const h = new Date().getHours();
    return h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches';
  });

  ngOnInit(): void {
    this.loadUnread();
    this.timer = setInterval(() => this.loadUnread(), 60_000);
    this.updateHelp();
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.updateHelp());
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected isRoot(link: string): boolean {
    return link.split('/').length === 2;
  }

  protected toggleNotifications(): void {
    this.notifOpen.update((v) => !v);
    if (this.notifOpen()) {
      this.api.get<Page<NotificationItem>>('/me/notifications', { size: 15 }).subscribe((p) => this.notifications.set(p.items));
    }
  }

  protected openNotification(n: NotificationItem): void {
    this.api.post(`/me/notifications/${n.id}/read`).subscribe(() => this.loadUnread());
    this.notifOpen.set(false);
    if (n.link) {
      void this.router.navigateByUrl(n.link);
    }
  }

  private loadUnread(): void {
    this.api.get<{ unread: number }>('/me/notifications/unread-count').subscribe({ next: (r) => this.unread.set(r.unread), error: () => undefined });
  }

  private updateHelp(): void {
    let r = this.route;
    while (r.firstChild) {
      r = r.firstChild;
    }
    this.helpSlug.set((r.snapshot.data['help'] as string) ?? 'preguntas-frecuentes');
  }
}
