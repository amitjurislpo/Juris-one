import { cookies } from "next/headers";
import { Shell } from "@/components/shell/Shell";
import { landingPath, requireUser } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso } from "@/lib/format";
import { shellData } from "@/lib/shell-data";
import { shiftLabel } from "@/lib/shifts";

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const u = await requireUser();
  const [emp, data, jar] = await Promise.all([
    db.employee.findUniqueOrThrow({
      where: { id: u.id },
      include: { department: true, reportingManager: true, shift: true },
    }),
    shellData(u),
    cookies(),
  ]);
  return (
    <Shell
      me={{
        code: u.code,
        name: u.name,
        designation: u.designation,
        roleName: u.roleName,
        department: emp.department?.name ?? "—",
        manager: emp.reportingManager?.fullName ?? "—",
        doj: dateToIso(emp.dateOfJoining),
        shift: emp.shift ? shiftLabel(emp.shift) : null,
        isAdminLike: !u.perms.has("self"),
      }}
      groups={data.groups}
      badges={data.badges}
      notes={data.notes}
      unread={data.unread}
      landing={landingPath(u)}
      canSelf={u.perms.has("self")}
      initialTheme={jar.get("jo_theme")?.value === "dark" ? "dark" : "light"}
    >
      {children}
    </Shell>
  );
}
