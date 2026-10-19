"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Asset tracking runs in the Spring Boot API (/api/assets).
const enc = encodeURIComponent;
const PAGES = ["/assets", "/my-assets"];

export interface AssetInput {
  tag: string; type: string; make: string; model: string; serial: string; purchaseDate: string; status?: string; notes: string;
}

export async function createAsset(i: AssetInput): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/assets", i, PAGES);
}

export async function updateAsset(id: string, i: AssetInput): Promise<ActionResult> {
  return apiAction("PUT", `/api/assets/${enc(id)}`, i, PAGES);
}

export async function deleteAsset(id: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/assets/${enc(id)}`, undefined, PAGES);
}

export async function assignAsset(id: string, i: { employeeId: string; assignedOn: string; notes: string }): Promise<ActionResult> {
  return apiAction("POST", `/api/assets/${enc(id)}/assignments`, i, PAGES);
}

export async function returnAsset(id: string, i: { condition: string; returnedOn: string; notes: string }): Promise<ActionResult> {
  return apiAction("PATCH", `/api/assets/${enc(id)}/assignments/current`, i, PAGES);
}
