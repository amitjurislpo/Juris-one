"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Payroll runs in the Spring Boot API (/api/payroll).
const PAGES = ["/payroll", "/payslips", "/home", "/overview", "/tax"];
const enc = encodeURIComponent;

/** Finance edits LOP / bonus / other deduction / note while the run is open (each edit audited). Amounts in rupees. */
export async function saveAdjustment(i: { month: string; employeeId: string; lopDays: number | null; bonus: number; otherDeduction: number; note: string }): Promise<ActionResult> {
  const { month, employeeId, ...adjustment } = i;
  return apiAction("PUT", `/api/payroll/${enc(month)}/adjustments/${enc(employeeId)}`, adjustment, PAGES);
}

export async function resetAdjustment(month: string, employeeId: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/payroll/${enc(month)}/adjustments/${enc(employeeId)}`, undefined, PAGES);
}

/** Approve & close (pay.close only): snapshots every payslip and publishes them. */
export async function approveAndClose(month: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/payroll/${enc(month)}/status`, { status: "CLOSED" }, PAGES);
}
