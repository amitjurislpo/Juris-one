/**
 * Income-tax engine — pure functions (REQUIREMENTS §5.9). Amounts are RUPEES here (slabs, caps
 * and limits are stored in rupees per financial year); callers convert to/from paise.
 * Tax rules are data: slabs, rebate, cess, surcharge and section caps come from `tax_years`
 * and `tax_sections`.
 */

export interface Slab { upto: number | null; rate: number }
export interface Surcharge { above: number; rate: number }

export interface TaxYearConfig {
  regime: "NEW" | "OLD";
  stdDeduction: number;
  rebateLimit: number;
  marginalRelief: boolean; // relief just above the rebate limit (new regime)
  cessRate: number;
  slabs: Slab[];
  surcharge: Surcharge[];
}

export interface SlabLine { from: number; to: number | null; rate: number; amount: number }

export interface TaxResult {
  regime: "NEW" | "OLD";
  gross: number; // income before deductions
  std: number;
  deductions: number; // old-regime chapter VI-A etc.
  taxable: number;
  slabTax: number;
  rebate: number;
  base: number; // after rebate / marginal relief
  surcharge: number;
  cess: number;
  total: number; // annual tax liability, rounded to the rupee
  lines: SlabLine[];
}

export function slabTax(taxable: number, slabs: Slab[]): { tax: number; lines: SlabLine[] } {
  let prev = 0, tax = 0;
  const lines: SlabLine[] = [];
  for (const s of slabs) {
    const top = s.upto ?? Infinity;
    const amt = taxable > prev ? (Math.min(taxable, top) - prev) * s.rate : 0;
    lines.push({ from: prev, to: s.upto, rate: s.rate, amount: amt });
    tax += amt;
    if (taxable <= top) break;
    prev = top;
  }
  return { tax, lines };
}

function surchargeOn(taxable: number, base: number, c: TaxYearConfig): number {
  const tiers = [...c.surcharge].sort((a, b) => a.above - b.above).filter((t) => taxable > t.above);
  if (!tiers.length) return 0;
  const tier = tiers[tiers.length - 1];
  let s = base * tier.rate;
  // marginal relief: tax + surcharge may not exceed tax at the threshold + income above it
  const atT = slabTax(tier.above, c.slabs).tax;
  const prevRate = tiers.length > 1 ? tiers[tiers.length - 2].rate : 0;
  const capAtThreshold = atT * (1 + prevRate) + (taxable - tier.above);
  if (base + s > capAtThreshold) s = Math.max(0, capAtThreshold - base);
  return s;
}

/** Annual tax on `gross` income after the standard deduction and `deductions`. */
export function computeTax(gross: number, deductions: number, c: TaxYearConfig): TaxResult {
  const std = Math.min(c.stdDeduction, Math.max(0, gross));
  const taxable = Math.max(0, Math.round(gross - std - deductions));
  const { tax, lines } = slabTax(taxable, c.slabs);
  let base = tax;
  let rebate = 0;
  if (taxable <= c.rebateLimit) {
    rebate = tax;
    base = 0;
  } else if (c.marginalRelief && tax > taxable - c.rebateLimit) {
    rebate = tax - (taxable - c.rebateLimit);
    base = taxable - c.rebateLimit;
  }
  const surcharge = surchargeOn(taxable, base, c);
  const cess = (base + surcharge) * c.cessRate;
  return { regime: c.regime, gross, std, deductions, taxable, slabTax: tax, rebate, base, surcharge, cess, total: Math.round(base + surcharge + cess), lines };
}

// ───────────────────────── old-regime deductions ─────────────────────────

export interface Caps { c80C: number; c80CCD1B: number; c80DSelf: number; c80DParents: number; c80DParentsSenior: number; c24B: number; c80TTA: number }

export interface Declared {
  rent: number; metro: boolean; parentsSenior: boolean;
  ppf: number; elss: number; lic: number; ssy: number; tuition: number; hlp: number; nsc: number;
  nps: number; medSelf: number; medParents: number; hli: number; edu: number; don: number; sav: number;
}

export const NO_DECLARATIONS: Declared = { rent: 0, metro: false, parentsSenior: false, ppf: 0, elss: 0, lic: 0, ssy: 0, tuition: 0, hlp: 0, nsc: 0, nps: 0, medSelf: 0, medParents: 0, hli: 0, edu: 0, don: 0, sav: 0 };

export interface Deductions { total: number; lines: [string, number][]; hraExemption: number; c80C: number }

/** HRA exemption = least of (HRA received, rent − 10% of Basic, 50% metro / 40% of Basic); caps per section. */
export function oldRegimeDeductions(basicAnnual: number, hraAnnual: number, epfAnnual: number, d: Declared, caps: Caps, labels: Record<string, string> = {}): Deductions {
  const L = (k: string, dflt: string) => labels[k] ?? dflt;
  const hra = d.rent ? Math.max(0, Math.min(hraAnnual, d.rent - 0.1 * basicAnnual, (d.metro ? 0.5 : 0.4) * basicAnnual)) : 0;
  const c80C = Math.min(caps.c80C, epfAnnual + d.ppf + d.elss + d.lic + d.ssy + d.tuition + d.hlp + d.nsc);
  const nps = Math.min(caps.c80CCD1B, d.nps);
  const med = Math.min(caps.c80DSelf, d.medSelf) + Math.min(d.parentsSenior ? caps.c80DParentsSenior : caps.c80DParents, d.medParents);
  const hli = Math.min(caps.c24B, d.hli);
  const sav = Math.min(caps.c80TTA, d.sav);
  const lines: [string, number][] = [
    [`${L("HRA", "HRA")} exemption`, hra],
    [`${L("80C", "80C")} (incl. PF)`, c80C],
    [`${L("80CCD1B", "80CCD(1B)")} NPS`, nps],
    [`${L("80D_SELF", "80D")} medical`, med],
    [`${L("24B", "24(b)")} home loan interest`, hli],
    [`${L("80E", "80E")} education loan`, d.edu],
    [`${L("80G", "80G")} donations`, d.don],
    [`${L("80TTA", "80TTA")} savings interest`, sav],
  ].filter((x) => (x[1] as number) > 0) as [string, number][];
  return { total: Math.round(lines.reduce((a, l) => a + l[1], 0)), lines: lines.map(([k, v]) => [k, Math.round(v)]), hraExemption: Math.round(hra), c80C: Math.round(c80C) };
}

// ───────────────────────── monthly TDS ─────────────────────────

/** FY months from April: "2026-09" → index 5; remaining months including the current one. */
export function fyOf(ym: string): { fy: string; index: number; remainingInclCurrent: number } {
  const [y, m] = ym.split("-").map(Number);
  const start = m >= 4 ? y : y - 1;
  const index = (m - 4 + 12) % 12;
  return { fy: `${start}-${String((start + 1) % 100).padStart(2, "0")}`, index, remainingInclCurrent: 12 - index };
}

/**
 * Monthly TDS = (annual tax − TDS already deducted this FY, incl. previous employer) ÷ remaining
 * months including the current one. Never negative.
 */
export function monthlyTds(annualTax: number, alreadyDeducted: number, remainingInclCurrent: number): number {
  if (remainingInclCurrent <= 0) return 0;
  return Math.max(0, Math.round((annualTax - alreadyDeducted) / remainingInclCurrent));
}
