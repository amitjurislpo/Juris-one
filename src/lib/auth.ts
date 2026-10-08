import "server-only";
import { cache } from "react";
import { redirect } from "next/navigation";
import { apiGet } from "./backend";
import { hasSessionCookie } from "./session-cookie";
import { NAV, navAllowed, type NavId } from "./nav";
import type { Permission } from "./permissions";

export interface CurrentUser {
  id: string;
  code: string;
  name: string;
  email: string;
  designation: string;
  roleId: string;
  roleKey: string;
  roleName: string;
  perms: ReadonlySet<Permission>;
  isSuperAdmin: boolean;
  region: "IN" | "US";
  timeZone: string;
  hasReports: boolean;
  sessionId: string;
}

export class ForbiddenError extends Error {
  constructor(message = "You don't have permission to do that.") {
    super(message);
    this.name = "ForbiddenError";
  }
}

/**
 * The signed-in user for this request, or null. Re-validated against the database on every
 * request: a revoked/expired session, disabled access or an exited employee all yield null.
 */
export const getCurrentUser = cache(async (): Promise<CurrentUser | null> => {
  if (!(await hasSessionCookie())) return null;
  // the Spring Boot API validates the session (revoked/expired, access off, exited) and loads permissions
  const me = await apiGet<{ user: Omit<CurrentUser, "perms"> & { perms: Permission[] } }>("/api/me");
  if (!me) return null;
  return { ...me.user, perms: new Set(me.user.perms) };
});

export const can = (u: CurrentUser | null, ...perms: Permission[]) => !!u && perms.some((p) => u.perms.has(p));

export function allowedNav(u: CurrentUser) {
  return NAV.filter((n) => navAllowed(n, u.perms, u.hasReports));
}

export function landingPath(u: CurrentUser) {
  if (u.perms.has("self")) return "/home";
  if (u.perms.has("roles.manage")) return "/overview";
  const first = allowedNav(u)[0];
  return first ? `/${first.id}` : "/no-access";
}

/** Pages: redirect to /login when signed out. */
export async function requireUser(): Promise<CurrentUser> {
  const u = await getCurrentUser();
  if (!u) redirect("/login");
  return u;
}

/** Pages: the nav entry for this route must be allowed, else bounce to the user's landing page. */
export async function requirePage(id: NavId): Promise<CurrentUser> {
  const u = await requireUser();
  const item = NAV.find((n) => n.id === id)!;
  if (!navAllowed(item, u.perms, u.hasReports)) redirect(landingPath(u));
  return u;
}

/** Server actions / route handlers: throws ForbiddenError (never redirects). */
export async function assertPerm(...perms: Permission[]): Promise<CurrentUser> {
  const u = await getCurrentUser();
  if (!u) throw new ForbiddenError("Your session has ended. Please sign in again.");
  if (perms.length && !can(u, ...perms)) throw new ForbiddenError();
  return u;
}
