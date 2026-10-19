import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import type { Announcement } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { ManageTable, NewAnnouncementButton } from "./ManageClient";

export const metadata: Metadata = { title: "Post announcements" };

/** HR / the owner: post, edit and unpublish announcements. */
export default async function ManageAnnouncementsPage() {
  await requirePage("manage-announcements");
  const rows = await apiData<Announcement[]>("/api/announcements?scope=all");

  return (
    <div className="col">
      <PageHeader eyebrow="People ops" title={<>Post <em>announcements</em></>}
        sub="Posting shows the announcement on everyone's Home page and sends each person a notification.">
        <NewAnnouncementButton />
      </PageHeader>
      <ManageTable rows={rows} />
    </div>
  );
}
