"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import type { createStage } from "../../lib/avatar/stage";
import styles from "./preview.module.css";

type Stage = Awaited<ReturnType<typeof createStage>>;
const labels = {
  loading: "モデルと音声を読み込んでいます…",
  ready: "再生できます",
  preparing: "音声を準備しています…",
  playing: "ライバルが話しています",
  stopped: "停止しました",
  ended: "再生が終わりました",
};
export default function AvatarPreview({ line }: { line: string }) {
  const host = useRef<HTMLDivElement>(null);
  const stage = useRef<Stage | null>(null);
  const [phase, setPhase] = useState<keyof typeof labels>("loading");
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    const target = host.current!;
    void import("../../lib/avatar/stage")
      .then(async ({ createStage }) => {
        if (controller.signal.aborted) return;
        const instance = await createStage(
          target,
          controller.signal,
          setPhase,
          setError,
        );
        if (controller.signal.aborted) instance.dispose();
        else stage.current = instance;
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setError(
            "アバターを読み込めませんでした。素材とWebGL対応を確認し、再読み込みしてください。",
          );
      });
    return () => {
      controller.abort();
      stage.current?.dispose();
      stage.current = null;
    };
  }, []);
  const ready = phase !== "loading" && !error;
  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <Link href="/">← ROAST ME</Link>
        <span>AVATAR LAB / 01</span>
      </header>
      <div className={styles.intro}>
        <p className={styles.eyebrow}>声と表情を、確かめる。</p>
        <h1>ライバルに、会ってみる。</h1>
        <p>固定の台詞で試す、3Dアバターのプロトタイプ。</p>
      </div>
      <div className={styles.grid}>
        <section className={styles.stage} aria-label="アバター表示">
          <span className={styles.badge}>
            PROTOTYPE <i /> PC PREVIEW
          </span>
          <div
            ref={host}
            className={styles.canvas}
            data-testid="avatar-stage"
          />
          {phase === "loading" && !error && (
            <div className={styles.loading}>
              モデルを準備中<span>初回は約37MBを読み込みます</span>
            </div>
          )}
          <div className={styles.caption}>
            <span>RIVAL / SAMPLE 01</span>
            <span>MPFB · 3D</span>
          </div>
        </section>
        <section className={styles.panel} aria-label="再生操作">
          <p className={styles.eyebrow}>TODAY’S LINE</p>
          <h2>
            口だけじゃないところ、
            <br />
            見せてもらおうか。
          </h2>
          <blockquote>{line}</blockquote>
          <p role="status" className={styles.status}>
            {error ? "再生を利用できません" : labels[phase]}
          </p>
          {error && (
            <div role="alert" className={styles.error}>
              {error}
              <button onClick={() => window.location.reload()}>
                再読み込み
              </button>
            </div>
          )}
          <div className={styles.controls}>
            <button
              className={styles.primary}
              disabled={!ready || phase === "playing" || phase === "preparing"}
              onClick={() => void stage.current?.play()}
            >
              ▶ 再生する
            </button>
            <button
              disabled={
                !ready || (phase !== "playing" && phase !== "preparing")
              }
              onClick={() => stage.current?.stop()}
            >
              停止
            </button>
          </div>
          <button
            className={styles.restart}
            disabled={!ready || phase === "preparing"}
            onClick={() => void stage.current?.play()}
          >
            ↻ 最初から再生
          </button>
          <p className={styles.note}>
            約10秒・音声が流れます。自動再生はしません。
            <br />
            停止すると音声と口パクが止まります。
          </p>
          <div className={styles.evaluate}>
            <h3>見て、聞いて、確かめてください。</h3>
            <p>
              口の動きは自然か。声と顔は合っているか。
              <br />
              同格のライバルに話しかけられる感覚はあるか。
            </p>
            <small>これは試作です。採用は、あなたの確認後に決めます。</small>
          </div>
        </section>
      </div>
      <footer className={styles.footer}>
        <p>
          音声：VOICEVOX Nemo（女声6） ·{" "}
          <a
            href="https://voicevox.hiroshiba.jp/nemo/term/"
            target="_blank"
            rel="noreferrer"
          >
            音声の利用規約 ↗
          </a>
        </p>
        <p>
          Model: met4citizen / TalkingHead MPFB sample (CC0), created with
          Blender and MPFB.{" "}
          <a
            href="https://github.com/met4citizen/TalkingHead"
            target="_blank"
            rel="noreferrer"
          >
            出典 ↗
          </a>
        </p>
        <p>
          用意済みの素材を再生しています。AI生成・課金・目標への投稿は行いません。
        </p>
      </footer>
    </main>
  );
}
