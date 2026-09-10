import { defineConfig } from "@playwright/test";
import fs from "node:fs";
const env = Object.fromEntries(
  fs
    .readFileSync("../.env", "utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      const at = line.indexOf("=");
      return [line.slice(0, at), line.slice(at + 1)];
    }),
);
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  use: {
    baseURL: "http://localhost:3000",
    httpCredentials: {
      username: env.FRONTEND_USER,
      password: env.FRONTEND_PASSWORD,
    },
    browserName: "chromium",
    screenshot: "only-on-failure",
  },
  reporter: "list",
});
