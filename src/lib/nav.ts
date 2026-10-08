import type { Permission } from "./permissions";

export type NavId =
  | "platform" | "plans" | "home" | "overview" | "attendance" | "leave" | "payslips" | "declare" | "directory" | "team"
  | "register" | "approvals" | "people" | "departments" | "holidays"
  | "payroll" | "structures" | "tax" | "proofs" | "roles" | "settings" | "audit" | "subscription";

export type NavGroup = "Platform" | "My workspace" | "Workspace" | "Team" | "People ops" | "Finance" | "Administration";

/** The platform super admin's permission — never part of a business role. */
export const PLATFORM_MANAGE = "platform.manage";
/** The business owner's (sub admin's) permission — subscription and employee export; owner role only. */
export const BUSINESS_MANAGE = "business.manage";

export interface NavItem {
  id: NavId;
  label: string;
  icon: string;
  /** Any one of these grants access. */
  perm: (Permission | typeof PLATFORM_MANAGE | typeof BUSINESS_MANAGE)[];
  /** Extra condition evaluated on the server (e.g. has direct reports). */
  needsReports?: boolean;
  group?: NavGroup;
}

/** Sidebar + route access table (prototype NAV). Every page checks this on the server. */
export const NAV: NavItem[] = [
  { id: "platform", label: "Businesses", icon: "building", perm: [PLATFORM_MANAGE], group: "Platform" },
  { id: "plans", label: "Plans", icon: "layers", perm: [PLATFORM_MANAGE], group: "Platform" },
  { id: "home", label: "Home", icon: "home", perm: ["self"] },
  { id: "overview", label: "Overview", icon: "home", perm: ["roles.manage"] },
  { id: "attendance", label: "My attendance", icon: "clock", perm: ["self"] },
  { id: "leave", label: "Leave & holidays", icon: "cal", perm: ["self"] },
  { id: "payslips", label: "Payslips", icon: "doc", perm: ["self"] },
  { id: "declare", label: "Tax declarations", icon: "shield", perm: ["self"] },
  { id: "directory", label: "Directory", icon: "book", perm: ["dir"] },
  { id: "team", label: "My team", icon: "team", perm: ["self"], needsReports: true, group: "Team" },
  { id: "register", label: "Attendance register", icon: "grid", perm: ["att.all"], group: "People ops" },
  { id: "approvals", label: "Approvals", icon: "check", perm: ["appr.leave", "appr.reg"], group: "People ops" },
  { id: "people", label: "People", icon: "users", perm: ["people.view"], group: "People ops" },
  { id: "departments", label: "Departments", icon: "building", perm: ["dept.manage"], group: "People ops" },
  { id: "holidays", label: "Holiday calendar", icon: "sun", perm: ["holidays.manage"], group: "People ops" },
  { id: "payroll", label: "Payroll", icon: "wallet", perm: ["pay.view"], group: "Finance" },
  { id: "structures", label: "Salary structures", icon: "layers", perm: ["struct.manage"], group: "Finance" },
  { id: "tax", label: "Tax & TDS", icon: "receipt", perm: ["tax.view"], group: "Finance" },
  { id: "proofs", label: "Investment proofs", icon: "inbox", perm: ["proofs.verify"], group: "Finance" },
  { id: "roles", label: "Roles & permissions", icon: "key", perm: ["roles.manage"], group: "Administration" },
  { id: "settings", label: "Policies", icon: "sliders", perm: ["roles.manage"], group: "Administration" },
  { id: "audit", label: "Audit log", icon: "doc", perm: ["roles.manage"], group: "Administration" },
  { id: "subscription", label: "Subscription", icon: "receipt", perm: [BUSINESS_MANAGE], group: "Administration" },
];

export const PAGE_TITLES = Object.fromEntries(NAV.map((n) => [n.id, n.label])) as Record<NavId, string>;

export function navAllowed(item: NavItem, perms: ReadonlySet<string>, hasReports: boolean) {
  return item.perm.some((p) => perms.has(p)) && (!item.needsReports || hasReports);
}

/** Landing page after login: the platform console for the super admin, Home for anyone with `self`, Overview for the business admin. */
export const landingFor = (perms: ReadonlySet<string>) =>
  perms.has(PLATFORM_MANAGE) ? "/platform" : perms.has("self") ? "/home" : perms.has("roles.manage") ? "/overview" : "/directory";
