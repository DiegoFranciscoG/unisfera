import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ApiService } from '../../core/api.service';
import { AuthStore } from '../../core/auth.store';
import { EcDatePipe } from '../../core/format';
import { Problem } from '../../core/models';
import { passwordIssues } from '../../core/rules';
import { SettingsStore, ToastService } from '../../core/ui.services';
import { IconComponent } from '../../shared/icon.component';
import { PageHeaderComponent, SkeletonComponent } from '../../shared/widgets';

interface PersonView {
  id: string;
  idType: string;
  idNumber: string;
  firstNames: string;
  lastNames: string;
  birthDate: string;
  sex: string;
  maritalStatus: string | null;
  nationality: string;
  address: string | null;
  phoneMobile: string | null;
  phoneHome: string | null;
  personalEmail: string | null;
  editableFields: string[];
}

interface Contact {
  id: string;
  fullName: string;
  relationship: string;
  phone: string;
}

interface Consent {
  purpose: string;
  textVersion: string;
  grantedAt: string;
  revokedAt: string | null;
}

const FIELDS: { key: keyof PersonView; label: string; type?: string }[] = [
  { key: 'address', label: 'Dirección' },
  { key: 'phoneMobile', label: 'Celular', type: 'tel' },
  { key: 'phoneHome', label: 'Teléfono fijo', type: 'tel' },
  { key: 'personalEmail', label: 'Correo personal', type: 'email' },
  { key: 'maritalStatus', label: 'Estado civil' },
];

const CONSENTS = [
  { purpose: 'WELFARE_SENSITIVE', title: 'Atención de Bienestar', text: 'Permito que Bienestar registre, cifrado, lo que comparto en atenciones de salud u orientación.' },
  { purpose: 'SOCIOECONOMIC', title: 'Ficha socioeconómica', text: 'Permito el uso de mi información socioeconómica para evaluar becas y ayudas.' },
  { purpose: 'COMMUNICATIONS', title: 'Comunicaciones', text: 'Acepto recibir avisos institucionales en mi correo personal.' },
  { purpose: 'PHOTO', title: 'Fotografía', text: 'Permito el uso de mi fotografía en el carné y documentos institucionales.' },
];

