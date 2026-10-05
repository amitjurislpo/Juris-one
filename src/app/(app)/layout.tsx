import { cookies } from "next/headers";
import { Shell } from "@/components/shell/Shell";
import { landingPath, requireUser } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso } from "@/lib/format";
import { shellData } from "@/lib/shell-data";

const shiftLabel = (s: { code: string; name: string; startLocal: string; endLocal: string } | null) => {
  if (!s) return null;
  const t = (hm: string) => {
    const [h, m] = hm.split(":").map(Number);
    return `${((h + 11) % 12) + 1}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
  };
  return `${s.name} · ${t(s.startLocal)} – ${t(s.endLocal)}${s.code === "US_DAY" ? " local" : " IST"}`;
};

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
        shift: shiftLabel(emp.shift),
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
