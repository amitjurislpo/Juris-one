import "server-only";
import { addDays, localParts } from "./attendance/engine";
import { attendancePolicy, loadAttEmployees, lockInstant, materialize, TRACKED } from "./attendance/service";
import { audit } from "./audit";
import { db } from "./db";
import { dateToIso } from "./format";
import { derivedStatus } from "./people-rules";
import { getSetting } from "./settings";
import { accrualFor, compOffExpiries, round2, yearEndLapse } from "./leave/rules";


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

/**
 * Monthly leave accrual (CL 1, EL 1.5 …) credited at the start of each month of the leave year,
 * pro-rata in the joining month. Backfills any month not yet credited; one ledger row per
 * employee, type and month (refId "accrual:<type>:<yyyy-mm>").
 */
export async function jobLeaveAccrual(now = new Date()) {
  const ym = localParts(now, IST).date.slice(0, 7);
  const year = ym.slice(0, 4);
  const types = await db.leaveType.findMany({ where: { active: true, accrualPerMonth: { gt: 0 } } });
  const emps = await db.employee.findMany({ where: { AND: [TRACKED, { status: { not: "EXITED" } }, { dateOfJoining: { lte: new Date(`${monthEnd(ym)}T00:00:00Z`) } }] }, select: { id: true, dateOfJoining: true } });
  const done = new Set((await db.leaveLedger.findMany({ where: { reason: "ACCRUAL", refId: { startsWith: "accrual:" }, employeeId: { in: emps.map((e) => e.id) } }, select: { employeeId: true, refId: true } })).map((r) => `${r.employeeId}|${r.refId}`));
  const rows: { employeeId: string; leaveType: string; delta: number; reason: "ACCRUAL"; refId: string; note: string }[] = [];
  for (const e of emps) {
    const doj = dateToIso(e.dateOfJoining);
    for (let m = 1; m <= Number(ym.slice(5)); m++) {
      const month = `${year}-${String(m).padStart(2, "0")}`;
      for (const t of types) {
        const refId = `accrual:${t.code}:${month}`;
        if (done.has(`${e.id}|${refId}`)) continue;
        const amount = accrualFor(month, doj, Number(t.accrualPerMonth));
        if (amount > 0) rows.push({ employeeId: e.id, leaveType: t.code, delta: amount, reason: "ACCRUAL", refId, note: `Accrual ${month}` });
      }
    }
  }
  if (rows.length) await db.leaveLedger.createMany({ data: rows });
  return { credited: rows.length };
}

/** Comp-offs expire 60 days after the work date; usage comes out of the oldest credits first. */
export async function jobCompOffExpiry(now = new Date()) {
  const today = localParts(now, IST).date;
  const credits = await db.leaveLedger.findMany({ where: { leaveType: "CO", reason: "COMP_OFF_CREDIT" } });
  const byEmp = new Map<string, typeof credits>();
  for (const c of credits) byEmp.set(c.employeeId, [...(byEmp.get(c.employeeId) ?? []), c]);
  let expired = 0;
  for (const [employeeId, cs] of byEmp) {
    const rows = await db.leaveLedger.findMany({ where: { employeeId, leaveType: "CO", reason: { in: ["USED", "CANCELLED", "EXPIRED", "ADJUSTMENT"] } } });
    const used = -rows.filter((r) => r.reason !== "EXPIRED").reduce((a, r) => a + Number(r.delta), 0);
    const exp = compOffExpiries(cs.map((c) => ({ id: c.id, amount: Number(c.delta), expiresOn: c.expiresOn ? dateToIso(c.expiresOn) : "9999-12-31", expired: -rows.filter((r) => r.reason === "EXPIRED" && r.refId === c.id).reduce((a, r) => a + Number(r.delta), 0) })), used, today);
    for (const x of exp) {
      await db.leaveLedger.create({ data: { employeeId, leaveType: "CO", delta: -x.amount, reason: "EXPIRED", refId: x.id, note: "Comp-off expired" } });
      expired++;
    }
  }
  return { expired };
}

/** On 1 January: casual leave lapses, earned leave carries forward up to its cap. Once per year. */
export async function jobLeaveYearEnd(now = new Date()) {
  const prev = String(Number(localParts(now, IST).date.slice(0, 4)) - 1);
  if (await db.jobRun.findUnique({ where: { name_key: { name: "leave.year_end", key: prev } } })) return { lapsed: 0 };
  const firstYear = (await attendancePolicy()).trackingStart.slice(0, 4);
  if (prev < firstYear) return { lapsed: 0 };
  const types = await db.leaveType.findMany({ where: { active: true, needsBalance: true, code: { not: "CO" } } });
  const end = new Date(`${prev}-12-31T23:59:59Z`);
  let lapsed = 0;
  for (const t of types) {
    const sums = await db.leaveLedger.groupBy({ by: ["employeeId"], where: { leaveType: t.code, at: { lte: end } }, _sum: { delta: true } });
    for (const s of sums) {
      const amount = yearEndLapse(round2(Number(s._sum.delta ?? 0)), Number(t.carryForwardCap));
      if (amount > 0) {
        await db.leaveLedger.create({ data: { employeeId: s.employeeId, leaveType: t.code, delta: -amount, reason: "EXPIRED", refId: `year_end:${prev}`, note: t.carryForwardCap.toString() === "0" ? "Lapsed at year end" : "Above carry-forward cap" } });
        lapsed++;
      }
    }
  }
  await db.jobRun.create({ data: { name: "leave.year_end", key: prev, detail: { lapsed } } });
  return { lapsed };
}

/** Runs every job once. A Postgres advisory lock keeps two app instances from overlapping. */
export async function runJobs(now = new Date()) {
  const [{ ok }] = await db.$queryRaw<{ ok: boolean }[]>`SELECT pg_try_advisory_lock(74202) AS ok`;
  if (!ok) return { skipped: true };
  try {
    const status = await jobEmployeeStatus(now);
    const lock = await jobLockMonths(now);
    const finalize = await jobFinalizeDays(now);
    const yearEnd = await jobLeaveYearEnd(now);
    const accrual = await jobLeaveAccrual(now);
    const compOff = await jobCompOffExpiry(now);
    return { status, lock, finalize, yearEnd, accrual, compOff };
  } finally {
    await db.$queryRaw`SELECT pg_advisory_unlock(74202)`;
  }
}
