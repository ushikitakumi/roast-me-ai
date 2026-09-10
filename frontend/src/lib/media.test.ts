import { describe, it, expect } from "vitest";
import { pollInterval } from "./media";
describe("polling contract", () => {
  const created = "2026-09-09T00:00:00Z";
  const start = Date.parse(created);
  it("uses the server interval while the job is visible", () =>
    expect(
      pollInterval(
        { status: "AUDIO_DONE", visibility: "VISIBLE", pollAfterMs: 2000 },
        created,
        start + 3000,
      ),
    ).toBe(2000));
  it("stops at three minutes without changing the job", () =>
    expect(pollInterval({ pollAfterMs: 2000 }, created, start + 180000)).toBe(
      false,
    ));
  it("stops for suppression and terminal status", () => {
    expect(
      pollInterval(
        { visibility: "SUPPRESSED", pollAfterMs: 2000 },
        created,
        start,
      ),
    ).toBe(false);
    expect(
      pollInterval(
        { status: "RESULT_UNKNOWN", pollAfterMs: null },
        created,
        start,
      ),
    ).toBe(false);
  });
});
