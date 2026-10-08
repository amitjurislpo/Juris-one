"use client";

import { useState } from "react";
import { Icon } from "@/components/Icon";
import { ConfirmDialog } from "@/components/ui/Modal";

/** A file download (an API route, not a page). */
function download(href: string) {
  const a = Object.assign(document.createElement("a"), { href, download: "" });
  document.body.append(a);
  a.click();
  a.remove();
}

/** Employee export as Excel: masked identity numbers, or in full after a confirmation (both are audited). */
export function ExportButtons() {
  const [ask, setAsk] = useState(false);
  return (
    <>
      <a className="btn btn-gold" href="/api/business/employees/export" download><Icon n="download" s={14} />Export employees (Excel)</a>
      <button className="btn" onClick={() => setAsk(true)}><Icon n="eye" s={14} />With full identity details</button>
      {ask && (
        <ConfirmDialog title="Export with full identity details" confirm="Download" onClose={() => setAsk(false)}
          body={<>The file will contain full PAN, Aadhaar, UAN, bank account numbers, phone numbers and addresses. Keep it somewhere safe. This download is recorded in the audit log.</>}
          onConfirm={() => { setAsk(false); download("/api/business/employees/export?identity=full"); }} />
      )}
    </>
  );
}
