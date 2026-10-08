"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction, apiCall } from "@/lib/backend";

// Proof verification runs in the Spring Boot API (/api/proofs).
const PAGES = ["/proofs", "/tax", "/declare", "/payroll"];
const enc = encodeURIComponent;

export interface ProofItem { id: string; key: string; label: string; sec: string; amount: number; status: string; proofName: string | null; hasProof: boolean; reason: string | null }
export interface ProofDetail { name: string; code: string; regime: "NEW" | "OLD"; metro: boolean; landlordPan: string | null; items: ProofItem[]; tdsNow: number | null; tdsIfAll: number | null; mine: boolean }

/** Drawer contents: items + TDS now vs. if everything pending is approved. */
export async function proofDetail(employeeId: string): Promise<ActionResult<ProofDetail>> {
  return (await apiCall<ProofDetail>("GET", `/api/proofs/${enc(employeeId)}`)).body;
}

/** Approve / reject one proof. Only approved amounts reduce TDS. */
export async function decideProof(i: { id: string; approve: boolean; reason?: string }): Promise<ActionResult> {
  return apiAction("PUT", `/api/proofs/items/${enc(i.id)}/decision`, { approve: i.approve, reason: i.reason }, PAGES);
}

export async function approveAllPending(employeeId: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/proofs/${enc(employeeId)}/decision`, { approve: true }, PAGES);
}
