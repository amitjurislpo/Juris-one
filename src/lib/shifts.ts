const t12 = (hm: string) => {
  const [h, m] = hm.split(":").map(Number);
  return `${((h + 11) % 12) + 1}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`;
};

/** "Night · 7:30 PM – 4:30 AM IST" */
export function shiftLabel(s: { code: string; name: string; startLocal: string; endLocal: string } | null | undefined) {
  if (!s) return "—";
  const short = s.code === "IN_NIGHT" ? "Night" : "Day";
  return `${short} · ${t12(s.startLocal)} – ${t12(s.endLocal)}${s.code === "US_DAY" ? " local" : " IST"}`;
}

export const SHIFT_OPTIONS = {
  IN: [
    { v: "IN_NIGHT", l: "Night · 7:30 PM – 4:30 AM IST" },
    { v: "IN_DAY", l: "Day · 9:30 AM – 6:30 PM IST" },
  ],
  US: [{ v: "US_DAY", l: "Day · 9:00 AM – 6:00 PM local" }],
} as const;
