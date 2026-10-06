import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { SegFilter } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtDL, MON3 } from "@/lib/format";
import { pick } from "@/lib/list";
import { PERMISSIONS } from "@/lib/permissions";
import { getSetting } from "@/lib/settings";
import { AttendancePolicyForm } from "./AttendancePolicyForm";

export const metadata: Metadata = { title: "Policies" };

const TABS = ["access", "leave", "hol", "att"] as const;
const ORDER = ["admin", "hrm", "hre", "fm", "fe", "employee"];
const num = (d: { toString(): string } | null) => (d == null ? 0 : Number(d.toString()));

export default async function SettingsPage(props: PageProps<"/settings">) {
  await requirePage("settings");
  const tab = pick(await props.searchParams, "tab", TABS, "access");

  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title="Policies &" em="access" sub="Who can see what, and the rules the portal runs on.">
        <SegFilter name="tab" dflt="access" label="Policy section" options={[{ v: "access", l: "Access" }, { v: "leave", l: "Leave policy" }, { v: "hol", l: "Holidays" }, { v: "att", l: "Attendance" }]} />
      </PageHeader>
      {tab === "access" && <AccessMatrix />}
      {tab === "leave" && <LeavePolicy />}
      {tab === "hol" && <HolidayList />}
      {tab === "att" && <AttendancePolicy />}
    </div>
  );
}

