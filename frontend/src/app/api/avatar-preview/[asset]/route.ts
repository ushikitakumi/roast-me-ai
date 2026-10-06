import { readFile } from "node:fs/promises";
import path from "node:path";
import { authenticated } from "../../../../lib/auth";
export const runtime = "nodejs";
export async function GET(
  request: Request,
  context: { params: Promise<{ asset: string }> },
) {
  if (!authenticated(request.headers.get("authorization")))
    return new Response(null, { status: 401 });
  const { asset } = await context.params;
  const files: Record<string, [string, string]> = {
    model: ["rival-mpfb.glb", "model/gltf-binary"],
    audio: ["rival-ja.wav", "audio/wav"],
  };
  if (!Object.hasOwn(files, asset)) return new Response(null, { status: 404 });
  const [file, type] = files[asset];
  try {
    const bytes = await readFile(
      path.resolve(process.cwd(), "../assets/avatar-prototype", file),
    );
    return new Response(bytes, {
      headers: {
        "Content-Type": type,
        "Content-Length": String(bytes.length),
        "Cache-Control": "private, no-store",
        "X-Content-Type-Options": "nosniff",
      },
    });
  } catch {
    return new Response("試作素材が見つかりません。", { status: 503 });
  }
}
