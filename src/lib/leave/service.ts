import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { attendancePolicy, currentWorkDate, isMonthLocked, type AttEmployee } from "../attendance/service";
import { db } from "../db";
import { dateToIso, isoToDate } from "../format";
import { lopSplit, requestDays, round2, workingDays, type Part } from "./rules";

type Tx = Prisma.TransactionClient;

export interface Balance { code: string; name: string; balance: number; pending: number; paid: boolean; needsBalance: boolean; annual: number; maxStretch: number | null }

/** Ledger balance per leave type, plus days held by pending requests. */
export async function balances(employeeId: string, tx: Tx = db): Promise<Balance[]> {
  const [types, sums, pending] = await Promise.all([
    tx.leaveType.findMany({ where: { active: true }, orderBy: { sortOrder: "asc" } }),
    tx.leaveLedger.groupBy({ by: ["leaveType"], where: { employeeId }, _sum: { delta: true } }),
    tx.leaveRequest.groupBy({ by: ["leaveType"], where: { employeeId, status: "PENDING" }, _sum: { days: true, lopDays: true } }),
  ]);
  return types.map((t) => {
    const p = pending.find((x) => x.leaveType === t.code);
    return {
      code: t.code, name: t.name, paid: t.paid, needsBalance: t.needsBalance, annual: Number(t.annualQuota), maxStretch: t.maxStretch ? Number(t.maxStretch) : null,
      balance: round2(Number(sums.find((s) => s.leaveType === t.code)?._sum.delta ?? 0)),
      pending: p ? round2(Number(p._sum.days ?? 0) - Number(p._sum.lopDays ?? 0)) : 0,
    };
  });
}

/** The employee's holidays (own region + everyone) between two dates. */
export async function holidayMap(region: "IN" | "US", from: string, to: string) {
  const rows = await db.holiday.findMany({ where: { date: { gte: isoToDate(from), lte: isoToDate(to) }, region: { in: ["ALL", region] } } });
  return new Map(rows.map((h) => [dateToIso(h.date), h.name]));
}

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

/**
 * Everything the apply form shows and the server enforces: working days, balance after, LOP if
 * the balance is short, plus the rules (no past dates, max stretch, overlaps, joining/exit).
 */
export async function previewRequest(emp: AttEmployee, typeCode: string, from: string, to: string, part: Part, opts: { excludeRequestId?: string; forDecision?: boolean } = {}): Promise<Preview> {
  if (to < from) [from, to] = [to, from];
  const policy = await attendancePolicy();
  const type = await db.leaveType.findUnique({ where: { code: typeCode } });
  const base: Preview = { from, to, dates: [], skipped: [], days: 0, available: 0, paidDays: 0, lopDays: 0, balanceAfter: null, error: null };
  if (!type || !type.active) return { ...base, error: "Pick a leave type." };
  const hol = await holidayMap(emp.region, from, to);
  const { dates, skipped } = workingDays(from, to, policy.weeklyOffs, hol);
  const days = requestDays(dates, dates.length === 1 ? part : "FULL");
  const bal = (await balances(emp.id)).find((b) => b.code === typeCode)!;
  // pending requests already hold part of the balance (except the one being decided)
  let held = bal.pending;
  if (opts.excludeRequestId) {
    const self = await db.leaveRequest.findUnique({ where: { id: opts.excludeRequestId } });
    if (self?.status === "PENDING") held = round2(held - (Number(self.days) - Number(self.lopDays)));
  }
  const available = round2(bal.balance - held);
  const { paidDays, lopDays } = lopSplit(days, available, type.needsBalance, type.paid);
  const out: Preview = { ...base, dates, skipped, days, available, paidDays, lopDays, balanceAfter: type.needsBalance ? round2(available - paidDays) : null };

  const today = currentWorkDate(emp);
  const err = (error: string) => ({ ...out, error });
  if (!opts.forDecision && from < today) return err("Leave can't start in the past. For a missed day, request a regularization.");
  if (from < emp.doj) return err("Leave can't start before your joining date.");
  if (emp.exit && to > emp.exit) return err("Leave can't go beyond your last working day.");
  if (daysBetweenInclusive(from, to) > 62) return err("Apply for at most two months at a time.");
  if (!dates.length) return err("Those dates are all weekends or holidays — nothing to apply for.");
  if (type.maxStretch && days > Number(type.maxStretch)) return err(`${type.name} can be taken for at most ${Number(type.maxStretch)} days at a stretch.`);
  if (isMonthLocked(from.slice(0, 7), policy)) return err("Attendance for that month is locked.");
  const overlap = await db.leaveRequest.findFirst({
    where: { employeeId: emp.id, status: { in: ["PENDING", "APPROVED"] }, fromDate: { lte: isoToDate(to) }, toDate: { gte: isoToDate(from) }, ...(opts.excludeRequestId ? { id: { not: opts.excludeRequestId } } : {}) },
  });
  if (overlap) return err(`You already have a ${overlap.status.toLowerCase()} request from ${dateToIso(overlap.fromDate)} to ${dateToIso(overlap.toDate)}.`);
  return out;
}

const daysBetweenInclusive = (a: string, b: string) => Math.round((Date.parse(b) - Date.parse(a)) / 86400_000) + 1;

/** Records leave used from the balance when a request is approved. */
export async function postUsage(tx: Tx, req: { id: string; employeeId: string; leaveType: string }, paidDays: number) {
  if (paidDays <= 0) return;
  const type = await tx.leaveType.findUniqueOrThrow({ where: { code: req.leaveType } });
  if (!type.needsBalance || !type.paid) return;
  await tx.leaveLedger.create({ data: { employeeId: req.employeeId, leaveType: req.leaveType, delta: -paidDays, reason: "USED", refId: req.id } });
}