async function AccessMatrix() {
  const roles = await db.role.findMany({ include: { permissions: true }, orderBy: { createdAt: "asc" } });
  roles.sort((a, b) => (ORDER.indexOf(a.key) + 1 || 99) - (ORDER.indexOf(b.key) + 1 || 99));
  return (
    <>
      <p className="muted enter" style={{ margin: 0, fontSize: 13.5 }}>
        Read-only summary generated from the roles table. Edit permissions in <Link href="/roles" style={{ color: "var(--accenttext)" }}>Roles &amp; permissions</Link>.
      </p>
      <section className="card enter">
        <div className="table-wrap">
          <table className="t matrix">
            <thead><tr><th>Permission</th>{roles.map((r) => <th key={r.id}>{r.name}</th>)}</tr></thead>
            <tbody>
              {PERMISSIONS.map(([p, l], i) => (
                <tr key={p} style={{ animationDelay: `${i * 30}ms` }}>
                  <td><b style={{ fontWeight: 500 }}>{l}</b></td>
                  {roles.map((r) => {
                    const yes = r.permissions.some((x) => x.permissionKey === p);
                    return <td key={r.id}><span className={yes ? "yes" : "no"} aria-label={yes ? "Yes" : "No"}>{yes ? "✓" : "—"}</span></td>;
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

async function LeavePolicy() {
  const types = await db.leaveType.findMany({ where: { active: true }, orderBy: { sortOrder: "asc" } });
  const att = await getSetting("attendance.policy");
  const card = (t: (typeof types)[number]): [string, string] => {
    if (t.code === "LOP") return ["Automatic", `Absent without leave · ${att.lateMarksPerHalfDayLop} late marks = ½ day · Gross ÷ days in month`];
    if (t.code === "CO") return ["On approval", `For holiday / weekend work of ${att.halfDayHours}h+ · expires in ${t.expiryDays ?? 60} days`];
    const parts = [`${num(t.accrualPerMonth)} per month`, num(t.carryForwardCap) ? `carry up to ${num(t.carryForwardCap)}` : "no carry forward"];
    if (t.maxStretch) parts.push(`max ${num(t.maxStretch)} at a stretch`);
    if (num(t.encashPerYear)) parts.push(`encash ${num(t.encashPerYear)} a year after ${t.encashAfterMonths} months`);
    return [`${num(t.annualQuota)} a year`, parts.join(" · ")];
  };
  return (
    <div className="kpis">
      {types.map((t, i) => {
        const [eyebrow, text] = card(t);
        return (
          <div key={t.code} className="card pad lift enter" style={{ ["--i" as string]: i }}>
            <span className="spot"></span>
            <span className="eyebrow">{eyebrow}</span>
            <h3 className="display" style={{ fontSize: 26, margin: "6px 0" }}>{t.name}</h3>
            <p className="muted" style={{ margin: 0, fontSize: 13.5, lineHeight: 1.6 }}>{text}</p>
          </div>
        );
      })}
    </div>
  );
}

async function HolidayList() {
  const year = new Date().getUTCFullYear();
  const hol = await db.holiday.findMany({ where: { date: { gte: new Date(Date.UTC(year, 0, 1)), lt: new Date(Date.UTC(year + 1, 0, 1)) } }, orderBy: { date: "asc" } });
  return (
    <section className="card pad enter">
      <div className="list">
        {hol.map((h) => {
          const k = dateToIso(h.date);
          return (
            <div className="it" key={h.id}>
              <span className="hol-date"><b>{h.date.getUTCDate()}</b><small>{MON3[h.date.getUTCMonth()].toUpperCase()}</small></span>
              <div style={{ flex: 1 }}><b style={{ fontWeight: 600 }}>{h.name}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtDL(k)}</div></div>
              <span className={`chip nodot ${h.region === "US" ? "info" : h.region === "ALL" ? "ok" : "gold"}`}>{h.region === "ALL" ? "Everyone" : h.region === "US" ? "US" : "India"}</span>
            </div>
          );
        })}
        {!hol.length && <div className="faint">No holidays for {year} yet.</div>}
      </div>
    </section>
  );
}

async function AttendancePolicy() {
  const [p, shifts, lockedRuns] = await Promise.all([getSetting("attendance.policy"), db.shift.findMany({ orderBy: { code: "asc" } }), db.jobRun.count({ where: { name: "attendance.lock" } })]);
  const grc = shifts[0]?.graceMinutes ?? p.graceMinutes;
  const t = (hm: string) => { const [h, m] = hm.split(":").map(Number); return `${((h + 11) % 12) + 1}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`; };
  const grace = (hm: string, g: number) => { const [h, m] = hm.split(":").map(Number); const x = h * 60 + m + g; return t(`${Math.floor(x / 60) % 24}:${x % 60}`); };
  const days = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  return (
    <>
    <AttendancePolicyForm locked={lockedRuns > 0} initial={{ fullDayHours: p.fullDayHours, halfDayHours: p.halfDayHours, graceMinutes: grc, lateMarksPerHalfDayLop: p.lateMarksPerHalfDayLop, weeklyOffs: p.weeklyOffs, absenceJobBufferMinutes: p.absenceJobBufferMinutes, lockDayOfNextMonth: p.lockDayOfNextMonth, lockTime: p.lockTime, trackingStartDate: p.trackingStartDate }} />
    <section className="card pad enter">
      {shifts.map((s) => (
        <div className="kv" key={s.id}><span className="muted">{s.name} shift</span><b>{t(s.startLocal)} – {t(s.endLocal)}{s.region === "US" ? " local" : " IST"} · grace till {grace(s.startLocal, s.graceMinutes)}</b></div>
      ))}
      <div className="kv"><span className="muted">Full day / half day</span><b>{p.fullDayHours}h+ / {p.halfDayHours}h – {p.fullDayHours}h worked</b></div>
      <div className="kv"><span className="muted">Late marks</span><b>Every check-in after grace · {p.lateMarksPerHalfDayLop} in a month = ½ day loss of pay</b></div>
      <div className="kv"><span className="muted">Weekly off</span><b>{p.weeklyOffs.map((d) => days[d]).join(" & ")}</b></div>
      <div className="kv"><span className="muted">Monthly attendance lock</span><b>{p.lockDayOfNextMonth === 1 ? "1st" : `Day ${p.lockDayOfNextMonth}`} of next month, {t(p.lockTime)} IST</b></div>
      <div className="kv"><span className="muted">US team</span><b>Day shift, local time · holidays from the US calendar</b></div>
      <div className="kv"><span className="muted">Regularization approvers</span><b>HR, Finance or Admin — the first decision counts</b></div>
      <div className="kv"><span className="muted">Admin accounts</span><b>No attendance or leave tracking</b></div>
      <div className="kv"><span className="muted">Salary visibility</span><b>Finance &amp; Admin; employees see their own payslip</b></div>
    </section>
    </>
  );
}
