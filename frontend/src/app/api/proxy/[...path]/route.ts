import { NextRequest } from "next/server";
import { authenticated, sameOrigin } from "@/lib/auth";
export const runtime = "nodejs";
const id = "[0-9a-fA-F-]{36}";
const routes: Record<string, RegExp[]> = {
  GET: [
    /^(me|personas|goals)$/,
    new RegExp(`^goals/${id}(/timeline)?$`),
    new RegExp(`^roasts/${id}(/media/(audio|video))?$`),
  ],
  POST: [/^goals$/, new RegExp(`^goals/${id}/(progress|achieve|abandon)$`)],
  PUT: [/^me\/settings$/],
  DELETE: [new RegExp(`^goals/${id}/progress/${id}$`)],
};
async function handle(
  req: NextRequest,
  context: { params: Promise<{ path: string[] }> },
) {
  if (!authenticated(req.headers.get("authorization")))
    return new Response(null, { status: 401 });
  const path = (await context.params).path.join("/");
  if (!(routes[req.method] ?? []).some((pattern) => pattern.test(path)))
    return new Response(null, { status: 404 });
  if (
    !["GET", "HEAD"].includes(req.method) &&
    !sameOrigin(req.headers.get("origin"))
  )
    return new Response(null, { status: 403 });
  const backend = process.env.BACKEND_URL;
  const user = process.env.BACKEND_USER;
  const password = process.env.BACKEND_PASSWORD;
  if (!backend || !user || !password)
    return Response.json(
      { detail: "サーバ設定が不足しています。" },
      { status: 503 },
    );
  const headers = new Headers({
    Authorization: `Basic ${Buffer.from(`${user}:${password}`).toString("base64")}`,
  });
  const range = req.headers.get("range");
  if (range) headers.set("Range", range);
  const key = req.headers.get("idempotency-key");
  if (key) headers.set("Idempotency-Key", key);
  let body: string | undefined;
  if (!["GET", "HEAD"].includes(req.method)) {
    body = await req.text();
    if (Buffer.byteLength(body) > 16000)
      return new Response(null, { status: 413 });
    if (body) headers.set("Content-Type", "application/json");
  }
  try {
    const response = await fetch(
      `${backend}/api/v1/${path}${req.nextUrl.search}`,
      {
        method: req.method,
        headers,
        body: body || undefined,
        cache: "no-store",
        redirect: "error",
        signal: AbortSignal.timeout(15000),
      },
    );
    return new Response(response.body, {
      status: response.status,
      headers: {
        "Content-Type":
          response.headers.get("content-type") ?? "application/json",
        "Cache-Control": "private, no-store",
        ...(response.headers.get("content-range")
          ? { "Content-Range": response.headers.get("content-range")! }
          : {}),
        ...(response.headers.get("accept-ranges")
          ? { "Accept-Ranges": response.headers.get("accept-ranges")! }
          : {}),
      },
    });
  } catch {
    return Response.json(
      {
        detail:
          "サーバに接続できません。記録された可能性があるため、同じ内容で再試行してください。",
      },
      { status: 503 },
    );
  }
}
export { handle as GET, handle as POST, handle as PUT, handle as DELETE };
