import "server-only";
import { attendanceFor, currentWorkDate, fmtTime, loadAttEmployees } from "./attendance/service";

export type TodayStatus = { label: string; tone: "ok" | "info" | "warn" | "gold" | "neutral" };

/**
 * "Right now" status for each employee, on their own current work date and time zone:
 * in since … / checked out / on leave / holiday / weekly off / absent / not in yet.
 */
export async function todayStatuses(ids: string[], now = new Date()): Promise<Map<string, TodayStatus>> {
  const out = new Map<string, TodayStatus>();
  const emps = await loadAttEmployees({ id: { in: ids } });
  if (!emps.length) return out;
  // one batched load across everyone's current work date (they differ by time zone / shift)
  const wds = new Map(emps.map((e) => [e.id, currentWorkDate(e, now)]));
  const all = [...wds.values()].sort();
  const days = await attendanceFor(emps, all[0], all.at(-1)!, now);
  for (const e of emps) {
    const wd = wds.get(e.id)!;
    const d = days.get(e.id)!.find((x) => x.date === wd)!;
    let s: TodayStatus;
    if (d.openSince) s = { label: `In since ${fmtTime(d.openSince, e.tz)}`, tone: "ok" };
    else if (d.status === "LEAVE") s = { label: "On leave", tone: "info" };
    else if (d.status === "HOLIDAY") s = { label: d.label, tone: "gold" };
    else if (d.status === "WEEKLY_OFF") s = { label: "Weekly off", tone: "neutral" };
    else if (d.punches.length) s = { label: "Checked out", tone: "neutral" };
    else if (d.status === "ABSENT") s = { label: "Absent", tone: "warn" };
    else s = { label: "Not in yet", tone: "warn" };
    out.set(e.id, s);
  }
  return out;
}
