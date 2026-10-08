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
