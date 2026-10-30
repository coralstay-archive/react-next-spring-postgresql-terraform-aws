import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { Suspense } from "react";

import { OfflineBanner } from "@/components/offline-banner";
import { Providers } from "@/components/providers";
import { SessionExpiredBanner } from "@/components/session-expired-banner";
import { SiteHeader } from "@/components/site-header";
import { StepUpBanner } from "@/components/step-up-banner";

import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "게시판",
  description: "Next.js + Spring Boot 게시판",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <Providers>
          <SiteHeader />
          <OfflineBanner />
          <StepUpBanner />
          <Suspense fallback={null}>
            <SessionExpiredBanner />
          </Suspense>
          {children}
        </Providers>
      </body>
    </html>
  );
}
