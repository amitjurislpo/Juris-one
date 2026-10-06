/**
 * Leave maths — pure functions (REQUIREMENTS §5.6). Dates are ISO strings in the employee's zone.
 */
import { addDays, dowOf } from "../attendance/engine";

export type Part = "FULL" | "FIRST_HALF" | "SECOND_HALF";

/** Working days in [from, to]: weekly offs and the employee's holidays are skipped (never counted as leave). */
export function workingDays(from: string, to: string, weeklyOffs: number[], holidays: Map<string, string>) {
  const dates: string[] = [];
  const skipped: { date: string; name: string }[] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) {
    if (weeklyOffs.includes(dowOf(d))) continue;
    const h = holidays.get(d);
    if (h) { skipped.push({ date: d, name: h }); continue; }
    dates.push(d);
  }
  return { dates, skipped };
}

/** Days a request consumes: a half-day part only applies to a single-day request. */
export function requestDays(dates: string[], part: Part) {
  if (!dates.length) return 0;
  return dates.length === 1 && part !== "FULL" ? 0.5 : dates.length;
}

/** How much is paid from the balance and how much becomes loss of pay. */
export function lopSplit(days: number, available: number, needsBalance: boolean, paid: boolean) {
  if (!paid) return { paidDays: 0, lopDays: days };
  if (!needsBalance) return { paidDays: days, lopDays: 0 };
  const paidDays = Math.max(0, Math.min(days, Math.floor(Math.max(0, available) * 2) / 2));
  return { paidDays, lopDays: round2(days - paidDays) };
}

/**
 * Paid fraction of each date (1 = fully paid, 0 = LOP). Paid days are used up from the start
 * of the range, so any shortfall falls on the last days.
 */
export function paidFractions(dates: string[], part: Part, paidDays: number): Map<string, number> {
  const unit = dates.length === 1 && part !== "FULL" ? 0.5 : 1;
  const out = new Map<string, number>();
  let left = paidDays;
  for (const d of dates) {
    const take = Math.min(unit, Math.max(0, left));
    out.set(d, unit ? take / unit : 0);
    left -= take;
  }
  return out;
}

export const round2 = (n: number) => Math.round(n * 100) / 100;

/** Monthly accrual, pro-rata in the joining month (and zero before it). */
export function accrualFor(ym: string, dojIso: string, perMonth: number): number {
  const [y, m] = ym.split("-").map(Number);
  const dim = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const first = `${ym}-01`, last = `${ym}-${String(dim).padStart(2, "0")}`;
  if (dojIso > last) return 0;
  if (dojIso <= first) return perMonth;
  const daysLeft = dim - Number(dojIso.slice(8)) + 1;
  return round2((perMonth * daysLeft) / dim);
}

/** Comp-off for work on a holiday / weekly off: a full day's hours → 1, half-day hours → 0.5. */
export function compOffDays(workedMinutes: number, fullDayHours: number, halfDayHours: number) {
  if (workedMinutes >= fullDayHours * 60) return 1;
  if (workedMinutes >= halfDayHours * 60) return 0.5;
  return 0;
}

export interface Credit { id: string; amount: number; expiresOn: string; expired: number }

/**
 * First-in-first-out comp-off expiry: usage is taken from the oldest credits; whatever is left
 * of a credit past its expiry date (and not yet written off) expires now.
 */
export function compOffExpiries(credits: Credit[], used: number, today: string): { id: string; amount: number }[] {
  let toAllocate = used;
  const out: { id: string; amount: number }[] = [];
  for (const c of [...credits].sort((a, b) => a.expiresOn.localeCompare(b.expiresOn))) {
    const usable = c.amount - c.expired;
    const fromThis = Math.min(Math.max(0, toAllocate), usable);
    toAllocate -= fromThis;
    const remaining = round2(usable - fromThis);
    if (c.expiresOn < today && remaining > 0) out.push({ id: c.id, amount: remaining });
  }
  return out;
}

/** Year-end: casual leave lapses entirely; earned leave carries forward up to the cap. */
export function yearEndLapse(balance: number, carryForwardCap: number): number {
  return round2(Math.max(0, balance - carryForwardCap));
}
