# 03. API 設計

> **v1 のスコープと、削った機能の理由は [README](./README.md) を参照。**

## 1. 共通仕様

| 項目 | 仕様 |
|---|---|
| ベース URL | `https://<railway-app>/api/v1`（ブラウザからは直接叩かず、Next.js の BFF 経由） |
| バージョニング | パス方式（`/api/v1`）で固定。Spring Framework 7 の API バージョニング機能（`spring.mvc.apiversion.*` + `@RequestMapping(version = ...)`）は、v2 が必要になるまで**導入しない**（BFF が唯一のクライアントであり、複数バージョンの同時提供が不要なため） |
| 形式 | JSON (`application/json; charset=utf-8`) |
| 認証 | **HTTP Basic**（`Authorization: Basic <base64>`）。資格情報は Next.js のサーバ側環境変数が保持し、ブラウザには渡さない。【将来】`Bearer <JWT>` へ移行 |
| 日時 | ISO 8601 / UTC（例 `2026-09-04T12:34:56Z`）。表示用のローカル変換はフロント責務 |
| ID | UUID v7（時系列ソート可能。DB のインデックス局所性が良い） |
| 命名 | JSON フィールドは lowerCamelCase |
| ページング | カーソル方式（`?limit=20&cursor=<opaque>`）。レスポンスに `nextCursor` |
| 冪等性 | `POST` のうち副作用が重いもの（進捗投稿・目標作成）は `Idempotency-Key` ヘッダを受け付け、24時間同一キーは同一レスポンスを返す |
| エラー | RFC 9457 Problem Details (`application/problem+json`) |

### 1.1 エラーレスポンス

```json
{
  "type": "https://roast-me.example/problems/daily-cost-limit",
  "title": "Daily cost limit reached",
  "status": 200,
  "detail": "本日の生成上限に達したため、テキストのみで返しています。",
  "code": "DEGRADED_TEXT_ONLY"
}
```

`code` はフロントで分岐するための安定した機械可読キー。`title` / `detail` は表示可能な日本語。

| HTTP | `code` | 意味 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 入力値エラー。`errors: [{field, message}]` を付与 |
| 401 | `UNAUTHENTICATED` | Basic 認証の失敗 |
| 404 | `RESOURCE_NOT_FOUND` | 他人のリソースもここに落とす（存在秘匿）【将来の複数ユーザー時に意味を持つ】 |
| 409 | `INVALID_STATE_TRANSITION` | 達成済み目標を再度達成しようとした等 |
| 422 | `UNPROCESSABLE_INPUT` | 入力が煽り生成に適さない（空白のみ、対応外言語など） |
| 429 | `ROAST_RATE_LIMIT_EXCEEDED` | 1日の生成上限超過 |
| 503 | `LLM_UNAVAILABLE` | フォールバックも失敗した場合のみ（通常は発生しない） |

**縮退（`DEGRADED`）はエラーではない。** コスト上限到達・音声/映像段の失敗はいずれも 2xx で返し、`roast.status` で表現する（§3.2）。

---

## 2. エンドポイント一覧

| Method | Path | 概要 | 優先度 |
|---|---|---|---|
| GET | `/me` | 自分のプロフィールと設定 | **P0** |
| PUT | `/me/settings` | 設定更新（煽り強度・セーフモード・生成プロバイダ） | **P0** |
| GET | `/personas` | アバター一覧（v1 は1件） | **P0** |
| POST | `/goals` | 目標作成（+ 初回煽りの生成を開始） | **P0** |
| GET | `/goals` | 目標一覧 | **P0** |
| GET | `/goals/{goalId}` | 目標詳細 | **P0** |
| POST | `/goals/{goalId}/progress` | 進捗報告（+ 煽り生成の開始）★中核 | **P0** |
| **GET** | **`/roasts/{roastId}`** | **煽りの現在状態を取得（ポーリング先）★中核** | **P0** |
| POST | `/roasts/{roastId}/reactions` | リアクション（怒りゲージ加算） | **P0** |
| GET | `/goals/{goalId}/timeline` | 進捗と煽りの時系列（過去の動画を再生できる） | **P0** |
| GET | `/me/anger-gauge` | 怒りゲージ現在値 | **P0** |
| POST | `/goals/{goalId}/achieve` | 達成（+ 掌返し煽り。**v1 はテキストのみ**） | **P0** |
| GET | `/stats/cost` | プロバイダ別のコスト・レイテンシ集計 | P1 |
| PATCH | `/goals/{goalId}` | 目標更新 | P1 |
| POST | `/goals/{goalId}/abandon` | 放棄 | P1 |
| DELETE | `/me` | 退会 | 【将来】 |
| POST | `/push/subscriptions` | Web Push 購読登録 | 【将来】 |

