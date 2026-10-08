import type { NextConfig } from "next";

const securityHeaders = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "same-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  ...(process.env.NODE_ENV === "production" ? [{ key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" }] : []),
];

/** Spring Boot API (backend/). The browser reaches it through /api/* on this origin; pages and server actions call it directly. */
const BACKEND_URL = (process.env.BACKEND_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

const nextConfig: NextConfig = {
  poweredByHeader: false,
  async rewrites() {
    // downloads and uploads the browser makes itself (CSV exports, payslip PDFs, investment proofs)
    return [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }];
  },
  async headers() {
    return [
      { source: "/:path*", headers: securityHeaders },
      // one-time links must never leak through the Referer header
      { source: "/(invite|reset|onboard)/:token*", headers: [{ key: "Referrer-Policy", value: "no-referrer" }, { key: "Cache-Control", value: "no-store" }] },
    ];
  },
};

export default nextConfig;
