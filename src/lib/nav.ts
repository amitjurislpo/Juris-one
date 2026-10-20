import type { Permission } from "./permissions";

export type NavId =
  | "platform" | "plans" | "juris-track" | "notifications" | "profile" | "home" | "overview" | "attendance" | "leave" | "payslips" | "declare" | "directory" | "team"
  | "register" | "approvals" | "employees" | "onboarding" | "departments" | "holidays"
  | "payroll" | "structures" | "tax" | "proofs" | "roles" | "policies" | "settings" | "audit" | "subscription" | "my-policies"
  | "my-assets" | "announcements" | "assets" | "manage-announcements" | "my-letters" | "letters";

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
  /** Only for businesses whose plan or grant includes this module (else the page doesn't exist). */
  module?: string;
}

/** Juris Track: a module of the plan, or granted on its own by the super admin. */
export const JURIS_TRACK = "JURIS_TRACK";

/** Sidebar + route access table (prototype NAV). Every page checks this on the server. */
export const NAV: NavItem[] = [
  { id: "platform", label: "Businesses", icon: "building", perm: [PLATFORM_MANAGE], group: "Platform" },
  { id: "plans", label: "Plans", icon: "layers", perm: [PLATFORM_MANAGE], group: "Platform" },
  { id: "home", label: "Home", icon: "home", perm: ["self"] },
  { id: "overview", label: "Overview", icon: "home", perm: ["roles.manage"] },
  { id: "notifications", label: "Notifications", icon: "bell", perm: ["self", "dir", "roles.manage"] },
  { id: "profile", label: "My profile", icon: "user", perm: ["self", "dir", "roles.manage"] },
  { id: "attendance", label: "My attendance", icon: "clock", perm: ["self"] },
  { id: "leave", label: "Leave & holidays", icon: "cal", perm: ["self"] },
  { id: "payslips", label: "Payslips", icon: "doc", perm: ["self"] },
  { id: "declare", label: "Tax declarations", icon: "shield", perm: ["self"] },
  { id: "my-letters", label: "My letters", icon: "doc", perm: ["self"] },
  { id: "directory", label: "Directory", icon: "book", perm: ["dir"] },
  { id: "my-policies", label: "My policies", icon: "shield", perm: ["self", "dir", "roles.manage"] },
  { id: "my-assets", label: "My assets", icon: "layers", perm: ["self", "dir", "roles.manage"] },
  { id: "announcements", label: "Announcements", icon: "bell", perm: ["self", "dir", "roles.manage"] },
  { id: "juris-track", label: "Juris Track", icon: "sparkle", perm: ["self", "roles.manage", "dir"], module: JURIS_TRACK },
  { id: "team", label: "My team", icon: "team", perm: ["self"], needsReports: true, group: "Team" },
  { id: "register", label: "Attendance register", icon: "grid", perm: ["att.all"], group: "People ops" },
  { id: "approvals", label: "Approvals", icon: "check", perm: ["appr.leave", "appr.reg"], group: "People ops" },
  { id: "employees", label: "Employees", icon: "users", perm: ["people.view"], group: "People ops" },
  { id: "onboarding", label: "Onboarding", icon: "inbox", perm: ["people.add"], group: "People ops" },
  { id: "departments", label: "Departments", icon: "building", perm: ["dept.manage"], group: "People ops" },
  { id: "holidays", label: "Holiday calendar", icon: "sun", perm: ["holidays.manage"], group: "People ops" },
  { id: "assets", label: "Assets", icon: "layers", perm: ["assets.manage"], group: "People ops" },
  { id: "manage-announcements", label: "Post announcements", icon: "bell", perm: ["announcements.manage"], group: "People ops" },
  { id: "letters", label: "HR letters", icon: "doc", perm: ["letters.manage"], group: "People ops" },
  { id: "payroll", label: "Payroll", icon: "wallet", perm: ["pay.view"], group: "Finance" },
  { id: "structures", label: "Salary structures", icon: "layers", perm: ["struct.manage"], group: "Finance" },
  { id: "tax", label: "Tax & TDS", icon: "receipt", perm: ["tax.view"], group: "Finance" },
  { id: "proofs", label: "Investment proofs", icon: "inbox", perm: ["proofs.verify"], group: "Finance" },
  { id: "roles", label: "Roles & permissions", icon: "key", perm: ["roles.manage"], group: "Administration" },
  { id: "policies", label: "Company policies", icon: "book", perm: ["policies.manage"], group: "Administration" },
  { id: "settings", label: "Settings", icon: "sliders", perm: ["roles.manage"], group: "Administration" },
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
