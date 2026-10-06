import type { NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { dailyLog, monthlyGrid, registerParams } from "@/lib/attendance/register";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { csvResponse, toCsv } from "@/lib/csv";

/** Attendance register export (att.all) — same filters as the page; daily log or monthly grid. */
export const GET = handler(async (req: NextRequest) => {
  const u = await assertPerm("att.all");
  const sp = Object.fromEntries(req.nextUrl.searchParams.entries());
  const p = registerParams(sp, u.timeZone);
  await audit({ actorId: u.id, action: "attendance.export", entity: "attendance", after: { view: p.view, day: p.day, ym: p.ym, filters: sp } });

  if (p.view === "day") {
    const { rows } = await dailyLog(p);
    const tzName = p.tz === "local" ? "employee local" : p.tz;
    return csvResponse(
      `jurisone-attendance-${p.day}.csv`,
      toCsv(
        ["Date", "Employee ID", "Name", "Department", "Team", "Shift", "Status", "Late", `Check-in (${tzName})`, `Check-out (${tzName})`, "Check-in (local)", "Check-out (local)", "Worked hours"],
        rows.map((r) => [p.day, r.e.code, r.e.name, r.e.department, r.e.region === "IN" ? "India" : "US", r.e.shift.name, r.st, r.d.late ? "Yes" : "", r.inn?.text ?? "", r.out?.text ?? "", r.inn?.local ?? r.inn?.text ?? "", r.out?.local ?? r.out?.text ?? "", r.h ? r.h.toFixed(2) : ""]),
      ),
    );
  }
  const { rows, days } = await monthlyGrid(p);
  return csvResponse(
    `jurisone-attendance-${p.ym}.csv`,
    toCsv(
      ["Employee ID", "Name", "Department", "Team", ...days.map((d) => d.k), "Present", "Absent / LOP", "Leave"],
      rows.map((r) => [r.e.code, r.e.name, r.e.department, r.e.region === "IN" ? "India" : "US", ...r.cells.map((c, i) => c.c || (days[i].wk ? "WO" : "")), r.P, r.A, r.L]),
    ),
  );
});
