"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// HR's side of onboarding (Spring Boot /api/onboardings).
const enc = encodeURIComponent;

export interface PlanInput {
  candidateName: string; personalEmail: string; designation?: string; departmentId?: string; roleId?: string; managerId?: string; joiningDate?: string;
  location?: string; requiredDocs?: string[];
}
export interface AddInput { workEmail: string; designation: string; departmentId: string; managerId: string; roleId: string; joiningDate: string; location: string; shift: string; joiningOffice: string }

const pages = (id?: string) => ["/onboarding", ...(id ? [`/onboarding/${id}`] : [])];

export async function startOnboarding(i: PlanInput): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/onboardings", i, pages());
}

export async function updateOnboarding(id: string, i: PlanInput): Promise<ActionResult> {
  return apiAction("PUT", `/api/onboardings/${enc(id)}`, i, pages(id));
}

export async function resendOnboarding(id: string): Promise<ActionResult> {
  return apiAction("POST", `/api/onboardings/${enc(id)}/invitations`, {}, pages(id));
}

export async function decideDocument(id: string, docId: string, status: "VERIFIED" | "REJECTED", comment?: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/onboardings/${enc(id)}/documents/${enc(docId)}/status`, { status, comment }, pages(id));
}

export async function setOnboardingStatus(id: string, status: "CHANGES_REQUESTED" | "VERIFIED" | "CANCELLED", comment?: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/onboardings/${enc(id)}/status`, { status, comment }, pages(id));
}

export async function addOnboardedEmployee(id: string, i: AddInput): Promise<ActionResult<{ id: string; code: string }>> {
  return apiAction("POST", `/api/onboardings/${enc(id)}/employee`, i, [...pages(id), "/people"]);
}
