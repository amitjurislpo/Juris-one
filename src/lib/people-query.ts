import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { listState, pick, str, type SearchParams } from "./list";

export const PEOPLE_SORTS = ["name", "code", "dept", "manager", "loc", "role", "status", "access", "doj"] as const;
export const PEOPLE_STATUS = ["Current", "ACTIVE", "PROBATION", "ONBOARDING", "EXITED", "All"] as const;
export const PEOPLE_ACCESS = ["All", "Enabled", "Disabled"] as const;

/** People list filters from the URL → Prisma where/orderBy. Used by the page and the export. */
export function peopleQuery(sp: SearchParams) {
  const ls = listState(sp, PEOPLE_SORTS, "name");
  const status = pick(sp, "status", PEOPLE_STATUS, "Current");
  const access = pick(sp, "access", PEOPLE_ACCESS, "All");
  const dept = str(sp, "dept", "All");

  const and: Prisma.EmployeeWhereInput[] = [];
  if (status === "Current") and.push({ status: { not: "EXITED" } });
  else if (status !== "All") and.push({ status });
  if (access !== "All") and.push({ portalAccess: access === "Enabled" });
  if (dept !== "All" && /^[0-9a-f-]{36}$/.test(dept)) and.push({ departmentId: dept });
  if (ls.q) {
    const c = { contains: ls.q, mode: "insensitive" as const };
    and.push({ OR: [{ fullName: c }, { code: c }, { email: c }, { designation: c }, { reportingManager: { fullName: c } }] });
  }
  const where: Prisma.EmployeeWhereInput = { AND: and };

  const d = ls.dir;
  const orderBy: Prisma.EmployeeOrderByWithRelationInput[] = {
    name: [{ fullName: d }],
    code: [{ code: d }],
    dept: [{ department: { name: d } }, { fullName: "asc" }],
    manager: [{ reportingManager: { fullName: d } }, { fullName: "asc" }],
    loc: [{ region: d }, { timeZone: d }, { fullName: "asc" }],
    role: [{ role: { name: d } }, { fullName: "asc" }],
    status: [{ status: d }, { fullName: "asc" }],
    access: [{ portalAccess: d }, { fullName: "asc" }],
    doj: [{ dateOfJoining: d }],
  }[ls.sort] as Prisma.EmployeeOrderByWithRelationInput[];

  return { ls, status, access, dept, where, orderBy };
}
