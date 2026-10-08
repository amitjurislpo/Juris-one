"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction, apiCall } from "@/lib/backend";
import type { Preview } from "@/lib/api-types";

// Leave requests run in the Spring Boot API (/api/leave).
const PAGES = ["/leave", "/approvals", "/home"];

export interface ApplyInput {
  type: string;
  from: string;
  to: string;
  part: "FULL" | "FIRST_HALF" | "SECOND_HALF";
  reason?: string;
}

/** Live numbers for the apply form — same rules the server enforces on submit. */
export async function preview(i: ApplyInput): Promise<ActionResult<Preview>> {
  return (await apiCall<Preview>("POST", "/api/leave/preview", i)).body;
}

export async function applyLeave(i: ApplyInput): Promise<ActionResult> {
  return apiAction("POST", "/api/leave/requests", i, PAGES);
}

export async function cancelLeave(id: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/leave/requests/${encodeURIComponent(id)}`, undefined, PAGES);
}
