import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { db } from "../db";
import { dateToIso, isoToDate } from "../format";
import { getSetting } from "../settings";
import { addDays, atLocal, computeDay, workDateOf, type DayResult, type Policy, type Punch, type ShiftDef, type Status } from "./engine";
import { paidFractions, workingDays } from "../leave/rules";

/** Employees whose attendance is tracked: their role has `self` (Admin has none). */
export const TRACKED: Prisma.EmployeeWhereInput = { role: { permissions: { some: { permissionKey: "self" } } } };

export interface AttEmployee {
  id: string;
  code: string;
  name: string;
  designation: string;
  department: string;
  departmentId: string | null;
  tz: string;
  region: "IN" | "US";
  doj: string;
  exit: string | null;
  shift: ShiftDef & { code: string; name: string };
}

const DEFAULT_SHIFT = { code: "IN_DAY", name: "India Day", startLocal: "09:30", endLocal: "18:30", graceMinutes: 15 };

export async function loadAttEmployees(where: Prisma.EmployeeWhereInput): Promise<AttEmployee[]> {
  const rows = await db.employee.findMany({
    where: { AND: [TRACKED, where] },
    include: { shift: true, department: { select: { name: true } } },
    orderBy: { fullName: "asc" },
  });
  return rows.map((e) => ({
    id: e.id, code: e.code, name: e.fullName, designation: e.designation, department: e.department?.name ?? "—", departmentId: e.departmentId,
    tz: e.timeZone, region: e.region, doj: dateToIso(e.dateOfJoining), exit: e.exitDate ? dateToIso(e.exitDate) : null,
    shift: e.shift ? { code: e.shift.code, name: e.shift.name, startLocal: e.shift.startLocal, endLocal: e.shift.endLocal, graceMinutes: e.shift.graceMinutes } : DEFAULT_SHIFT,
  }));
}

export async function attendancePolicy(): Promise<Policy & { trackingStart: string; shiftTargetHours: number; lockDayOfNextMonth: number; lockTime: string }> {
  const p = await getSetting("attendance.policy");
  return { ...p, trackingStart: p.trackingStartDate };
}

/** When a month's attendance locks: the configured day of the next month at the lock time, IST. */
export function lockInstant(ym: string, p: { lockDayOfNextMonth: number; lockTime: string }): Date {
  const [y, m] = ym.split("-").map(Number);
  const next = new Date(Date.UTC(y, m, p.lockDayOfNextMonth)).toISOString().slice(0, 10);
  return atLocal(next, p.lockTime, "Asia/Kolkata");
}

export const isMonthLocked = (ym: string, p: { lockDayOfNextMonth: number; lockTime: string }, now = new Date()) => now >= lockInstant(ym, p);

export interface DayView extends DayResult {
  punches: (Punch & { source: string })[];
}

/**
 * Day-by-day attendance for each employee over [from, to] (inclusive, work dates).
 * Locked days come from `attendance_days` as frozen; everything else is computed live.
 */
