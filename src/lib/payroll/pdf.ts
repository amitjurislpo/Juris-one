import "server-only";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { PDFDocument, rgb, StandardFonts, type PDFFont, type PDFPage } from "pdf-lib";
import { fmtDY, ymLabel } from "../format";
import type { PayslipSnapshot } from "./service";

// Standard PDF fonts can't draw "₹", so amounts are written "Rs." (as in the prototype PDF).
const rs = (paise: number) => "Rs. " + Math.round(paise / 100).toLocaleString("en-IN");
const INK = rgb(20 / 255, 20 / 255, 26 / 255);
const MUTED = rgb(110 / 255, 112 / 255, 125 / 255);
const FAINT = rgb(130 / 255, 132 / 255, 145 / 255);
const LINE = rgb(230 / 255, 230 / 255, 236 / 255);
const ACCENT = rgb(110 / 255, 86 / 255, 207 / 255);
const mm = (v: number) => (v * 72) / 25.4;

let logoBytes: Uint8Array | null = null;
async function logo() {
  if (!logoBytes) logoBytes = await readFile(path.join(process.cwd(), "public", "brand", "logo-ink.png"));
  return logoBytes;
}

/** A4 payslip PDF from an immutable snapshot (REQUIREMENTS §5.11). */
export async function payslipPdf(s: PayslipSnapshot): Promise<Uint8Array> {
  const doc = await PDFDocument.create();
  doc.setTitle(`Payslip ${s.employee.code} ${ymLabel(s.ym)}`);
  doc.setAuthor(s.company.name);
  doc.setCreator("JurisOne");
  const page = doc.addPage([mm(210), mm(297)]);
  const H = page.getHeight();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  const bold = await doc.embedFont(StandardFonts.HelveticaBold);
  const W = 210, M = 16;
  // y in millimetres from the top, like the prototype's jsPDF layout
  const text = (t: string, x: number, y: number, o: { size?: number; f?: PDFFont; color?: ReturnType<typeof rgb>; align?: "right" } = {}) => {
    const size = o.size ?? 10, f = o.f ?? font;
    const w = f.widthOfTextAtSize(t, size);
    page.drawText(t, { x: mm(x) - (o.align === "right" ? w : 0), y: H - mm(y), size, font: f, color: o.color ?? INK });
  };
  const line = (x1: number, y: number, x2: number, color = LINE, thickness = 0.6) => page.drawLine({ start: { x: mm(x1), y: H - mm(y) }, end: { x: mm(x2), y: H - mm(y) }, color, thickness });
  const rect = (x: number, y: number, w: number, h: number, color: ReturnType<typeof rgb>) => page.drawRectangle({ x: mm(x), y: H - mm(y + h), width: mm(w), height: mm(h), color });

  let y = 16;
  try {
    const img = await doc.embedPng(await logo());
    const w = 46, h = (img.height / img.width) * w;
    page.drawImage(img, { x: mm(M), y: H - mm(y + h), width: mm(w), height: mm(h) });
  } catch { /* logo is decorative */ }
  text("PAYSLIP", W - M, y + 7, { size: 18, f: bold, align: "right" });
  text(ymLabel(s.ym), W - M, y + 13, { size: 11, color: MUTED, align: "right" });
  text(`${s.company.name} · ${s.company.address}`, W - M, y + 18.5, { size: 8.5, color: MUTED, align: "right" });
  y += 28;
  line(M, y, W - M, ACCENT, 2.2);
  y += 8;

  const kv = (x: number, yy: number, k: string, v: string) => {
    text(k.toUpperCase(), x, yy, { size: 7.5, color: FAINT });
    text(v, x, yy + 5, { size: 10.5, f: bold });
  };
  const cols = [M, M + 62, M + 124];
  const e = s.employee;
  kv(cols[0], y, "Employee", e.name); kv(cols[1], y, "Employee ID", e.code); kv(cols[2], y, "Designation", e.designation);
  y += 13;
  kv(cols[0], y, "Department", e.department); kv(cols[1], y, "Date of joining", fmtDY(e.doj)); kv(cols[2], y, "Tax regime", s.regime === "OLD" ? "Old regime" : "New regime");
  y += 13;
  kv(cols[0], y, "PAN", e.panLast4 ? `XXXXXX${e.panLast4}` : "—"); kv(cols[1], y, "Bank account", e.bankLast4 ? `${e.bankName ?? "Bank"} XXXX${e.bankLast4}` : "—"); kv(cols[2], y, "Pay date", fmtDY(s.payDate));
  y += 15;

  rect(M, y, W - 2 * M, 20, rgb(244 / 255, 244 / 255, 247 / 255));
  const att: [string, number][] = [["Days in month", s.attendance.daysInMonth], ["Weekly offs", s.attendance.weeklyOffs], ["Holidays", s.attendance.holidays], ["Present", s.attendance.present], ["Loss of pay", s.attendance.lopDays], ["Paid days", s.attendance.paidDays]];
  att.forEach(([k, v], i) => {
    const x = M + 5 + i * ((W - 2 * M - 10) / 6);
    text(k, x, y + 7.5, { size: 7.5, color: MUTED });
    text(String(v), x, y + 15, { size: 13, f: bold, color: k === "Loss of pay" && v ? rgb(207 / 255, 58 / 255, 64 / 255) : INK });
  });
  y += 28;

  const half = (W - 2 * M - 8) / 2, xE = M, xD = M + half + 8;
  const head = (x: number, t: string, c2: string, c3: string) => {
    text(t, x, y, { size: 8, f: bold, color: MUTED });
    if (c2) text(c2, x + half - 30, y, { size: 8, f: bold, color: MUTED, align: "right" });
    text(c3, x + half, y, { size: 8, f: bold, color: MUTED, align: "right" });
    line(x, y + 2, x + half, INK, 0.8);
  };
  head(xE, "EARNINGS", "RATE", "EARNED");
  head(xD, "DEDUCTIONS", "", "AMOUNT");
  let ye = y + 8, yd = y + 8;
  for (const c of s.components) {
    text(c.label, xE, ye, { size: 9.5 });
    if (c.rate) text(rs(c.rate), xE + half - 30, ye, { size: 9.5, color: MUTED, align: "right" });
    text(rs(c.earned), xE + half, ye, { size: 9.5, align: "right" });
    line(xE, ye + 2.5, xE + half);
    ye += 8;
  }
  for (const d of s.deductions) {
    text(d.label, xD, yd, { size: 9.5 });
    text(rs(d.amount), xD + half, yd, { size: 9.5, align: "right" });
    line(xD, yd + 2.5, xD + half);
    yd += 8;
  }
  const yt = Math.max(ye, yd) + 2;
  text("Gross earnings", xE, yt, { size: 9.5, f: bold }); text(rs(s.grossEarned), xE + half, yt, { size: 9.5, f: bold, align: "right" });
  text("Total deductions", xD, yt, { size: 9.5, f: bold }); text(rs(s.totalDeductions), xD + half, yt, { size: 9.5, f: bold, align: "right" });

  y = yt + 10;
  page.drawRectangle({ x: mm(M), y: H - mm(y + 24), width: mm(W - 2 * M), height: mm(24), color: ACCENT });
  const white = rgb(1, 1, 1);
  text("NET PAY", M + 6, y + 8, { size: 8.5, color: white });
  wrap(page, `Rupees ${s.netWords} only`, M + 6, y + 15.5, 120, font, 9, white, H);
  text(rs(s.net), W - M - 6, y + 15, { size: 20, f: bold, color: white, align: "right" });

  y += 34;
  const notes = [
    "Earned = monthly rate x paid days / days in month. PF is 12% of earned Basic (wage ceiling Rs. 15,000).",
    `TDS is based on the ${s.regime === "OLD" ? "old regime with verified investment proofs." : "new regime."}`,
    ...(s.note ? [`Note: ${s.note}`] : []),
    "This is a system-generated payslip and does not require a signature.",
  ];
  notes.forEach((n, i) => text(n, M, y + i * 5, { size: 8, color: MUTED }));
  return doc.save();
}

function wrap(page: PDFPage, t: string, xmm: number, ymm: number, wmm: number, f: PDFFont, size: number, color: ReturnType<typeof rgb>, H: number) {
  const words = t.split(" ");
  let lineText = "", row = 0;
  for (const w of words) {
    const next = lineText ? `${lineText} ${w}` : w;
    if (f.widthOfTextAtSize(next, size) > mm(wmm) && lineText) {
      page.drawText(lineText, { x: mm(xmm), y: H - mm(ymm + row * 4.2), size, font: f, color });
      lineText = w;
      row++;
    } else lineText = next;
  }
  if (lineText) page.drawText(lineText, { x: mm(xmm), y: H - mm(ymm + row * 4.2), size, font: f, color });
}
