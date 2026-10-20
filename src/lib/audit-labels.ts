/** Event groups for the audit filter (the API applies them); each matches action prefixes. */
export const AUDIT_GROUPS = {
  auth: { label: "Sign-in & accounts", prefixes: ["auth."] },
  people: { label: "Employees & access", prefixes: ["employee."] },
  roles: { label: "Roles, policies & company", prefixes: ["role.", "policy.", "asset.", "announcement."] },
  time: { label: "Leave & attendance", prefixes: ["leave.", "regularization.", "attendance."] },
  org: { label: "Departments & holidays", prefixes: ["department.", "holiday."] },
  payroll: { label: "Salary & payroll", prefixes: ["salary.", "payroll."] },
  tax: { label: "Tax & proofs", prefixes: ["tax.", "declaration.", "proof."] },
  letters: { label: "HR letters", prefixes: ["letter.", "letter_template."] },
} as const;
export type AuditGroup = keyof typeof AUDIT_GROUPS;

export interface AuditRow {
  id: string;
  at: string; // ISO timestamp
  actor: string;
  actorCode: string | null;
  action: string;
  entity: string;
  entityId: string | null;
  subject: string | null; // employee the entity refers to, when it is one
  ip: string | null;
  before: unknown;
  after: unknown;
  redacted: boolean;
}

/** "employee.access_toggle" → "Employee · access toggle" */
export const actionLabel = (a: string) => {
  const [head, ...rest] = a.split(".");
  return `${head.charAt(0).toUpperCase()}${head.slice(1)} · ${rest.join(" ").replace(/_/g, " ")}`;
};
