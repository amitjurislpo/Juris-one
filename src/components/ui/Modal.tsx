"use client";

import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";
import { Icon } from "@/components/Icon";
import { useT } from "@/lib/i18n/client";

/** Overlays live at <body> level so page cards (animated, overflow:hidden) can't clip or cover them. */
const portal = (node: React.ReactNode) => (typeof document === "undefined" ? null : createPortal(node, document.body));

/** Esc closes, focus moves into the dialog and returns to the trigger afterwards. */
function useDialog(onClose: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  useEffect(() => { close.current = onClose; });
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    const el = ref.current;
    const first = el?.querySelector<HTMLElement>("[autofocus], input, select, textarea, button:not(.icon-btn)");
    (first ?? el)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") { e.stopPropagation(); close.current(); }
      if (e.key === "Tab" && el) {
        const items = [...el.querySelectorAll<HTMLElement>("a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])")];
        if (!items.length) return;
        const a = items[0], b = items[items.length - 1];
        if (e.shiftKey && document.activeElement === a) { e.preventDefault(); b.focus(); }
        else if (!e.shiftKey && document.activeElement === b) { e.preventDefault(); a.focus(); }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => { document.removeEventListener("keydown", onKey); prev?.focus?.(); };
  }, []);
  return ref;
}

export function Modal({ title, eyebrow, onClose, footer, children, size, maxWidth, stacked }: { title: React.ReactNode; eyebrow?: React.ReactNode; onClose: () => void; footer?: React.ReactNode; children: React.ReactNode; size?: "sm"; maxWidth?: number; /** opened from another dialog or drawer */ stacked?: boolean }) {
  const t = useT();
  const ref = useDialog(onClose);
  const id = useId();
  return portal(
    <div className="overlay" style={stacked ? { zIndex: 90 } : undefined} onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={ref} tabIndex={-1} className={`modal${size ? " " + size : ""}`} style={maxWidth ? { maxWidth } : undefined} role="dialog" aria-modal="true" aria-labelledby={id}>
        <div className="modal-h">
          <div>{eyebrow && <span className="eyebrow">{eyebrow}</span>}<h2 id={id} className="display">{title}</h2></div>
          <button className="icon-btn" onClick={onClose} aria-label={t("Close")}><Icon n="x" s={16} /></button>
        </div>
        <div className="modal-b">{children}</div>
        {footer && <div className="modal-f">{footer}</div>}
      </div>
    </div>
  );
}

export function Drawer({ label, onClose, children, width = 440 }: { label: string; onClose: () => void; children: React.ReactNode; width?: number }) {
  const ref = useDialog(onClose);
  return portal(
    <>
      <div className="overlay" onMouseDown={onClose}></div>
      <aside ref={ref} tabIndex={-1} className="drawer" style={{ width: `min(${width}px, calc(100% - 16px))` }} role="dialog" aria-modal="true" aria-label={label}>
        {children}
      </aside>
    </>
  );
}

export function ConfirmDialog({ title, body, confirm, danger, busy, onConfirm, onClose, children }: { title: string; body: React.ReactNode; confirm: string; danger?: boolean; busy?: boolean; onConfirm: () => void; onClose: () => void; children?: React.ReactNode }) {
  const t = useT();
  return (
    <Modal title={title} onClose={onClose} size="sm" stacked
      footer={<>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className={danger ? "btn btn-danger" : "btn btn-gold"} disabled={busy} onClick={onConfirm}>{confirm}</button>
      </>}>
      <p className="muted" style={{ margin: 0, fontSize: 14, lineHeight: 1.6 }}>{body}</p>
      {children}
    </Modal>
  );
}
