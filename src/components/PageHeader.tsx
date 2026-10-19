import type { T } from "@/lib/i18n/translate";

/** Prototype page header: eyebrow, Balkind display title with gradient <em>, subtitle, actions. */
export function PageHeader({ eyebrow, title, em, after, sub, children }: { eyebrow: React.ReactNode; title?: React.ReactNode; em?: React.ReactNode; after?: React.ReactNode; sub?: React.ReactNode; children?: React.ReactNode }) {
  return (
    <div className="page-h enter">
      <div>
        <span className="eyebrow">{eyebrow}</span>
        <h1 className="display">
          {title}
          {title && em ? " " : null}
          {em && <em>{em}</em>}
          {after}
        </h1>
        {sub && <p>{sub}</p>}
      </div>
      {children && <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "center" }}>{children}</div>}
    </div>
  );
}

/** Placeholder for modules that land in a later build phase. */
export function ComingInPhase({ phase, what, t }: { phase: number; what: string; t: T }) {
  return (
    <div className="empty enter" style={{ ["--i" as string]: 2 }}>
      <b className="display">{t("Arrives in phase {phase}", { phase })}</b>
      <span className="muted">{what}</span>
    </div>
  );
}
