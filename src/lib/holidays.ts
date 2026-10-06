import "server-only";
import { db } from "./db";
import { dateToIso, isoToDate } from "./format";

/** Holidays an employee sees: their own region plus "Everyone" (REQUIREMENTS §5.5). */
export async function upcomingHolidays(region: "IN" | "US", fromIso: string, take = 6) {
  const rows = await db.holiday.findMany({ where: { date: { gte: isoToDate(fromIso) }, region: { in: ["ALL", region] } }, orderBy: { date: "asc" }, take });
  return rows.map((h) => ({ id: h.id, date: dateToIso(h.date), name: h.name, region: h.region }));
}