/** Mi ficha: datos que el estudiante puede actualizar (según política por campo), contactos, consentimientos LOPDP y contraseña. */
@Component({
  selector: 'app-profile-page',
  imports: [IconComponent, PageHeaderComponent, SkeletonComponent, EcDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header kicker="Mi ficha" title="Tus datos, bajo tu control" subtitle="Solo pedimos lo necesario (LOPDP). Los datos de identidad los corrige Secretaría con respaldo." />

    @if (profile.value(); as p) {
      <div class="grid gap-5 lg:grid-cols-[1fr_1fr]">
        <section class="tile p-5" style="animation: var(--animate-rise)">
          <div class="flex items-center gap-4">
            <span class="avatar grid h-16 w-16 place-items-center rounded-2xl text-xl font-bold text-white">{{ p.firstNames[0] }}{{ p.lastNames[0] }}</span>
            <div>
              <h2 class="text-2xl">{{ p.firstNames }} {{ p.lastNames }}</h2>
              <p class="muted mono text-sm">{{ masked(p.idNumber) }} · {{ p.nationality }}</p>
            </div>
          </div>
          <dl class="mt-5 grid grid-cols-2 gap-3 text-sm">
            <div><dt class="label">Nacimiento</dt><dd>{{ p.birthDate | ecDate }}</dd></div>
            <div><dt class="label">Sexo</dt><dd>{{ p.sex === 'MUJER' ? 'Mujer' : 'Hombre' }}</dd></div>
          </dl>
          <form class="mt-5 space-y-3" (submit)="$event.preventDefault(); save(p)">
            @for (f of fields; track f.key) {
              <div>
                <label class="label" [attr.for]="'f-' + f.key">{{ f.label }}
                  @if (!p.editableFields.includes(f.key)) { <app-icon name="lock" [size]="11" /> }</label>
                <input [id]="'f-' + f.key" class="field" [type]="f.type ?? 'text'" [value]="p[f.key] ?? ''" [disabled]="!p.editableFields.includes(f.key)"
                  (input)="edit(f.key, $any($event.target).value)" maxlength="300" />
              </div>
            }
            <button class="btn btn-primary" [disabled]="!dirty() || busy()">Guardar cambios</button>
          </form>
        </section>

        <div class="space-y-5">
          <section class="tile p-5" style="animation: var(--animate-rise); animation-delay: 80ms">
            <h2 class="text-xl">Contactos de emergencia</h2>
            <ul class="mt-3 space-y-2">
              @for (c of contacts.value() ?? []; track c.id) {
                <li class="flex items-center gap-3 rounded-xl p-3" style="background: var(--surface-2)">
                  <app-icon name="heart" [size]="16" />
                  <div class="min-w-0 flex-1"><p class="font-semibold">{{ c.fullName }}</p><p class="muted text-xs">{{ c.relationship }} · {{ c.phone }}</p></div>
                  <button class="muted" (click)="removeContact(c)" [attr.aria-label]="'Quitar ' + c.fullName"><app-icon name="x" [size]="15" /></button>
                </li>
              } @empty {
                <li class="muted text-sm">Aún no registras contactos.</li>
              }
            </ul>
            <form class="mt-3 grid gap-2 sm:grid-cols-3" (submit)="$event.preventDefault(); addContact()">
              <input class="field" placeholder="Nombre" maxlength="160" [value]="cName()" (input)="cName.set($any($event.target).value)" aria-label="Nombre del contacto" />
              <input class="field" placeholder="Parentesco" maxlength="40" [value]="cRel()" (input)="cRel.set($any($event.target).value)" aria-label="Parentesco" />
              <input class="field" placeholder="Teléfono" type="tel" [value]="cPhone()" (input)="cPhone.set($any($event.target).value)" aria-label="Teléfono" />
              <button class="btn btn-ghost sm:col-span-3" [disabled]="!cName() || !cRel() || !cPhone()"><app-icon name="plus" [size]="15" /> Agregar contacto</button>
            </form>
          </section>

          <section class="tile p-5" style="animation: var(--animate-rise); animation-delay: 140ms">
            <h2 class="text-xl">Consentimientos (LOPDP)</h2>
            <p class="muted text-sm">Puedes otorgarlos o revocarlos cuando quieras; queda registro con fecha.</p>
            <ul class="mt-3 space-y-2">
              @for (c of consentList(); track c.purpose) {
                <li class="flex items-start gap-3 rounded-xl p-3" style="background: var(--surface-2)">
                  <button class="switch" role="switch" [attr.aria-checked]="c.active" [class.on]="c.active" (click)="toggleConsent(c.purpose, c.active)"
                    [attr.aria-label]="c.title"><span></span></button>
                  <div class="min-w-0 flex-1">
                    <p class="font-semibold">{{ c.title }}</p>
                    <p class="muted text-xs">{{ c.text }}</p>
                    @if (c.grantedAt) { <p class="muted mt-1 text-[11px]">Otorgado el {{ c.grantedAt | ecDate: 'datetime' }}</p> }
                  </div>
                </li>
              }
            </ul>
          </section>

          <section class="tile p-5" style="animation: var(--animate-rise); animation-delay: 200ms">
            <h2 class="text-xl">Contraseña</h2>
            <form class="mt-3 space-y-3" (submit)="$event.preventDefault(); changePassword(p.idNumber)">
              <input class="field" type="password" placeholder="Contraseña actual" autocomplete="current-password" [value]="current()" (input)="current.set($any($event.target).value)" aria-label="Contraseña actual" />
              <input class="field" type="password" placeholder="Nueva contraseña" autocomplete="new-password" [value]="next()" (input)="next.set($any($event.target).value)" aria-label="Nueva contraseña" />
              @for (i of issues(p.idNumber); track i) { <p class="text-xs" style="color: var(--color-alerta-500)">• {{ i }}</p> }
              <button class="btn btn-ghost" [disabled]="!current() || !next() || issues(p.idNumber).length > 0">Cambiar contraseña</button>
            </form>
          </section>
        </div>
      </div>
    } @else {
      <app-skeleton [count]="3" [height]="160" />
    }
  `,
  styles: `
    .avatar { background: linear-gradient(140deg, var(--color-teja-400), var(--color-cupula-700)); }
    .switch { width: 42px; height: 24px; border-radius: 999px; background: var(--line); position: relative; flex-shrink: 0; transition: background .25s; margin-top: 2px; }
    .switch span { position: absolute; top: 3px; left: 3px; width: 18px; height: 18px; border-radius: 999px; background: white; transition: transform .25s cubic-bezier(.2,.9,.3,1.3); box-shadow: 0 1px 3px rgba(0,0,0,.3); }
    .switch.on { background: var(--color-rio-500); }
    .switch.on span { transform: translateX(18px); }
  `,
})
export class ProfilePage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthStore);
  private readonly settings = inject(SettingsStore);
  protected readonly profile = rxResource({ stream: () => this.api.get<PersonView>('/me/profile') });
  protected readonly contacts = rxResource({ stream: () => this.api.get<Contact[]>('/me/profile/emergency-contacts') });
  protected readonly consents = rxResource({ stream: () => this.api.get<Consent[]>('/me/consents') });
  protected readonly fields = FIELDS;
  private readonly changes = signal<Record<string, string>>({});
  protected readonly dirty = computed(() => Object.keys(this.changes()).length > 0);
  protected readonly busy = signal(false);
  protected readonly cName = signal('');
  protected readonly cRel = signal('');
  protected readonly cPhone = signal('');
  protected readonly current = signal('');
  protected readonly next = signal('');

  protected readonly consentList = computed(() =>
    CONSENTS.map((c) => {
      const record = (this.consents.value() ?? []).find((x) => x.purpose === c.purpose && !x.revokedAt);
      return { ...c, active: !!record, grantedAt: record?.grantedAt ?? null };
    }),
  );

  /** LOPDP: incluso al titular se le muestra su identificación parcialmente en pantalla. */
  protected masked(id: string): string {
    return id.length > 4 ? '•'.repeat(id.length - 4) + id.slice(-4) : id;
  }

  protected edit(key: string, value: string): void {
    this.changes.update((c) => ({ ...c, [key]: value }));
  }

  protected save(p: PersonView): void {
    this.busy.set(true);
    this.api.patch<PersonView>('/me/profile', this.changes()).subscribe({
      next: () => {
        this.busy.set(false);
        this.changes.set({});
        this.toasts.ok('Datos actualizados');
        this.profile.reload();
      },
      error: (e: Problem) => {
        this.busy.set(false);
        this.toasts.problem(e);
      },
    });
  }

  protected addContact(): void {
    this.api.post('/me/profile/emergency-contacts', { fullName: this.cName(), relationship: this.cRel(), phone: this.cPhone() }).subscribe({
      next: () => {
        this.cName.set('');
        this.cRel.set('');
        this.cPhone.set('');
        this.contacts.reload();
      },
      error: (e: Problem) => this.toasts.problem(e),
    });
  }

  protected removeContact(c: Contact): void {
    this.api.post(`/me/profile/emergency-contacts/${c.id}/deactivate`).subscribe({ next: () => this.contacts.reload(), error: (e) => this.toasts.problem(e) });
  }

  protected toggleConsent(purpose: string, active: boolean): void {
    this.api.post(active ? '/me/consents/revoke' : '/me/consents', { purpose }).subscribe({
      next: () => {
        this.toasts.ok(active ? 'Consentimiento revocado' : 'Consentimiento otorgado');
        this.consents.reload();
      },
      error: (e: Problem) => this.toasts.problem(e),
    });
  }

  protected issues(idNumber: string): string[] {
    return this.next() ? passwordIssues(this.next(), idNumber, this.settings.settings().passwordMinLength || 12) : [];
  }

  protected changePassword(_id: string): void {
    this.api.post('/auth/password/change', { currentPassword: this.current(), newPassword: this.next() }).subscribe({
      next: () => {
        this.toasts.ok('Contraseña actualizada', 'Por seguridad, vuelve a ingresar.');
        this.auth.logout();
      },
      error: (e: Problem) => this.toasts.problem(e),
    });
  }
}
