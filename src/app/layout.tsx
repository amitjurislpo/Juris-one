import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";
import { Bodoni_Moda, Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geist = Geist({ subsets: ["latin"], variable: "--font-geist", weight: ["400", "500", "600", "700"] });
const geistMono = Geist_Mono({ subsets: ["latin"], variable: "--font-geist-mono", weight: ["400", "500", "600"] });
// Fallback for the Balkind display face (commercial licence pending — REQUIREMENTS §9.6).
const bodoni = Bodoni_Moda({ subsets: ["latin"], variable: "--font-bodoni", weight: ["400", "500"] });

export const metadata: Metadata = {
  title: { default: "JurisOne — Juris Consultants", template: "%s · JurisOne" },
  description: "Attendance, leave, payroll and tax for Juris Consultants.",
  icons: { icon: "/icon.svg" },
};

export const viewport: Viewport = { width: "device-width", initialScale: 1 };

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const theme = (await cookies()).get("jo_theme")?.value === "dark" ? "dark" : "light";
  return (
    <html lang="en" data-theme={theme} className={`${geist.variable} ${geistMono.variable} ${bodoni.variable}`}>
      <body>
        <div className="ambient" aria-hidden="true"><i></i><i></i><i></i></div>
        {children}
      </body>
    </html>
  );
}
