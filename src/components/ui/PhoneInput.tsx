"use client";

import { AE, AU, BD, FR, GB, IE, IN, JP, LK, NP, QA, SA, SG, US, ZA, type FlagComponent } from "country-flag-icons/react/3x2";
import { useEffect, useId, useRef, useState } from "react";
import { useT } from "@/lib/i18n/client";
import { PHONE_COUNTRIES, phoneCountry, phoneDigits } from "@/lib/phone";

/** SVG flags (emoji flags don't render on Windows). */
const FLAGS: Record<string, FlagComponent> = { AE, AU, BD, FR, GB, IE, IN, JP, LK, NP, QA, SA, SG, US, ZA };

function Flag({ iso }: { iso: string }) {
  const F = FLAGS[iso];
  return F ? <F aria-hidden="true" style={{ width: 20, height: 14, borderRadius: 2, flex: "none", boxShadow: "0 0 0 1px var(--line)" }} /> : null;
}

/**
 * Country code picker (flag + code) and the national number: digits only, and no more than the country's number
 * length. The picker is a small listbox: arrows / Enter / Escape, or type a letter to jump to a country.
 */
export function PhoneInput({ code, number, onChange, placeholder, disabled, id, label }: {
  code: string; number: string; onChange: (code: string, number: string) => void; placeholder?: string; disabled?: boolean; id?: string;
  /** accessible name of the number box */
  label?: string;
}) {
  const t = useT();
  const lbl = label ? t(label) : t("Phone number");
  const max = phoneDigits(code);
  const current = phoneCountry(code) ?? PHONE_COUNTRIES[0];
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [up, setUp] = useState(false); // open upwards when the dialog / page has no room below
  const wrap = useRef<HTMLSpanElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const listId = useId();

  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => { if (!wrap.current?.contains(e.target as Node)) setOpen(false); };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);
  useEffect(() => {
    if (open) list.current?.querySelector<HTMLElement>(`[data-i="${active}"]`)?.scrollIntoView({ block: "nearest" });
  }, [open, active]);

  const choose = (i: number) => {
    const c = PHONE_COUNTRIES[i];
    onChange(c.code, number.replace(/\D/g, "").slice(0, c.digits));
    setOpen(false);
  };
  const openAt = () => {
    setActive(Math.max(0, PHONE_COUNTRIES.findIndex((c) => c.code === current.code)));
    // the nearest scrolling box (a dialog body) clips the list: flip it when there's more room above
    const btn = wrap.current!.getBoundingClientRect();
    let box: DOMRect = new DOMRect(0, 0, window.innerWidth, window.innerHeight);
    for (let el = wrap.current!.parentElement; el; el = el.parentElement) {
      const o = getComputedStyle(el).overflowY;
      if (o === "auto" || o === "scroll" || o === "hidden") { box = el.getBoundingClientRect(); break; }
    }
    const below = box.bottom - btn.bottom, above = btn.top - box.top;
    setUp(below < 270 && above > below);
    setOpen(true);
  };
  const onKey = (e: React.KeyboardEvent) => {
    if (!open) {
      if (["ArrowDown", "ArrowUp", "Enter", " "].includes(e.key)) { e.preventDefault(); openAt(); }
      return;
    }
    if (e.key === "Escape") { e.preventDefault(); setOpen(false); }
    else if (e.key === "ArrowDown") { e.preventDefault(); setActive((a) => Math.min(PHONE_COUNTRIES.length - 1, a + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(0, a - 1)); }
    else if (e.key === "Enter" || e.key === " ") { e.preventDefault(); choose(active); }
    else if (e.key === "Tab") setOpen(false);
    else if (/^[a-z]$/i.test(e.key)) {
      const i = PHONE_COUNTRIES.findIndex((c) => c.name.toLowerCase().startsWith(e.key.toLowerCase()));
      if (i >= 0) setActive(i);
    }
  };

  return (
    <span ref={wrap} style={{ display: "flex", gap: 6, position: "relative" }}>
      <button type="button" role="combobox" className="phone-cc" disabled={disabled} onClick={() => (open ? setOpen(false) : openAt())} onKeyDown={onKey}
        aria-label={t("{label}: country code, {name} {code}", { label: lbl, name: current.name, code: current.code })} aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? listId : undefined}
        aria-activedescendant={open ? `${listId}-${active}` : undefined}>
        <Flag iso={current.iso} /><span>{current.code}</span><span aria-hidden="true" style={{ fontSize: 10, opacity: 0.6 }}>▾</span>
      </button>
      {open && (
        <ul ref={list} id={listId} role="listbox" aria-label={t("Country code")} className={`phone-cc-list${up ? " up" : ""}`}>
          {PHONE_COUNTRIES.map((c, i) => (
            <li key={c.code} id={`${listId}-${i}`} data-i={i} role="option" aria-selected={c.code === current.code}
              className={i === active ? "on" : undefined} onMouseEnter={() => setActive(i)} onMouseDown={(e) => { e.preventDefault(); choose(i); }}>
              <Flag iso={c.iso} /><span style={{ flex: 1 }}>{c.name}</span><span className="muted">{c.code}</span>
            </li>
          ))}
        </ul>
      )}
      <input id={id} aria-label={lbl} type="tel" inputMode="numeric" autoComplete="tel-national" value={number} disabled={disabled}
        placeholder={placeholder ?? t("{n} digits", { n: max })} style={{ flex: 1, minWidth: 0 }}
        onChange={(e) => onChange(code, e.target.value.replace(/\D/g, "").slice(0, max))} />
    </span>
  );
}
