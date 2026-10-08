import type { NextConfig } from "next";

const securityHeaders = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "same-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  ...(process.env.NODE_ENV === "production" ? [{ key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" }] : []),
];

/** Spring Boot API (backend/). The browser and server actions reach it through /api/* on this origin. */
const BACKEND_URL = (process.env.BACKEND_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

/**
 * /api paths already served by Spring Boot even though a Next.js route handler still exists for them
 * (module-by-module migration; the handlers are deleted in the final cleanup).
 */
const MIGRATED_API: string[] = ["/api/people/export", "/api/attendance/register", "/api/payroll/export", "/api/payslips", "/api/declarations/proof", "/api/tax/export"];

const nextConfig: NextConfig = {
  poweredByHeader: false,
  serverExternalPackages: ["@node-rs/argon2", "pg"],
  async rewrites() {
    return {
      beforeFiles: MIGRATED_API.map((p) => ({ source: `${p}/:path*`, destination: `${BACKEND_URL}${p}/:path*` })),
      afterFiles: [],
      // any /api path Next.js doesn't handle goes to Spring Boot
      fallback: [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }],
    };
  },
  async headers() {
    return [
      { source: "/:path*", headers: securityHeaders },
      // one-time links must never leak through the Referer header
      { source: "/(invite|reset)/:token*", headers: [{ key: "Referrer-Policy", value: "no-referrer" }, { key: "Cache-Control", value: "no-store" }] },
    ];
  },
};

export default nextConfig;