---

## 3. 主要エンドポイント詳細

### 3.1 進捗報告（中核）

```
POST /api/v1/goals/{goalId}/progress
Authorization: Basic <base64>
Idempotency-Key: 018f2c3d-...
Content-Type: application/json
```

**リクエスト**

```json
{
  "body": "今日は参考書を30分だけ読んだ。眠かった。",
  "selfRating": 2,
  "kind": "REPORT"
}
```

| フィールド | 型 | 必須 | 制約 |
|---|---|---|---|
| `body` | string | ○ | 1〜1000字。前後空白トリム後に空なら 422 |
| `selfRating` | integer | ○ | 1〜5（1=全然ダメ, 5=完璧） |
| `kind` | enum | – | `REPORT`(既定) / `DECLARATION`（決意表明） |

**レスポンス `202 Accepted`**

煽り文までは同期で完成させ、音声・映像は非同期で続く。**`202` は「テキストは完成した、続きは走っている」という意味。**

```json
{
  "progressLog": {
    "id": "018f2c3d-8a11-7c02-9f01-6b1d9e5c0a31",
    "goalId": "018f2b90-...",
    "body": "今日は参考書を30分だけ読んだ。眠かった。",
    "selfRating": 2,
    "kind": "REPORT",
    "createdAt": "2026-09-04T12:34:56Z"
  },
  "roast": {
    "id": "018f2c3d-9b44-7d10-a220-0f3e77c81b2e",
    "status": "TEXT_DONE",
    "goalId": "018f2b90-...",
    "trigger": "PROGRESS_REPORTED",
    "personaId": "senpai",
    "intensity": "NORMAL",
    "text": "30分。へえ、30分ね。それ「勉強した」じゃなくて「本を開いた」って言うんだよ。眠いのはわかるけど、眠さに負けた記録を律儀に報告してくるところは嫌いじゃない。明日も30分で満足するなら、その参考書は一生読み終わらないけど。",
    "emotion": "SMIRK",
    "audioUrl": null,
    "videoUrl": null,
    "createdAt": "2026-09-04T12:34:58Z"
  },
  "angerGauge": { "value": 40, "delta": 0 },
  "pollAfterMs": 2000
}
```

**フロントの扱い**: `roast.text` を即座に表示し、`roast.id` を使って §3.2 のポーリングを開始する。`pollAfterMs` はサーバが指示する次回間隔。

**エラー**

| 状況 | HTTP / code |
|---|---|
| 目標が存在しない | 404 `RESOURCE_NOT_FOUND` |
| 目標が `ACHIEVED` / `ABANDONED` | 409 `INVALID_STATE_TRANSITION` |
| 1日の生成上限超過 | 429 `ROAST_RATE_LIMIT_EXCEEDED` |
| 日次コスト上限到達 | **202 で返す**。`roast.status = "DEGRADED"`、テキストのみ |
| セーフモード発動 | **200 で返す**（後述 3.4） |

### 3.2 煽りの状態取得（ポーリング先）★中核

```
GET /api/v1/roasts/{roastId}
```

**フロントはこの 1 エンドポイントだけを 2〜3秒間隔で叩く。** SSE も WebSocket も使わない（[README](./README.md) の「選ばなかった選択肢」参照）。

