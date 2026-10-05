import "server-only";
import { db } from "./db";
import { allowedNav, can, type CurrentUser } from "./auth";
import type { NavGroup } from "./nav";
import { dateToIso, fmtDateTime, fmtDL, isoToDate, todayIn } from "./format";

export interface ShellNote {
  t: string;
  s: string;
  href?: string;
}

/** Sidebar groups, badges and bell contents for the signed-in user — all permission-driven. */
export async function shellData(u: CurrentUser) {
  const isAdminLike = !u.perms.has("self");
  const groups: { g: NavGroup; items: { id: string; label: string; icon: string }[] }[] = [];
  for (const n of allowedNav(u)) {
    const g: NavGroup = n.group ?? (isAdminLike ? "Workspace" : "My workspace");
    let G = groups.find((x) => x.g === g);
    if (!G) groups.push((G = { g, items: [] }));
    G.items.push({ id: n.id, label: n.label, icon: n.icon });
  }

  const [pendingLeave, pendingReg, proofs, unread, nextHoliday] = await Promise.all([
    can(u, "appr.leave") ? db.leaveRequest.count({ where: { status: "PENDING", employeeId: { not: u.id } } }) : 0,
    can(u, "appr.reg") ? db.regularizationRequest.count({ where: { status: "PENDING", employeeId: { not: u.id } } }) : 0,
    can(u, "proofs.verify") ? db.declarationItem.count({ where: { status: "PENDING", employeeId: { not: u.id } } }) : 0,
    db.notification.findMany({ where: { employeeId: u.id, readAt: null }, orderBy: { createdAt: "desc" }, take: 8 }),
    db.holiday.findFirst({
      where: { date: { gte: isoToDate(todayIn(u.timeZone)) }, region: { in: ["ALL", u.region] } },
      orderBy: { date: "asc" },
    }),
  ]);
  const pending = pendingLeave + pendingReg;

  const notes: ShellNote[] = [];
  if (can(u, "appr.leave", "appr.reg")) notes.push({ t: `${pending} request${pending === 1 ? "" : "s"} waiting for approval`, s: "Approvals", href: "/approvals" });
  if (can(u, "proofs.verify")) notes.push({ t: `${proofs} investment proof${proofs === 1 ? "" : "s"} to verify`, s: "Finance", href: "/proofs" });
  for (const n of unread) notes.push({ t: n.text, s: fmtDateTime(n.createdAt, u.timeZone), href: n.link ?? undefined });
  if (nextHoliday) {
    notes.push({ t: `${nextHoliday.name} holiday on ${fmtDL(dateToIso(nextHoliday.date))}`, s: "Holiday calendar" });
  }

  return { groups, badges: { approvals: pending, proofs }, notes, unread: unread.length };
}
