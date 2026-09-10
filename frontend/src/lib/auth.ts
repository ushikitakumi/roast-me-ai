import { timingSafeEqual } from "node:crypto";
export function authenticated(header: string | null): boolean {
  const user = process.env.FRONTEND_USER;
  const password = process.env.FRONTEND_PASSWORD;
  if (
    !user ||
    !password ||
    password.length < 16 ||
    !header?.startsWith("Basic ")
  )
    return false;
  const expected = Buffer.from(`${user}:${password}`);
  const received = Buffer.from(header.slice(6), "base64");
  return (
    expected.length === received.length && timingSafeEqual(expected, received)
  );
}
export function sameOrigin(origin: string | null): boolean {
  return !!process.env.APP_ORIGIN && origin === process.env.APP_ORIGIN;
}
