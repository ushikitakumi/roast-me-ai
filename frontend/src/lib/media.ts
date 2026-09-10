export type PollState = {
  status?: string;
  visibility?: string;
  pollAfterMs?: number | null;
};
export function pollInterval(
  data: PollState | undefined,
  createdAt: string,
  now: number,
): number | false {
  if (
    !data ||
    data.visibility === "SUPPRESSED" ||
    !data.pollAfterMs ||
    now - Date.parse(createdAt) >= 180000
  )
    return false;
  return data.pollAfterMs;
}
export function stopAllMedia() {
  if (typeof document === "undefined") return;
  document.querySelectorAll("audio, video").forEach((element) => {
    const media = element as HTMLMediaElement;
    media.pause();
    media.removeAttribute("src");
    media.load();
  });
}
