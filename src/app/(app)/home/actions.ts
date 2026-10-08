"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

/**
 * Check in / check out (`self`), recorded by the API. `expect` is the action the user saw on the button, so a
 * double-click or a stale tab can't record the opposite of what they meant.
 */
export async function punch(expect: "IN" | "OUT"): Promise<ActionResult<{ kind: "IN" | "OUT" }>> {
  return apiAction("POST", "/api/attendance/punches", { expect }, ["/home", "/attendance"]);
}
