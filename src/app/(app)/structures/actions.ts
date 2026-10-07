"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

/**
 * Adds a salary revision (REQUIREMENTS §5.7) in the API. History is never edited — only added to.
 * Amounts in rupees; stored in paise. India employees only.
 */
export async function addStructure(i: { employeeId: string; effectiveFrom: string; reason: "JOINING" | "APPRAISAL" | "PROMOTION" | "CORRECTION"; basic: number; hra: number; lta: number; special: number }): Promise<ActionResult> {
  return apiAction("POST", "/api/structures", i, ["/structures", "/payroll", "/tax", "/people"]);
}
