import "server-only";
import { cache } from "react";
import { notFound, redirect } from "next/navigation";
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
  /** Whether the user's business may use JurisOne today; null for the super admin. */
  subscriptionActive: boolean | null;
  /** Modules the business can use today (e.g. JURIS_TRACK). */
  modules: string[];
  /** Published policies this user still has to accept (they see only the acknowledgement screen until then). */
  pendingPolicies: number;
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
  const me = await apiGet<{
    user: Omit<CurrentUser, "perms" | "subscriptionActive" | "modules" | "pendingPolicies"> & { perms: Permission[] };
    subscriptionActive: boolean | null; modules: string[]; pendingPolicies: number;
  }>("/api/me");
  if (!me) return null;
  return { ...me.user, perms: new Set(me.user.perms), subscriptionActive: me.subscriptionActive, modules: me.modules, pendingPolicies: me.pendingPolicies };
});

export const can = (u: CurrentUser | null, ...perms: Permission[]) => !!u && perms.some((p) => u.perms.has(p));

export function allowedNav(u: CurrentUser) {
  return NAV.filter((n) => navAllowed(n, u.perms, u.hasReports) && (!n.module || u.modules.includes(n.module)));
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

/** Pages that stay open when the business's subscription isn't active (the API allows the same). */
const OPEN_WITHOUT_SUBSCRIPTION: NavId[] = ["subscription"];

/**
 * Pages: the nav entry for this route must be allowed, else bounce to the user's landing page. When the business's
 * subscription isn't active, every page except the owner's Subscription page shows the "paused" screen instead; while
 * a published policy waits for the user's acceptance, every page shows the acknowledgement screen.
 */
export async function requirePage(id: NavId): Promise<CurrentUser> {
  const u = await requireUser();
  const item = NAV.find((n) => n.id === id)!;
  // a module page doesn't exist for a business without the module
  if (item.module && !u.modules.includes(item.module)) notFound();
  if (!navAllowed(item, u.perms, u.hasReports)) redirect(landingPath(u));
  if (u.subscriptionActive === false && !OPEN_WITHOUT_SUBSCRIPTION.includes(id)) redirect("/paused");
  if (u.pendingPolicies > 0) redirect("/acknowledge");
  return u;
}

/** Server actions / route handlers: throws ForbiddenError (never redirects). */
export async function assertPerm(...perms: Permission[]): Promise<CurrentUser> {
  const u = await getCurrentUser();
  if (!u) throw new ForbiddenError("Your session has ended. Please sign in again.");
  if (perms.length && !can(u, ...perms)) throw new ForbiddenError();
  return u;
}
