import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SegFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtD, fmtDL, MON3 } from "@/lib/format";
import { ApplyButton, CancelLeave } from "./LeaveClient";

export const metadata: Metadata = { title: "Leave & holidays" };

const STATUS = ["All", "PENDING", "APPROVED", "DECLINED", "CANCELLED"] as const;
const SLABEL: Record<string, string> = { PENDING: "Pending", APPROVED: "Approved", DECLINED: "Declined", CANCELLED: "Cancelled" };
const SCHIP: Record<string, string> = { PENDING: "warn", APPROVED: "ok", DECLINED: "bad", CANCELLED: "neutral" };
const PART: Record<string, string> = { FULL: "Full day", FIRST_HALF: "First half", SECOND_HALF: "Second half" };

function Ring({ value, max, color, i }: { value: number; max: number; color: string; i: number }) {
  const C = 207;
  const frac = Math.min(1, Math.max(0.03, max ? value / max : 0));
  return (
    <div className="ring">
      <svg width="78" height="78" viewBox="0 0 78 78" aria-hidden="true">
        <circle cx="39" cy="39" r="33" stroke="var(--line)" strokeWidth="6" fill="none"></circle>
        <circle cx="39" cy="39" r="33" stroke={color} strokeWidth="6" strokeLinecap="round" fill="none" transform="rotate(-90 39 39)" className="ring-arc" style={{ strokeDasharray: C, strokeDashoffset: C * (1 - frac), animationDelay: `${i * 0.12}s` }}></circle>
      </svg>
      <b>{Number.isInteger(value) ? value : value.toFixed(2)}</b>
    </div>
  );
}

