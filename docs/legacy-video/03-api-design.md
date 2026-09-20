> **旧動画生成方式の設計・検証記録（2026-09-19に保存）。新規開発の指示ではありません。** 現行方針は [現行ドキュメント](../README.md) を参照してください。

# 03. API 設計

> 更新日: 2026-09-08。状態と永続化は [04](./04-data-model.md)。以下は初期版の契約。

## 1. 共通仕様

`/api/v1`、JSON、lowerCamelCase、UUID、UTCのISO 8601日時。予算集計はAsia/Tokyo。ブラウザは認証済みNext.js BFF経由で接続する。所有者不一致は404。履歴はカーソル方式、既定20件。

目標作成・進捗投稿・達成・取りやめは `Idempotency-Key` 必須。同じ論理操作の再送では同じキーを使う。同一キーの異なる本文は409。同時再送は処理中として409 `REQUEST_IN_PROGRESS` を返し、処理を重複開始しない。確定結果は24時間保持するが、本文・メディアは現在の表示資格で再評価する。削除・停止前のレスポンスをそのまま再送しない。

| HTTP | 意味 |
|---|---|
| 201 | 目標/進捗を作成し、無料テキストで完了 |
| 202 | 作成成功、テキストを返し非同期メディア生成が残る |
| 200 | 読み取り・設定変更・達成・取りやめ |
| 204 | 進捗削除（繰り返しも同じ結果） |
| 400 / 401 / 404 | 入力不正 / 未認証 / 対象なし・閲覧不可 |
| 409 | 不正な状態遷移、冪等キー不一致、処理中、安全停止解除待ち |
| 429 | 短時間の過剰リクエスト。通常投稿を日次動画枠で制限しない |

エラーはRFC 9457 Problem Details、安定した `code` を持つ。予算不足・生成枠使用中・停止は投稿を保存したうえで201の無料応答。2xxの成功をproblem+jsonにしない。

## 2. エンドポイント

| Method | Path | 内容 |
|---|---|---|
| GET | `/me` | 固定キャラクター・音声、設定、安全状態、予算と動画利用可否 |
| PUT | `/me/settings` | 強度・手動停止の更新 |
| GET | `/personas` | 初期版はrival 1件、標準音声・クレジット付き |
| POST / GET | `/goals` | 作成 / 一覧 |
| GET | `/goals/{goalId}` | 勝利条件・状態・期限情報 |
| POST | `/goals/{goalId}/progress` | 進捗作成、任意の動画生成 |
| DELETE | `/goals/{goalId}/progress/{progressId}` | 報告削除・参照する煽りの無効化 |
| POST | `/goals/{goalId}/achieve` | 本人による達成、無料勝利文 |
| POST | `/goals/{goalId}/abandon` | 取りやめ、追い打ちなし |
| GET | `/goals/{goalId}/timeline` | 有効な報告・煽り・目標イベント |
| GET | `/roasts/{roastId}` | 本文・状態・手動再生用リンク |
| GET | `/roasts/{roastId}/media/{audioOrVideo}` | 都度認可するメディア配信 |

目標/進捗の編集API、取りやめ後の再開API、再生成APIは初期版に提供しない。怒りゲージ・リアクション・プロバイダ比較APIは後続。キャラクター/音声の選択APIは将来。

## 3. 目標・進捗の作成

### 3.1 目標

```json
{
  "title": "参考書を終える",
  "successCriteria": "参考書を1冊最後まで解く",
  "description": "毎日30分取り組む",
  "category": "STUDY",
  "deadline": "2026-12-31",
  "requestVideo": false
}
```

`title` は1〜100字、`successCriteria` は1〜1000字で必須。`description` は任意・最大1000字、`category` はSTUDY/HEALTH/WORK/HABIT/CREATIVE/OTHER（省略時OTHER）、`deadline` は任意の日付（作成時の過去日は400）。`requestVideo` は省略時false。作成後は宣言を変更しない。

### 3.2 進捗

```json
{
  "body": "30分の予定だったけど10分だけやった",
  "progressCategory": "PARTIAL",
  "requestVideo": true
}
```

| progressCategory | 表示 |
|---|---|
| DONE | できた |
| PARTIAL | 一部できた |
| NOT_DONE | できなかった |
| REST | 意図的な休み |
| UNWELL | 体調不良 |

区分必須、本文はトリム後1〜1000字必須。旧 `selfRating`、`kind`、怒りゲージは使わない。期限を過ぎたACTIVE目標には投稿可能、ACHIEVED/ABANDONEDには409。

### 3.3 作成レスポンス

```json
{
  "progressLog": {
    "id": "018f2c3d-8a11-7c02-9f01-6b1d9e5c0a31",
    "body": "30分の予定だったけど10分だけやった",
    "progressCategory": "PARTIAL"
  },
  "roast": {
    "id": "018f2c3d-9b44-7d10-a220-0f3e77c81b2e",
    "status": "TEXT_DONE",
    "visibility": "VISIBLE",
    "personaId": "rival",
    "intensity": "NORMAL",
    "text": "10分はやったんだな。宣言の30分には届いてないけど？",
    "audioUrl": null,
    "videoUrl": null,
    "pollAfterMs": 2000
  },
  "videoDecision": "ACCEPTED"
}
```

