"use client";

import { usePathname, useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog } from "@/components/ui/Modal";
import { cancelLeave } from "./actions";
import { ApplyLeave, type TypeOpt } from "./ApplyLeave";

export function ApplyButton({ types, today, holidays, weeklyOffs, autoOpen }: { types: TypeOpt[]; today: string; holidays: Record<string, string>; weeklyOffs: number[]; autoOpen: boolean }) {
  const router = useRouter();
  const path = usePathname();
  const [open, setOpen] = useState(autoOpen);
  const close = () => { setOpen(false); if (autoOpen) router.replace(path, { scroll: false }); };
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={18} />Apply leave</button>
      {open && <ApplyLeave types={types} today={today} holidays={holidays} weeklyOffs={weeklyOffs} onClose={close} />}
    </>
  );
}

export function CancelLeave({ id }: { id: string }) {
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-danger" onClick={() => setAsk(true)}>Cancel</button>
      {ask && (
        <ConfirmDialog title="Cancel leave request" danger confirm="Cancel request" busy={busy} onClose={() => setAsk(false)} body="This withdraws the request before HR decides on it."
          onConfirm={() => start(async () => { const r = await cancelLeave(id); toast(r.ok ? r.msg! : r.error, r.ok ? "ok" : "bad"); setAsk(false); })} />
      )}
    </>
  );
}
