/** Exit status → chip class and label. */
export const EXIT_STATUS = {
  RESIGNED: { cls: "warn", text: "Resigned" },
  INITIATED: { cls: "warn", text: "Initiated" },
  NOTICE: { cls: "info", text: "Serving notice" },
  CLEARANCE: { cls: "info", text: "Clearance" },
  FNF_PENDING: { cls: "warn", text: "Settlement due" },
  SETTLED: { cls: "ok", text: "Settled" },
  CLOSED: { cls: "ok", text: "Closed" },
  WITHDRAWN: { cls: "neutral", text: "Withdrawn" },
  CANCELLED: { cls: "neutral", text: "Cancelled" },
} as const;

export type ExitStatus = keyof typeof EXIT_STATUS;

export const EXIT_KIND: Record<string, string> = { RESIGNATION: "Resignation", TERMINATION: "Termination", OTHER: "Other" };

export const DEPT_LABEL: Record<string, string> = { HR: "HR", FINANCE: "Finance", IT: "IT / assets", MANAGER: "Manager" };

/** An exit that is still in progress. */
export const isOpen = (s: string) => !["CLOSED", "WITHDRAWN", "CANCELLED"].includes(s);
