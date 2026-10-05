"use client";

import { useEffect, useState } from "react";
import { fmtDL, isoInZone } from "@/lib/format";

/** "Good evening, Amit" with a live clock in the employee's own time zone. */
export function Greeting({ first, timeZone, initialIso }: { first: string; timeZone: string; initialIso: string }) {
  const [now, setNow] = useState<Date | null>(null);
  useEffect(() => {
    const tick = () => setNow(new Date());
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, []);
  const at = now ?? new Date(initialIso);
  const hour = Number(new Intl.DateTimeFormat("en-GB", { timeZone, hour: "numeric", hourCycle: "h23" }).format(at));
  const greet = hour < 5 ? "Working late" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
  const clock = new Intl.DateTimeFormat("en-US", { timeZone, hour: "numeric", minute: "2-digit", second: "2-digit" }).format(at);
  return (
    <div>
      <span className="eyebrow">{fmtDL(isoInZone(at, timeZone))} · <span className="num" suppressHydrationWarning>{clock}</span></span>
      <h1 className="display" suppressHydrationWarning>{greet}, <em>{first}</em></h1>
      <p>Here&apos;s your day at a glance.</p>
    </div>
  );
}
