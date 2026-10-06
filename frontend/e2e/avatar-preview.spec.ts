import { test, expect } from "@playwright/test";
import fs from "node:fs";
test("avatar loads, speaks, stops and restarts without generating anything", async ({
  page,
}) => {
  test.setTimeout(90000);
  const errors: string[] = [],
    posts: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("request", (r) => {
    if (r.method() === "POST") posts.push(r.url());
  });
  await page.goto("/avatar-preview");
  await expect(page.getByRole("status")).toHaveText("再生できます", {
    timeout: 60000,
  });
  const stage = page.getByTestId("avatar-stage");
  await expect(stage.locator("canvas")).toBeVisible();
  await expect(stage).toHaveAttribute("data-mouth", "0.000");
  fs.mkdirSync("../artifacts", { recursive: true });
  await page.screenshot({
    path: "../artifacts/avatar-preview-idle.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "▶ 再生する" }).click();
  await expect(page.getByRole("status")).toHaveText("ライバルが話しています");
  await expect
    .poll(async () => Number(await stage.getAttribute("data-mouth")), {
      intervals: [50],
      timeout: 8000,
    })
    .toBeGreaterThan(0.05);
  await page.screenshot({
    path: "../artifacts/avatar-preview-speaking.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "停止", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("停止しました");
  await expect
    .poll(async () => Number(await stage.getAttribute("data-mouth")))
    .toBe(0);
  await page.getByRole("button", { name: "↻ 最初から再生" }).click();
  await expect(page.getByRole("status")).toHaveText("ライバルが話しています");
  await page.getByRole("button", { name: "↻ 最初から再生" }).click();
  await expect(page.getByRole("status")).toHaveText("再生が終わりました", {
    timeout: 20000,
  });
  await expect
    .poll(async () => Number(await stage.getAttribute("data-mouth")))
    .toBe(0);
  expect(errors).toEqual([]);
  expect(posts).toEqual([]);
});
test("asset failures are explained and playback is disabled", async ({
  page,
}) => {
  await page.route("**/api/avatar-preview/model", (route) =>
    route.fulfill({ status: 503 }),
  );
  await page.goto("/avatar-preview");
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "アバターを読み込めませんでした" }),
  ).toContainText("アバターを読み込めませんでした");
  await expect(page.getByRole("button", { name: "▶ 再生する" })).toBeDisabled();
});
test("preview assets require authentication and unknown files are rejected", async ({
  request,
}) => {
  for (const suffix of [
    "/avatar-preview",
    "/api/avatar-preview/model",
    "/api/avatar-preview/audio",
  ]) {
    expect((await fetch("http://localhost:3000" + suffix)).status).toBe(401);
  }
  expect((await request.get("/api/avatar-preview/secret")).status()).toBe(404);
  const audio = await request.get("/api/avatar-preview/audio");
  expect(audio.status()).toBe(200);
  expect(audio.headers()["cache-control"]).toContain("no-store");
  expect((await audio.body()).subarray(0, 4).toString()).toBe("RIFF");
});
