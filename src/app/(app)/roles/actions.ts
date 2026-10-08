"use server";

import { apiAction } from "@/lib/backend";
import type { ActionResult } from "@/lib/action";

// Roles & permissions run in the Spring Boot API (/api/roles); these keep the client's calls unchanged.
const enc = encodeURIComponent;
const PAGES = ["/roles", "/settings"];

export async function setPermission(roleId: string, perm: string, on: boolean): Promise<ActionResult> {
  return apiAction(on ? "PUT" : "DELETE", `/api/roles/${enc(roleId)}/permissions/${enc(perm)}`, undefined, PAGES);
}

export async function setAssignable(roleId: string, assignableRoleId: string, on: boolean): Promise<ActionResult> {
  return apiAction(on ? "PUT" : "DELETE", `/api/roles/${enc(roleId)}/assignable-roles/${enc(assignableRoleId)}`, undefined, PAGES);
}

export async function createRole(input: { name: string; fromRoleId: string }): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/roles", input, PAGES);
}

export async function deleteRole(roleId: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/roles/${enc(roleId)}`, undefined, PAGES);
}
