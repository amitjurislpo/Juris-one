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
  for (const e of emps) {
    const wd = currentWorkDate(e, now);
    const d = (await attendanceFor([e], wd, wd, now)).get(e.id)![0];
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
