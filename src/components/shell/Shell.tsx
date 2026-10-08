"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { startTransition, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { logoutAction } from "@/app/(auth)/actions";
import { markAllNotifications, markNotification } from "@/app/(app)/notification-actions";
import { Icon } from "@/components/Icon";
import { fmtDL, initials } from "@/lib/format";
import { PAGE_TITLES, type NavId } from "@/lib/nav";
import { ToastProvider } from "./Toasts";
import { currentTheme, setTheme, type Theme } from "./theme";

export interface ShellProps {
  me: {
    code: string;
    name: string;
    designation: string;
    roleName: string;
    department: string;
    manager: string;
    doj: string;
    shift: string | null;
    isAdminLike: boolean;
  };
  groups: { g: string; items: { id: string; label: string; icon: string }[] }[];
  badges: { approvals: number; proofs: number };
  notes: { t: string; s: string; href?: string; id?: string | null; unread?: boolean; sticky?: boolean }[];
  unread: number;
  landing: string;
  canSelf: boolean;
  /** null for the super admin (the platform console) */
  businessName: string | null;
  initialTheme: Theme;
  children: React.ReactNode;
}

interface Act { g: string; label: string; icon: string; run: () => void; idx: number }

export function Shell(props: ShellProps) {
  const { me, groups, badges, notes, landing, canSelf, businessName } = props;
  const router = useRouter();
  const pathname = usePathname();
  const page = (pathname.split("/")[1] || "home") as NavId;

  const [sideOpen, setSideOpen] = useState(false);
  const [menu, setMenu] = useState(false);
  const [notif, setNotif] = useState(false);
  const [profile, setProfile] = useState(false);
  // "My profile" is a page for everyone in a business (the super admin keeps the small card)
  const hasProfilePage = groups.some((g) => g.items.some((i) => i.id === "profile"));
  const openProfile = useCallback(() => (hasProfilePage ? router.push("/profile") : setProfile(true)), [hasProfilePage, router]);
  const [pal, setPal] = useState(false);
  const [pq, setPq] = useState("");
  const [pi, setPi] = useState(0);
  const [theme, setThemeState] = useState<Theme>(props.initialTheme);
  const palIn = useRef<HTMLInputElement>(null);

  // Close transient UI on navigation (state adjusted during render, not in an effect).
  const [lastPath, setLastPath] = useState(pathname);
  if (lastPath !== pathname) {
    setLastPath(pathname);
    setSideOpen(false);
    setMenu(false);
    setNotif(false);
  }

  const toggleTheme = useCallback(() => {
    const t: Theme = currentTheme() === "dark" ? "light" : "dark";
    setTheme(t);
    setThemeState(t);
  }, []);

  const go = useCallback((id: string) => router.push(`/${id}`), [router]);
  const logout = useCallback(() => startTransition(() => logoutAction()), []);

  const openPal = useCallback(() => {
    setPal(true);
    setPq("");
    setPi(0);
    setMenu(false);
    setNotif(false);
    requestAnimationFrame(() => palIn.current?.focus());
  }, []);

  const acts = useMemo(() => {
    const q = pq.trim().toLowerCase();
    const out: Act[] = [];
    const add = (g: string, label: string, icon: string, run: () => void) => {
      if (!q || label.toLowerCase().includes(q) || g.toLowerCase().includes(q)) out.push({ g, label, icon, run, idx: out.length });
    };
    if (canSelf) {
      add("Actions", "Check in / out", "sunrise", () => go("home"));
      add("Actions", "Apply for leave", "plus", () => router.push("/leave?apply=1"));
    }
    add("Actions", theme === "dark" ? "Switch to light theme" : "Switch to dark theme", theme === "dark" ? "sun" : "moon", toggleTheme);
    groups.forEach((G) => G.items.forEach((n) => add("Go to", n.label, n.icon, () => go(n.id))));
    add("Account", "My profile", "user", openProfile);
    add("Account", "Sign out", "logout", logout);
    return out;
  }, [pq, canSelf, theme, groups, go, router, toggleTheme, logout, openProfile]);

  const groupedActs = useMemo(() => {
    const g: { g: string; items: Act[] }[] = [];
    acts.forEach((a) => {
      let G = g.find((x) => x.g === a.g);
      if (!G) g.push((G = { g: a.g, items: [] }));
      G.items.push(a);
    });
    return g;
  }, [acts]);

  const runAct = (a: Act) => {
    setPal(false);
    a.run();
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        if (pal) setPal(false);
        else openPal();
      } else if (e.key === "Escape") {
        setMenu(false);
        setNotif(false);
        setProfile(false);
        setSideOpen(false);
      }
    };
    const away = (e: MouseEvent) => {
      if (!(e.target as HTMLElement).closest?.(".menu-anchor")) {
        setMenu(false);
        setNotif(false);
      }
    };
    const spot = (e: PointerEvent) => {
      const c = (e.target as HTMLElement).closest?.(".card") as HTMLElement | null;
      if (!c) return;
      const r = c.getBoundingClientRect();
      c.style.setProperty("--mx", `${e.clientX - r.left}px`);
      c.style.setProperty("--my", `${e.clientY - r.top}px`);
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("click", away);
    document.addEventListener("pointermove", spot, { passive: true });
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("click", away);
      document.removeEventListener("pointermove", spot);
    };
  }, [pal, openPal]);

  return (
    <ToastProvider>
      <div className="shell">
        <a className="skip" href="#main">Skip to content</a>
        <div className={`scrim${sideOpen ? " open" : ""}`} onClick={() => setSideOpen(false)}></div>
        <aside id="side" className={`side${sideOpen ? " open" : ""}`} aria-label="Main menu">
          <Link className="ws" href={landing}>
            <span className="monogram">{(businessName ?? "J").charAt(0).toUpperCase()}</span>
            <span style={{ flex: 1, minWidth: 0 }}><b>{businessName ?? "JurisOne"}</b><small>{businessName ? "JurisOne workspace" : "Platform console"}</small></span>
            <Icon n="chevD" s={14} />
          </Link>
          <button className="side-search" type="button" onClick={openPal}><Icon n="search" s={15} />Search<kbd>⌘K</kbd></button>
          {groups.map((G) => (
            <div key={G.g} style={{ display: "contents" }}>
              <div className="group">{G.g}</div>
              {G.items.map((n) => (
                <Link key={n.id} href={`/${n.id}`} className={`nav-item${page === n.id ? " on" : ""}`} aria-current={page === n.id ? "page" : undefined}>
                  <span className="ic"><Icon n={n.icon} s={17} /></span>
                  {n.label}
                  {n.id === "approvals" && badges.approvals > 0 && <span className="badge">{badges.approvals}</span>}
                  {n.id === "proofs" && badges.proofs > 0 && <span className="badge">{badges.proofs}</span>}
                </Link>
              ))}
            </div>
          ))}
        </aside>

        <div className="main">
          <header className="top">
            <button className="icon-btn burger" onClick={() => setSideOpen(true)} aria-label="Open menu" aria-controls="side" aria-expanded={sideOpen}><Icon n="menu" /></button>
            <div className="crumbs"><span>Juris</span><Icon n="chevR" s={14} /><b>{PAGE_TITLES[page] ?? "JurisOne"}</b></div>
            <div className="grow"></div>
            <button className="top-search" type="button" onClick={openPal}><Icon n="search" s={15} />Search or jump to…<kbd>⌘K</kbd></button>
            <button className="icon-btn" onClick={toggleTheme} aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}>
              <Icon n={theme === "dark" ? "sun" : "moon"} s={17} />
            </button>
            <div className="menu-anchor" style={{ position: "relative" }}>
              <button className="icon-btn" onClick={() => { setNotif(!notif); setMenu(false); }} aria-label={`Notifications (${props.unread} unread)`} aria-expanded={notif}>
                <Icon n="bell" s={17} />
                {(props.unread > 0 || badges.approvals + badges.proofs > 0) && <span style={{ position: "absolute", top: 8, right: 9, width: 7, height: 7, borderRadius: "50%", background: "var(--accent)", boxShadow: "0 0 0 2px var(--surface)" }}></span>}
              </button>
              {notif && (
                <div className="menu notif">
                  <div className="head" style={{ justifyContent: "space-between" }}>
                    <b>Notifications</b>
                    {props.unread > 0 ? <span className="chip gold nodot">{props.unread} unread</span> : <span className="chip neutral nodot">All read</span>}
                  </div>
                  {notes.length === 0 && <div className="n faint">You&apos;re all caught up.</div>}
                  {notes.map((n, i) => {
                    const unread = n.id ? !!n.unread : true;
                    const body = (<><i style={unread ? undefined : { background: "transparent", boxShadow: "inset 0 0 0 1px var(--line3)" }}></i><div><div style={{ fontWeight: unread ? 600 : 400 }}>{n.t}</div><div className="faint" style={{ fontSize: 12, marginTop: 2 }}>{n.s}{n.sticky ? " · accept to clear" : ""}</div></div></>);
                    // opening a stored notification marks it read (policy ones clear only when the policy is accepted)
                    const onOpen = () => { if (n.id && n.unread && !n.sticky) startTransition(() => { void markNotification(n.id!, true).then(() => router.refresh()); }); setNotif(false); };
                    return n.href ? <Link key={n.id ?? i} href={n.href} onClick={onOpen} className="n" style={{ color: "inherit", textDecoration: "none" }}>{body}</Link> : <div key={n.id ?? i} className="n">{body}</div>;
                  })}
                  <div style={{ display: "flex", justifyContent: "space-between", gap: 8, padding: "8px 10px 4px", borderTop: "1px solid var(--line)" }}>
                    <button type="button" className="btn btn-sm btn-ghost" disabled={props.unread === 0} onClick={() => startTransition(() => { void markAllNotifications().then(() => router.refresh()); })}>Mark all read</button>
                    <Link className="btn btn-sm" href="/notifications" onClick={() => setNotif(false)}>View all</Link>
                  </div>
                </div>
              )}
            </div>
            <div className="menu-anchor" style={{ position: "relative" }}>
              <button className="profile" onClick={() => { setMenu(!menu); setNotif(false); }} aria-haspopup="menu" aria-expanded={menu}>
                <span className="avatar">{initials(me.name)}</span>
                <span className="who"><b>{me.name}</b><small>{me.designation}</small></span>
                <Icon n="chevD" s={14} />
              </button>
              {menu && (
                <div className="menu" role="menu">
                  <div className="head">
                    <span className="avatar lg">{initials(me.name)}</span>
                    <div style={{ display: "flex", flexDirection: "column", gap: 3, minWidth: 0 }}><b>{me.name}</b><span className="faint" style={{ fontSize: 12.5 }}>{me.code} · {me.roleName}</span></div>
                  </div>
                  <button role="menuitem" onClick={() => { setMenu(false); openProfile(); }}><Icon n="user" s={16} />My profile</button>
                  {canSelf && <button role="menuitem" onClick={() => { setMenu(false); go("payslips"); }}><Icon n="doc" s={16} />My payslips</button>}
                  <button role="menuitem" onClick={toggleTheme}><Icon n={theme === "dark" ? "sun" : "moon"} s={16} />{theme === "dark" ? "Light theme" : "Dark theme"}</button>
                  <button role="menuitem" onClick={() => { setMenu(false); openPal(); }}><Icon n="search" s={16} />Command menu<kbd style={{ marginLeft: "auto" }}>⌘K</kbd></button>
                  <div className="sep"></div>
                  <button role="menuitem" className="danger" onClick={logout}><Icon n="logout" s={16} />Sign out</button>
                </div>
              )}
            </div>
          </header>
          <main id="main" tabIndex={-1} className="content" key={pathname}>{props.children}</main>
        </div>

        {profile && (
          <div className="overlay" onClick={(e) => e.target === e.currentTarget && setProfile(false)}>
            <div className="modal sm" role="dialog" aria-modal="true" aria-labelledby="pf-t">
              <div className="modal-h">
                <div style={{ display: "flex", gap: 14, alignItems: "center" }}>
                  <span className="avatar lg">{initials(me.name)}</span>
                  <div><span className="eyebrow">{me.code}</span><h2 id="pf-t" className="display">{me.name}</h2></div>
                </div>
                <button className="icon-btn" onClick={() => setProfile(false)} aria-label="Close"><Icon n="x" s={16} /></button>
              </div>
              <div className="modal-b">
                <div className="kv"><span className="muted">Designation</span><b>{me.designation}</b></div>
                <div className="kv"><span className="muted">Role</span><b>{me.roleName}</b></div>
                <div className="kv"><span className="muted">Department</span><b>{me.department}</b></div>
                <div className="kv"><span className="muted">Reporting manager</span><b>{me.manager}</b></div>
                <div className="kv"><span className="muted">Date of joining</span><b>{fmtDL(me.doj)}</b></div>
                {!me.isAdminLike && me.shift && <div className="kv"><span className="muted">Shift</span><b>{me.shift}</b></div>}
              </div>
              <div className="modal-f">
                <span className="faint" style={{ fontSize: 12.5 }}>To change details, contact HR.</span>
                <button className="btn btn-danger" onClick={() => { setProfile(false); logout(); }}><Icon n="logout" s={16} />Sign out</button>
              </div>
            </div>
          </div>
        )}

        {pal && (
          <div className="pal-wrap" onClick={(e) => e.target === e.currentTarget && setPal(false)}>
            <div className="pal" role="dialog" aria-modal="true" aria-label="Command menu">
              <div className="pal-in">
                <Icon n="search" s={18} />
                <input
                  ref={palIn}
                  value={pq}
                  onChange={(e) => { setPq(e.target.value); setPi(0); }}
                  onKeyDown={(e) => {
                    const L = acts.length;
                    if (e.key === "ArrowDown") { e.preventDefault(); if (L) setPi((pi + 1) % L); }
                    else if (e.key === "ArrowUp") { e.preventDefault(); if (L) setPi((pi - 1 + L) % L); }
                    else if (e.key === "Enter") { e.preventDefault(); if (acts[pi]) runAct(acts[pi]); }
                    else if (e.key === "Escape") setPal(false);
                  }}
                  placeholder="Type a command or search…"
                  aria-label="Command"
                />
                <kbd>esc</kbd>
              </div>
              <div className="pal-list" role="listbox">
                {groupedActs.map((g) => (
                  <div key={g.g}>
                    <div className="pal-g">{g.g}</div>
                    {g.items.map((a) => (
                      <button key={a.label} type="button" role="option" aria-selected={a.idx === pi} className={`pal-item${a.idx === pi ? " act" : ""}`} onMouseEnter={() => setPi(a.idx)} onClick={() => runAct(a)}>
                        <span className="ic"><Icon n={a.icon} s={15} /></span>{a.label}
                      </button>
                    ))}
                  </div>
                ))}
                {!acts.length && <div className="pal-g" style={{ padding: 24, textAlign: "center" }}>No results for “{pq}”</div>}
              </div>
              <div className="pal-foot"><span><kbd>↑</kbd> <kbd>↓</kbd> navigate</span><span><kbd>↵</kbd> select</span><span style={{ marginLeft: "auto" }}>JurisOne</span></div>
            </div>
          </div>
        )}
      </div>
    </ToastProvider>
  );
}