目標作成の場合は `progressLog` の代わりに `goal`。無料完了は `status=TEXT_ONLY`、URLと `pollAfterMs` はnull。`videoDecision` は `NOT_REQUESTED` / `ACCEPTED` / `DAILY_BUDGET` / `MONTHLY_BUDGET` / `BUSY` / `PAUSED` / `UNAVAILABLE`。予算予約はサーバで再判定し、古い画面から動画希望を送っても上限超過させない。LLM等の失敗で完了する場合は `DEGRADED` と理由を返す。

## 4. 状態取得と待機

`GET /roasts/{id}` は上記 `roast` の形に加え、必要に応じ `degradedReason`、`progress.stage`、`createdAt` を返す。

| status | 意味 | pollAfterMs |
|---|---|---|
| TEXT_ONLY | 定型文・勝利文・停止中応答で完了 | null |
| PENDING / TEXT_DONE / AUDIO_DONE / VIDEO_SUBMITTING / VIDEO_SUBMITTED | 生成処理中 | サーバ指定の間隔 |
| VIDEO_DONE | 通常完成、手動再生可能 | null |
| DEGRADED | 完成した上流成果だけを利用 | null |
| SUBMISSION_UNKNOWN | 受付不明、再送なし | null |
| RESULT_UNKNOWN | 結果不明、遅延完成は運用確認対象 | null |
| FAILED / CANCELLED | 失敗 / 未投入段の取消 | null |

`degradedReason`: DAILY_BUDGET / MONTHLY_BUDGET / LLM_FAILED / SPEECH_FAILED / VIDEO_FAILED。不明状態は理由ではなく専用statusで区別する。

表示資格は状態より優先。所有する無効化済み煽りは `visibility=SUPPRESSED`、本文・URLはnull、`pollAfterMs=null` の最小応答にする。停止中の過去の挑発も同じく返さない。安全な記録確認文・勝利文は表示可能。他人のリソースは404。

フロントは投稿から3分でポーリングを止めるが、ジョブ取消APIは呼ばない。通常完成は履歴へ。不明状態になったら生成枠は解放済み、予算は予約保持。遅延結果を通常の動画リンクにしない。

メディアURLはBFF経由の認証付き相対URL。取得時にも資格を確認し、無効なら404。R2の期限付き直リンクを恒久的な再生権限として扱わない。レスポンスは `Cache-Control: private, no-store`。

## 5. 達成・取りやめ・削除

`POST /goals/{id}/achieve` は本文不要。本人の操作だけでACTIVE→ACHIEVEDとし、同一トランザクションで実行中の煽りを無効化、無料勝利文を作成する。

```json
{
  "goal": {"id": "...", "status": "ACHIEVED"},
  "roast": {
    "status": "TEXT_ONLY",
    "visibility": "VISIBLE",
    "text": "参考書を1冊最後まで解いたんだな。今回はお前の勝ちだ、やり切ったのは認める。",
    "audioUrl": null,
    "videoUrl": null,
    "pollAfterMs": null
  }
}
```

予算・LLM・停止状態に依存しない。取りやめはACTIVE→ABANDONED、追い打ちの `roast` は作らない。両方とも完成済み履歴は保持する。

進捗DELETEは自身の目標の報告に対して実行可能（終了した目標も対象）。報告を論理削除し、対応する煽りと `roast_context_sources` で参照する全煽りをSUPPRESSEDにする。後続報告は残す。発生済み費用・不明予約は変更しない。成功後、一覧・本文・メディア・再送応答から除外する。

## 6. 設定・利用可否

```json
{"roastIntensity": "NORMAL", "paused": true}
```

強度はMILD/NORMAL/SAVAGE。停止は独立した `paused`。初期版はpersonaId・voiceId・llmProviderを変更できない。

手動停止は再開まで継続。`paused=false` は明示再開操作として扱うが、自動停止の `resumeAllowedAt` より前なら409 `SAFE_RESUME_TOO_EARLY`。時間だけでは再開しない。手動・自動停止が重なる場合は自動停止の解除条件を優先する。

`GET /me` は `safety {manualPaused, autoPaused, resumeAllowedAt}`、`settingsRevision`、`budget {timezone, dailyLimitMicroUsd, monthlyLimitMicroUsd, dailyCommittedMicroUsd, monthlyCommittedMicroUsd}` と `videoAvailability {available, reason}` を返す。committedは実費 + 未精算予約。月次上限は20,000,000 micro USD。アカウント課金残高とは区別する。

## 7. 型と契約検証

OpenAPIをバックエンドから生成し、フロントの通信型をopenapi-typescriptで生成する。CIで仕様差分を検出。通常投稿・予算縮退・停止・不明・削除の各例を契約テストに含める。一般的なUIローカル型まで自動生成できるとはしない。
