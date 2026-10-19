import Link from "next/link";
import { PolicyText } from "@/app/(app)/my-policies/PolicyText";
import { Icon } from "@/components/Icon";
import type { Announcement } from "@/lib/api-types";
import { fmtDY } from "@/lib/format";

/** One announcement: title, pin, audience, posted date and the plain-text body. */
export function AnnouncementItem({ a, full = false }: { a: Announcement; full?: boolean }) {
  return (
    <div style={{ padding: "10px 0", borderBottom: "1px solid var(--line)" }}>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <b style={{ fontSize: 14 }}>{a.title}</b>
        {a.pinned && <span className="chip gold nodot">Pinned</span>}
        {a.audience !== "ALL" && <span className="chip neutral nodot">{a.audience === "IN" ? "India" : "US"}</span>}
      </div>
      <div className="faint" style={{ fontSize: 12, margin: "2px 0 6px" }}>{fmtDY(a.createdAt.slice(0, 10))}{a.author ? ` · ${a.author}` : ""}{a.expiresOn ? ` · until ${fmtDY(a.expiresOn)}` : ""}</div>
      {full ? <PolicyText body={a.body} /> : <p style={{ margin: 0, fontSize: 13.5, lineHeight: 1.5, whiteSpace: "pre-wrap" }}>{a.body.length > 160 ? `${a.body.slice(0, 160).trimEnd()}…` : a.body}</p>}
    </div>
  );
}

/** Home card: the latest active announcements (pinned first). Hidden when there are none. */
export function AnnouncementsCard({ items, i }: { items: Announcement[]; i: number }) {
  if (!items.length) return null;
  return (
    <section className="card pad enter" style={{ ["--i" as string]: i }}>
      <span className="spot"></span>
      <div className="card-h"><h3><Icon n="bell" s={15} /> Announcements</h3><Link className="btn btn-sm btn-ghost" href="/announcements">All</Link></div>
      {items.map((a) => <AnnouncementItem key={a.id} a={a} />)}
    </section>
  );
}