export default async function LeavePage(props: PageProps<"/leave">) {
  await requirePage("leave");
  const sp = await props.searchParams;
  const d = await apiData<{
    tab: "requests" | "holidays"; st: (typeof STATUS)[number]; today: string; year: string; canApply: boolean; lopYear: number;
    balances: { code: string; name: string; balance: number; pending: number; paid: boolean; needsBalance: boolean; annual: number }[];
    requests: { id: string; typeName: string; leaveType: string; from: string; to: string; part: string; days: number; lopDays: number; applied: string; reason: string | null; status: string; decidedBy: string | null }[];
    holidays: { id: string; date: string; name: string }[]; holidayMap: Record<string, string>;
    types: { code: string; name: string; paid: boolean; available: number | null }[]; weeklyOffs: number[];
  }>(`/api/leave${qs(sp)}`);
  const { tab, st, today, year, balances: bals, requests: reqs, holidays: hols, holidayMap, types, lopYear } = d;

  const bal = (c: string) => bals.find((b) => b.code === c);
  const avail = (c: string) => { const b = bal(c); return b ? Math.round((b.balance - b.pending) * 100) / 100 : 0; };
  const rings: [string, number, number, string, string][] = [
    ["Casual leave", avail("CL"), bal("CL")?.annual || 12, "#6E56CF", "Accrues 1 a month"],
    ["Earned leave", avail("EL"), bal("EL")?.annual || 18, "#17834F", "Carry up to 30 · encashable"],
    ["Comp-off", avail("CO"), 5, "#0A6BC2", "For holiday work · 60 days"],
    ["Loss of pay", lopYear, 5, "#CF3A40", `Taken in ${year}`],
  ];

  return (
    <div className="col" style={{ gap: 24 }}>
      <PageHeader eyebrow={`Leave · ${year}`} title="Time away," em="well planned" sub="Apply in three steps — leave type, dates, and an optional reason.">
        {d.canApply && <ApplyButton types={types} today={today} holidays={holidayMap} weeklyOffs={d.weeklyOffs} autoOpen={sp.apply === "1"} />}
      </PageHeader>
      <div className="kpis">
        {rings.map(([name, v, max, color, note], i) => (
          <div className="card bal lift enter" key={name} style={{ ["--i" as string]: i + 1 }}>
            <span className="spot" aria-hidden="true"></span>
            <Ring value={v} max={max} color={color} i={i} />
            <div><b style={{ fontSize: 15 }}>{name}</b><div className="faint" style={{ fontSize: 12.5, marginTop: 4 }}>{note}</div></div>
          </div>
        ))}
      </div>
      <SegFilter name="tab" dflt="requests" label="Leave section" options={[{ v: "requests", l: "My requests" }, { v: "holidays", l: "Holiday calendar" }]} />
      {tab === "requests" ? (
        <>
          <div className="toolbar enter" style={{ ["--i" as string]: 6 }}>
            <SegFilter name="st" dflt="All" label="Status" options={STATUS.map((s) => ({ v: s, l: s === "All" ? "All" : SLABEL[s] }))} />
            <span className="tcount">{reqs.length} requests</span>
          </div>
          <section className="card enter" style={{ ["--i" as string]: 6 }}>
            <Pending>
              <div className="table-wrap">
                <table className="t">
                  <thead><tr>
                    <SortTh k="type" dflt="applied" dfltDir="desc">Type</SortTh>
                    <SortTh k="from" dflt="applied" dfltDir="desc">Dates</SortTh>
                    <SortTh k="days" dflt="applied" dfltDir="desc">Days</SortTh>
                    <SortTh k="applied" dflt="applied" dfltDir="desc">Applied</SortTh>
                    <th>Reason</th>
                    <SortTh k="status" dflt="applied" dfltDir="desc">Status</SortTh>
                    <th><span className="sr-only">Actions</span></th>
                  </tr></thead>
                  <tbody>
                    {reqs.map((l, i) => {
                      const f = l.from, t = l.to;
                      return (
                        <tr key={l.id} style={{ animationDelay: `${i * 50}ms` }}>
                          <td><b>{l.typeName}</b></td>
                          <td>{fmtD(f)}{t !== f ? ` – ${fmtD(t)}` : ""}<div className="faint" style={{ fontSize: 12 }}>{PART[l.part]}{l.lopDays > 0 && l.leaveType !== "LOP" ? ` · ${l.lopDays}d unpaid` : ""}</div></td>
                          <td className="num">{l.days}</td>
                          <td className="muted">{fmtD(l.applied)}</td>
                          <td className="muted" style={{ maxWidth: 240 }}>{l.reason || "—"}</td>
                          <td><span className={`chip ${SCHIP[l.status]}`}>{SLABEL[l.status]}</span>{l.decidedBy && <div className="faint" style={{ fontSize: 11.5, marginTop: 3 }}>by {l.decidedBy}</div>}</td>
                          <td className="r">{l.status === "PENDING" && <CancelLeave id={l.id} />}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {!reqs.length && <div className="empty"><b>No requests</b><span className="muted">{st === "All" ? "You haven't applied for leave yet." : "Nothing with this status."}</span>{st !== "All" && <ClearFilters />}</div>}
            </Pending>
          </section>
        </>
      ) : (
        <section className="kpis enter" style={{ ["--i" as string]: 6 }}>
          {hols.map((h, i) => {
            const k = h.date;
            const past = k < today;
            return (
              <div className="card pad lift" key={h.id} style={{ opacity: past ? 0.5 : 1, animation: `enter .6s ${i * 50}ms both`, display: "flex", gap: 14, alignItems: "center" }}>
                <span className="spot" aria-hidden="true"></span>
                <span className="hol-date"><b>{Number(k.slice(8))}</b><small>{MON3[Number(k.slice(5, 7)) - 1].toUpperCase()}</small></span>
                <div><b>{h.name}</b><div className="faint" style={{ fontSize: 12.5 }}>{fmtDL(k)}{past ? " · passed" : ""}</div></div>
              </div>
            );
          })}
          {!hols.length && <div className="empty"><b>No holidays yet</b><span className="muted">HR hasn&apos;t published this year&apos;s calendar.</span></div>}
        </section>
      )}
    </div>
  );
}