**`status = AUDIO_DONE` のとき**

```json
{
  "id": "018f2c3d-9b44-...",
  "status": "AUDIO_DONE",
  "text": "30分。へえ、30分ね。…",
  "emotion": "SMIRK",
  "audioUrl": "https://<r2-public-or-signed>/audio/018f2c3d-....wav?X-Amz-Expires=3600&...",
  "videoUrl": null,
  "progress": { "stage": "VIDEO", "elapsedMs": 8200, "estimatedTotalMs": 60000 },
  "pollAfterMs": 3000
}
```

**`status = VIDEO_DONE` のとき**

```json
{
  "id": "018f2c3d-9b44-...",
  "status": "VIDEO_DONE",
  "text": "30分。へえ、30分ね。…",
  "emotion": "SMIRK",
  "audioUrl": "https://.../audio/018f2c3d-....wav?...",
  "videoUrl": "https://.../video/018f2c3d-....mp4?...",
  "progress": null,
  "pollAfterMs": null
}
```

**`status = DEGRADED` のとき**

```json
{
  "id": "018f2c3d-9b44-...",
  "status": "DEGRADED",
  "text": "30分。へえ、30分ね。…",
  "emotion": "SMIRK",
  "audioUrl": "https://.../audio/018f2c3d-....wav?...",
  "videoUrl": null,
  "degradedReason": "DAILY_COST_LIMIT",
  "pollAfterMs": null
}
```

#### ポーリングの契約

| フィールド | 意味 |
|---|---|
| `status` | `PENDING` / `TEXT_DONE` / `AUDIO_DONE` / `VIDEO_SUBMITTED` / `VIDEO_DONE` / `DEGRADED` / `FAILED`（[04](./04-data-model.md) §3.1） |
| `audioUrl` / `videoUrl` | **R2 の署名付き URL**（有効期限 1時間）。`null` の間はまだ完成していない |
| `progress.stage` | 現在の処理段。UI に「音声を作っています」等を出すために使う |
| `pollAfterMs` | **次にポーリングすべき間隔をサーバが指示する。** 終端状態では `null`（＝もう叩かなくてよい） |
| `degradedReason` | `DAILY_COST_LIMIT` / `SPEECH_FAILED` / `VIDEO_FAILED` / `VIDEO_TIMEOUT` |

- **`pollAfterMs` をサーバが返す設計にする理由**: 段階ごとに適切な間隔が違う（音声は 2秒、映像は 3〜5秒）。フロントに間隔のロジックを持たせず、サーバが制御する。
- フロントは**一定時間（既定 3分）でポーリングを打ち切る**。打ち切っても生成は続き、完成後はタイムライン（§3.6）に現れる（Q16 の (d)）。
- 署名付き URL は**リクエストのたびに発行し直す**。URL を DB に保存しない（保存するのは R2 のキーのみ）。

### 3.3 目標作成

```
POST /api/v1/goals
```

```json
{
  "title": "TOEIC 800点を取る",
  "description": "毎日30分、単語帳と公式問題集",
  "category": "STUDY",
  "deadline": "2026-12-31"
}
```

`202 Accepted` — レスポンスは `{ goal, roast }`。`roast.trigger = "GOAL_DECLARED"`、`roast.status = "TEXT_DONE"`。進捗報告と同じくポーリングで続きを取る。

| フィールド | 制約 |
|---|---|
| `title` | 1〜100字、必須 |
| `description` | 0〜1000字 |
| `category` | `STUDY` / `HEALTH` / `WORK` / `HABIT` / `CREATIVE` / `OTHER` |
| `deadline` | ISO 日付。過去日は 400 |

### 3.4 セーフモード時のレスポンス

ユーザー入力に深刻な落ち込み・自傷の兆候が検知された場合、**エラーにせず**、煽らない応答を返す。**音声も映像も生成しない**（`200 OK`、ポーリング不要）。

