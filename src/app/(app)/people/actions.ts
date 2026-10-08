"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction, apiCall } from "@/lib/backend";
import type { EmployeeInput } from "@/lib/employee-schema";

// People records run in the Spring Boot API (/api/people). Salary is only sent to struct.manage; PII is masked.
const PAGES = ["/people", "/directory", "/team", "/departments"];
const enc = encodeURIComponent;

// ───────────────────────── profile drawer ─────────────────────────

export interface Profile {
  id: string;
  code: string;
  name: string;
  father: string;
  email: string;
  phone: string;
  address: string;
  pan: string;
  aadhaar: string;
  uan: string;
  designation: string;
  department: string;
  manager: string;
  reports: string[];
  doj: string;
  exit: string | null;
  location: string;
  india: boolean;
  shift: string;
  role: string;
  status: string;
  access: boolean;
  invitePending: boolean;
  gross: number | null; // paise, only for struct.manage
  canEdit: boolean;
  canToggle: boolean;
}

export async function getProfile(id: string): Promise<ActionResult<Profile>> {
  return (await apiCall<Profile>("GET", `/api/people/${enc(id)}`)).body;
}

/** Full phone + address for people who may edit the record. Every reveal is audited. */
export async function revealContact(id: string): Promise<ActionResult<{ phone: string; address: string }>> {
  return (await apiCall<{ phone: string; address: string }>("GET", `/api/people/${enc(id)}/contact`)).body;
}

// ───────────────────────── access & exit ─────────────────────────

export async function toggleAccess(id: string, on: boolean): Promise<ActionResult> {
  return apiAction("PUT", `/api/people/${enc(id)}/access`, { on }, PAGES);
}

export async function markExited(id: string, exitDate: string): Promise<ActionResult> {
  return apiAction("PUT", `/api/people/${enc(id)}/exit`, { exitDate }, PAGES);
}

export async function resendInvite(id: string): Promise<ActionResult> {
  return apiAction("POST", `/api/people/${enc(id)}/invitations`);
}

// ───────────────────────── add / edit ─────────────────────────

export interface FormOptions {
  nextCode: string;
  departments: { id: string; name: string; headId: string | null }[];
  lockedDepartmentId: string | null;
  managers: { id: string; name: string; title: string }[];
  roles: { id: string; name: string; perms: number }[];
  defaults: EmployeeInput;
  editing: { code: string; name: string; last4: { phone: string | null; pan: string | null; aadhaar: string | null; uan: string | null }; hasAddress: boolean; hadManager: boolean } | null;
}

export async function getFormOptions(editId?: string): Promise<ActionResult<FormOptions>> {
  return (await apiCall<FormOptions>("GET", `/api/people/form-options${editId ? `?id=${enc(editId)}` : ""}`)).body;
}

export async function saveEmployee(input: EmployeeInput, editId?: string): Promise<ActionResult<{ id: string; code: string }>> {
  return editId
    ? apiAction("PUT", `/api/people/${enc(editId)}`, input, PAGES)
    : apiAction("POST", "/api/people", input, PAGES);
}
