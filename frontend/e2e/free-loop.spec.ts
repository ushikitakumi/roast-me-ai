import { test, expect } from "@playwright/test";
test("declare, report, pause, resume, delete, win", async ({
  page,
  request,
}) => {
  const reset = await request.put("/api/proxy/me/settings", {
    headers: { Origin: "http://localhost:3000" },
    data: { roastIntensity: "NORMAL", paused: false },
  });
  expect(reset.status()).toBe(200);
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "煽りを停止する", exact: true }),
  ).toBeEnabled();
  await page.getByRole("button", { name: "＋ 目標を宣言する" }).click();
  const title = `ブラウザ検証 ${Date.now()}`;
  await page.getByLabel("目標のタイトル").fill(title);
  await page.getByLabel("勝利条件", { exact: true }).fill("参考書を1冊解く");
  await page.getByLabel("取り組み方・宣言").fill("毎日30分取り組む");
  await page.getByRole("button", { name: "この目標を宣言する →" }).click();
  await expect(page.getByRole("heading", { name: title })).toBeVisible();
  await page.getByLabel("進捗の報告").fill("今日は10分だけ進んだ");
  await page.getByRole("button", { name: "進捗を報告する →" }).click();
  await expect(
    page.getByText("今日は10分だけ進んだ", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "煽りを停止する", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "煽りを再開する" }),
  ).toBeVisible();
  await expect(
    page.locator(".history").getByText("RIVAL", { exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "煽りを再開する" }).click();
  page.on("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "削除", exact: true }).click();
  await expect(
    page.getByText("今日は10分だけ進んだ", { exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "達成した。文句ある？ ↗" }).click();
  await expect(
    page.getByText("目標を達成しました。おめでとう。"),
  ).toBeVisible();
  await page.reload();

  await expect(
    page.getByText("目標を達成しました。おめでとう。"),
  ).toBeVisible();
  await page.screenshot({ path: "../artifacts/free-loop.png", fullPage: true });
});
test("BFF refuses unauthenticated and cross-origin writes", async ({
  playwright,
  request,
}) => {
  expect((await fetch("http://localhost:3000/api/proxy/me")).status).toBe(401);
  expect(
    (
      await request.post("/api/proxy/goals", {
        headers: { Origin: "https://untrusted.example" },
        data: {},
      })
    ).status(),
  ).toBe(403);
});
