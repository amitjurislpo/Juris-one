"use server";

import { apiCall } from "@/lib/backend";

/** Opening the bell marks your own notifications as read. */
export async function markNotificationsRead() {
  await apiCall("PATCH", "/api/notifications", { read: true });
}
