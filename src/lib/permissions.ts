/** Permission catalogue (REQUIREMENTS §3.1). Shared by server and client — contains no data. */
export const PERMISSIONS = [
  ["self", "Own attendance, leave, payslips & tax declarations"],
  ["dir", "Employee directory"],
  ["att.all", "View everyone’s attendance (with time zones)"],
  ["appr.leave", "Approve leave"],
  ["appr.reg", "Approve attendance regularization"],
  ["people.view", "View employee records"],
  ["people.add", "Add & edit employees"],
  ["access.toggle", "Enable / disable portal access"],
  ["dept.manage", "Create departments"],
  ["holidays.manage", "Manage holiday calendar"],
  ["letters.manage", "Issue HR letters & edit letter templates"],
  ["pay.view", "View & edit payroll"],
  ["pay.close", "Close payroll & publish payslips"],
  ["struct.manage", "Salary structures"],
  ["tax.view", "Tax & TDS"],
  ["proofs.verify", "Verify investment proofs"],
  ["policies.manage", "Publish company policies & see acknowledgements"],
  ["assets.manage", "Manage the asset register & assign assets"],
  ["announcements.manage", "Post announcements"],
  ["roles.manage", "Roles, permissions & policies"],
] as const;

export type Permission = (typeof PERMISSIONS)[number][0];

export const PERMISSION_KEYS = PERMISSIONS.map((p) => p[0]) as Permission[];
export const PERMISSION_LABEL = Object.fromEntries(PERMISSIONS) as Record<Permission, string>;

export const PERMISSION_GROUPS: [string, Permission[]][] = [
  ["Self-service", ["self", "dir"]],
  ["People operations", ["att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage", "letters.manage"]],
  ["Finance", ["pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify"]],
  ["Administration", ["policies.manage", "assets.manage", "announcements.manage", "roles.manage"]],
];

/** Permissions that expose salary / tax figures. HR roles must never hold these. */
export const SALARY_PERMISSIONS: Permission[] = ["pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify"];

export const isPermission = (s: string): s is Permission => (PERMISSION_KEYS as string[]).includes(s);