```json
{
  "progressLog": { "...": "..." },
  "roast": {
    "id": "018f...",
    "status": "DEGRADED",
    "trigger": "SAFE_MODE",
    "intensity": "NONE",
    "text": "……ごめん、今日は煽るのやめとく。無理してない？ 目標より先に、あんたが大事だから。",
    "emotion": "WORRIED",
    "audioUrl": null,
    "videoUrl": null,
    "pollAfterMs": null
  },
  "safeMode": {
    "activated": true,
    "reason": "SELF_HARM_SIGNAL",
    "resources": [
      { "label": "こころの健康相談統一ダイヤル", "url": "https://www.mhlw.go.jp/..." }
    ],
    "roastPausedUntil": "2026-09-05T12:34:58Z"
  }
}
```

セーフモード発動後 24 時間は煽り生成を停止し、設定画面で明示的に解除するまで穏当なモードを維持する。

### 3.5 目標の達成（カタルシス）

```
POST /api/v1/goals/{goalId}/achieve
```

```json
{ "comment": "受かった。文句あるか" }
```

`200 OK` — **v1 は動画を生成しない**ため、`202` ではなく `200` で完結する。

```json
{
  "goal": { "id": "...", "status": "ACHIEVED", "achievedAt": "2026-12-20T09:00:00Z" },
  "roast": {
    "status": "DEGRADED",
    "trigger": "GOAL_ACHIEVED",
    "text": "……は？ 800？ うそでしょ。……いや、ごめん。ちゃんと見てた。3ヶ月前に「30分で満足するな」って言われて、次の日から45分にしたの、私知ってるから。……今回は、あんたの勝ちでいい。次はもっと難しいの持ってきなさいよ。",
    "emotion": "RELUCTANT_PRAISE",
    "audioUrl": null,
    "videoUrl": null
  },
  "reward": {
    "angerConverted": 85
  }
}
```

`reward.angerConverted` は達成時点の怒りゲージ値。**溜めた怒りが達成で「消費される」演出**の入力になる（ゲージが 0 に落ちるアニメーション）。

> 【将来】達成時も動画を生成する場合は `202 Accepted` + ポーリングに揃える。**レスポンス形状は既にその形になっている**ので、変更は `status` の遷移だけで済む。

### 3.6 タイムライン

```
GET /api/v1/goals/{goalId}/timeline?limit=20&cursor=...
```

```json
{
  "items": [
    { "type": "ROAST", "at": "2026-09-04T12:34:58Z", "data": {
        "id": "018f...", "status": "VIDEO_DONE", "text": "30分。へえ…",
        "emotion": "SMIRK",
        "audioUrl": "https://.../audio/018f....wav?...",
        "videoUrl": "https://.../video/018f....mp4?..."
    }},
    { "type": "PROGRESS", "at": "2026-09-04T12:34:56Z", "data": { "...": "..." } },
    { "type": "GOAL_CREATED", "at": "2026-09-01T00:00:00Z", "data": { "...": "..." } }
  ],
  "nextCursor": "eyJhdCI6..."
}
```

**過去の動画が再生できることが、Hedra Character-3（非同期）を選んだ理由そのもの**である。`videoUrl` は R2 の署名付き URL を都度発行する。

### 3.7 リアクション

```
POST /api/v1/roasts/{roastId}/reactions
```

```json
{ "reaction": "ANGRY" }
```

| `reaction` | 効果 |
|---|---|
| `ANGRY` | 怒りゲージ +10（上限100）。煽りの品質シグナル: 良 |
| `LAUGH` | ゲージ +3。品質シグナル: 良 |
| `HURT` | ゲージ変動なし。**品質シグナル: 悪**。同一強度で 3回連続なら強度を自動的に 1段下げ、その旨を通知する |

`200 OK` → `{ "angerGauge": { "value": 50, "delta": 10 }, "intensityAdjusted": null }`

同一 `roastId` への再送は最後の 1件で上書き（`UNIQUE(roast_id, user_id)` の upsert）。

### 3.8 設定更新

```
PUT /api/v1/me/settings
```

