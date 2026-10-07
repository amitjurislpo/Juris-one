"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// The holiday calendar runs in the Spring Boot API (/api/holidays).
const PAGES = ["/holidays", "/home", "/attendance", "/register", "/settings", "/team"];

export async function addHoliday(input: { date: string; name: string; region: "IN" | "US" | "ALL" }): Promise<ActionResult> {
  return apiAction("POST", "/api/holidays", input, PAGES);
}

export async function deleteHoliday(id: string): Promise<ActionResult> {
  return apiAction("DELETE", `/api/holidays/${encodeURIComponent(id)}`, undefined, PAGES);
}
