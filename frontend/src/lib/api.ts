export async function api<T>(
  path: string,
  options?: {
    method?: string;
    body?: unknown;
    key?: string;
    signal?: AbortSignal;
  },
): Promise<T> {
  const response = await fetch(`/api/proxy${path}`, {
    method: options?.method ?? "GET",
    headers: {
      ...(options?.body ? { "Content-Type": "application/json" } : {}),
      ...(options?.key ? { "Idempotency-Key": options.key } : {}),
    },
    body: options?.body ? JSON.stringify(options.body) : undefined,
    cache: "no-store",
    signal: options?.signal,
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(
      error.detail ?? "操作に失敗しました。もう一度お試しください。",
    );
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
// Keep one key for a logical action across failed network attempts.
export function operationKey(
  previous: { signature: string; key: string } | null,
  signature: string,
) {
  return previous?.signature === signature
    ? previous
    : { signature, key: crypto.randomUUID() };
}
