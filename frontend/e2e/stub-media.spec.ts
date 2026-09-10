import { test, expect } from "@playwright/test";

test("stub media becomes playable without autoplay and access stops when paused", async ({
  page,
  request,
}) => {
  const me = await request.get("/api/proxy/me");
  const profile = await me.json();
  test.skip(
    profile.videoAvailability.reason !== "STUB",
    "Run the backend with STUB_VIDEO_ENABLED=true and a test daily limit.",
  );
  const result = await request.post("/api/proxy/goals", {
    headers: {
      Origin: "http://localhost:3000",
      "Idempotency-Key": crypto.randomUUID(),
    },
    data: {
      title: `スタブ検証 ${Date.now()}`,
      successCriteria: "検証を終える",
      requestVideo: true,
    },
  });
  expect(result.status()).toBe(202);
  const data = await result.json();
  await page.goto(`/goals/${data.goal.id}`);
  const video = page.locator("video").first();
  await expect(video).toBeVisible({ timeout: 20000 });
  expect(await video.evaluate((el) => (el as HTMLVideoElement).paused)).toBe(
    true,
  );
  const audio = page.locator("audio").first();
  expect(await audio.evaluate((el) => (el as HTMLAudioElement).paused)).toBe(
    true,
  );
  const source = await video.getAttribute("src");
  const partial = await request.get(source!, {
    headers: { Range: "bytes=0-99" },
  });
  expect(partial.status()).toBe(206);
  expect(partial.headers()["content-range"]).toMatch(/^bytes 0-99\//);
  await video.evaluate((el) => (el as HTMLVideoElement).play());
  await page
    .getByRole("button", { name: "煽りを停止する", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "煽りを再開する", exact: true }),
  ).toBeVisible();
  await expect(page.locator("video")).toHaveCount(0);
  expect((await request.get(source!)).status()).toBe(404);
  await page
    .getByRole("button", { name: "煽りを再開する", exact: true })
    .click();
  await expect(page.locator("video")).toBeVisible();
  await page.screenshot({
    path: "../artifacts/stub-media.png",
    fullPage: true,
  });
});
