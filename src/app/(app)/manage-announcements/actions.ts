"use server";

import type { ActionResult } from "@/lib/action";
import { apiAction } from "@/lib/backend";

// Announcements run in the Spring Boot API (/api/announcements).
const PAGES = ["/manage-announcements", "/announcements", "/home", "/overview", "/notifications"];

export interface AnnouncementInput { title: string; body: string; audience: string; pinned: boolean; expiresOn: string }

export async function createAnnouncement(i: AnnouncementInput): Promise<ActionResult<{ id: string }>> {
  return apiAction("POST", "/api/announcements", i, PAGES);
}

export async function updateAnnouncement(id: string, i: AnnouncementInput): Promise<ActionResult> {
  return apiAction("PUT", `/api/announcements/${encodeURIComponent(id)}`, i, PAGES);
}

export async function setAnnouncementStatus(id: string, status: "PUBLISHED" | "UNPUBLISHED"): Promise<ActionResult> {
  return apiAction("PUT", `/api/announcements/${encodeURIComponent(id)}/status`, { status }, PAGES);
}
