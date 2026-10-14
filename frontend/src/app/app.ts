import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HelpDrawerComponent, ToastsComponent } from './layout/overlays';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastsComponent, HelpDrawerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <router-outlet />
    <app-toasts />
    <app-help-drawer />
  `,
})
export class App {}
