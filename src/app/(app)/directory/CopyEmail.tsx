"use client";

import { useToast } from "@/components/shell/Toasts";

export function CopyEmail({ email }: { email: string }) {
  const toast = useToast();
  return (
    <button className="linkish" title={`Copy ${email}`} onClick={() => { navigator.clipboard?.writeText(email).catch(() => {}); toast(`Copied ${email}`); }}>
      {email}
    </button>
  );
}
