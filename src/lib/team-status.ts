import "server-only";
import { db } from "./db";
import { daysBetween, isoInZone, isoToDate, todayIn } from "./format";
import { getSetting } from "./settings";

export type TodayStatus = { label: string; tone: "ok" | "info" | "warn" | "gold" | "neutral" };

/**
 * "Right now" status for a set of employees, each in their own time zone:
 * holiday / weekly off / on leave / in since … / checked out / not in yet.
 */
export async function todayStatuses(emps: { id: string; timeZone: string; region: "IN" | "US" }[]): Promise<Map<string, TodayStatus>> {
  const out = new Map<string, TodayStatus>();
  if (!emps.length) return out;
  const ids = emps.map((e) => e.id);
  const policy = await getSetting("attendance.policy");
  const dates = [...new Set(emps.map((e) => todayIn(e.timeZone)))];
  const since = new Date(Date.now() - 20 * 3600_000); // covers a night shift that started yesterday evening
  const [holidays, leaves, punches] = await Promise.all([
    db.holiday.findMany({ where: { date: { in: dates.map(isoToDate) } } }),
    db.leaveRequest.findMany({ where: { employeeId: { in: ids }, status: "APPROVED", fromDate: { lte: isoToDate(dates.sort().at(-1)!) }, toDate: { gte: isoToDate(dates.sort()[0]) } } }),
    db.punch.findMany({ where: { employeeId: { in: ids }, at: { gte: since } }, orderBy: { at: "asc" } }),
  ]);
  for (const e of emps) {
    const today = todayIn(e.timeZone);
    const dow = isoToDate(today).getUTCDay();
    const last = punches.filter((p) => p.employeeId === e.id).at(-1);
    const hol = holidays.find((h) => h.date.toISOString().startsWith(today) && (h.region === "ALL" || h.region === e.region));
    const onLeave = leaves.some((l) => l.employeeId === e.id && daysBetween(l.fromDate.toISOString().slice(0, 10), today) >= 0 && daysBetween(today, l.toDate.toISOString().slice(0, 10)) >= 0);
    if (last?.kind === "IN") {
      const t = new Intl.DateTimeFormat("en-US", { timeZone: e.timeZone, hour: "numeric", minute: "2-digit" }).format(last.at);
      out.set(e.id, { label: `In since ${t}`, tone: "ok" });
    } else if (onLeave) out.set(e.id, { label: "On leave", tone: "info" });
    else if (hol) out.set(e.id, { label: hol.name, tone: "gold" });
    else if (policy.weeklyOffs.includes(dow)) out.set(e.id, { label: "Weekly off", tone: "neutral" });
    else if (last?.kind === "OUT" && isoInZone(last.at, e.timeZone) === today) out.set(e.id, { label: "Checked out", tone: "neutral" });
    else out.set(e.id, { label: "Not in yet", tone: "warn" });
  }
  return out;
}
