import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * La marca: una cúpula-esfera de azulejos con meridianos que giran y tres satélites que orbitan
 * (los tres portales sobre el mismo núcleo). SVG + CSS, sin JavaScript de animación.
 */
@Component({
  selector: 'app-sphere',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 200 200" role="img" aria-label="UNISFERA">
      <defs>
        <radialGradient id="sph-body" cx="36%" cy="30%" r="75%">
          <stop offset="0" stop-color="#9cc3f5" />
          <stop offset=".45" stop-color="#2f69d1" />
          <stop offset="1" stop-color="#132a57" />
        </radialGradient>
        <radialGradient id="sph-shine" cx="32%" cy="26%" r="30%">
          <stop offset="0" stop-color="#fff" stop-opacity=".75" />
          <stop offset="1" stop-color="#fff" stop-opacity="0" />
        </radialGradient>
        <clipPath id="sph-clip"><circle cx="100" cy="100" r="62" /></clipPath>
        <pattern id="sph-tiles" width="14" height="14" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="14" height="14" fill="none" stroke="#e8f0ff" stroke-opacity=".22" stroke-width="1.4" />
        </pattern>
      </defs>
      @if (orbits()) {
        <g class="orbit o1"><ellipse cx="100" cy="100" rx="94" ry="30" fill="none" stroke="currentColor" stroke-opacity=".22" stroke-width="1.2" />
          <circle class="moon" cx="194" cy="100" r="6" fill="#e2a93b" /></g>
        <g class="orbit o2"><ellipse cx="100" cy="100" rx="86" ry="40" fill="none" stroke="currentColor" stroke-opacity=".16" stroke-width="1.2" transform="rotate(-28 100 100)" />
          <circle class="moon" cx="182" cy="62" r="5" fill="#c4532d" /></g>
        <g class="orbit o3"><ellipse cx="100" cy="100" rx="80" ry="22" fill="none" stroke="currentColor" stroke-opacity=".14" stroke-width="1.2" transform="rotate(32 100 100)" />
          <circle class="moon" cx="168" cy="140" r="4.5" fill="#2e8c7e" /></g>
      }
      <circle cx="100" cy="100" r="62" fill="url(#sph-body)" />
      <g clip-path="url(#sph-clip)">
        <rect x="20" y="20" width="160" height="160" fill="url(#sph-tiles)" />
        <g class="meridians" fill="none" stroke="#dbe8ff" stroke-opacity=".38" stroke-width="1.3">
          <ellipse cx="100" cy="100" rx="62" ry="62" />
          <ellipse cx="100" cy="100" rx="44" ry="62" />
          <ellipse cx="100" cy="100" rx="22" ry="62" />
          <ellipse cx="100" cy="100" rx="4" ry="62" />
        </g>
        <g fill="none" stroke="#dbe8ff" stroke-opacity=".3" stroke-width="1.2">
          <ellipse cx="100" cy="78" rx="58" ry="10" />
          <ellipse cx="100" cy="100" rx="62" ry="12" />
          <ellipse cx="100" cy="122" rx="58" ry="10" />
        </g>
      </g>
      <circle cx="100" cy="100" r="62" fill="url(#sph-shine)" />
      <path d="M100 30 v-14 M93 23 h14" stroke="#e2a93b" stroke-width="3.2" stroke-linecap="round" />
    </svg>
  `,
  styles: `
    :host { display: inline-flex; color: var(--brand); }
    .meridians { animation: turn 9s linear infinite; transform-origin: 100px 100px; transform-box: view-box; }
    .orbit { transform-origin: 100px 100px; transform-box: view-box; }
    .o1 { animation: spin 14s linear infinite; }
    .o2 { animation: spin 21s linear infinite reverse; }
    .o3 { animation: spin 28s linear infinite; }
    .moon { filter: drop-shadow(0 0 4px rgba(255,255,255,.6)); }
    @keyframes turn { from { transform: scaleX(1); } 50% { transform: scaleX(-1); } to { transform: scaleX(1); } }
    @keyframes spin { to { transform: rotate(360deg); } }
  `,
})
export class SphereLogoComponent {
  readonly size = input(44);
  readonly orbits = input(false);
}
