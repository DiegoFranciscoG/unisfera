/** Contratos de la API (espejo de los DTO del backend). */

export interface Problem {
  code: string;
  detail: string;
  status: number;
  helpUrl?: string;
  requestId?: string;
  violations?: Violation[];
  errors?: { field: string; message: string }[];
}

export interface Violation {
  code: string;
  subjectId: string | null;
  message: string;
}

export interface UserSummary {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
  permissions: string[];
}

export interface AuthResponse {
  status: 'AUTHENTICATED' | 'PASSWORD_CHANGE_REQUIRED' | 'MFA_REQUIRED' | 'MFA_ENROLLMENT_REQUIRED';
  accessToken?: string;
  expiresIn?: number;
  challengeToken?: string;
  otpauthUri?: string;
  mfaSecret?: string;
  user?: UserSummary;
}

export interface Settings {
  scaleMin: number;
  scaleMax: number;
  passingGrade: number;
  minAttendance: number;
  attendanceWarningMargin: number;
  maxSubjectsPerPeriod: number;
  passwordMinLength: number;
  currency: string;
  zone: string;
  closeConfirmationPhrase: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Block {
  weekday: number;
  start: string;
  end: string;
  classroom: string | null;
}

export type SubjectStatus = 'APPROVED' | 'IN_PROGRESS' | 'AVAILABLE' | 'BLOCKED' | 'NEEDS_AUTHORIZATION' | 'EXHAUSTED';

export interface SubjectProgress {
  id: string;
  code: string;
  name: string;
  level: number;
  totalHours: number;
  credits: number;
  status: SubjectStatus;
  grade: number | null;
  attemptsUsed: number;
  missingPrerequisites: string[];
  prerequisites: string[];
  corequisites: string[];
}

export interface Progress {
  studentId: string;
  studentCode: string;
  fullName: string;
  careerName: string;
  status: string;
  approvedCredits: number;
  totalCredits: number;
  weightedAverage: number | null;
  approvedSubjects: number;
  totalSubjects: number;
  subjects: SubjectProgress[];
}

export interface SectionOffer {
  sectionId: string;
  code: string;
  capacity: number;
  seatsLeft: number;
  teacherName: string | null;
  blocks: Block[];
}

export interface Offer {
  periodId: string;
  periodCode: string;
  periodName: string;
  typeToday: 'ORDINARY' | 'EXTRAORDINARY' | 'SPECIAL' | null;
  ordinaryFrom: string;
  ordinaryTo: string;
  extraordinaryTo: string;
  specialTo: string;
  maxSubjects: number;
  alreadyEnrolled: boolean;
  subjects: { subject: SubjectProgress; sections: SectionOffer[] }[];
}

export interface QuoteLine {
  concept: string;
  description: string;
  base: number;
  discount: number;
  net: number;
}

export interface Preview {
  periodId: string;
  type: string | null;
  violations: Violation[];
  lines: { sectionId: string; subjectId: string; subjectName: string; sectionCode: string; attempt: number; seatsLeft: number }[];
  quote: { currency: string; lines: QuoteLine[]; total: number; installments: number; firstInstallment: number } | null;
}

export interface Confirmation {
  enrollmentId: string;
  periodCode: string;
  type: string;
  studentStatus: string;
  details: { detailId: string; subjectId: string; subjectName: string; sectionCode: string; attempt: number }[];
}

export interface ScheduleEntry {
  detailId: string;
  sectionId: string;
  subjectCode: string;
  subjectName: string;
  sectionCode: string;
  attempt: number;
  teacherName: string | null;
  blocks: Block[];
}

export interface ComponentGrade {
  componentId: string;
  name: string;
  weight: number;
  value: number | null;
}

export interface CurrentSubject {
  detailId: string;
  subjectCode: string;
  subjectName: string;
  sectionCode: string;
  attempt: number;
  components: ComponentGrade[];
  accumulated: number;
  needed: { accumulated: number; pendingWeight: number; neededAverage: number | null; alreadyPassed: boolean; reachable: boolean };
  attendancePct: number;
  minAttendance: number;
  attendanceWarning: boolean;
  provisional: boolean;
  actStatus: string;
}

export interface KardexEntry {
  subjectId: string;
  subjectCode: string;
  subjectName: string;
  level: number;
  totalHours: number;
  credits: number;
  periodCode: string | null;
  attempt: number | null;
  finalGrade: number;
  attendancePct: number | null;
  result: 'APPROVED' | 'FAILED' | 'FAILED_ATTENDANCE';
  source: 'ACT' | 'HOMOLOGATION';
  recordedAt: string;
}

export interface Kardex {
  studentId: string;
  weightedAverage: number | null;
  approvedCredits: number;
  approvedSubjects: number;
  failedAttempts: number;
  entries: KardexEntry[];
}

export interface Statement {
  summary: {
    charges: number;
    lateFees: number;
    discounts: number;
    payments: number;
    creditNotes: number;
    reversals: number;
    balance: number;
    balances: boolean;
  };
  installments: {
    id: string;
    periodCode: string;
    number: number;
    dueDate: string;
    amountDue: number;
    lateFee: number;
    paid: number;
    outstanding: number;
    status: string;
    overdue: boolean;
  }[];
  entries: Page<{ id: string; occurredAt: string; type: string; direction: number; amount: number; description: string }>;
}

export interface PaymentView {
  id: string;
  amount: number;
  currency: string;
  method: string;
  status: string;
  reference: string;
  createdAt: string;
  confirmedAt: string | null;
  invoiceId: string | null;
  checkoutUrl: string | null;
}

export interface SectionCard {
  id: string;
  subjectCode: string;
  subjectName: string;
  sectionCode: string;
  periodCode: string;
  enrolled: number;
  capacity: number;
  actStatus: string;
  sessions: number;
}

export interface RosterRow {
  detailId: string;
  studentId: string;
  studentCode: string;
  fullName: string;
  attempt: number;
  grades: { componentId: string; value: number; version: number }[];
  accumulated: number;
  finalGrade: number | null;
  attendancePct: number;
  provisionalResult: string | null;
  attendanceRisk: boolean;
}

export interface Roster {
  sectionId: string;
  subjectName: string;
  sectionCode: string;
  scheme: {
    id: string;
    scale: { min: number; max: number; passing: number; minAttendance: number };
    components: { id: string; name: string; weight: number }[];
  };
  actStatus: string;
  sessions: number;
  rows: RosterRow[];
}

export interface ActView {
  id: string;
  sectionId: string;
  subjectName: string;
  sectionCode: string;
  periodCode: string;
  type: string;
  status: string;
  contentHash: string;
  signature: string;
  closedAt: string;
  rectifiesActId: string | null;
  supersededById: string | null;
  reason: string | null;
  lines: { detailId: string; studentCode: string; fullName: string; finalGrade: number; attendancePct: number; result: string }[];
}

export interface NotificationItem {
  id: string;
  category: string;
  title: string;
  body: string;
  link: string | null;
  createdAt: string;
  readAt: string | null;
}

export interface Manual {
  slug: string;
  title: string;
  audience: string;
  markdown?: string;
}
