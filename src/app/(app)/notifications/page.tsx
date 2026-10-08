import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { MarkAllReadButton, NotificationList } from "@/components/home/NotificationList";
import type { InboxPage } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";

export const metadata: Metadata = { title: "Notifications" };

/** Every notification of the signed-in user, unread first; 30 a page. */
export default async function NotificationsPage(props: PageProps<"/notifications">) {
  const u = await requirePage("notifications");
  const sp = await props.searchParams;
  const unreadOnly = sp.filter === "unread";
  const page = Math.max(1, Number(sp.page) || 1);
  const box = await apiData<InboxPage>(`/api/notifications?page=${page}${unreadOnly ? "&unread=true" : ""}`);
  const href = (p: number, unread = unreadOnly) => `/notifications?${new URLSearchParams({ ...(unread ? { filter: "unread" } : {}), ...(p > 1 ? { page: String(p) } : {}) })}`;

  return (
    <div className="col">
      <PageHeader eyebrow="Inbox" title={<>Notifi<em>cations</em></>} sub="Notifications stay here until you read them. Policies to accept stay until you accept them.">
        <MarkAllReadButton disabled={!box.unread} />
      </PageHeader>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <div className="seg" role="tablist" aria-label="Show">
          <Link role="tab" aria-selected={!unreadOnly} className={!unreadOnly ? "on" : ""} href={href(1, false)}>All</Link>
          <Link role="tab" aria-selected={unreadOnly} className={unreadOnly ? "on" : ""} href={href(1, true)}>Unread{box.unread ? ` (${box.unread})` : ""}</Link>
        </div>
        <span className="tcount">{box.total} notification{box.total === 1 ? "" : "s"}</span>
      </div>
      <section className="card pad enter" style={{ ["--i" as string]: 2 }}>
        <NotificationList items={box.items} timeZone={u.timeZone} empty={unreadOnly ? "No unread notifications." : "No notifications yet."} />
        {box.pages > 1 && (
          <div className="pager">
            <span>Page {box.page} of {box.pages}</span>
            <span style={{ display: "flex", gap: 6 }}>
              {box.page > 1 && <Link className="btn btn-sm" href={href(box.page - 1)}>Newer</Link>}
              {box.page < box.pages && <Link className="btn btn-sm" href={href(box.page + 1)}>Older</Link>}
            </span>
          </div>
        )}
      </section>
    </div>
  );
}
