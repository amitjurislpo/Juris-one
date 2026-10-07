"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Approvals run in the Spring Boot API (/api/approvals); the first decision wins there.
const PAGES = ["/approvals", "/leave", "/attendance", "/home", "/register", "/team"];

/** HR (`appr.leave`; Admin too). Approving posts the used days to the ledger; any shortfall is LOP. */
export async function decideLeave(i: { id: string; approve: boolean; note?: string }): Promise<ActionResult> {
  return apiAction("POST", "/api/approvals/leave", i, PAGES);
}

/** Regularization goes to HR, Finance and Admin together (`appr.reg`); the first decision is final. */
export async function decideRegularization(i: { id: string; approve: boolean; note?: string }): Promise<ActionResult> {
  return apiAction("POST", "/api/approvals/regularization", i, PAGES);
}
