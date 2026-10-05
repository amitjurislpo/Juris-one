import { NextResponse, type NextRequest } from "next/server";

const PUBLIC = ["/login", "/forgot", "/invite/", "/reset/"];

/**
 * Optimistic check only: signed-out visitors are bounced to /login before rendering.
 * The real session + permission checks run on the server in every page, action and route.
 */
export function proxy(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  if (PUBLIC.some((p) => pathname === p || pathname.startsWith(p)) || pathname.startsWith("/api/")) return NextResponse.next();
  if (!req.cookies.has("jo_session")) {
    const url = new URL("/login", req.url);
    if (pathname !== "/") url.searchParams.set("next", pathname + search);
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|brand/|fonts/|icon.svg|favicon.ico|robots.txt).*)"],
};
