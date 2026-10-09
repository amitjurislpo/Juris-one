/**
 * Who may do what to an employee record (REQUIREMENTS §3.3). Pure functions — the server
 * actions call these after loading actor + target from the database.
 */

export interface Actor {
  id: string;
  roleKey: string;
  perms: ReadonlySet<string>;
  /** Role ids this actor's role may assign (role_assignable_roles). */
  assignable: ReadonlySet<string>;
  departmentId: string | null;
  /** Role id of the built-in Employee role — decides the department lock. */
  employeeRoleId: string;
}

export interface Target {
  id: string;
  roleId: string;
  roleKey: string;
  status: "ONBOARDING" | "PROBATION" | "ACTIVE" | "EXITED";
  isSuperAdmin: boolean;
}

export const isAdmin = (a: Pick<Actor, "roleKey">) => a.roleKey === "owner" || a.roleKey === "super_admin"; // the business owner (sub admin), or the super admin in the platform company

/** People ops can add employees only if their role can assign at least one role. */
export const canAddEmployees = (a: Actor) => a.perms.has("people.add") && (isAdmin(a) || a.assignable.size > 0);

/** Edit needs people.add and the target's role in the actor's assignable list. Admin edits anyone. Nobody edits themselves. */
export function canEditEmployee(a: Actor, t: Target): boolean {
  if (!a.perms.has("people.add") || a.id === t.id || t.isSuperAdmin) return false;
  return isAdmin(a) || a.assignable.has(t.roleId);
}

/** access.toggle; never yourself; never the super admin; only Admin toggles an Admin; exited people stay off. */
export function canToggleAccess(a: Actor, t: Target): boolean {
  if (!a.perms.has("access.toggle") || a.id === t.id || t.isSuperAdmin || t.status === "EXITED") return false;
  return isAdmin(a) || t.roleKey !== "owner";
}

/** May the actor give `roleId` to someone? Keeping a person's current role is always allowed for an editor. */
export function canAssignRole(a: Actor, roleId: string, currentRoleId?: string): boolean {
  if (currentRoleId && roleId === currentRoleId) return true;
  return a.assignable.has(roleId);
}

/**
 * Managers who can't hire plain Employees (e.g. Finance Manager → Finance Executive) add people
 * into their own department only. Admin and HR choose freely.
 */
export function lockedDepartment(a: Actor): string | null {
  if (isAdmin(a) || a.assignable.has(a.employeeRoleId)) return null;
  return a.departmentId;
}

/** Status for a new hire: Onboarding until the joining date, then Probation. */
export function initialStatus(dojIso: string, todayIso: string): "ONBOARDING" | "PROBATION" {
  return dojIso > todayIso ? "ONBOARDING" : "PROBATION";
}

/** Status as time passes (daily job): Onboarding → Probation on DOJ → Active after N months. */
export function derivedStatus(current: Target["status"], dojIso: string, todayIso: string, probationMonths: number): Target["status"] {
  if (current === "EXITED") return current;
  if (dojIso > todayIso) return "ONBOARDING";
  const [y, m, d] = dojIso.split("-").map(Number);
  const end = new Date(Date.UTC(y, m - 1 + probationMonths, d)).toISOString().slice(0, 10);
  if (todayIso >= end) return current === "ONBOARDING" || current === "PROBATION" ? "ACTIVE" : current;
  return current === "ONBOARDING" ? "PROBATION" : current;
}

/** Would setting `managerId` as the manager of `employeeId` create a reporting loop? */
export function createsCycle(employeeId: string, managerId: string, managerOf: ReadonlyMap<string, string | null>): boolean {
  let cur: string | null | undefined = managerId;
  for (let i = 0; cur && i < 10_000; i++) {
    if (cur === employeeId) return true;
    cur = managerOf.get(cur);
  }
  return false;
}

// ─── locations ───
export const LOCATIONS = [
  { v: "IN_IST", l: "India · IST", tz: "Asia/Kolkata", region: "IN" },
  { v: "US_ET", l: "US East · ET", tz: "America/New_York", region: "US" },
  { v: "US_CT", l: "US Central · CT", tz: "America/Chicago", region: "US" },
  { v: "US_PT", l: "US West · PT", tz: "America/Los_Angeles", region: "US" },
] as const;
export type LocationCode = (typeof LOCATIONS)[number]["v"];
export const locationOfTz = (tz: string) => LOCATIONS.find((l) => l.tz === tz) ?? LOCATIONS[0];

// ─── identity validation ───
export const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
export const digits = (s: string) => s.replace(/\D/g, "");
export const validAadhaar = (s: string) => digits(s).length === 12;
export const validUan = (s: string) => digits(s).length === 12;
export const validPhone = (s: string) => digits(s).length >= 10 && digits(s).length <= 15;
