"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Departments run in the Spring Boot API (/api/departments).

export async function createDepartment(input: { name: string; headId?: string; description?: string }): Promise<ActionResult> {
  return apiAction("POST", "/api/departments", input, ["/departments"]);
}

export async function deleteDepartment(id: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/departments/${encodeURIComponent(id)}`, undefined, ["/departments"]);
}
