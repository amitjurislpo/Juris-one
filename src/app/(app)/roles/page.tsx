import type { Metadata } from "next";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { RolesClient, type RoleView } from "./RolesClient";

export const metadata: Metadata = { title: "Roles & permissions" };

export default async function RolesPage(props: PageProps<"/roles">) {
  await requirePage("roles");
  const sp = await props.searchParams;
  const roles = await db.role.findMany({
    orderBy: [{ isBuiltin: "desc" }, { createdAt: "asc" }],
    include: {
      permissions: true,
      assignable: true,
      employees: { where: { status: { not: "EXITED" } }, select: { id: true, fullName: true }, orderBy: { fullName: "asc" } },
      _count: { select: { employees: true } },
    },
  });
  // Built-in order from the prototype
  const ORDER = ["admin", "hrm", "hre", "fm", "fe", "employee"];
  roles.sort((a, b) => (ORDER.indexOf(a.key) + 1 || 99) - (ORDER.indexOf(b.key) + 1 || 99));

  const view: RoleView[] = roles.map((r) => ({
    id: r.id,
    key: r.key,
    name: r.name,
    builtin: r.isBuiltin,
    locked: r.isLocked,
    perms: r.permissions.map((p) => p.permissionKey),
    assign: r.assignable.map((a) => a.assignableRoleId),
    members: r.employees.map((e) => ({ id: e.id, name: e.fullName })),
    totalEver: r._count.employees,
  }));
  const sel = typeof sp.role === "string" && view.some((r) => r.id === sp.role) ? sp.role : (view.find((r) => r.key === "hrm") ?? view[0]).id;
  return <RolesClient roles={view} initialSel={sel} />;
}