export async function attendanceFor(emps: AttEmployee[], from: string, to: string, now = new Date()): Promise<Map<string, DayView[]>> {
  const out = new Map<string, DayView[]>();
  if (!emps.length || from > to) return out;
  const ids = emps.map((e) => e.id);
  const policy = await attendancePolicy();

  const [leaves, regs, punches, locked] = await Promise.all([
    db.leaveRequest.findMany({ where: { employeeId: { in: ids }, status: "APPROVED", fromDate: { lte: isoToDate(to) }, toDate: { gte: isoToDate(from) } }, include: { type: true } }),
    db.regularizationRequest.findMany({ where: { employeeId: { in: ids }, status: "APPROVED", workDate: { gte: isoToDate(from), lte: isoToDate(to) } } }),
    db.punch.findMany({ where: { employeeId: { in: ids }, at: { gte: new Date(Date.parse(`${from}T00:00:00Z`) - 36 * 3600_000), lt: new Date(Date.parse(`${to}T00:00:00Z`) + 60 * 3600_000) } }, orderBy: { at: "asc" } }),
    db.attendanceDay.findMany({ where: { employeeId: { in: ids }, locked: true, workDate: { gte: isoToDate(from), lte: isoToDate(to) } } }),
  ]);
  // Holidays across every leave range too: a leave's paid days are allocated from its first working day.
  const hFrom = [from, ...leaves.map((l) => dateToIso(l.fromDate))].sort()[0];
  const hTo = [to, ...leaves.map((l) => dateToIso(l.toDate))].sort().at(-1)!;
  const holidays = await db.holiday.findMany({ where: { date: { gte: isoToDate(hFrom), lte: isoToDate(hTo) } } });

  const holidayFor = (date: string, region: "IN" | "US") => holidays.find((h) => dateToIso(h.date) === date && (h.region === "ALL" || h.region === region))?.name ?? null;
  const byEmp = new Map<string, Map<string, DayView["punches"]>>();
  for (const e of emps) byEmp.set(e.id, new Map());
  const empById = new Map(emps.map((e) => [e.id, e]));
  for (const p of punches) {
    const e = empById.get(p.employeeId)!;
    const wd = workDateOf(p.at, e.shift, e.tz);
    const m = byEmp.get(e.id)!;
    if (!m.has(wd)) m.set(wd, []);
    m.get(wd)!.push({ at: p.at, kind: p.kind, source: p.source });
  }

  const fractions = new Map<string, Map<string, number>>(); // leave id → date → paid fraction
  for (const l of leaves) {
    const e = empById.get(l.employeeId)!;
    const hol = new Map<string, string>();
    for (const h of holidays) if (h.region === "ALL" || h.region === e.region) hol.set(dateToIso(h.date), h.name);
    const { dates } = workingDays(dateToIso(l.fromDate), dateToIso(l.toDate), policy.weeklyOffs, hol);
    const paidDays = l.type.paid ? Number(l.days) - Number(l.lopDays) : 0;
    fractions.set(l.id, paidFractions(dates, l.part, paidDays));
  }

  for (const e of emps) {
    const days: DayView[] = [];
    for (let d = from; d <= to; d = addDays(d, 1)) {
      const lockedRow = locked.find((r) => r.employeeId === e.id && dateToIso(r.workDate) === d);
      const ps = byEmp.get(e.id)!.get(d) ?? [];
      if (lockedRow) {
        days.push({
          date: d, status: lockedRow.status as Status, label: lockedRow.label ?? "", firstIn: lockedRow.firstIn, lastOut: lockedRow.lastOut, workedMinutes: lockedRow.workedMinutes,
          openSince: null, late: lockedRow.late && !lockedRow.lateWaived, lateWaived: lockedRow.lateWaived, missedCheckout: lockedRow.missedCheckout, regularized: lockedRow.regularized,
          lopDays: Number(lockedRow.lopDays), leaveDays: Number(lockedRow.leaveDays), live: false, future: false, offDayWork: false, punches: ps,
        });
        continue;
      }
      const lv = leaves.find((l) => l.employeeId === e.id && dateToIso(l.fromDate) <= d && dateToIso(l.toDate) >= d);
      const rs = regs.filter((r) => r.employeeId === e.id && dateToIso(r.workDate) === d);
      const r = computeDay({
        date: d, now, tz: e.tz, shift: e.shift, policy, doj: e.doj, exitDate: e.exit, trackingStart: policy.trackingStart,
        holiday: holidayFor(d, e.region),
        leave: lv ? { code: lv.leaveType, name: lv.type.name, part: lv.part, paidFraction: fractions.get(lv.id)?.get(d) ?? (lv.type.paid ? 1 : 0) } : null,
        punches: ps,
        reg: { absent: rs.some((x) => x.kind === "ABSENT" || x.kind === "MISSED_CHECKOUT"), lateWaived: rs.some((x) => x.kind === "LATE_CHECKIN") },
      });
      days.push({ ...r, punches: ps });
    }
    out.set(e.id, days);
  }
  return out;
}

/** Persist computed days (finished days only unless locking). Locked rows are never rewritten. */
export async function materialize(emps: AttEmployee[], from: string, to: string, opts: { lock?: boolean; now?: Date } = {}) {
  const now = opts.now ?? new Date();
  const all = await attendanceFor(emps, from, to, now);
  const lockedRows = await db.attendanceDay.findMany({ where: { employeeId: { in: emps.map((e) => e.id) }, locked: true, workDate: { gte: isoToDate(from), lte: isoToDate(to) } }, select: { employeeId: true, workDate: true } });
  const isLocked = new Set(lockedRows.map((r) => `${r.employeeId}|${dateToIso(r.workDate)}`));
  let written = 0;
  for (const [employeeId, days] of all) {
    for (const d of days) {
      if (d.future || (d.live && !opts.lock)) continue;
      const data = {
        status: d.status, label: d.label, firstIn: d.firstIn, lastOut: d.lastOut, workedMinutes: d.workedMinutes, late: d.late || d.lateWaived,
        lateWaived: d.lateWaived, regularized: d.regularized, missedCheckout: d.missedCheckout, lopDays: d.lopDays, leaveDays: d.leaveDays, locked: !!opts.lock,
      };
      if (isLocked.has(`${employeeId}|${d.date}`)) continue;
      const key = { employeeId_workDate: { employeeId, workDate: isoToDate(d.date) } };
      await db.attendanceDay.upsert({ where: key, create: { employeeId, workDate: isoToDate(d.date), ...data }, update: data });
      written++;
    }
  }
  return written;
}

/** The work date an employee is "on" right now (night shift at 2 AM → yesterday). */
export const currentWorkDate = (e: AttEmployee, now = new Date()) => workDateOf(now, e.shift, e.tz);

export const fmtTime = (at: Date | null, tz: string) => (at ? new Intl.DateTimeFormat("en-US", { timeZone: tz, hour: "numeric", minute: "2-digit" }).format(at) : "—");
