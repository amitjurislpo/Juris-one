import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDL, MON3, MONTHS } from "@/lib/format";
import { AttendanceCalendar, type CalDay, type RegView } from "./AttendanceClient";

export const metadata: Metadata = { title: "My attendance" };

const shiftYm = (ym: string, n: number) => new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5, 7)) - 1 + n, 1)).toISOString().slice(0, 7);

export default async function AttendancePage(props: PageProps<"/attendance">) {
  await requirePage("attendance");
  const t = await getT();
  const d = await apiData<{
    tracked: boolean; ym: string; minYm: string; wd: string; locked: boolean; initialSel: string;
    summary: { present: number; absent: number; leave: number; holidays: number; weeklyOffs: number; workedMinutes: number; lateMarks: number; lateLopDays: number };
    days: CalDay[]; regs: RegView[]; holidays: { id: string; date: string; name: string }[];
  }>(`/api/attendance/me${qs(await props.searchParams)}`);
  if (!d.tracked) return <div className="col"><PageHeader eyebrow={t("Attendance")} title={t("My")} em={t("attendance")} /><ComingInPhase phase={2} t={t} what={t("Attendance isn't tracked for your account.")} /></div>;

  const { ym, minYm, wd, locked, initialSel, summary: s, days: cal, regs: regView, holidays: hols } = d;
  const prev = shiftYm(ym, -1), next = shiftYm(ym, 1);

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow={t("Attendance")} title={t(MONTHS[Number(ym.slice(5)) - 1])} em={ym.slice(0, 4)} sub={t("Tap any day to see its check-in, check-out and how it counts.")}>
        {prev >= minYm ? <Link className="icon-btn" href={`/attendance?m=${prev}`} aria-label={t("Previous month")}><Icon n="chevL" /></Link> : <span className="icon-btn" aria-disabled="true" style={{ opacity: 0.4 }}><Icon n="chevL" /></span>}
        {next <= wd.slice(0, 7) ? <Link className="icon-btn" href={`/attendance?m=${next}`} aria-label={t("Next month")}><Icon n="chevR" /></Link> : <span className="icon-btn" aria-disabled="true" style={{ opacity: 0.4 }}><Icon n="chevR" /></span>}
      </PageHeader>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--ok)" }}></i> {t("Present")}</span><span className="v num">{s.present}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--bad)" }}></i> {t("Absent · loss of pay")}</span><span className="v num">{s.absent}</span>{s.lateMarks > 0 && <span className="s">{s.lateMarks === 1 ? t("{n} late mark", { n: s.lateMarks }) : t("{n} late marks", { n: s.lateMarks })}{s.lateLopDays ? ` · ${t("+{n} day LOP", { n: s.lateLopDays })}` : ""}</span>}</div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> {t("On leave")}</span><span className="v num">{s.leave}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--gold)" }}></i> {t("Holidays · weekly off")}</span><span className="v">{s.holidays} · {s.weeklyOffs}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 5 }}><span className="spot" aria-hidden="true"></span><span className="l"><i className="dot" style={{ color: "var(--text)" }}></i> {t("Hours worked")}</span><span className="v num">{(s.workedMinutes / 60).toFixed(1)}</span></div>
      </div>
      <AttendanceCalendar key={ym} days={cal} regs={regView} initialSel={initialSel} locked={locked} monthLabel={`${t(MONTHS[Number(ym.slice(5)) - 1])} ${ym.slice(0, 4)}`} />
      <section className="card pad enter" style={{ ["--i" as string]: 8 }}>
        <span className="spot" aria-hidden="true"></span>
        <div className="card-h"><div><h3>{t("Upcoming holidays")}</h3><span className="faint" style={{ fontSize: 12.5 }}>{t("Official holidays for your team, set by HR")}</span></div></div>
        <div className="up-hol">
          {hols.map((h, i) => (
            <div className="it" key={h.id} style={{ animationDelay: `${i * 50}ms` }}>
              <span className="hol-date"><b>{Number(h.date.slice(8))}</b><small>{MON3[Number(h.date.slice(5, 7)) - 1].toUpperCase()}</small></span>
              <div style={{ minWidth: 0 }}><b style={{ fontSize: 13.5, fontWeight: 600 }}>{h.name}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtDL(h.date)}</div></div>
            </div>
          ))}
          {!hols.length && <div className="faint">{t("No more holidays this year.")}</div>}
        </div>
      </section>
    </div>
  );
}
