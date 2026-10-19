"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction, apiCall } from "@/lib/backend";
import type { LetterTemplateDetail, LetterType } from "@/lib/api-types";

// HR letters run in the Spring Boot API (/api/letters).
const enc = encodeURIComponent;
const PAGES = ["/letters", "/my-letters"];

export interface TemplateInput { type: LetterType; name: string; subject: string; body: string; active?: boolean }

export async function createTemplate(i: TemplateInput): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/letters/templates", i, PAGES);
}

export async function updateTemplate(id: string, i: Omit<TemplateInput, "type">): Promise<ActionResult> {
  return apiAction("PUT", `/api/letters/templates/${enc(id)}`, i, PAGES);
}

export async function templateDetail(id: string): Promise<ActionResult<LetterTemplateDetail>> {
  return (await apiCall<LetterTemplateDetail>("GET", `/api/letters/templates/${enc(id)}`)).body;
}

/** What the letter would say (nothing is saved). */
export async function previewLetter(i: { templateId: string; employeeId: string; lastWorkingDate: string }): Promise<ActionResult<{ subject: string; text: string; needsSalary: boolean }>> {
  const q = new URLSearchParams({ templateId: i.templateId, employeeId: i.employeeId });
  if (i.lastWorkingDate) q.set("lastWorkingDate", i.lastWorkingDate);
  return (await apiCall<{ subject: string; text: string; needsSalary: boolean }>("GET", `/api/letters/previews?${q}`)).body;
}

export async function issueLetter(i: { templateId: string; employeeId: string; lastWorkingDate: string }): Promise<ActionResult<{ id: string; reference: string }>> {
  return apiAction("POST", "/api/letters", { templateId: i.templateId, employeeId: i.employeeId, lastWorkingDate: i.lastWorkingDate || undefined }, PAGES);
}
