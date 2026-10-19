import type { Metadata } from "next";
import Link from "next/link";
import { AnnouncementItem } from "@/components/home/AnnouncementsCard";
import { PageHeader } from "@/components/PageHeader";
import type { Announcement } from "@/lib/api-types";
import { can, requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";

export const metadata: Metadata = { title: "Announcements" };

/** Every active announcement for the signed-in user, pinned first. */
export default async function AnnouncementsPage() {
  const u = await requirePage("announcements");
  const items = await apiData<Announcement[]>("/api/announcements");

  return (
    <div className="col">
      <PageHeader eyebrow="Company" title={<>Announce<em>ments</em></>} sub="News from HR and the leadership team.">
        {can(u, "announcements.manage") && <Link className="btn" href="/manage-announcements">Manage announcements</Link>}
      </PageHeader>
      <section className="card pad enter" style={{ ["--i" as string]: 1 }}>
        {items.map((a) => <AnnouncementItem key={a.id} a={a} full />)}
        {!items.length && <div className="empty"><b className="display">No announcements</b><span className="muted">When HR posts something for everyone it will appear here.</span></div>}
      </section>
    </div>
  );
}