```json
{
  "roastIntensity": "SAVAGE",
  "personaId": "senpai",
  "safeMode": false,
  "llmProvider": "anthropic",
  "videoEnabled": true
}
```

| `roastIntensity` | 内容 |
|---|---|
| `MILD` | 軽口。皮肉は薄く、最後に必ず前向きな一言 |
| `NORMAL` | 既定。しっかり刺すが、行動にのみ言及 |
| `SAVAGE` | 容赦なし。ただし NG ラインは同じ（人格・容姿・出自には触れない） |
| `NONE` | 煽り停止。淡々と記録のみ返す |

| 追加フィールド | 内容 |
|---|---|
| `llmProvider` | `anthropic` / `openai` / `template`。**画面から切り替えて比較できるようにする**（Q15） |
| `videoEnabled` | `false` にすると動画生成を行わない。**コストを節約したい日に手動で落とせる** |

### 3.9 コスト集計（ポートフォリオの一枚看板）

```
GET /api/v1/stats/cost?from=2026-09-01&to=2026-09-30
```

```json
{
  "byProvider": [
    { "provider": "anthropic", "model": "claude-opus-5",  "count": 42, "totalCostUsd": 0.987, "avgLatencyMs": 2310, "angryRate": 0.62 },
    { "provider": "openai",    "model": "gpt-...",         "count": 38, "totalCostUsd": 0.512, "avgLatencyMs": 1890, "angryRate": 0.55 },
    { "provider": "template",  "model": null,              "count": 11, "totalCostUsd": 0.0,   "avgLatencyMs": 4,    "angryRate": 0.18 }
  ],
  "media": {
    "speechCount": 80, "speechCostUsd": 0.0,
    "videoCount": 71,  "videoCostUsd": 8.34
  },
  "dailyLimitUsd": 0.70,
  "todayUsd": 0.42
}
```

**このエンドポイントの出力が README のグラフになる。** 「プロバイダを変えたらコストと反応がどう変わったか」を実測で示すのが Q15 の目的であり、そのためのデータは `roasts` テーブルに全件そろっている（[04](./04-data-model.md) §2.6）。

`angryRate` は `ANGRY` + `LAUGH` の割合。**単価だけでなく品質シグナルを並べて初めて比較になる。**

---

## 4. レート制限とコストガード

**Redis を使わない。** 単一ユーザー・単一インスタンスでは DB カウンタと Caffeine で足りる。

| 対象 | 制限 | 実装 |
|---|---|---|
| 煽り生成（`POST /goals`, `/progress`, `/achieve`） | **1日 10回**（設定可） | `roasts` の当日件数を DB で数え、Caffeine に短時間キャッシュ |
| 全 API | 120 req / 分 | インメモリのスライディングウィンドウ |
| **日次コスト上限** | 環境変数で設定（既定 **$0.70/日** ≒ 月 $20） | `roasts.cost_micro_usd` + `roast_jobs.video_cost_micro_usd` の当日合計。**超過時は `videoEnabled=false` 相当 + テンプレート生成へ自動切替**（サービスは止めない） |

レスポンスヘッダ: `X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset`。

> **日次コスト上限は v1 の必須機能**である。動画生成の単価が LLM より一桁高いため、上限がないまま連打すると月額が数倍に跳ねる。Q12 の判断（上限到達で自動的にテキストのみへ縮退）はここで実装される。

---

## 5. OpenAPI

`springdoc-openapi` で `/v3/api-docs` を生成し、CI で `openapi.yaml` としてコミットする。フロントは `openapi-typescript` で型を自動生成し、手書きの API 型定義を持たない（バックエンドとフロントの型ずれを構造的に防ぐ）。

```
backend/build/openapi.yaml ──▶ frontend/src/lib/api/schema.d.ts (openapi-typescript)
                          └──▶ CI で差分検出、未反映なら fail
```

**TypeScript 未経験でも型が保証される**という点で、この自動生成は v1 において特に価値が高い。手書きの型定義を1つも書かずに済む。
