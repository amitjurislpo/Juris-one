import "server-only";
import { db } from "../db";
import { computeTax, fyOf, monthlyTds, NO_DECLARATIONS, oldRegimeDeductions, type Caps, type Declared, type Deductions, type TaxResult, type TaxYearConfig } from "./engine";
import { daysInMonth, eligibleDays } from "../payroll/engine";

const r = (paise: number | bigint) => Number(paise) / 100; // paise → rupees

export async function taxConfig(fy: string) {
  const [years, sections] = await Promise.all([db.taxYear.findMany({ where: { fy } }), db.taxSection.findMany({ where: { fy } })]);
  if (years.length < 2) throw new Error(`Tax tables for FY ${fy} are missing — add them under tax_years.`);
  const cfg = (regime: "NEW" | "OLD"): TaxYearConfig => {
    const y = years.find((x) => x.regime === regime)!;
    return { regime, stdDeduction: y.stdDeduction, rebateLimit: y.rebateLimit, marginalRelief: y.marginalRelief, cessRate: Number(y.cessRate), slabs: y.slabs as unknown as TaxYearConfig["slabs"], surcharge: y.surcharge as unknown as TaxYearConfig["surcharge"] };
  };
  const cap = (k: string, d: number) => sections.find((s) => s.key === k)?.cap ?? d;
  const caps: Caps = { c80C: cap("80C", 150000), c80CCD1B: cap("80CCD1B", 50000), c80DSelf: cap("80D_SELF", 25000), c80DParents: cap("80D_PARENTS", 25000), c80DParentsSenior: cap("80D_PARENTS_SENIOR", 50000), c24B: cap("24B", 200000), c80TTA: cap("80TTA", 10000) };
  const labels = Object.fromEntries(sections.map((s) => [s.key, s.label]));
  return { NEW: cfg("NEW"), OLD: cfg("OLD"), caps, labels };
}

/** Declared amounts (₹) — approved only, unless `includePending` (for "if everything is approved"). */
export async function declarations(employeeId: string, fy: string, includePending = false): Promise<Declared & { pendingCount: number }> {
  const [items, profile] = await Promise.all([
    db.declarationItem.findMany({ where: { employeeId, fy } }),
    db.taxProfile.findUnique({ where: { employeeId_fy: { employeeId, fy } } }),
  ]);
  const d: Declared = { ...NO_DECLARATIONS, metro: profile?.metro ?? false };
  for (const it of items) {
    const ok = it.status === "APPROVED" || (includePending && it.status === "PENDING");
    if (!ok) continue;
    if (it.itemKey === "parentsSenior") { d.parentsSenior = Number(it.amount) > 0; continue; }
    if (it.itemKey in d && it.itemKey !== "metro") (d as unknown as Record<string, number>)[it.itemKey] = r(it.amount);
  }
  return { ...d, pendingCount: items.filter((i) => i.status === "PENDING").length };
}

export interface MonthFigures { gross: number; basic: number; hra: number; pf: number } // paise, this month

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
  regimeOverridden: boolean;
}

/**
 * Annual taxable income = actuals so far (closed payslips this FY) + this month + current
 * structure × remaining months + previous employer income − standard deduction − (old) deductions.
 * Monthly TDS = (annual tax − TDS already deducted this FY) ÷ remaining months incl. this one.
 */
