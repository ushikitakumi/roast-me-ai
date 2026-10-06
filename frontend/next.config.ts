import type { NextConfig } from "next";
import path from "node:path";
const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  outputFileTracingRoot: path.resolve(process.cwd(), ".."),
  outputFileTracingIncludes: {
    "/avatar-preview": ["../assets/avatar-prototype/line.ja.txt"],
    "/api/avatar-preview/*": [
      "../assets/avatar-prototype/*.glb",
      "../assets/avatar-prototype/*.wav",
    ],
  },
};
export default config;
