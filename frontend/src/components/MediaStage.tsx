"use client";
import { useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import { api } from "@/lib/api";
import { pollInterval } from "@/lib/media";
import type { components } from "@/lib/schema";
type Roast = components["schemas"]["Roast"];
export default function MediaStage({ initial }: { initial: Roast }) {
  const audio = useRef<HTMLAudioElement>(null);
  const query = useQuery({
    queryKey: ["roast", initial.id],
    queryFn: ({ signal }) => api<Roast>(`/roasts/${initial.id}`, { signal }),
    initialData: initial,
    refetchInterval: (q) =>
      pollInterval(q.state.data, initial.createdAt, Date.now()),
    refetchOnWindowFocus: true,
  });
  const roast = query.data;
  if (roast.visibility === "SUPPRESSED") return null;
  const waiting = !!roast.pollAfterMs;
  const timedOut = Date.now() - Date.parse(initial.createdAt) >= 180000;
  const unknown =
    roast.status === "SUBMISSION_UNKNOWN" || roast.status === "RESULT_UNKNOWN";
  return (
    <div className="media-stage">
      <p className="quote" role="status">
        {roast.text}
      </p>
      {roast.audioUrl && (
        <div>
          <label>音声を手動で再生（スタブ・無音）</label>
          <audio ref={audio} src={roast.audioUrl} controls preload="none" />
        </div>
      )}
      {roast.videoUrl && (
        <div>
          <label>動画を手動で再生（スタブ検証用）</label>
          <video
            src={roast.videoUrl}
            controls
            playsInline
            preload="metadata"
            onPlay={() => audio.current?.pause()}
          />
        </div>
      )}
      {waiting && (
        <small>
          {timedOut
            ? "完成したら履歴に表示されます。"
            : "音声・動画を準備しています…"}
        </small>
      )}
      {unknown && (
        <p className="muted">
          {roast.status === "SUBMISSION_UNKNOWN"
            ? "動画の受付状況を確認できません。"
            : "動画の結果を確認できません。"}{" "}
          自動再送はせず、予算を確認待ちにしています。
        </p>
      )}
      {roast.status === "DEGRADED" && (
        <small>
          メディア生成を完了できませんでした。利用できる内容を表示しています。
        </small>
      )}
    </div>
  );
}
