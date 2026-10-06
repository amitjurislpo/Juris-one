import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtDL, todayIn } from "@/lib/format";
import { listState, pick } from "@/lib/list";
import { DeleteHoliday, HolidayPlanner } from "./HolidaysClient";

export const metadata: Metadata = { title: "Holiday calendar" };

const SORTS = ["k", "n", "region", "by"] as const;
const RCHIP = { IN: "gold", US: "info", ALL: "ok" } as const;
const RLABEL = { IN: "India team", US: "US team", ALL: "Everyone" } as const;

export default async function HolidaysPage(props: PageProps<"/holidays">) {
  const u = await requirePage("holidays");
  const sp = await props.searchParams;
  const ls = listState(sp, SORTS, "k");
  const region = pick(sp, "region", ["All", "IN", "US"] as const, "All");
  const today = todayIn(u.timeZone);

  const all = await db.holiday.findMany({ include: { createdBy: { select: { fullName: true } } }, orderBy: { date: "asc" } });
  const items = all.map((h) => ({ id: h.id, k: dateToIso(h.date), n: h.name, region: h.region, by: h.createdBy?.fullName ?? "—" }));

  const q = ls.q.toLowerCase();
  const rows = items
    .filter((h) => (region === "All" || h.region === region || h.region === "ALL") && (!q || h.n.toLowerCase().includes(q)))
    .sort((a, b) => (a[ls.sort] > b[ls.sort] ? 1 : a[ls.sort] < b[ls.sort] ? -1 : 0) * (ls.dir === "asc" ? 1 : -1));

  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title="Holiday" em="calendar" sub="India and US teams each see their own holidays — plus the ones marked for everyone." />
      <HolidayPlanner holidays={items.map(({ k, n, region: r }) => ({ k, n, region: r }))} today={today} />
      <div className="toolbar enter" style={{ ["--i" as string]: 3 }}>
        <SearchBox placeholder="Search holidays" label="Search holidays" />
        <SegFilter name="region" dflt="All" label="Team" options={[{ v: "All", l: "All teams" }, { v: "IN", l: "India" }, { v: "US", l: "US" }]} />
        <span className="tcount">{rows.length} holidays</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 4 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="k" dflt="k">Date</SortTh>
                  <SortTh k="n" dflt="k">Holiday</SortTh>
                  <SortTh k="region" dflt="k">Applies to</SortTh>
                  <SortTh k="by" dflt="k">Added by</SortTh>
                  <th><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((h) => {
                  const past = h.k < today;
                  return (
                    <tr key={h.id} className={past ? "dim" : undefined}>
                      <td className="num">{fmtDL(h.k)}</td>
                      <td><b style={{ fontWeight: 600 }}>{h.n}</b></td>
                      <td><span className={`chip nodot ${RCHIP[h.region]}`}>{RLABEL[h.region]}</span></td>
                      <td className="muted">{h.by}</td>
                      <td className="r">{past ? <span className="faint" style={{ fontSize: 12 }}>Passed</span> : <DeleteHoliday id={h.id} name={h.n} />}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No holidays match</b><span className="muted">Try another search.</span><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
