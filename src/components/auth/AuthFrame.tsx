import { Icon } from "@/components/Icon";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";
import { getT } from "@/lib/i18n/server";

/** Login layout from the prototype: form column + animated art panel. */
export async function AuthFrame({ children }: { children: React.ReactNode }) {
  const t = await getT();
  return (
    <div className="login">
      <section className="login-form-wrap">
        <div className="brandmark">
          <span className="monogram">J</span>JurisOne
          <span style={{ marginLeft: "auto" }}><LanguageSwitcher /></span>
        </div>
        {children}
        <div className="faint" style={{ fontSize: 12.5, display: "flex", justifyContent: "space-between", gap: 12, flexWrap: "wrap" }}>
          <span>© {new Date().getFullYear()} JurisOne</span>
          <span>{t("Privacy")} · {t("Help desk")}</span>
        </div>
      </section>
      <section className="login-art" aria-hidden="true">
        <div className="mesh"><i></i><i></i><i></i><i></i></div>
        <div className="gridlines"></div>
        <div className="grain"></div>
        <div className="copy">
          <span className="pill-glass"><i className="dot live" style={{ color: "#7CFFB2" }}></i>{t("Attendance · Leave · Payroll · Tax")}</span>
          <h1 className="display">{t("Your workday,")}<br />{t("beautifully in sync.")}</h1>
          <p>{t("One portal for your whole team — check in, plan time off, download payslips and declare investments.")}</p>
        </div>
        <div className="mock">
          <div className="glass a">
            <small>Today · Night shift</small>
            <div className="mono" style={{ fontSize: 34, fontWeight: 500, margin: "6px 0 10px" }}>04:28:12</div>
            <div style={{ height: 6, borderRadius: 9, background: "rgba(255,255,255,.2)" }}>
              <div style={{ width: "50%", height: "100%", borderRadius: 9, background: "#fff" }}></div>
            </div>
          </div>
          <div className="glass b" style={{ display: "flex", gap: 12, alignItems: "center" }}>
            <span style={{ width: 36, height: 36, borderRadius: "50%", background: "#7CFFB2", color: "#0D3B24", display: "grid", placeItems: "center", flex: "none" }}>
              <Icon n="ok" s={16} />
            </span>
            <div><b style={{ fontSize: 14 }}>Leave approved</b><br /><small>Casual leave · 19 – 22 Oct</small></div>
          </div>
          <div className="glass c">
            <small>Attendance · last 14 weeks</small>
            <div className="mini-heat">
              {Array.from({ length: 98 }, (_, i) => i + 1).map((n) => (
                <i key={n} style={{ background: `rgba(255,255,255,${0.12 + ((n * 37) % 10) / 13})` }}></i>
              ))}
            </div>
          </div>
        </div>
        <div className="foot"><span>{t("Role-based access")}</span><span>·</span><span>{t("Salary stays with Finance")}</span><span>·</span><span>{t("Works on any device")}</span></div>
      </section>
    </div>
  );
}
