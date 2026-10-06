"use server";

import { getCurrentUser } from "@/lib/auth";
import { db } from "@/lib/db";

/** Opening the bell marks your own notifications as read. */
export async function markNotificationsRead() {
  const u = await getCurrentUser();
  if (!u) return;
  await db.notification.updateMany({ where: { employeeId: u.id, readAt: null }, data: { readAt: new Date() } });
}
