import { describe, it, expect, vi, afterEach } from "vitest";
import { operationKey } from "./api";
import { authenticated, sameOrigin } from "./auth";
afterEach(() => vi.unstubAllEnvs());
describe("request identity", () => {
  it("keeps a key across retries but changes it for a different operation", () => {
    const first = operationKey(null, "goal:a");
    expect(operationKey(first, "goal:a")).toBe(first);
    expect(operationKey(first, "goal:b").key).not.toBe(first.key);
  });
});
describe("BFF boundary", () => {
  it("fails closed without credentials", () =>
    expect(authenticated(null)).toBe(false));
  it("checks exact credentials", () => {
    vi.stubEnv("FRONTEND_USER", "owner");
    vi.stubEnv("FRONTEND_PASSWORD", "long-private-password");
    expect(
      authenticated(
        "Basic " +
          Buffer.from("owner:long-private-password").toString("base64"),
      ),
    ).toBe(true);
    expect(
      authenticated("Basic " + Buffer.from("owner:wrong").toString("base64")),
    ).toBe(false);
  });
  it("requires the configured origin for writes", () => {
    vi.stubEnv("APP_ORIGIN", "http://localhost:3000");
    expect(sameOrigin(null)).toBe(false);
    expect(sameOrigin("https://evil.example")).toBe(false);
    expect(sameOrigin("http://localhost:3000")).toBe(true);
  });
});
