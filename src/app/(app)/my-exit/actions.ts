"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// The employee's own exit (Spring Boot /api/exits/me).

export async function resign(i: { reason: string; requestedLastDay: string }): Promise<ActionResult> {
  return apiAction("POST", "/api/exits/me", i, ["/my-exit"]);
}

export async function withdrawResignation(comment?: string): Promise<ActionResult> {
  return apiAction("PUT", "/api/exits/me/status", { status: "WITHDRAWN", comment }, ["/my-exit"]);
}
