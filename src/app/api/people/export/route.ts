import type { NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { csvResponse, toCsv } from "@/lib/csv";
import { db } from "@/lib/db";
import { dateToIso, todayIn } from "@/lib/format";
import { locationOfTz } from "@/lib/people-rules";
import { peopleQuery } from "@/lib/people-query";

/** People export (people.view). Same filters as the page; no salary and no identity numbers. */
export const GET = handler(async (req: NextRequest) => {
  const u = await assertPerm("people.view");
  const sp = Object.fromEntries(req.nextUrl.searchParams.entries());
  const { where, orderBy } = peopleQuery(sp);
  const rows = await db.employee.findMany({
    where, orderBy, take: 20_000,
    include: { department: true, reportingManager: { select: { fullName: true, code: true } }, role: true, shift: true },
  });
  await audit({ actorId: u.id, action: "employee.export", entity: "employee", after: { rows: rows.length, filters: sp } });
  const body = toCsv(
    ["Employee ID", "Name", "Email", "Designation", "Department", "Reporting manager", "Manager ID", "Location", "Time zone", "Shift", "Role", "Status", "Portal access", "Date of joining", "Last working day"],
    rows.map((e) => [
      e.code, e.fullName, e.email, e.designation, e.department?.name ?? "", e.reportingManager?.fullName ?? "", e.reportingManager?.code ?? "",
      locationOfTz(e.timeZone).l, e.timeZone, e.shift?.name ?? "", e.role.name, e.status, e.portalAccess ? "Enabled" : "Disabled",
      dateToIso(e.dateOfJoining), e.exitDate ? dateToIso(e.exitDate) : "",
    ]),
  );
  return csvResponse(`jurisone-people-${todayIn(u.timeZone)}.csv`, body);
});
