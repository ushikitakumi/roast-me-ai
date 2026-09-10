import { NextRequest, NextResponse } from "next/server";
import { authenticated } from "./lib/auth";
export function proxy(request: NextRequest) {
  if (!authenticated(request.headers.get("authorization"))) {
    return new NextResponse("認証が必要です。", {
      status: 401,
      headers: {
        "WWW-Authenticate": 'Basic realm="Roast Me", charset="UTF-8"',
        "Cache-Control": "no-store",
      },
    });
  }
  const response = NextResponse.next();
  response.headers.set("Cache-Control", "private, no-store");
  response.headers.set("X-Content-Type-Options", "nosniff");
  response.headers.set("Referrer-Policy", "same-origin");
  response.headers.set("X-Frame-Options", "DENY");
  return response;
}
export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
