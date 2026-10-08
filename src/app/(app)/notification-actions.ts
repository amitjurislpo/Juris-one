"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

const enc = encodeURIComponent;

/** Marks one of your notifications read (or unread again). Policy notifications clear only when the policy is accepted. */
export async function markNotification(id: string, read: boolean): Promise<ActionResult> {
  return apiAction("PATCH", `/api/notifications/${enc(id)}`, { read }, ["/notifications", "/home", "/overview"]);
}

/** Marks all of your notifications read, except policies still to accept. */
export async function markAllNotifications(): Promise<ActionResult<{ count: number }>> {
  return apiAction("PATCH", "/api/notifications", { read: true }, ["/notifications", "/home", "/overview"]);
}
