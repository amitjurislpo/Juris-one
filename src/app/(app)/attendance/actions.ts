"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Regularization requests run in the Spring Boot API (/api/attendance/regularizations).

export interface RegInput {
  date: string;
  kind: "ABSENT" | "LATE_CHECKIN" | "MISSED_CHECKOUT" | "HOLIDAY_WORK";
  proposedIn: string;
  proposedOut: string;
  reason: string;
}

/**
 * Regularization request (REQUIREMENTS §5.4). Goes to HR, Finance and Admin together;
 * allowed only before that month's attendance lock.
 */
export async function requestRegularization(input: RegInput): Promise<ActionResult> {
  return apiAction("POST", "/api/attendance/regularizations", input, ["/attendance"]);
}

export async function cancelRegularization(id: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/attendance/regularizations/${encodeURIComponent(id)}`, undefined, ["/attendance"]);
}
