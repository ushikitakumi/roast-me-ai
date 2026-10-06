import { readFile } from "node:fs/promises";
import path from "node:path";
import AvatarPreview from "./preview";
export const dynamic = "force-dynamic";
export default async function Page() {
  const line = await readFile(
    path.resolve(process.cwd(), "../assets/avatar-prototype/line.ja.txt"),
    "utf8",
  );
  return <AvatarPreview line={line.trim()} />;
}
