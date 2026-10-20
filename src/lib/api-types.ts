/**
 * Shapes of data the Spring Boot API returns that the pages and client components render.
 * (Types only — the logic lives in backend/.)
 */
import type { Deductions, TaxResult } from "./tax/engine";
import type { DEFAULT_SETTINGS } from "./settings-defaults";

/** One line in the notification bell. */
export interface ShellNote {
  t: string;
  s: string;
  href?: string;
  /** set for a stored notification (system lines such as "3 requests waiting" have none) */
  id?: string | null;
  unread?: boolean;
  /** stays until the policy is accepted */
  sticky?: boolean;
}

/** One item in the notification box. */
export interface InboxItem {
  id: string;
  text: string;
  link: string | null;
  createdAt: string;
  readAt: string | null;
  sticky: boolean;
}

export interface InboxPage {
  items: InboxItem[];
  unread: number;
  total: number;
  page: number;
  pages: number;
}

/** Leave apply preview (holidays skipped, paid vs LOP split). */
export interface Preview {
  from: string;
  to: string;
  dates: string[];
  skipped: { date: string; name: string }[];
  days: number;
  available: number;
  paidDays: number;
  lopDays: number;
  balanceAfter: number | null;
  error: string | null;
}

/** One payroll row (paise). */
export interface PayView {
  employeeId: string; code: string; name: string; department: string; regime: "NEW" | "OLD";
  monthlyGross: number; lop: number; attendanceLop: number; paid: number; days: number; bonus: number; earned: number; pfPt: number; tds: number; other: number; net: number;
  edited: boolean; note: string | null;
}

/** The immutable payslip stored when a payroll month closes (paise). */
export interface PayslipSnapshot {
  version: 1;
  ym: string;
  company: { name: string; address: string };
  employee: { code: string; name: string; designation: string; department: string; doj: string; panLast4: string | null; uanLast4: string | null; bankName: string | null; bankLast4: string | null };
  regime: "NEW" | "OLD";
  payDate: string;
  attendance: { daysInMonth: number; eligibleDays: number; weeklyOffs: number; holidays: number; present: number; lopDays: number; paidDays: number };
  components: { label: string; rate: number; earned: number }[];
  bonus: number;
  grossEarned: number;
  pf: number;
  pt: number;
  tds: number;
  otherDeduction: number;
  deductions: { label: string; amount: number }[];
  totalDeductions: number;
  net: number;
  netWords: string;
  note: string | null;
}

/** An employee's projected tax for the financial year (rupees). */
export interface TaxProjection {
  fy: string;
  regime: "NEW" | "OLD";
  monthsLeft: number; // remaining incl. current
  jurisIncome: number; // ₹ actuals + this month + projection
  jurisLabel: string;
  prevIncome: number;
  prevTds: number;
  jurisTdsSoFar: number;
  income: number;
  deductions: Deductions;
  current: TaxResult;
  newRegime: TaxResult;
  oldRegime: TaxResult;
  monthly: number; // ₹ TDS this month
  pfAnnual: number; // ₹ employee PF projected for the FY (counts under 80C)
  regimeOverridden: boolean;
}

/** A policy setting as stored (defaults merged with saved values). */
export type SettingKey = keyof typeof DEFAULT_SETTINGS;
type Widen<T> = T extends readonly (infer U)[] ? Widen<U>[] : T extends object ? { -readonly [K in keyof T]: Widen<T[K]> } : T extends string ? string : T extends number ? number : T;
export type SettingValue<K extends SettingKey> = Widen<(typeof DEFAULT_SETTINGS)[K]>;

/** A business's subscription today (from the API). `daysLeft`: whole days until the end date (0 = ends today). */
export interface SubscriptionCurrent {
  state: "ACTIVE" | "UPCOMING" | "EXPIRED" | "NONE";
  active: boolean;
  planName: string | null;
  startsOn: string | null;
  endsOn: string | null;
  daysLeft: number | null;
  maxEmployees: number | null;
  modules: string[];
  employees: number;
}

