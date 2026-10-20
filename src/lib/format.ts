/**
 * Formatting + calendar-date helpers shared by server and client.
 * Calendar dates travel as ISO strings ("2026-10-06"); `@db.Date` columns are UTC-midnight Dates.
 */
export const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
export const MON3 = MONTHS.map((m) => m.slice(0, 3));
export const DOW = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

const pad = (n: number) => String(n).padStart(2, "0");

/** ISO date ("YYYY-MM-DD") of `at` as seen in an IANA zone. */
export function isoInZone(at: Date, timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(at);
}

/** The calendar date of a UTC timestamp string as seen in `timeZone`. */
export const isoDateIn = (ts: string, timeZone: string) => isoInZone(new Date(ts), timeZone);

export const todayIn = (timeZone: string) => isoInZone(new Date(), timeZone);

/** `@db.Date` value → ISO date string. */
export const dateToIso = (d: Date) => d.toISOString().slice(0, 10);

/** ISO date string → `@db.Date` value (UTC midnight). */
export const isoToDate = (s: string) => new Date(`${s}T00:00:00.000Z`);

const parts = (s: string) => {
  const [y, m, d] = s.split("-").map(Number);
  return { y, m, d, dow: new Date(Date.UTC(y, m - 1, d)).getUTCDay() };
};

export const fmtD = (s: string) => { const p = parts(s); return `${p.d} ${MON3[p.m - 1]}`; };
export const fmtDY = (s: string) => { const p = parts(s); return `${p.d} ${MON3[p.m - 1]} ${p.y}`; };
export const fmtDL = (s: string) => { const p = parts(s); return `${DOW[p.dow]}, ${p.d} ${MON3[p.m - 1]} ${p.y}`; };
export const ymLabel = (ym: string) => { const [y, m] = ym.split("-").map(Number); return `${MONTHS[m - 1]} ${y}`; };

export function fmtDateTime(at: Date, timeZone: string) {
  return new Intl.DateTimeFormat("en-GB", { timeZone, day: "numeric", month: "short", hour: "numeric", minute: "2-digit", hour12: true }).format(at);
}

/** Whole-day difference b − a between two ISO dates. */
export function daysBetween(a: string, b: string) {
  return Math.round((isoToDate(b).getTime() - isoToDate(a).getTime()) / 86400_000);
}

/** Paise → "₹1,23,456" (Indian grouping). */
export const inr = (paise: number | bigint | null | undefined) =>
  "₹" + Math.round(Number(paise ?? 0) / 100).toLocaleString("en-IN");

export const fmtH = (h: number) => `${Math.floor(h)}h ${pad(Math.round((h % 1) * 60))}m`;

export const initials = (n: string) => n.split(/\s+/).filter(Boolean).map((x) => x[0]).slice(0, 2).join("").toUpperCase();

const AVC = ["c0", "c1", "c2", "c3", "c4"];
export const avc = (s: string) => { let h = 0; for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) % 997; return AVC[h % 5]; };
