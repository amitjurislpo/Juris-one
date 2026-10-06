import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { isoToDate, todayIn } from "../format";
import { listState, pick, str, type SearchParams } from "../list";
import { addDays, dowOf, localParts, summarize } from "./engine";
import { attendanceFor, attendancePolicy, loadAttEmployees, type AttEmployee, type DayView } from "./service";

export const ZONES = [
  { v: "Asia/Kolkata", l: "IST · India" },
  { v: "America/New_York", l: "ET · New York" },
  { v: "America/Chicago", l: "CT · Chicago" },
  { v: "America/Los_Angeles", l: "PT · Los Angeles" },
  { v: "UTC", l: "UTC" },
  { v: "local", l: "Each employee’s local time" },
] as const;

export const DAY_STATUSES = ["All", "Present", "Working", "Not checked in", "Absent", "On leave", "Holiday", "Weekly off", "Half day"] as const;
const DAY_SORTS = ["name", "loc", "st", "inn", "out", "h"] as const;
const MONTH_SORTS = ["name", "P", "A", "L"] as const;

export function registerParams(sp: SearchParams, viewerTz: string) {
  const today = todayIn("Asia/Kolkata");
  const view = pick(sp, "view", ["day", "month"] as const, "day");
  let day = str(sp, "day", today);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || day > today) day = today;
  let ym = str(sp, "ym", today.slice(0, 7));
  if (!/^\d{4}-\d{2}$/.test(ym) || ym > today.slice(0, 7)) ym = today.slice(0, 7);
  const tz = pick(sp, "tz", ZONES.map((z) => z.v), (ZONES.some((z) => z.v === viewerTz) ? viewerTz : "Asia/Kolkata") as (typeof ZONES)[number]["v"]);
  return {
    view, day, ym, tz, today,
    region: pick(sp, "region", ["All", "IN", "US"] as const, "All"),
    dept: str(sp, "dept", "All"),
    st: pick(sp, "st", DAY_STATUSES, "All"),
    ls: view === "day" ? listState(sp, DAY_SORTS, "name", 50) : listState(sp, MONTH_SORTS, "name", 50),
  };
}
export type RegisterParams = ReturnType<typeof registerParams>;

function empWhere(p: RegisterParams, from: string, to: string): Prisma.EmployeeWhereInput {
  const and: Prisma.EmployeeWhereInput[] = [
    { dateOfJoining: { lte: isoToDate(to) } },
    { OR: [{ exitDate: null }, { exitDate: { gte: isoToDate(from) } }] },
  ];
  if (p.region !== "All") and.push({ region: p.region });
  if (p.dept !== "All" && /^[0-9a-f-]{36}$/.test(p.dept)) and.push({ departmentId: p.dept });
  if (p.ls.q) {
    const c = { contains: p.ls.q, mode: "insensitive" as const };
    and.push({ OR: [{ fullName: c }, { code: c }, { designation: c }] });
  }
  return { AND: and };
}

/** Daily-log status wording (prototype register). */
export function dayStatusLabel(d: DayView): string {
  switch (d.status) {
    case "PRESENT": return "Present";
    case "HALF_DAY": return "Half day";
    case "ABSENT": return "Absent";
    case "LEAVE": return "On leave";
    case "HOLIDAY": return "Holiday";
    case "WEEKLY_OFF": return "Weekly off";
    case "PENDING": return d.openSince ? "Working" : d.punches.length ? "Present" : "Not checked in";
    default: return d.label || "—";
  }
}

export interface Clock {
  text: string;
  local: string | null; // employee's own time when a different zone is selected
}

export function clock(at: Date | null, zone: string, e: AttEmployee, rowDay: string): Clock | null {
  if (!at) return null;
  const z = zone === "local" ? e.tz : zone;
  const fmt = (tz: string) => new Intl.DateTimeFormat("en-US", { timeZone: tz, hour: "numeric", minute: "2-digit", timeZoneName: "short" }).format(at).replace("GMT+5:30", "IST");
  const day = localParts(at, z).date;
  const shift = day === rowDay ? "" : day > rowDay ? " (+1)" : " (−1)";
  return { text: fmt(z) + shift, local: zone !== "local" && z !== e.tz ? fmt(e.tz) : null };
}

