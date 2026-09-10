import type { Metadata } from "next";
import "./style.css";
import Providers from "./providers";
export const metadata: Metadata = {
  title: "ROAST ME — 行動で、言い返せ。",
  description: "ライバルへの反発心を、次の一歩に。",
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ja">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
