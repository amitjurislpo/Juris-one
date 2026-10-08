"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Decisions run in the Spring Boot API (PUT …/{id}/decision); the first decision wins there.
const enc = encodeURIComponent;
const PAGES = ["/approvals", "/leave", "/attendance", "/home", "/register", "/team"];

/** HR (`appr.leave`; Admin too). Approving posts the used days to the ledger; any shortfall is LOP. */
export async function decideLeave(i: { id: string; approve: boolean; note?: string }): Promise<ActionResult> {
  return apiAction("PUT", `/api/leave/requests/${enc(i.id)}/decision`, { approve: i.approve, note: i.note }, PAGES);
}

/** Regularization goes to HR, Finance and Admin together (`appr.reg`); the first decision is final. */
export async function decideRegularization(i: { id: string; approve: boolean; note?: string }): Promise<ActionResult> {
  return apiAction("PUT", `/api/attendance/regularizations/${enc(i.id)}/decision`, { approve: i.approve, note: i.note }, PAGES);
}
