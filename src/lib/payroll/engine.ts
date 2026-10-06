/**
 * Payroll maths — pure functions, integer PAISE throughout (REQUIREMENTS §5.8).
 *
 *   earned_component = monthly_rate × paid_days ÷ days_in_month
 *   PF (employee)    = 12% × min(earned Basic, ₹15,000)
 *   net              = gross_earned − PF − PT − TDS − other deductions
 */

export interface Structure { basic: number; hra: number; lta: number; special: number } // paise / month

export const grossOf = (s: Structure) => s.basic + s.hra + s.lta + s.special;

export interface PayInput {
  structure: Structure;
  daysInMonth: number;
  eligibleDays: number; // max(DOJ, 1st) → min(exit, month end)
  lopDays: number; // may be fractional (0.5 steps)
  bonus: number; // paise
  otherDeduction: number; // paise
  tds: number; // paise
  pfRate: number; // 0.12
  pfWageCeiling: number; // paise (₹15,000 → 1_500_000)
  professionalTax: number; // paise per month (when anything is earned)
}

export interface PayLine { label: string; rate: number; earned: number }

export interface PayResult {
  daysInMonth: number;
  eligibleDays: number;
  lopDays: number;
  paidDays: number;
  components: PayLine[];
  bonus: number;
  grossEarned: number;
  pf: number;
  pt: number;
  tds: number;
  otherDeduction: number;
  totalDeductions: number;
  net: number;
}

export function computePay(i: PayInput): PayResult {
  const lop = Math.min(Math.max(0, i.lopDays), i.eligibleDays);
  const paidDays = Math.max(0, i.eligibleDays - lop);
  // earned amounts are rounded to whole rupees (100 paise), as on a printed payslip
  const f = (rate: number) => Math.round((rate * paidDays) / i.daysInMonth / 100) * 100;
  const components: PayLine[] = [
    { label: "Basic", rate: i.structure.basic, earned: f(i.structure.basic) },
    { label: "House rent allowance", rate: i.structure.hra, earned: f(i.structure.hra) },
    { label: "Leave travel allowance", rate: i.structure.lta, earned: f(i.structure.lta) },
    { label: "Special allowance", rate: i.structure.special, earned: f(i.structure.special) },
  ];
  const grossEarned = components.reduce((a, c) => a + c.earned, 0) + i.bonus;
  const pf = Math.round(i.pfRate * Math.min(components[0].earned, i.pfWageCeiling));
  const pt = grossEarned > 0 ? i.professionalTax : 0;
  const totalDeductions = pf + pt + i.tds + i.otherDeduction;
  return { daysInMonth: i.daysInMonth, eligibleDays: i.eligibleDays, lopDays: lop, paidDays, components, bonus: i.bonus, grossEarned, pf, pt, tds: i.tds, otherDeduction: i.otherDeduction, totalDeductions, net: grossEarned - totalDeductions };
}

/** Days in [max(doj, 1st), min(exit, month end)] — 0 if not employed that month. */
export function eligibleDays(ym: string, doj: string, exit: string | null): number {
  const [y, m] = ym.split("-").map(Number);
  const dim = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const first = `${ym}-01`, last = `${ym}-${String(dim).padStart(2, "0")}`;
  const from = doj > first ? doj : first;
  const to = exit && exit < last ? exit : last;
  if (from > to) return 0;
  return Math.round((Date.parse(to) - Date.parse(from)) / 86400_000) + 1;
}

export const daysInMonth = (ym: string) => new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5)), 0)).getUTCDate();

/** "Split" helper: Basic 50% · HRA 20% · LTA 5% · Special = the rest (paise, rupee-rounded). */
export function splitGross(target: number): Structure {
  const r = (x: number) => Math.round(x / 100) * 100;
  const basic = r(target * 0.5), hra = r(target * 0.2), lta = r(target * 0.05);
  return { basic, hra, lta, special: target - basic - hra - lta };
}

/** Rupees in words, Indian system (for the payslip). */
export function rupeesInWords(n: number): string {
  n = Math.round(n);
  if (!n) return "Zero";
  const a = ["", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"];
  const b = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"];
  const two = (x: number) => (x < 20 ? a[x] : b[Math.floor(x / 10)] + (x % 10 ? " " + a[x % 10] : ""));
  const three = (x: number) => (x >= 100 ? a[Math.floor(x / 100)] + " Hundred" + (x % 100 ? " " : "") : "") + (x % 100 ? two(x % 100) : "");
  const parts: string[] = [];
  const cr = Math.floor(n / 1e7), lk = Math.floor(n / 1e5) % 100, th = Math.floor(n / 1e3) % 100, rest = n % 1000;
  if (cr) parts.push((cr >= 100 ? three(cr) : two(cr)) + " Crore");
  if (lk) parts.push(two(lk) + " Lakh");
  if (th) parts.push(two(th) + " Thousand");
  if (rest) parts.push(three(rest));
  return parts.join(" ");
}