export async function dailyLog(p: RegisterParams) {
  const emps = await loadAttEmployees(empWhere(p, p.day, p.day));
  const all = await attendanceFor(emps, p.day, p.day);
  const rows = emps.map((e) => {
    const d = all.get(e.id)![0];
    return { e, d, st: dayStatusLabel(d), inn: clock(d.firstIn, p.tz, e, p.day), out: clock(d.lastOut, p.tz, e, p.day), h: d.workedMinutes / 60 };
  });
  const sum = {
    t: rows.length,
    p: rows.filter((r) => ["Present", "Working", "Half day"].includes(r.st)).length,
    n: rows.filter((r) => r.st === "Not checked in").length,
    l: rows.filter((r) => r.st === "On leave").length,
    a: rows.filter((r) => r.st === "Absent").length,
  };
  const filtered = rows.filter((r) => p.st === "All" || r.st === p.st || (p.st === "Present" && r.st === "Working"));
  const key = p.ls.sort as (typeof DAY_SORTS)[number];
  const get = {
    name: (r: (typeof rows)[number]) => r.e.name,
    loc: (r: (typeof rows)[number]) => `${r.e.region}${r.e.tz}`,
    st: (r: (typeof rows)[number]) => r.st,
    inn: (r: (typeof rows)[number]) => r.d.firstIn?.getTime() ?? 9e15,
    out: (r: (typeof rows)[number]) => r.d.lastOut?.getTime() ?? 9e15,
    h: (r: (typeof rows)[number]) => r.h,
  }[key];
  filtered.sort((a, b) => {
    const x = get(a), y = get(b);
    return (x > y ? 1 : x < y ? -1 : 0) * (p.ls.dir === "asc" ? 1 : -1);
  });
  return { rows: filtered, sum };
}

export const CODE: Record<string, [string, string]> = {
  PRESENT: ["P", "ok"], HALF_DAY: ["½", "warn"], ABSENT: ["A", "bad"], LEAVE: ["L", "info"], HOLIDAY: ["H", "gold"], WEEKLY_OFF: ["", "off"],
};

export function cellCode(d: DayView): { c: string; cls: string } {
  if (d.future) return { c: "", cls: "none" };
  if (d.status === "PENDING") return d.punches.length ? { c: "P", cls: "ok" } : d.live ? { c: "–", cls: "warn" } : { c: "", cls: "none" };
  const c = CODE[d.status];
  return c ? { c: c[0], cls: c[1] } : { c: "", cls: "none" };
}

export async function monthlyGrid(p: RegisterParams) {
  const policy = await attendancePolicy();
  const from = `${p.ym}-01`;
  const to = addDays(new Date(Date.UTC(Number(p.ym.slice(0, 4)), Number(p.ym.slice(5)), 1)).toISOString().slice(0, 10), -1);
  const emps = await loadAttEmployees(empWhere(p, from, to));
  const all = await attendanceFor(emps, from, to);
  const days: { k: string; d: number; dow: string; wk: boolean }[] = [];
  for (let k = from; k <= to; k = addDays(k, 1)) days.push({ k, d: Number(k.slice(8)), dow: "SMTWTFS"[dowOf(k)], wk: policy.weeklyOffs.includes(dowOf(k)) });
  const rows = emps.map((e) => {
    const ds = all.get(e.id)!;
    const s = summarize(ds.filter((x) => !x.future), policy);
    return { e, days: ds, cells: ds.map(cellCode), P: s.present, A: s.absent, L: s.leave };
  });
  const key = p.ls.sort as (typeof MONTH_SORTS)[number];
  rows.sort((a, b) => {
    const x = key === "name" ? a.e.name : a[key], y = key === "name" ? b.e.name : b[key];
    return (x > y ? 1 : x < y ? -1 : 0) * (p.ls.dir === "asc" ? 1 : -1);
  });
  return { rows, days, from, to };
}

export function registerMonths(trackingStart: string, today: string) {
  const out: string[] = [];
  for (let ym = trackingStart.slice(0, 7); ym <= today.slice(0, 7); ym = new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5)), 1)).toISOString().slice(0, 7)) out.push(ym);
  return out.reverse();
}
