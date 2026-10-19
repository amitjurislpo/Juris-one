"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction, apiCall } from "@/lib/backend";

// HR's and Finance's side of exits and the full & final settlement (Spring Boot /api/exits). Amounts are paise.
const enc = encodeURIComponent;
const pages = (id?: string) => ["/exits", ...(id ? [`/exits/${id}`] : [])];

export interface StartInput { employeeId: string; kind: "TERMINATION" | "OTHER" | "RESIGNATION"; reason: string; lastWorkingDay?: string; noticeDays?: number; noticeReason?: string }
export interface NoticeInput { lastWorkingDay: string; noticeDays?: number; reason?: string }
export interface SettlementLine { label: string; amount: number }
export interface SettlementInput { additions: SettlementLine[]; deductions: SettlementLine[]; note?: string }
export interface ExitPolicy { noticeDays: number; encashableLeaveTypes: string[]; gratuityEnabled: boolean; gratuityMinYears: number; gratuityCapRupees: number }
export type ExitPolicyView = ExitPolicy & { leaveTypes: { code: string; name: string }[] };

export async function startExit(i: StartInput): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/exits", i, pages());
}

export async function saveNotice(id: string, i: NoticeInput): Promise<ActionResult> {
  return apiAction("PUT", `/api/exits/${enc(id)}/notice`, i, pages(id));
}

export async function setExitStatus(id: string, status: "NOTICE" | "CLEARANCE" | "FNF_PENDING" | "CLOSED" | "CANCELLED", comment?: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/exits/${enc(id)}/status`, { status, comment }, [...pages(id), "/employees"]);
}

export async function addClearanceItem(id: string, dept: string, title: string): Promise<ActionResult> {
  return apiAction("POST", `/api/exits/${enc(id)}/clearance`, { dept, title }, pages(id));
}

export async function clearItem(id: string, itemId: string, cleared: boolean, remarks?: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/exits/${enc(id)}/clearance/${enc(itemId)}`, { cleared, remarks }, pages(id));
}

export async function getExitPolicy(): Promise<ActionResult<ExitPolicyView>> {
  return (await apiCall<ExitPolicyView>("GET", "/api/exits/policy")).body;
}

export async function saveExitPolicy(p: Partial<ExitPolicy>): Promise<ActionResult> {
  return apiAction("PUT", "/api/exits/policy", p, pages());
}

export async function saveSettlement(id: string, i: SettlementInput): Promise<ActionResult> {
  return apiAction("PUT", `/api/exits/${enc(id)}/settlement`, i, pages(id));
}

export async function approveSettlement(id: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/exits/${enc(id)}/settlement/status`, { status: "APPROVED" }, pages(id));
}
