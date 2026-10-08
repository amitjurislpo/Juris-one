"use client";

import { PHONE_COUNTRIES, phoneDigits } from "@/lib/phone";

/** Country code picker + national number; only digits, and no more than the country's number length. */
export function PhoneInput({ code, number, onChange, placeholder, disabled, id, label = "Phone number" }: {
  code: string; number: string; onChange: (code: string, number: string) => void; placeholder?: string; disabled?: boolean; id?: string;
  /** accessible name of the number box */
  label?: string;
}) {
  const max = phoneDigits(code);
  return (
    <span style={{ display: "flex", gap: 6 }}>
      <select aria-label={`${label}: country code`} value={code} disabled={disabled} style={{ width: 104, flex: "none" }}
        onChange={(e) => onChange(e.target.value, number.replace(/\D/g, "").slice(0, phoneDigits(e.target.value)))}>
        {PHONE_COUNTRIES.map((c) => <option key={c.code} value={c.code} title={c.name}>{c.code} {c.name.length > 12 ? c.name.slice(0, 10) + "…" : c.name}</option>)}
      </select>
      <input id={id} aria-label={label} type="tel" inputMode="numeric" autoComplete="tel-national" value={number} disabled={disabled}
        placeholder={placeholder ?? `${max} digits`} style={{ flex: 1, minWidth: 0 }}
        onChange={(e) => onChange(code, e.target.value.replace(/\D/g, "").slice(0, max))} />
    </span>
  );
}
