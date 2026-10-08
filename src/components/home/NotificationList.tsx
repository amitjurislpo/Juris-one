"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { markAllNotifications, markNotification } from "@/app/(app)/notification-actions";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import type { InboxItem } from "@/lib/api-types";
import { fmtDateTime } from "@/lib/format";

/**
 * Notifications, unread first. Opening one marks it read; each can be marked read / unread on its own. Policy
 * notifications stay until the policy is accepted.
 */
export function NotificationList({ items, timeZone, empty = "You're all caught up." }: { items: InboxItem[]; timeZone: string; empty?: string }) {
  const toast = useToast();
  const router = useRouter();
  const [busy, start] = useTransition();
  const mark = (id: string, read: boolean) => start(async () => {
    const r = await markNotification(id, read);
    if (!r.ok) toast(r.error, "bad");
    router.refresh();
  });
  const open = (n: InboxItem) => {
    if (!n.readAt && !n.sticky) void markNotification(n.id, true);
    if (n.link) router.push(n.link); else router.refresh();
  };
  if (!items.length) return <p className="muted" style={{ margin: "6px 0" }}>{empty}</p>;
  return (
    <ul style={{ listStyle: "none", margin: 0, padding: 0, display: "flex", flexDirection: "column" }}>
      {items.map((n) => {
        const unread = !n.readAt;
        return (
          <li key={n.id} style={{ display: "flex", gap: 10, alignItems: "flex-start", padding: "10px 4px", borderBottom: "1px solid var(--line)" }}>
            <span aria-hidden style={{ width: 8, height: 8, borderRadius: "50%", marginTop: 6, flex: "none", background: unread ? "var(--accent)" : "transparent", boxShadow: unread ? "none" : "inset 0 0 0 1px var(--line3)" }}></span>
            <button type="button" onClick={() => open(n)} style={{ all: "unset", cursor: "pointer", flex: 1, minWidth: 0 }} aria-label={`${unread ? "Unread: " : ""}${n.text}`}>
              <div style={{ fontWeight: unread ? 600 : 400, fontSize: 13.5, lineHeight: 1.45 }}>{n.text}</div>
              <div className="faint" style={{ fontSize: 12, marginTop: 2 }}>{fmtDateTime(new Date(n.createdAt), timeZone)}</div>
            </button>
            {n.sticky ? <Link href="/acknowledge" className="chip warn nodot" style={{ textDecoration: "none" }}>Accept to clear</Link>
              : <button type="button" className="btn btn-sm btn-ghost" disabled={busy} onClick={() => mark(n.id, unread)} aria-label={unread ? "Mark as read" : "Mark as unread"} title={unread ? "Mark as read" : "Mark as unread"}>
                <Icon n={unread ? "check" : "eye"} s={13} />
              </button>}
          </li>
        );
      })}
    </ul>
  );
}

export function MarkAllReadButton({ disabled }: { disabled?: boolean }) {
  const toast = useToast();
  const router = useRouter();
  const [busy, start] = useTransition();
  return (
    <button type="button" className="btn btn-sm" disabled={disabled || busy} onClick={() => start(async () => {
      const r = await markAllNotifications();
      toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad");
      router.refresh();
    })}><Icon n="check" s={13} />Mark all read</button>
  );
}

/** The dashboard card: the newest notifications, unread first. */
export function NotificationsCard({ items, unread, timeZone, i }: { items: InboxItem[]; unread: number; timeZone: string; i: number }) {
  return (
    <section className="card pad enter" style={{ ["--i" as string]: i }} aria-labelledby="notif-card-h">
      <div className="card-h">
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <h3 id="notif-card-h">Notifications</h3>
          {unread > 0 && <span className="chip gold nodot">{unread} unread</span>}
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          <MarkAllReadButton disabled={!unread} />
          <Link className="btn btn-sm btn-ghost" href="/notifications">View all</Link>
        </div>
      </div>
      <NotificationList items={items} timeZone={timeZone} />
    </section>
  );
}
