"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// The candidate's side of onboarding: the one-time link (token) is the only credential.
const enc = encodeURIComponent;

export interface CandidateForm {
  firstName: string; middleName: string; lastName: string; fatherName: string; dateOfBirth: string; gender: string; phoneCountry: string; phone: string;
  currentAddress: string; permanentAddress: string; pan: string; aadhaar: string; uan: string; bankName: string; bankAccount: string; ifsc: string;
  previousEmployer: string; previousDesignation: string; emergencyName: string; emergencyRelation: string; emergencyPhoneCountry: string; emergencyPhone: string;
}

export async function saveCandidateForm(token: string, form: CandidateForm): Promise<ActionResult> {
  return apiAction("PUT", `/api/onboarding/${enc(token)}`, form, [`/onboard/${token}`]);
}

export async function removeCandidateDocument(token: string, docId: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/onboarding/${enc(token)}/documents/${enc(docId)}`, undefined, [`/onboard/${token}`]);
}

export async function submitOnboarding(token: string): Promise<ActionResult> {
  return apiAction("POST", `/api/onboarding/${enc(token)}/submission`, {}, [`/onboard/${token}`]);
}
