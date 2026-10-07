import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDL } from "@/lib/format";
import { DeleteHoliday, HolidayPlanner } from "./HolidaysClient";

export const metadata: Metadata = { title: "Holiday calendar" };

/** Filtered (?q=&region=) and sorted by the API. */
interface Item { id: string; k: string; n: string; region: "IN" | "US" | "ALL"; by: string }
const RCHIP = { IN: "gold", US: "info", ALL: "ok" } as const;
const RLABEL = { IN: "India team", US: "US team", ALL: "Everyone" } as const;

export default async function HolidaysPage(props: PageProps<"/holidays">) {
  await requirePage("holidays");
  const { items, rows, today } = await apiData<{ items: Item[]; rows: Item[]; today: string }>(`/api/holidays${qs(await props.searchParams)}`);

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
