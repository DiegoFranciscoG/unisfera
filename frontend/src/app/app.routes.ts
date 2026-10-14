import { Routes } from '@angular/router';
import { authGuard, guestGuard, homeRedirect, permissionGuard } from './core/guards';
import { ShellComponent } from './layout/shell.component';

/** Cada ruta declara su manual en data.help: el botón "¿Cómo funciona?" abre justo ese. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },
  {
    path: 'ingresar',
    canActivate: [guestGuard],
    title: 'Ingresar · UNISFERA',
    loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'recuperar',
    title: 'Recuperar acceso · UNISFERA',
    loadComponent: () => import('./features/auth/recover.page').then((m) => m.RecoverPage),
  },
  {
    path: 'pagos/pasarela/:reference',
    title: 'Pasarela de pago (simulada) · UNISFERA',
    loadComponent: () => import('./features/student/gateway.page').then((m) => m.GatewayPage),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: 'estudiante',
        canActivate: [permissionGuard('SELF_SERVICE')],
        children: [
          { path: '', title: 'Mi órbita · UNISFERA', data: { help: 'primer-ingreso' }, loadComponent: () => import('./features/student/home.page').then((m) => m.StudentHomePage) },
          { path: 'malla', title: 'Mi malla · UNISFERA', data: { help: 'prerrequisitos' }, loadComponent: () => import('./features/student/curriculum.page').then((m) => m.CurriculumPage) },
          { path: 'matricula', title: 'Matrícula · UNISFERA', data: { help: 'como-me-matriculo' }, loadComponent: () => import('./features/student/enrollment.page').then((m) => m.EnrollmentPage) },
          { path: 'horario', title: 'Horario · UNISFERA', data: { help: 'como-me-matriculo' }, loadComponent: () => import('./features/student/schedule.page').then((m) => m.SchedulePage) },
          { path: 'notas', title: 'Mis notas · UNISFERA', data: { help: 'como-leo-mis-notas' }, loadComponent: () => import('./features/student/grades.page').then((m) => m.GradesPage) },
          { path: 'kardex', title: 'Kardex · UNISFERA', data: { help: 'kardex-y-promedio' }, loadComponent: () => import('./features/student/kardex.page').then((m) => m.KardexPage) },
          { path: 'cuenta', title: 'Cuenta y pagos · UNISFERA', data: { help: 'como-pago-en-linea' }, loadComponent: () => import('./features/student/account.page').then((m) => m.AccountPage) },
          { path: 'becas', title: 'Becas · UNISFERA', data: { help: 'como-postulo-a-una-beca' }, loadComponent: () => import('./features/student/scholarships.page').then((m) => m.ScholarshipsPage) },
          { path: 'solicitudes', title: 'Solicitudes · UNISFERA', data: { help: 'retiros-homologaciones-certificados' }, loadComponent: () => import('./features/student/requests.page').then((m) => m.RequestsPage) },
          { path: 'ficha', title: 'Mi ficha · UNISFERA', data: { help: 'primer-ingreso' }, loadComponent: () => import('./features/student/profile.page').then((m) => m.ProfilePage) },
        ],
      },
      {
        path: 'docente',
        canActivate: [permissionGuard('TEACHING_OWN_SECTIONS')],
        children: [
          { path: '', title: 'Mis paralelos · UNISFERA', data: { help: 'docente-calificaciones-y-actas' }, loadComponent: () => import('./features/teacher/sections.page').then((m) => m.TeacherSectionsPage) },
          { path: 'actas', title: 'Historial de actas · UNISFERA', data: { help: 'docente-calificaciones-y-actas' }, loadComponent: () => import('./features/teacher/acts.page').then((m) => m.TeacherActsPage) },
          { path: 'paralelos/:sectionId', title: 'Paralelo · UNISFERA', data: { help: 'docente-calificaciones-y-actas' }, loadComponent: () => import('./features/teacher/section.page').then((m) => m.TeacherSectionPage) },
        ],
      },
      {
        path: 'admin',
        children: [
          { path: '', canActivate: [permissionGuard('REPORT_READ')], title: 'Tablero · UNISFERA', data: { help: 'administrador-guia' }, loadComponent: () => import('./features/admin/dashboard.page').then((m) => m.AdminDashboardPage) },
          { path: 'estudiantes', canActivate: [permissionGuard('STUDENT_READ')], title: 'Estudiantes · UNISFERA', data: { help: 'administrador-guia' }, loadComponent: () => import('./features/admin/students.page').then((m) => m.AdminStudentsPage) },
          { path: 'oferta', canActivate: [permissionGuard('CATALOG_READ')], title: 'Oferta académica · UNISFERA', data: { help: 'administrador-guia' }, loadComponent: () => import('./features/admin/offer.page').then((m) => m.AdminOfferPage) },
          { path: 'parametros', canActivate: [permissionGuard('PARAMETER_READ')], title: 'Parámetros · UNISFERA', data: { help: 'administrador-parametros' }, loadComponent: () => import('./features/admin/parameters.page').then((m) => m.AdminParametersPage) },
          { path: 'finanzas', canActivate: [permissionGuard('FINANCE_READ')], title: 'Finanzas · UNISFERA', data: { help: 'administrador-guia' }, loadComponent: () => import('./features/admin/finance.page').then((m) => m.AdminFinancePage) },
          { path: 'bienestar', canActivate: [permissionGuard('SCHOLARSHIP_READ', 'DROPOUT_MANAGE')], title: 'Bienestar · UNISFERA', data: { help: 'como-mantengo-mi-beca' }, loadComponent: () => import('./features/admin/welfare.page').then((m) => m.AdminWelfarePage) },
          { path: 'reportes', canActivate: [permissionGuard('REPORT_READ')], title: 'Reportes · UNISFERA', data: { help: 'administrador-guia' }, loadComponent: () => import('./features/admin/reports.page').then((m) => m.AdminReportsPage) },
          { path: 'solicitudes', canActivate: [permissionGuard('REQUEST_MANAGE')], title: 'Solicitudes · UNISFERA', data: { help: 'retiros-homologaciones-certificados' }, loadComponent: () => import('./features/admin/requests.page').then((m) => m.AdminRequestsPage) },
          { path: 'auditoria', canActivate: [permissionGuard('AUDIT_READ')], title: 'Auditoría · UNISFERA', data: { help: 'administrador-guia' }, loadComponent: () => import('./features/admin/audit.page').then((m) => m.AdminAuditPage) },
        ],
      },
      { path: 'ayuda', title: 'Manuales · UNISFERA', loadComponent: () => import('./features/help/manuals.page').then((m) => m.ManualsPage) },
      { path: 'ayuda/:slug', title: 'Manual · UNISFERA', loadComponent: () => import('./features/help/manual.page').then((m) => m.ManualPage) },
    ],
  },
  { path: '**', title: 'No encontrado · UNISFERA', loadComponent: () => import('./features/help/not-found.page').then((m) => m.NotFoundPage) },
];
