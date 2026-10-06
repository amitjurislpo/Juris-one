import { describe, expect, it } from "vitest";
import { computePay, eligibleDays, rupeesInWords, splitGross, type Structure } from "@/lib/payroll/engine";
import { computeTax, fyOf, monthlyTds, NO_DECLARATIONS, oldRegimeDeductions, type TaxYearConfig } from "@/lib/tax/engine";

const NEW: TaxYearConfig = {
  regime: "NEW", stdDeduction: 75000, rebateLimit: 1200000, marginalRelief: true, cessRate: 0.04,
  slabs: [{ upto: 400000, rate: 0 }, { upto: 800000, rate: 0.05 }, { upto: 1200000, rate: 0.1 }, { upto: 1600000, rate: 0.15 }, { upto: 2000000, rate: 0.2 }, { upto: 2400000, rate: 0.25 }, { upto: null, rate: 0.3 }],
  surcharge: [{ above: 5000000, rate: 0.1 }, { above: 10000000, rate: 0.15 }, { above: 20000000, rate: 0.25 }],
};
const OLD: TaxYearConfig = {
  regime: "OLD", stdDeduction: 50000, rebateLimit: 500000, marginalRelief: false, cessRate: 0.04,
  slabs: [{ upto: 250000, rate: 0 }, { upto: 500000, rate: 0.05 }, { upto: 1000000, rate: 0.2 }, { upto: null, rate: 0.3 }],
  surcharge: [{ above: 5000000, rate: 0.1 }, { above: 10000000, rate: 0.15 }, { above: 20000000, rate: 0.25 }, { above: 50000000, rate: 0.37 }],
};
const CAPS = { c80C: 150000, c80CCD1B: 50000, c80DSelf: 25000, c80DParents: 25000, c80DParentsSenior: 50000, c24B: 200000, c80TTA: 10000 };
const JC074: Structure = { basic: 7_500_000, hra: 3_000_000, lta: 750_000, special: 3_750_000 }; // ₹1,50,000 / month

describe("golden case — JC-074 Amit Jaspal, September 2026 (REQUIREMENTS §7)", () => {
  it("joined 8 Sep with 1 LOP → 22 of 30 paid days", () => {
    const elig = eligibleDays("2026-09", "2026-09-08", null);
    expect(elig).toBe(23);
    const p = computePay({ structure: JC074, daysInMonth: 30, eligibleDays: elig, lopDays: 1, bonus: 0, otherDeduction: 0, tds: 0, pfRate: 0.12, pfWageCeiling: 1_500_000, professionalTax: 20_000 });
    expect(p.paidDays).toBe(22);
    expect(p.grossEarned).toBe(11_000_000); // ₹1,10,000
    expect(p.pf).toBe(180_000); // 12% of ₹15,000
  });
  it("monthly TDS ≈ ₹14,926 on the new regime with ₹7.5L / ₹38,000 from the previous employer", () => {
    const { remainingInclCurrent } = fyOf("2026-09");
    expect(remainingInclCurrent).toBe(7);
    const juris = 110000 + 150000 * 6; // Sep actual + Oct–Mar projected
    const t = computeTax(juris + 750000, 0, NEW);
    expect(t.taxable).toBe(1685000);
    expect(t.total).toBe(142480);
    expect(monthlyTds(t.total, 38000, remainingInclCurrent)).toBe(14926);
  });
});

describe("tax engine", () => {
  it("new regime: full rebate up to ₹12L taxable, marginal relief just above", () => {
    expect(computeTax(1275000, 0, NEW).total).toBe(0); // 12L taxable
    const just = computeTax(1285000, 0, NEW); // 12.1L taxable
    expect(just.base).toBe(10000); // relief caps tax at income above 12L
    expect(computeTax(1675000, 0, NEW).total).toBe(Math.round(120000 * 1.04));
  });
  it("old regime: rebate up to ₹5L, slabs above", () => {
    expect(computeTax(550000, 0, OLD).total).toBe(0);
    expect(computeTax(1050000, 0, OLD).total).toBe(Math.round((12500 + 100000) * 1.04));
  });
  it("surcharge above ₹50L with marginal relief", () => {
    const t = computeTax(5_085_000, 0, NEW); // 50.1L taxable
    const at50 = computeTax(5_075_000, 0, NEW);
    expect(t.base + t.surcharge).toBeLessThanOrEqual(at50.base + 10000 + 1);
  });
  it("old-regime deductions: HRA least-of, 80C cap incl. PF, 80D parents", () => {
    const d = oldRegimeDeductions(432000, 172800, 51840, { ...NO_DECLARATIONS, rent: 180000, metro: true, ppf: 80000, lic: 25000, medSelf: 18000 }, CAPS);
    expect(d.hraExemption).toBe(Math.round(Math.min(172800, 180000 - 43200, 216000)));
    expect(d.c80C).toBe(150000);
    expect(d.total).toBe(136800 + 150000 + 18000);
  });
  it("monthly TDS is never negative", () => expect(monthlyTds(10000, 50000, 3)).toBe(0));
});

describe("payroll helpers", () => {
  it("split: Basic 50 · HRA 20 · LTA 5 · Special rest", () => {
    expect(splitGross(15_000_000)).toEqual(JC074);
  });
  it("exit mid-month limits eligible days", () => {
    expect(eligibleDays("2026-08", "2023-02-01", "2026-08-31")).toBe(31);
    expect(eligibleDays("2026-09", "2023-02-01", "2026-08-31")).toBe(0);
    expect(eligibleDays("2026-08", "2023-02-01", "2026-08-15")).toBe(15);
  });
  it("net pay in words (Indian system)", () => {
    expect(rupeesInWords(105274)).toBe("One Lakh Five Thousand Two Hundred Seventy Four");
    expect(rupeesInWords(12000000)).toBe("One Crore Twenty Lakh");
  });
});
