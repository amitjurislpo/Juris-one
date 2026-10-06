import "server-only";
import { addDays, localParts } from "./attendance/engine";
import { attendancePolicy, loadAttEmployees, lockInstant, materialize } from "./attendance/service";
import { audit } from "./audit";
import { db } from "./db";
import { dateToIso } from "./format";
import { derivedStatus } from "./people-rules";
import { getSetting } from "./settings";

const IST = "Asia/Kolkata";
const monthEnd = (ym: string) => new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5, 7)), 0)).toISOString().slice(0, 10);
const nextYm = (ym: string) => new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5, 7)), 1)).toISOString().slice(0, 7);

/**
 * Daily absence marking: persists finished days (shift end + buffer passed) for the last week,
 * so a working day with no check-in and no approved leave is recorded as Absent (LOP).
 */
export async function jobFinalizeDays(now = new Date()) {
  const p = await attendancePolicy();
  const emps = await loadAttEmployees({ OR: [{ status: { not: "EXITED" } }, { exitDate: { gte: new Date(now.getTime() - 10 * 86400_000) } }] });
  const today = localParts(now, IST).date;
  const from = [addDays(today, -7), p.trackingStart].sort().at(-1)!;
  return { written: await materialize(emps, from, today, { now }) };
}

/** Locks each month at the configured time (default: 1st of next month, 10:00 IST). Idempotent. */
export async function jobLockMonths(now = new Date()) {
  const p = await attendancePolicy();
  const locked: string[] = [];
  for (let ym = p.trackingStart.slice(0, 7); lockInstant(ym, p) <= now; ym = nextYm(ym)) {
    const done = await db.jobRun.findUnique({ where: { name_key: { name: "attendance.lock", key: ym } } });
    if (done) continue;
    const from = `${ym}-01` < p.trackingStart ? p.trackingStart : `${ym}-01`;
    const to = monthEnd(ym);
    const emps = await loadAttEmployees({ dateOfJoining: { lte: new Date(`${to}T00:00:00Z`) }, OR: [{ exitDate: null }, { exitDate: { gte: new Date(`${from}T00:00:00Z`) } }] });
    const written = await materialize(emps, from, to, { lock: true, now });
    await db.jobRun.create({ data: { name: "attendance.lock", key: ym, detail: { employees: emps.length, rows: written } } });
    await audit({ actorId: null, action: "attendance.month_locked", entity: "attendance", entityId: ym, after: { employees: emps.length, rows: written } });
    locked.push(ym);
  }
  return { locked };
}

/** Onboarding → Probation on the joining date → Active after the probation period. */
export async function jobEmployeeStatus(now = new Date()) {
  const { probationMonths } = await getSetting("employee.policy");
  const today = localParts(now, IST).date;
  const emps = await db.employee.findMany({ where: { status: { in: ["ONBOARDING", "PROBATION"] } }, select: { id: true, status: true, dateOfJoining: true } });
  let changed = 0;
  for (const e of emps) {
    const next = derivedStatus(e.status, dateToIso(e.dateOfJoining), today, probationMonths);
    if (next === e.status) continue;
    await db.employee.update({ where: { id: e.id }, data: { status: next } });
    await audit({ actorId: null, action: "employee.status_auto", entity: "employee", entityId: e.id, before: { status: e.status }, after: { status: next } });
    changed++;
  }
  return { changed };
}

/** Runs every job once. A Postgres advisory lock keeps two app instances from overlapping. */
export async function runJobs(now = new Date()) {
  const [{ ok }] = await db.$queryRaw<{ ok: boolean }[]>`SELECT pg_try_advisory_lock(74202) AS ok`;
  if (!ok) return { skipped: true };
  try {
    const status = await jobEmployeeStatus(now);
    const lock = await jobLockMonths(now);
    const finalize = await jobFinalizeDays(now);
    return { status, lock, finalize };
  } finally {
    await db.$queryRaw`SELECT pg_advisory_unlock(74202)`;
  }
}
