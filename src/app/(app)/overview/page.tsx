import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { allowedNav, can, requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { AnnouncementsCard } from "@/components/home/AnnouncementsCard";
import { NotificationsCard } from "@/components/home/NotificationList";
import type { Announcement, InboxPage } from "@/lib/api-types";
import { fmtDL, inr, todayIn, ymLabel } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.overview };

type Overview = {
  head: number; india: number; us: number; accessOff: number; deptCount: number; inNow: number;
  pending: { leave: number; reg: number; proofs: number };
  payroll: { month: string; status: "Closed" | "Approved" | "In review"; lastClosed: string | null; net: number; people: number; noStructure: number };
  byDept: { name: string; n: number }[];
};

export default async function OverviewPage() {
  const u = await requirePage("overview");
  const t = await getT();
  const [d, box, news] = await Promise.all([apiData<Overview>("/api/overview"), apiData<InboxPage>("/api/notifications?limit=5"), apiData<Announcement[]>("/api/announcements?limit=3")]);
  const pending = d.pending.leave + d.pending.reg;
  const maxDept = Math.max(1, ...d.byDept.map((x) => x.n));
  const pay = d.payroll;
  const chip = pay.status === "Closed" ? "ok" : "warn";

  return (
    <div className="col">
      <PageHeader eyebrow={`${fmtDL(todayIn(u.timeZone))} · ${t("Administration")}`} title={t("Company")} em={t("overview")} sub={t("Everything HR and Finance are working on, in one place.")}>
        <Link className="btn" href="/register"><Icon n="grid" s={15} />{t("Attendance register")}</Link>
        <Link className="btn" href="/employees"><Icon n="users" s={15} />{t("Employees")}</Link>
        {can(u, "pay.view") && <Link className="btn btn-gold" href="/payroll"><Icon n="wallet" s={15} />{t("Open payroll")}</Link>}
      </PageHeader>

      <div className="kpis">
        <div className="card kpi lift enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="ico" style={{ background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="users" s={16} /></span><span className="l">{t("Headcount")}</span><span className="v num">{d.head}</span><span className="s">{t("{india} India · {us} US", { india: d.india, us: d.us })}{d.accessOff ? ` · ${t("{n} access off", { n: d.accessOff })}` : ""}</span></div>
        <div className="card kpi lift enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="ico" style={{ background: "var(--okbg)", color: "var(--ok)" }}><Icon n="clock" s={16} /></span><span className="l">{t("Checked in now")}</span><span className="v num">{d.inNow}</span><span className="s">{t("India + US teams")} · <Link href="/register" style={{ color: "inherit" }}>{t("see times")}</Link></span></div>
        <div className="card kpi lift enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="ico" style={{ background: "var(--warnbg)", color: "var(--warn)" }}><Icon n="check" s={16} /></span><span className="l">{t("Pending approvals")}</span><span className="v num">{pending}</span><span className="s">{t("{leave} leave · {reg} attendance", { leave: d.pending.leave, reg: d.pending.reg })}</span></div>
        <div className="card kpi lift enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="ico" style={{ background: "var(--infobg)", color: "var(--info)" }}><Icon n="inbox" s={16} /></span><span className="l">{t("Proofs to verify")}</span><span className="v num">{d.pending.proofs}</span><span className="s">{t("investment declarations")}</span></div>
      </div>

      <AnnouncementsCard items={news} i={5} />
      {(box.total > 0) && <NotificationsCard items={box.items} unread={box.unread} timeZone={u.timeZone} i={5} />}

      <div className="bento">
        <section className="card pad span-8 enter" style={{ ["--i" as string]: 5 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("Payroll · {month}", { month: ymLabel(pay.month) })}</h3><span className={`chip ${chip}`}>{pay.status === "Closed" ? t("Closed") : pay.status === "Approved" ? t("Approved") : t("In review")}</span></div>
          <div style={{ display: "flex", alignItems: "baseline", gap: 12, flexWrap: "wrap" }}>
            <span className="display num" style={{ fontSize: 48, lineHeight: 1 }}>{inr(pay.net)}</span>
            <span className="muted">{t(pay.people === 1 ? "net payout · {n} employee" : "net payout · {n} employees", { n: pay.people })}</span>
          </div>
          <p className="muted" style={{ margin: "12px 0 16px", fontSize: 13.5 }}>
            {pay.noStructure ? `${t(pay.noStructure === 1 ? "{n} employee has no salary structure yet." : "{n} employees have no salary structure yet.", { n: pay.noStructure })} ` : ""}
            {pay.status === "Closed" ? t("Payslips are published to employees.") : t("Payslips are published to employees as soon as Finance closes payroll.")}
          </p>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {can(u, "pay.view") && <Link className="btn btn-gold" href="/payroll">{t("Review payroll")}</Link>}
            {can(u, "struct.manage") && <Link className="btn" href="/structures">{t("Salary structures")}</Link>}
            {can(u, "tax.view") && <Link className="btn" href="/tax">{t("Tax & TDS")}</Link>}
          </div>
        </section>

        <section className="card pad span-4 enter" style={{ ["--i" as string]: 6 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("People by department")}</h3><Link className="btn btn-sm btn-ghost" href="/departments">{t("Manage")}</Link></div>
          <div className="list">
            {d.byDept.map((x) => (
              <div className="it" key={x.name}>
                <span style={{ flex: 1, minWidth: 0 }}>{x.name}</span>
                <span style={{ width: 90, height: 6, borderRadius: 9, background: "var(--hover)", overflow: "hidden" }} aria-hidden="true"><i style={{ display: "block", height: "100%", width: `${(x.n / maxDept) * 100}%`, background: "var(--accentgrad)" }}></i></span>
                <b className="num" style={{ width: 28, textAlign: "right" }}>{x.n}</b>
              </div>
            ))}
            {!d.byDept.length && <div className="faint" style={{ fontSize: 13 }}>{t("No departments yet.")}</div>}
          </div>
        </section>

        <section className="card pad span-6 enter" style={{ ["--i" as string]: 7 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("HR queue")}</h3>{can(u, "appr.leave", "appr.reg") && <Link className="btn btn-sm btn-ghost" href="/approvals">{t("Open")}</Link>}</div>
          <p className="muted" style={{ margin: 0, fontSize: 13.5 }}>
            {pending ? t("{leave} and {reg} are waiting.", { leave: t(d.pending.leave === 1 ? "{n} leave request" : "{n} leave requests", { n: d.pending.leave }), reg: t(d.pending.reg === 1 ? "{n} regularization" : "{n} regularizations", { n: d.pending.reg }) }) : t("No leave or attendance requests are waiting.")} {t("Leave is approved by HR; regularizations can be approved by HR, Finance or Admin — the first decision wins.")}
          </p>
        </section>

        <section className="card pad span-6 enter" style={{ ["--i" as string]: 8 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("Finance queue")}</h3>{can(u, "proofs.verify") && <Link className="btn btn-sm btn-ghost" href="/proofs">{t("Open")}</Link>}</div>
          <p className="muted" style={{ margin: 0, fontSize: 13.5 }}>
            {d.pending.proofs ? t(d.pending.proofs === 1 ? "{n} investment proof is pending verification." : "{n} investment proofs are pending verification.", { n: d.pending.proofs }) : t("No investment proofs are waiting.")} {t("Approved proofs reduce TDS from the next payroll.")}
          </p>
        </section>

        <section className="card pad span-12 enter" style={{ ["--i" as string]: 9 }}>
          <span className="spot"></span>
          <div className="card-h"><h3>{t("Shortcuts")}</h3></div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {allowedNav(u).filter((n) => n.group && n.group !== "Team").map((n) => <Link key={n.id} className="btn" href={`/${n.id}`}><Icon n={n.icon} s={15} />{t(n.label)}</Link>)}
          </div>
        </section>
      </div>
    </div>
  );
}
