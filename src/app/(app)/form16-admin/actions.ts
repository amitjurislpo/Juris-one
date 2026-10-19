"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Form 16 runs in the Spring Boot API (/api/form16).
const PAGES = ["/form16-admin", "/form16"];

/** Generates and publishes Form 16 for the year (needs all 12 months closed); published forms are never replaced. */
export async function publishForm16(fy: string): Promise<ActionResult<{ fy: string; published: number; alreadyPublished: number }>> {
  return apiAction("POST", `/api/form16/${encodeURIComponent(fy)}/forms`, {}, PAGES);
}

/** The deductor's TAN and PAN (both optional; blank clears). */
export async function saveDeductor(i: { tan: string; pan: string }): Promise<ActionResult> {
  return apiAction("PUT", "/api/form16/deductor", i, PAGES);
}