/** One period of a business's subscription history (price in paise). */
export interface SubscriptionRow {
  id: string;
  planId: string;
  planName: string;
  startsOn: string;
  endsOn: string;
  pricePaise: number;
  status: "ACTIVE" | "CANCELLED";
  notes: string | null;
  createdAt: string;
}

/** A plan in the super admin's catalogue (price in paise; maxEmployees null = no limit). */
export interface PlanRow {
  id: string;
  name: string;
  description: string | null;
  pricePaise: number;
  periodMonths: number;
  maxEmployees: number | null;
  modules: string[];
  active: boolean;
  businesses: number;
}

export interface BusinessProfile {
  name: string;
  legalName: string | null;
  code: string;
  employeePrefix: string;
  gstin: string | null;
  address: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  timeZone: string;
}

/** A module granted to a business on its own (price in paise; endsOn null = no end). */
export interface ModuleGrant {
  id: string;
  module: string;
  source: "COMPLIMENTARY" | "PAID";
  pricePaise: number;
  startsOn: string;
  endsOn: string | null;
  notes: string | null;
  createdAt: string;
  revokedAt: string | null;
  state: "ACTIVE" | "UPCOMING" | "ENDED" | "REVOKED";
}

/** A published policy as an employee reads it (current version). */
export interface PolicyToRead {
  id: string;
  version: number;
  title: string;
  body: string;
  fileName: string | null;
  publishedAt: string;
  requiresAck?: boolean;
  acknowledgedAt?: string | null;
}

/** A policy as HR / the owner manage it: the draft, its published versions and who accepted the current one. */
export interface PolicyDetail {
  id: string; title: string; body: string; fileName: string | null; requiresAck: boolean; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; version: number;
  updatedAt: string; hasChanges: boolean;
  versions: { versionId: string; version: number; title: string; publishedAt: string; publishedBy: string | null; fileName: string | null; requiresAck: boolean;
    bodySha256: string; fileSha256: string | null; acknowledgements: number }[];
  people: { code: string; name: string; department: string | null; acknowledgedAt: string | null; lastVersion: number | null }[];
}

/** One asset of the register (GET /api/assets); holder* is set while it is assigned. */
export interface AssetRow {
  id: string; tag: string; type: string; make: string | null; model: string | null; serial: string | null; purchaseDate: string | null;
  status: "AVAILABLE" | "ASSIGNED" | "IN_REPAIR" | "RETIRED"; notes: string | null;
  holderId: string | null; holderCode: string | null; holderName: string | null; heldSince: string | null;
}

/** One time an asset was held by an employee (returnedOn null = still held). */
export interface AssetAssignment {
  id: string; assetId: string; tag: string; type: string; make: string | null; model: string | null; serial: string | null;
  employeeId: string; employeeCode: string; employeeName: string;
  assignedOn: string; assignNotes: string | null; assignedBy: string | null;
  returnedOn: string | null; returnCondition: "GOOD" | "FAIR" | "DAMAGED" | "LOST" | null; returnNotes: string | null; returnedBy: string | null;
}

export interface Announcement {
  id: string; title: string; body: string; audience: "ALL" | "IN" | "US"; pinned: boolean; expiresOn: string | null;
  status: "PUBLISHED" | "UNPUBLISHED"; author: string | null; createdAt: string; active: boolean;
}

export type LetterType = "OFFER" | "APPOINTMENT" | "EXPERIENCE" | "RELIEVING" | "EMPLOYMENT_VERIFICATION";

export interface LetterTemplateRow {
  id: string; type: LetterType; name: string; subject: string; needsSalary: boolean; active: boolean; version: number; updatedAt: string;
}

export interface LetterTemplateDetail extends LetterTemplateRow { body: string }

export interface LetterField { name: string; label: string; salary: boolean }

/** An issued letter (list rows have no text). */
export interface IssuedLetter {
  id: string; reference: string; type: LetterType; templateName: string; subject: string; employeeId: string; employeeCode: string; employeeName: string;
  issuedBy: string; issuedAt: string; needsSalary: boolean; canOpen: boolean;
}
