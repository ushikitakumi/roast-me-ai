"use client";
import {
  useQuery,
  useInfiniteQuery,
  useQueryClient,
  type InfiniteData,
} from "@tanstack/react-query";
import { useRef, useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { api, operationKey } from "@/lib/api";
import MediaStage from "@/components/MediaStage";
import { stopAllMedia } from "@/lib/media";
import { goalForm, progressForm } from "@/lib/forms";

import type { components } from "@/lib/schema";
type Goal = components["schemas"]["Goal"];
type Roast = components["schemas"]["Roast"];
type Me = components["schemas"]["Me"];
type Timeline = components["schemas"]["Timeline"];
const categories = [
  ["DONE", "できた"],
  ["PARTIAL", "一部できた"],
  ["NOT_DONE", "できなかった"],
  ["REST", "意図的な休み"],
  ["UNWELL", "体調不良"],
];
export default function CoreApp({
  initialGoal = null,
  initialCreate = false,
  initialSettings = false,
}: {
  initialGoal?: string | null;
  initialCreate?: boolean;
  initialSettings?: boolean;
}) {
  const router = useRouter();
  const client = useQueryClient();
  const [selected, setSelected] = useState<string | null>(initialGoal);
  const [filter, setFilter] = useState("ACTIVE");
  const [create, setCreate] = useState(initialCreate);
  const [settings, setSettings] = useState(initialSettings);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [reply, setReply] = useState<Roast | null>(null);
  const [notice, setNotice] = useState("");
  const [requestVideo, setRequestVideo] = useState(false);
  const [progressCategory, setCategory] = useState("PARTIAL");
  const [body, setBody] = useState("");
  const op = useRef<{ signature: string; key: string } | null>(null);
  const me = useQuery({
    queryKey: ["me"],
    queryFn: () => api<Me>("/me"),
    refetchInterval: 5000,
  });
  const goals = useInfiniteQuery({
    queryKey: ["goals", filter],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) =>
      api<components["schemas"]["GoalList"]>(
        `/goals?status=${filter}&limit=20${pageParam ? "&cursor=" + encodeURIComponent(pageParam) : ""}`,
      ),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const goalItems = goals.data?.pages.flatMap((page) => page.items);
  const goal = useQuery({
    queryKey: ["goal", selected],
    queryFn: () => api<Goal>(`/goals/${selected}`),
    enabled: !!selected,
  });
  const timeline = useInfiniteQuery({
    queryKey: ["timeline", selected],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) =>
      api<Timeline>(
        `/goals/${selected}/timeline?limit=20${pageParam ? "&cursor=" + encodeURIComponent(pageParam) : ""}`,
        { signal },
      ),
    enabled: !!selected,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const historyItems = timeline.data?.pages.flatMap((page) => page.items);
  function suppressCachedRoasts() {
    client.setQueriesData<Roast>({ queryKey: ["roast"] }, (old) =>
      old
        ? {
            ...old,
            visibility: "SUPPRESSED",
            text: null,
            audioUrl: null,
            videoUrl: null,
            pollAfterMs: null,
          }
        : old,
    );
    client.setQueriesData<InfiniteData<Timeline>>(
      { queryKey: ["timeline"] },
      (old) =>
        old
          ? {
              ...old,
              pages: old.pages.map((page) => ({
                ...page,
                items: page.items.filter((item) => item.type !== "ROAST"),
              })),
            }
          : old,
    );
  }
  useEffect(() => {
    const ch = new BroadcastChannel("roast-settings");
    ch.onmessage = () => {
      stopAllMedia();
      setReply(null);
      suppressCachedRoasts();
      void client.invalidateQueries();
    };
    return () => ch.close();
  }, [client]);
  async function action<T>(
    signature: string,
    run: (key: string) => Promise<T>,
    done?: (value: T) => void,
  ) {
    setBusy(true);
    setError("");
    op.current = operationKey(op.current, signature);
    try {
      const value = await run(op.current.key);
      op.current = null;
      done?.(value);
      await client.invalidateQueries();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作に失敗しました。");
    } finally {
      setBusy(false);
    }
  }
  async function setting(level: string, paused: boolean) {
    stopAllMedia();
    setReply(null);
    await client.cancelQueries();
    suppressCachedRoasts();
    const ch = new BroadcastChannel("roast-settings");
    ch.postMessage("invalidate");
    ch.close();
    await action(
      `settings:${level}:${paused}`,
      () =>
        api("/me/settings", {
          method: "PUT",
          body: { roastIntensity: level, paused },
        }),
      () => {
        setNotice(
          paused
            ? "煽りを停止しました。記録は続けられます。"
            : "設定を更新しました。",
        );
      },
    );
  }
  return (
    <div className="shell">
      <aside>
        <a className="brand" href="/">
          ROAST<span>ME</span>
          <small>行動で、言い返せ。</small>
        </a>
        <div className="nav-label">YOUR ARENA</div>
        <button
          className="nav active"
          onClick={() => {
            router.push("/");
            setSelected(null);
            setCreate(false);
          }}
        >
          ↗ 目標一覧
        </button>
        <button className="nav" onClick={() => router.push("/settings")}>
          ⚙ 強度と設定
        </button>
        <div className="side-bottom">
          <span className="dot" /> PRIVATE SPACE
          <p>あなたとライバルだけの場所。</p>
        </div>
      </aside>
      <main>
        <header>
          <span className="eyebrow">THE NEXT MOVE IS YOURS.</span>
          <a className="mobile-settings" href="/settings">
            設定
          </a>
          <button
            className={me.data?.paused ? "resume" : "stop"}
            disabled={
              busy ||
              !me.data ||
              (me.data.safety.autoPaused &&
                !!me.data.safety.resumeAllowedAt &&
                Date.parse(me.data.safety.resumeAllowedAt) > Date.now())
            }
            onClick={() =>
              void setting(me.data!.roastIntensity, !me.data!.paused)
            }
          >
            {me.data?.paused ? "煽りを再開する" : "煽りを停止する"}
          </button>
        </header>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        {(goals.error || me.error || goal.error || timeline.error) && (
          <div className="error" role="alert">
            読み込みに失敗しました。サーバの起動を確認してください。
          </div>
        )}
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {me.data?.paused && (
          <div className="paused">
            煽りは停止中です。目標の記録・達成は引き続き使えます。
            {me.data.safety.autoPaused && (
              <p>
                再開できる日時:{" "}
                {new Date(me.data.safety.resumeAllowedAt!).toLocaleString(
                  "ja-JP",
                )}
              </p>
            )}
          </div>
        )}
        {settings && (
          <section className="panel">
            <h2>ライバルとの距離感</h2>
            <p>強度を変えても、煽るのは行動だけ。</p>
            <div className="choices">
              {[
                ["MILD", "弱"],
                ["NORMAL", "標準"],
                ["SAVAGE", "強"],
              ].map(([value, label]) => (
                <button
                  key={value}
                  aria-pressed={me.data?.roastIntensity === value}
                  disabled={busy || !me.data}
                  onClick={() => void setting(value, me.data!.paused)}
                >
                  {label}
                </button>
              ))}
            </div>
          </section>
        )}
        {settings && me.data && (
          <section className="panel">
            <h2>生成予算</h2>
            <p>確定した費用と、確認待ちの予約を合算しています。</p>
            <div className="budget-grid">
              <div>
                <small>今日 / 日本時間</small>
                <p>
                  {money(me.data.budget.dailyCommittedMicroUsd)} /{" "}
                  {me.data.budget.dailyLimitMicroUsd === null
                    ? "実測後に設定"
                    : money(me.data.budget.dailyLimitMicroUsd)}
                </p>
              </div>
              <div>
                <small>今月 / 日本時間</small>
                <p>
                  {money(me.data.budget.monthlyCommittedMicroUsd)} /{" "}
                  {money(me.data.budget.monthlyLimitMicroUsd)}
                </p>
              </div>
            </div>
            <small>インフラ費用は別枠です。スタブ検証の実費は0です。</small>
          </section>
        )}
        {!selected && !create && !settings && (
          <>
            <div className="heading">
              <div>
                <p className="eyebrow orange">MAKE YOUR MOVE</p>
                <h1>
                  宣言は、もういい。
                  <br />
                  <span>次は行動で見せよう。</span>
                </h1>
                <p>小さな一歩でもいい。ライバルに言い返す材料を、ここに。</p>
              </div>
              <button
                className="primary"
                onClick={() => router.push("/goals/new")}
              >
                ＋ 目標を宣言する
              </button>
            </div>
            <div className="tabs">
              {[
                ["ACTIVE", "進行中"],
                ["ACHIEVED", "達成済み"],
                ["ABANDONED", "取りやめ"],
              ].map(([value, label]) => (
                <button
                  key={value}
                  aria-pressed={filter === value}
                  onClick={() => setFilter(value)}
                >
                  {label}
                </button>
              ))}
            </div>
            <div className="goals">
              {goalItems?.map((g) => (
                <button
                  className="goal-card"
                  key={g.id}
                  onClick={() => {
                    router.push(`/goals/${g.id}`);
                    setSelected(g.id);
                    setReply(null);
                  }}
                >
                  <div className="card-top">
                    <span>
                      {g.status === "ACTIVE"
                        ? "IN PROGRESS"
                        : g.status === "ACHIEVED"
                          ? "YOU WON"
                          : "CLOSED"}
                    </span>
                    <span>↗</span>
                  </div>
                  <h2>{g.title}</h2>
                  <p>{g.successCriteria}</p>
                  <footer>
                    {g.deadline
                      ? `期限 ${g.deadline}`
                      : "自分のペースで、前へ。"}
                  </footer>
                </button>
              ))}
            </div>
            {goals.isLoading && <p>読み込み中…</p>}
            {goalItems?.length === 0 && (
              <section className="empty">
                <span>01 / DECLARE</span>
                <h2>最初の勝負を、始めよう。</h2>
                <p>
                  何ができたら、あなたの勝ち？
                  <br />
                  勝利条件を決めて、ライバルに宣言しよう。
                </p>
                <button onClick={() => router.push("/goals/new")}>
                  目標をつくる →
                </button>
              </section>
            )}
            {goals.hasNextPage && (
              <button
                className="quiet"
                disabled={goals.isFetchingNextPage}
                onClick={() => void goals.fetchNextPage()}
              >
                目標をもっと見る
              </button>
            )}
            <div className="rule">
              <strong>HOW IT WORKS</strong>
              <span>01 宣言する</span>
              <span>02 行動して報告</span>
              <span>03 達成して勝つ</span>
            </div>
          </>
        )}
        {create && (
          <section className="panel form-panel">
            <button className="back" onClick={() => router.push("/")}>
              ← 目標一覧
            </button>
            <p className="eyebrow orange">DECLARE YOUR WIN</p>
            <h1>
              何ができたら、
              <br />
              あなたの勝ち？
            </h1>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                const data = new FormData(e.currentTarget);
                const input = {
                  title: data.get("title"),
                  successCriteria: data.get("criteria"),
                  description: data.get("description"),
                  deadline: data.get("deadline") || null,
                  requestVideo:
                    requestVideo && !!me.data?.videoAvailability.available,
                };
                const parsed = goalForm.safeParse(input);
                if (!parsed.success) {
                  setError("必須項目と文字数を確認してください。");
                  return;
                }
                void action(
                  JSON.stringify(parsed.data),
                  (key) =>
                    api<{ goal: Goal; roast: Roast }>("/goals", {
                      method: "POST",
                      key,
                      body: parsed.data,
                    }),
                  (r) => {
                    router.push(`/goals/${r.goal.id}`);
                    setCreate(false);
                    setSelected(r.goal.id);
                    setReply(r.roast);
                  },
                );
              }}
            >
              <label>
                目標のタイトル
                <input
                  name="title"
                  required
                  maxLength={100}
                  placeholder="例: 参考書を終える"
                />
              </label>
              <label>
                勝利条件
                <textarea
                  name="criteria"
                  required
                  maxLength={1000}
                  placeholder="例: 参考書を1冊、最後まで解く"
                />
              </label>
              <label>
                取り組み方・宣言
                <textarea
                  name="description"
                  maxLength={1000}
                  placeholder="例: 毎日30分取り組む"
                />
              </label>
              <label>
                期限（任意）
                <input type="date" name="deadline" />
              </label>
              <p className="muted">
                宣言は後から編集できません。変更したい場合は取りやめて、新しい目標を作成します。
              </p>
              <VideoChoice
                available={!!me.data?.videoAvailability.available}
                selected={requestVideo}
                onChange={setRequestVideo}
              />
              <button className="primary" disabled={busy}>
                {busy ? "記録中…" : "この目標を宣言する →"}
              </button>
            </form>
          </section>
        )}
        {selected && goal.data && (
          <>
            <button
              className="back"
              onClick={() => {
                router.push("/");
                setSelected(null);
                setReply(null);
              }}
            >
              ← 目標一覧
            </button>
            <div className="heading">
              <div>
                <p className="eyebrow orange">YOUR CHALLENGE</p>
                <h1>{goal.data.title}</h1>
                <p className="criteria">
                  勝利条件 / {goal.data.successCriteria}
                </p>
                <p>{goal.data.description}</p>
                {goal.data.deadline && (
                  <p>
                    期限: {goal.data.deadline}
                    {goal.data.status === "ACTIVE" &&
                    goal.data.deadline <
                      new Intl.DateTimeFormat("sv-SE", {
                        timeZone: "Asia/Tokyo",
                      }).format(new Date())
                      ? "（期限を過ぎています。引き続き取り組めます）"
                      : ""}
                  </p>
                )}
              </div>
            </div>
            <section className="rival">
              <div className="rival-symbol" aria-hidden="true">
                R<span>↗</span>
              </div>
              <div>
                <p className="eyebrow">YOUR RIVAL</p>
                <>
                  {reply?.visibility === "VISIBLE" ? (
                    <MediaStage initial={reply} />
                  ) : (
                    <p className="quote">
                      {me.data?.paused
                        ? "今は記録に集中しよう。"
                        : goal.data.status === "ACHIEVED"
                          ? "今回は、あなたの勝ち。"
                          : "次の一歩、見せてよ。"}
                    </p>
                  )}
                </>
                <small>
                  キャラクターと音声は準備中。今はテキストで応答します。
                </small>
              </div>
            </section>
            {goal.data.status === "ACTIVE" ? (
              <div className="detail-grid">
                <section className="panel">
                  <h2>今日、どこまで進んだ？</h2>
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      const input = {
                        body,
                        progressCategory,
                        requestVideo:
                          requestVideo &&
                          !!me.data?.videoAvailability.available,
                      };
                      const parsed = progressForm.safeParse(input);
                      if (!parsed.success) {
                        setError("進捗本文を1〜1000字で入力してください。");
                        return;
                      }
                      void action(
                        `${selected}:${JSON.stringify(parsed.data)}`,
                        (key) =>
                          api<{ roast: Roast }>(`/goals/${selected}/progress`, {
                            method: "POST",
                            key,
                            body: parsed.data,
                          }),
                        (r) => {
                          setReply(r.roast);
                          setBody("");
                          setRequestVideo(false);
                        },
                      );
                    }}
                  >
                    <div className="choices">
                      {categories.map(([value, label]) => (
                        <button
                          type="button"
                          key={value}
                          aria-pressed={progressCategory === value}
                          onClick={() => setCategory(value)}
                        >
                          {label}
                        </button>
                      ))}
                    </div>
                    <label>
                      進捗の報告
                      <textarea
                        value={body}
                        onChange={(e) => setBody(e.target.value)}
                        required
                        maxLength={1000}
                        placeholder="できたこと、できなかったこと。そのままでいい。"
                      />
                    </label>
                    <VideoChoice
                      available={!!me.data?.videoAvailability.available}
                      selected={requestVideo}
                      onChange={setRequestVideo}
                    />
                    <button className="primary" disabled={busy}>
                      {busy ? "記録中…" : "進捗を報告する →"}
                    </button>
                  </form>
                </section>
                <section className="panel win">
                  <p className="eyebrow">YOU DECIDE THE FINISH.</p>
                  <h2>
                    やり切ったなら、
                    <br />
                    勝利を宣言しよう。
                  </h2>
                  <p>
                    判定するのはあなた自身。
                    <br />
                    ライバルに負けを認めさせよう。
                  </p>
                  <button
                    disabled={busy}
                    onClick={() =>
                      void action(
                        `achieve:${selected}`,
                        (key) =>
                          api<{ roast: Roast }>(`/goals/${selected}/achieve`, {
                            method: "POST",
                            key,
                          }),
                        (r) => setReply(r.roast),
                      )
                    }
                  >
                    達成した。文句ある？ ↗
                  </button>
                  <button
                    className="quiet"
                    disabled={busy}
                    onClick={() => {
                      if (
                        confirm(
                          "この目標を取りやめます。記録は残りますが、再開はできません。",
                        )
                      )
                        void action(`abandon:${selected}`, (key) =>
                          api(`/goals/${selected}/abandon`, {
                            method: "POST",
                            key,
                          }),
                        );
                    }}
                  >
                    この目標を取りやめる
                  </button>
                </section>
              </div>
            ) : (
              <p className="notice">
                {goal.data.status === "ACHIEVED"
                  ? "目標を達成しました。おめでとう。"
                  : "この目標は取りやめました。記録はここに残ります。"}
              </p>
            )}
            <section className="history">
              <h2>
                これまでの足跡 <small>YOUR RECORD</small>
              </h2>
              {historyItems?.map((item) => (
                <article key={`${item.type}:${item.data.id}`}>
                  <time>{new Date(item.at).toLocaleString("ja-JP")}</time>
                  <div>
                    <span className="eyebrow">
                      {item.type === "ROAST"
                        ? "RIVAL"
                        : item.type === "PROGRESS"
                          ? "YOU"
                          : "START"}
                    </span>
                    <>
                      {"text" in item.data ? (
                        <MediaStage initial={item.data as Roast} />
                      ) : (
                        <p>
                          {"body" in item.data
                            ? item.data.body
                            : "目標を宣言しました。"}
                        </p>
                      )}
                    </>
                  </div>
                  {item.type === "PROGRESS" && (
                    <button
                      className="quiet"
                      disabled={busy}
                      onClick={() => {
                        if (
                          confirm(
                            "この報告と、これを参照する煽りを非表示にします。発生済みの費用は戻りません。",
                          )
                        ) {
                          stopAllMedia();
                          setReply(null);
                          void client.cancelQueries();
                          suppressCachedRoasts();
                          void action(`delete:${item.data.id}`, () =>
                            api(`/goals/${selected}/progress/${item.data.id}`, {
                              method: "DELETE",
                            }),
                          );
                        }
                      }}
                    >
                      削除
                    </button>
                  )}
                </article>
              ))}
            </section>
          </>
        )}
        <footer className="disclaimer">
          このアプリはあなたの行動を煽ります。強度は変更でき、いつでも停止できます。
        </footer>
      </main>
    </div>
  );
}
function VideoChoice({
  available,
  selected,
  onChange,
}: {
  available: boolean;
  selected: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="video-off">
      <label>
        <input
          type="checkbox"
          checked={selected && available}
          onChange={(e) => onChange(e.target.checked)}
          disabled={!available}
        />{" "}
        動画で煽ってもらう
      </label>
      <small>
        {available
          ? "スタブ検証モード: 実際のAI・音声は呼びません。"
          : "動画機能は準備中、または生成枠を使用中です。通常投稿は無料です。"}
      </small>
    </div>
  );
}

function money(value: number) {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 4,
  }).format(value / 1000000);
}
