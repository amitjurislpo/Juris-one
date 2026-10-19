import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { MarkAllReadButton, NotificationList } from "@/components/home/NotificationList";
import type { InboxPage } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { getT } from "@/lib/i18n/server";

export const metadata: Metadata = { title: "Notifications" };

/** Every notification of the signed-in user, unread first; 30 a page. */
export default async function NotificationsPage(props: PageProps<"/notifications">) {
  const u = await requirePage("notifications");
  const t = await getT();
  const sp = await props.searchParams;
  const unreadOnly = sp.filter === "unread";
  const page = Math.max(1, Number(sp.page) || 1);
  const box = await apiData<InboxPage>(`/api/notifications?page=${page}${unreadOnly ? "&unread=true" : ""}`);
  const href = (p: number, unread = unreadOnly) => `/notifications?${new URLSearchParams({ ...(unread ? { filter: "unread" } : {}), ...(p > 1 ? { page: String(p) } : {}) })}`;

  return (
    <div className="col">
      <PageHeader eyebrow={t("Inbox")} title={<>{t("Notifications")}</>} sub={t("Notifications stay here until you read them. Policies to accept stay until you accept them.")}>
        <MarkAllReadButton disabled={!box.unread} />
      </PageHeader>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <div className="seg" role="tablist" aria-label={t("Show")}>
          <Link role="tab" aria-selected={!unreadOnly} className={!unreadOnly ? "on" : ""} href={href(1, false)}>{t("All")}</Link>
          <Link role="tab" aria-selected={unreadOnly} className={unreadOnly ? "on" : ""} href={href(1, true)}>{t("Unread")}{box.unread ? ` (${box.unread})` : ""}</Link>
        </div>
        <span className="tcount">{t(box.total === 1 ? "{n} notification" : "{n} notifications", { n: box.total })}</span>
      </div>
      <section className="card pad enter" style={{ ["--i" as string]: 2 }}>
        <NotificationList items={box.items} timeZone={u.timeZone} empty={unreadOnly ? t("No unread notifications.") : t("No notifications yet.")} />
        {box.pages > 1 && (
          <div className="pager">
            <span>{t("Page {a} of {b}", { a: box.page, b: box.pages })}</span>
            <span style={{ display: "flex", gap: 6 }}>
              {box.page > 1 && <Link className="btn btn-sm" href={href(box.page - 1)}>{t("Newer")}</Link>}
              {box.page < box.pages && <Link className="btn btn-sm" href={href(box.page + 1)}>{t("Older")}</Link>}
            </span>
          </div>
        )}
      </section>
    </div>
  );
}
