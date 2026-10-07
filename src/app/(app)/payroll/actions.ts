"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Payroll runs in the Spring Boot API (/api/payroll).
const PAGES = ["/payroll", "/payslips", "/home", "/overview", "/tax"];

/** Finance edits LOP / bonus / other deduction / note while the run is open (each edit audited). Amounts in rupees. */
export async function saveAdjustment(i: { month: string; employeeId: string; lopDays: number | null; bonus: number; otherDeduction: number; note: string }): Promise<ActionResult> {
  return apiAction("POST", "/api/payroll/adjustments", i, PAGES);
}

export async function resetAdjustment(month: string, employeeId: string): Promise<ActionResult> {
  return apiAction("POST", "/api/payroll/adjustments/reset", { month, employeeId }, PAGES);
}

/** Approve & close (pay.close only): snapshots every payslip and publishes them. */
export async function approveAndClose(month: string): Promise<ActionResult> {
  return apiAction("POST", "/api/payroll/close", { month }, PAGES);
}
