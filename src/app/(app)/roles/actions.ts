"use server";

import { apiAction } from "@/lib/backend";
import type { ActionResult } from "@/lib/action";

// Roles & permissions run in the Spring Boot API (/api/roles); these keep the client's calls unchanged.
const PAGES = ["/roles", "/settings"];

export async function setPermission(roleId: string, perm: string, on: boolean): Promise<ActionResult> {
  return apiAction("PUT", `/api/roles/${encodeURIComponent(roleId)}/permissions`, { perm, on }, PAGES);
}

export async function setAssignable(roleId: string, assignableRoleId: string, on: boolean): Promise<ActionResult> {
  return apiAction("PUT", `/api/roles/${encodeURIComponent(roleId)}/assignable`, { roleId: assignableRoleId, on }, PAGES);
}

export async function createRole(input: { name: string; fromRoleId: string }): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/roles", input, PAGES);
}

export async function deleteRole(roleId: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/roles/${encodeURIComponent(roleId)}`, undefined, PAGES);
}