export async function projectTax(employeeId: string, ym: string, month: MonthFigures, structureGross: number, structureBasic: number, structureHra: number, opts: { includePending?: boolean; regime?: "NEW" | "OLD" } = {}): Promise<TaxProjection> {
  const { fy, index, remainingInclCurrent } = fyOf(ym);
  const fyStartYm = `${fy.slice(0, 4)}-04`;
  const [cfg, profile, prior, dec, emp, structures] = await Promise.all([
    taxConfig(fy),
    db.taxProfile.findUnique({ where: { employeeId_fy: { employeeId, fy } } }),
    db.payslip.findMany({ where: { employeeId, run: { status: "CLOSED", month: { gte: fyStartYm, lt: ym } } }, select: { snapshot: true, run: { select: { month: true } } } }),
    declarations(employeeId, fy, opts.includePending),
    db.employee.findUniqueOrThrow({ where: { id: employeeId }, select: { dateOfJoining: true, exitDate: true } }),
    db.salaryStructure.findMany({ where: { employeeId }, orderBy: { effectiveFrom: "asc" } }),
  ]);
  type Snap = { grossEarned: number; tds: number; pf: number; components: { label: string; earned: number }[] };
  const snaps = prior.map((p) => p.snapshot as unknown as Snap);
  const sum = (f: (s: Snap) => number) => snaps.reduce((a, s) => a + f(s), 0);

  // Earlier FY months without a closed payslip (e.g. before go-live) are estimated from the
  // structure then in force, pro-rata to the days employed.
  const closedMonths = new Set(prior.map((p) => p.run.month));
  const doj = emp.dateOfJoining.toISOString().slice(0, 10), exit = emp.exitDate?.toISOString().slice(0, 10) ?? null;
  let est = { gross: 0, basic: 0, hra: 0, pf: 0 };
  for (let k = 0; k < index; k++) {
    const m = new Date(Date.UTC(Number(fyStartYm.slice(0, 4)), 3 + k, 1)).toISOString().slice(0, 7);
    if (closedMonths.has(m)) continue;
    const D = daysInMonth(m), elig = eligibleDays(m, doj, exit);
    const st = [...structures].reverse().find((s) => s.effectiveFrom.toISOString().slice(0, 10) <= `${m}-${String(D).padStart(2, "0")}`);
    if (!elig || !st) continue;
    const f = elig / D;
    const basic = Math.round(st.basic * f);
    est = { gross: est.gross + Math.round((st.basic + st.hra + st.lta + st.special) * f), basic: est.basic + basic, hra: est.hra + Math.round(st.hra * f), pf: est.pf + Math.round(0.12 * Math.min(basic, 1_500_000)) };
  }

  const after = remainingInclCurrent - 1;
  const grossAnnual = sum((s) => s.grossEarned) + est.gross + month.gross + structureGross * after;
  const basicAnnual = sum((s) => s.components.find((c) => c.label === "Basic")?.earned ?? 0) + est.basic + month.basic + structureBasic * after;
  const hraAnnual = sum((s) => s.components.find((c) => c.label === "House rent allowance")?.earned ?? 0) + est.hra + month.hra + structureHra * after;
  const pfAnnual = sum((s) => s.pf) + est.pf + month.pf + Math.round(0.12 * Math.min(structureBasic, 1_500_000)) * after;
  const jurisTdsSoFar = sum((s) => s.tds);

  const prevIncome = r(profile?.prevEmployerIncome ?? 0);
  const prevTds = r(profile?.prevEmployerTds ?? 0);
  const income = r(grossAnnual) + prevIncome;
  const deductions = oldRegimeDeductions(r(basicAnnual), r(hraAnnual), r(pfAnnual), dec, cfg.caps, cfg.labels);
  const newRegime = computeTax(income, 0, cfg.NEW);
  const oldRegime = computeTax(income, deductions.total, cfg.OLD);
  const regime = opts.regime ?? profile?.regime ?? "NEW";
  const current = regime === "OLD" ? oldRegime : newRegime;
  const monthly = monthlyTds(current.total, prevTds + r(jurisTdsSoFar), remainingInclCurrent);
  return {
    fy, regime, monthsLeft: remainingInclCurrent, jurisIncome: r(grossAnnual),
    jurisLabel: `${snaps.length ? "closed payslips + " : ""}${est.gross ? "estimated earlier months + " : ""}this month + ${after} month${after === 1 ? "" : "s"} projected`,
    prevIncome, prevTds, jurisTdsSoFar: r(jurisTdsSoFar), income, deductions, current, newRegime, oldRegime, monthly, regimeOverridden: !!profile?.regimeOverridden,
  };
}
