# 07. 非機能・運用・実装ロードマップ

> **v1 のスコープと、削った機能の理由は [README](./README.md) を参照。**

## 1. 性能目標

### 1.1 【v1】の目標値

利用者は 1人、1日 3〜5本。**スループットは問題にならない。問題はレイテンシの見せ方である。**

| 指標 | 目標 | 備考 |
|---|---|---|
| 煽り文の表示 `p50` | 2.0 秒 | LLM のレイテンシが支配的。**ここは同期で返す** |
| 煽り文の表示 `p95` | 4.0 秒 | 超過分はテンプレートで打ち切り |
| 音声の再生可能まで | 10 秒 | VOICEVOX は内部ネットワークなので LLM より速い |
| **動画の差し替えまで** | **3分（目標）** | **P0 で実測して確定する。** 超過でポーリング打ち切り |
| 動画の完了待ち上限 | 10分 | 超過で `DEGRADED` |
| その他 API `p95` | 300 ms | DB アクセスのみ |
| LCP（目標詳細画面） | 2.5 秒以内 | 動画は遅延読み込みするため LCP に含めない |

**「動画の差し替えまで 3分」は SLA ではなく、ポーリングを打ち切る閾値である。** 超えても生成は続き、完成後はタイムラインに現れる（[06 §3](./06-frontend-design.md)）。

### 1.2 スレッド枯渇について

**LLM・VOICEVOX・Hedra の呼び出しはいずれもブロッキング I/O で、数秒〜数分スレッドを占有する。** Tomcat 11 の既定スレッド数（200）では、同時 200 リクエストで詰まる。**この弱点は Spring Boot 4 を採用しても解消しない**（仮想スレッドは Java 21 以降の機能であり、Boot 4 の Java 17 サポートとは無関係）。

**v1 では利用者が1人なので顕在化しない。** ただし設計としては対策を入れておく。

1. **`@Async` + 専用スレッドプール**でパイプラインを Tomcat のワーカースレッドから分離する（[02 §5.4](./02-architecture.md)）
   - Spring Boot 4.1 で `@Async` のコンテキスト自動伝播が入ったため、セキュリティコンテキストと `traceId` を手で引き回す必要がない。Boot 3 時代の `TaskDecorator` 自前実装は不要
2. 各外部呼び出しに **`@ConcurrencyLimit`**（Spring Framework 7 標準）で隔壁を設ける
   - LLM: 50 / VOICEVOX: 2（CPU 推論）/ Hedra: 2
3. リトライは **`@Retryable`**（同上）。一過性障害のみを `includes` に指定し、モデルの拒否や投入済みジョブのエラーは除外する（[02 §7.1](./02-architecture.md)）
4. サーキットブレーカ: LLM のエラー率が 50% を超えたら 60秒間オープンし、全リクエストをテンプレートへ
   - **Spring Framework 7 の標準レジリエンスにサーキットブレーカは含まれない。** `CompositeRoastGenerator` 内でエラー率を数えて自前で状態を持つ（実装は小さい）。それでも足りなければこの用途に限って Resilience4j の追加を検討する

> **【将来】の懸念**: Hedra の完了待ちは `@Async` のスレッドを数分占有する。v1（1日3〜5本）では問題ないが、本数が増えたら完了待ちを `@Scheduled` のポーリングに寄せ、スレッドを保持しない形へ変える。

> **【将来】Java 21 以上へ上げれば** `spring.threads.virtual.enabled=true` の 1行で 1. の問題が解消する。Spring Boot 4.1 は Java 17〜26 に対応しているため、**アップグレードはフレームワークの変更なしに Java バージョンの差し替えだけで完了する。**

---

## 2. 監視・可観測性

### 2.1 メトリクス（Micrometer → Actuator）

| メトリクス | 種別 | 用途 |
|---|---|---|
| `roast.generation.duration{provider,model,trigger}` | Timer | レイテンシ監視 |
| `roast.generation.count{provider,model,result}` | Counter | `result` = success / fallback / refused / rate_limited |
| `roast.tokens{type}` | Counter | `type` = input / output / cache_read |
| `roast.cost.micro_usd{provider,model}` | Counter | **日次コストのリアルタイム把握** |
| **`roast.speech.duration`** | Timer | VOICEVOX のレイテンシ |
| **`roast.video.duration{stage}`** | Timer | `stage` = submit / wait / download。**`wait` が P0 の実測値になる** |
| **`roast.video.cost.micro_usd`** | Counter | 映像コスト。LLM より支配的な可能性が高い |
| **`roast.job.status{status}`** | Gauge | 各状態のジョブ数。**`VIDEO_SUBMITTED` が溜まっていたら Hedra 側の異常** |
| **`roast.job.recovered{from_status}`** | Counter | **復旧スケジューラが再開したジョブ数。この値が動くこと自体が設計の証明** |
| `roast.moderation{status}` | Counter | 出力ガードの発動率 |
| `roast.safe_mode.activated` | Counter | **安全指標。最重要** |
| `roast.reaction{type}` | Counter | 品質シグナル |
| `llm.circuit_breaker.state` | Gauge | |

### 2.2 アラート

1人用なので通知先はメール1本で足りる。

| 条件 | 重大度 | アクション |
|---|---|---|
| 日次コストが上限の 80% | 警告 | 通知のみ |
| 日次コストが上限の 100% | 重大 | **自動で映像停止 + テンプレート生成へ切替**（自動対応済みの通知） |
| `VIDEO_SUBMITTED` のジョブが 10分以上滞留 | 警告 | Hedra 側の異常の疑い |
| `roast.job.recovered` が急増 | 警告 | プロセスが頻繁に落ちている疑い |
| `safe_mode.activated` の発火 | 重大 | **人が内容を確認する** |
| API 5xx 率 > 1% | 重大 | |

### 2.3 ログ

- 構造化ログ（JSON）。`traceId` / `roastId` / **`jobStatus`** を全ログに付与
- **進捗本文・煽り本文はログに出力しない。** 内容の調査が必要な場合は DB を直接参照する
- 外部 API 呼び出しは「メタデータのみ」をログに出す（provider / model / tokens / latency / stop_reason / credits）
- **`external_job_id` はログに出す。** Hedra 側の問い合わせに必要で、それ自体は機微情報ではない

> **Spring Boot 4.1 の `@Async` コンテキスト自動伝播により、同期部分と非同期部分のトレースが 1本に繋がる。** 「投稿から動画完成まで」を 1 トレースで追えることが、3段連鎖の可観測性における要点。

### 2.4 ダッシュボード

**v1 のダッシュボードは 1枚でよい。** `/stats` 画面（[03 §3.9](./03-api-design.md)）がそれを兼ねる。

1. **プロバイダ比較**: `provider` × `model` 別の 件数 / 総コスト / 平均レイテンシ / `ANGRY+LAUGH` 率 ← **README の一枚看板**
2. **コスト推移**: 日次コスト（LLM / 映像の内訳）と上限への到達状況
3. **パイプライン**: ジョブ状態の分布、`DEGRADED` の理由内訳、動画生成の実測所要時間の分布

---

## 3. セキュリティ

| 領域 | 対策 |
|---|---|
| 認証 | **HTTP Basic**（ブラウザ ↔ Next.js、Next.js ↔ Spring の 2系統）。**HTTPS 必須**（Vercel / Railway とも既定で TLS） |
| 認可 | 全リソースアクセスで所有者チェック。他人のリソースは 403 ではなく **404** で返し、存在を秘匿する（v1 は単一ユーザーだが実装は入れる） |
| CORS | フロントのオリジンのみ許可 |
| 入力検証 | Bean Validation + 長さ制限。SQL は全て JPA / バインドパラメータ経由 |
| 依存関係 | Dependabot + `gradle dependencyCheck`（OWASP）を CI で実行 |
| **シークレット** | Railway / Vercel の環境変数。**Anthropic / OpenAI / Hedra / R2 の API キーはバックエンドのみが保持し、フロントに絶対に出さない** |
| **SSRF** | Spring Boot 4.1 の `InetAddressFilter` で外向き呼び出し先をホワイトリスト制御 |
| プロンプトインジェクション | [05 §5.3](./05-llm-design.md)。**LLM にツールを与えないことが最大の防御** |
| レート制限 | 1日 10回 + 日次コスト上限 |
| **メディア URL** | R2 は**公開バケットにしない。** 有効期限 1時間の署名付き URL を都度発行する |

### なぜ 1人用でも認証を外さないか

**デプロイされたアプリは Anthropic / OpenAI / Hedra の API キーを保持し、リクエスト1回が実費を発生させる。**

無認証で公開することは、他人に自分の財布を叩かせることに等しい。しかも動画生成は LLM より単価が高い。**Basic 認証（実装 1時間）を省くことで生じるリスクが、省いて得られる時間に見合わない。**

加えて、公開 URL を配らない運用と併用する。Basic 認証は「他人が偶然たどり着いても入れない」ための最小限の錠であり、それ以上の強度は主張しない。

### 個人情報の扱い

- 進捗テキストは機微情報になりうる（健康・仕事・家庭の事情）。**設計上「一般的なユーザー生成コンテンツ」ではなく「機微情報」として扱う**
- LLM への送信は必要最小限（目標・進捗・直近履歴のみ）
- LLM プロバイダは**入力データを学習に利用しない**設定であることを必須要件とする
- **音声・動画にも本人の目標内容が含まれる。** R2 を公開バケットにしないのはこのため

---

## 4. テスト戦略

| 層 | 対象 | ツール | CI |
|---|---|---|---|
| 単体（BE） | ドメインロジック、プロンプト組み立て、モデレーション | JUnit 5 + AssertJ | ○ |
| アーキテクチャ | レイヤ依存規則（`domain` が SDK に依存していないこと） | ArchUnit | ○ |
| 統合（BE） | Repository、Flyway マイグレーション | Testcontainers (PostgreSQL) | ○ |
| 契約（LLM） | 正常 / 429 / 5xx / refusal / タイムアウト の分岐 | WireMock | ○ |
| 契約（音声） | VOICEVOX の 2段階 API | WireMock | ○ |
| **契約（映像）** | **`PROCESSING` → `COMPLETE` の遷移、`ERROR`、タイムアウト** | WireMock | ○ |
| **パイプライン** | **★ 各段で落として再開できること。とくに `VIDEO_SUBMITTED` から復旧したとき `submit` が 2回呼ばれないこと** | Testcontainers + WireMock | ○ |
| API | エンドポイントの入出力・認可 | `@SpringBootTest` + **`RestTestClient`**（Boot 4 の新テストクライアント）。モック Bean は `@MockitoBean` | ○ |
| 単体（FE） | ポーリングの停止条件、状態→表示のマッピング | Vitest | ○ |
| ゴールデン | 煽りの品質（30パターン、人手レビュー） | 手動スクリプト | × (手動) |
| 安全性 | 危険シグナル 50件 + インジェクション 30件 | JUnit（実 API 使用） | △ (リリース前必須) |

**★ のパイプラインテストが、この設計で最も価値のあるテストである。** 「途中で落ちても再開でき、かつ二重課金しない」という主張を、コードで証明する。

**リリースブロッカー**: 安全性テストの全件パス。ここだけは例外を認めない。

**実 API を叩くテストは CI に入れない。** 課金が発生するため手動トリガのみ。

---

## 5. CI/CD

```
Pull Request
  ├─ backend:  build → 単体 → ArchUnit → 統合(Testcontainers) → 契約(WireMock) → パイプライン
  ├─ frontend: typecheck → lint → 単体 → build
  ├─ openapi:  生成 → コミット済み openapi.yaml との差分検出（差分があれば fail）
  └─ security: 依存脆弱性スキャン

main へマージ
  ├─ backend:  Railway が Git 連携で自動デプロイ（起動時に Flyway マイグレーション）
  └─ frontend: Vercel が Git 連携で自動デプロイ
```

- **デプロイは Railway / Vercel の Git 連携に任せる。** GitHub Actions はビルドとテストのみ。**インフラ未経験で CD パイプラインを自前構築しない**
- **Railway は再デプロイのたびにコンテナが落ちる。** これが `roast_jobs` によるジョブ永続化を必須にしている実務上の理由でもある（[02 §5.2](./02-architecture.md)）
- 単一インスタンスなのでマイグレーションはアプリ起動時に実行してよい。【将来】複数インスタンス化する場合は起動前の単独ジョブに分離する

---

## 6. 環境とデプロイ構成

### 6.1 環境

| 環境 | 用途 | LLM | 映像 |
|---|---|---|---|
| local | 開発 | `template` 既定。`ANTHROPIC_API_KEY` があれば実 API に切替可 | 既定で無効。手動で有効化 |
| ci | 自動テスト | WireMock スタブのみ。**実 API を叩かない** | WireMock スタブのみ |
| production | 本番（1人用） | 実 API。日次コスト上限 $0.70 | 実 API。上限到達で自動停止 |

ローカル開発では Docker Compose で PostgreSQL + VOICEVOX を起動する。**VOICEVOX のイメージは 1.79GB（CPU 版）** なので、初回 pull に時間がかかる点を見込んでおく。

### 6.2 デプロイ構成

```
Vercel                    Railway（1プロジェクト内）           外部
┌──────────────┐         ┌────────────────────────────┐    ┌──────────────┐
│ Next.js      │────────▶│ Spring Boot                │───▶│ Anthropic    │
│ (BFF/Basic)  │  HTTPS  │                            │───▶│ OpenAI       │
└──────────────┘         │   │ 内部ネットワーク         │───▶│ Hedra        │
                         │   ├──▶ VOICEVOX (1.79GB)   │───▶│ Cloudflare R2│
                         │   └──▶ PostgreSQL          │    └──────────────┘
                         └────────────────────────────┘
```

| 項目 | 設定 |
|---|---|
| Spring Boot | Dockerfile または Nixpacks。ヘルスチェックは `/actuator/health` |
| VOICEVOX | Docker イメージ `voicevox/voicevox_engine:cpu-*` を指定するだけ。**外部に公開しない**（内部ネットワークのみ） |
| PostgreSQL | Railway のマネージド Postgres |
| 環境変数 | `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `HEDRA_API_KEY` / `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` / `R2_ENDPOINT` / `BASIC_AUTH_USER` / `BASIC_AUTH_PASSWORD` / `DAILY_COST_LIMIT_USD` |
| 月額の目安 | Railway $5〜20 + 生成コスト $10〜20 + R2 $0（無料枠内） |

### 6.3 Cloud Run を選ばなかったこと

**技術的には Cloud Run の方が本設計に適している。**

- VOICEVOX は**使用時のみ必要**なコンポーネントであり、ゼロスケールできる
- **本設計は非同期なので、コールドスタート 20〜40秒を許容できる**（同期設計なら許容できない）
- 使わない時間帯のコストがゼロになる

今回は**インフラ未経験で 5〜10時間を溶かすリスク**を取らず、Railway を選んだ。

> **README にはこの判断を明記する。** 「VOICEVOX はゼロスケールが最適だが、工数の都合で Railway を選択。コスト最適化の次の一手として明確」——**選ばなかった選択肢とその理由を書けることが、選択そのものより評価される。**

---

## 7. 実装ロードマップ

### P0: 前提検証（1時間）★最優先

**目的**: 設計の土台になっている未検証の仮定を、コードを書く前に潰す。

Hedra の無料枠でイラスト画像1枚 + VOICEVOX の WAV を投げ、**10秒の動画を1本作って記録する。**

| 確認項目 | 外れた場合 |
|---|---|
| イラスト調画像で Character-3 が顔を認識するか | **実写での開始に変更**（設計の画風前提が崩れる） |
| VOICEVOX の WAV を Hedra が受けるか | **Hedra 内蔵 TTS に切替**（VOICEVOX 採用が崩れ、コスト増） |
| Character-3 の実生成時間 | 段階表示のタイムアウト値とフォールバック設計を調整 |
| クレジット→ドル単価 | 1日3〜5本という前提を再計算 |

**別途、VOICEVOX のキャラクター個別規約を読む。** 「キャラクターのイメージを損なう利用」を禁じる条項がある場合、煽り content が抵触しうる。クレジット表記（例 `VOICEVOX:ずんだもん`）は必須。

> **この 1時間を惜しんではいけない。** 上の 4つは全て「設計の前提」であり、どれか 1つが外れると Q10 / Q11 / Q16 が連鎖して崩れる。**80時間の終盤に発見するのは致命的で、最初に発見すれば設計を組み替えるだけで済む。**

### P1: 縦串（walking skeleton）15〜20時間

**目的**: 「テキスト → Claude → VOICEVOX → Hedra → 再生」を 1本通す。

| やること | やらないこと |
|---|---|
| Spring Boot の起動、PostgreSQL 接続、Flyway | 認証 |
| `RoastGenerator`（Claude のみ）/ `SpeechSynthesizer` / `AvatarVideoGenerator` の 3ポートと各 1実装 | OpenAI / テンプレート実装 |
| 進捗投稿 → 動画再生の 1本道 | ジョブ永続化、復旧 |
| UI は `<button>` と `<textarea>` と `<video>` だけ | デザイン、怒りゲージ、履歴 |
| ローカルで動けばよい | デプロイ |

**完了条件**: ローカルで、進捗を投稿したら数分後にアバターが喋る動画が再生される。

> **ここが通れば、残りは全部「削れる機能」になる。** 時間が尽きても、動くものが手元に残る。

### P2: 非同期化と永続化 8〜12時間

| やること |
|---|
| `roast_jobs` テーブルと状態遷移 |
| `@Async` によるパイプライン分離 |
| `@Scheduled` による復旧（`external_job_id` からの再開） |
| Cloudflare R2 への音声・動画の保存、署名付き URL |
| フロントのポーリングと段階表示 |
| `@Retryable` / `@ConcurrencyLimit` |
| **パイプラインテスト（各段で落として再開、二重投入しないこと）** |

**完了条件**: 動画生成の途中でアプリを再起動しても、生成が再開して完了する。

### P3: 認証とデプロイ 8〜12時間

| やること |
|---|
| Spring Security の HTTP Basic |
| Next.js middleware の Basic 認証、BFF プロキシ |
| Railway に Spring Boot / PostgreSQL / VOICEVOX を配置 |
| Vercel に Next.js を配置 |
| 環境変数とシークレットの設定 |
| **日次コスト上限と自動縮退** |

**完了条件**: 公開 URL 上で、自分だけがログインして使える。**ここで当初の「デプロイまで完了」という目標が達成される。**

### P4: 体験の完成 8〜12時間

| やること |
|---|
| UI をまともにする（Tailwind） |
| 「ムカついた」ボタンと**怒りゲージ** |
| タイムライン（**過去の動画の再生**） |
| 目標の達成（テキストのカタルシス）と、ゲージが 0 に落ちる演出 |
| 安全機構（入力ガード / 出力ガード / セーフモード / 強度設定） |
| VOICEVOX のクレジット表記 |

**完了条件**: 自分が毎日使い続けられる。

### P5: プロバイダ比較 5〜8時間

| やること |
|---|
| `OpenAiRoastGenerator`（HTTP Service Client） |
| `TemplateRoastGenerator` |
| 設定画面からのプロバイダ切替 |
| `GET /stats/cost` と `/stats` 画面のグラフ |
| 実際に 3実装を回してデータを溜める |

**完了条件**: コスト・レイテンシ・反応率を並べたグラフが出る。**README の一枚看板。**

### P6: ドキュメント整理 3〜5時間

| やること |
|---|
| README に P0 の実測値（生成時間・クレジット単価）を反映 |
| 実測したコスト比較グラフの掲載 |
| 「選ばなかった選択肢とその理由」の最終版 |
| 設計書と実装の差分の解消 |

---

**合計 48〜70時間。** P3 まで到達すれば目標達成。P4 と P5 は独立しているので、状況に応じて順序を入れ替えてよい。

---

## 8. 主要リスクと対応

| リスク | 影響 | 対応 |
|---|---|---|
| **P0 の検証で前提が外れる** | Q10 / Q11 / Q16 が連鎖して崩れる | **着手前 1時間で潰す。** これが P0 を最優先にする唯一の理由 |
| **Hedra のクレジット単価が想定より高い** | 1日3本すら回せない | 日次コスト上限で必ず止まる。動画を短くする（秒課金）。最悪 `video_enabled=false` でテキスト運用に落とす |
| **動画生成が遅すぎる（5分超）** | 段階表示の体験が成立しない | ポーリング打ち切り + タイムライン表示で成立させる設計になっている。**そもそもこの前提で設計してある** |
| **VOICEVOX の規約が煽り content を許さない** | 音声設計のやり直し | キャラクターを変更する。それでも駄目なら Hedra 内蔵 TTS へ（`SpeechSynthesizer` ポートの差し替えで済む） |
| **React / Next.js の学習に時間を取られる** | v1 が完成しない | 使う概念を絞る（[06 §0](./06-frontend-design.md)）。`openapi-typescript` で型を自動生成し、手書きの型を書かない |
| **インフラ構築でつまずく** | デプロイまで到達しない | Railway / Vercel の Git 連携に任せ、CD を自前構築しない。**Cloud Run を選ばなかったのはこのリスクを避けるため** |
| **API キーの漏洩** | 金銭的被害 | キーはバックエンドのみが保持。Basic 認証。SSRF フィルタ。**無認証デプロイをしない** |
| **煽りがユーザーを傷つける** | 使えなくなる | 三層防御（[05 §5](./05-llm-design.md)）。**出力ガードを音声・映像より前に置く**。動画を自動再生しない |
| **プロンプトインジェクション** | 不適切な出力が動画になって残る | LLM にツールを一切与えない。タグ隔離 + 出力検査 |
| **Spring Boot 4 の情報の少なさ** | 実装時の手戻り | Boot 3 系の記事をそのまま適用しない。差分は [02 §2.4](./02-architecture.md) に集約済み。**スタータ名（`-webmvc` / `-security-oauth2-resource-server`）と Jackson 3 のパッケージ変更は初回ビルドで必ず踏む** |
| **サードパーティが Boot 4 / Jackson 3 に未対応** | 依存の入れ替え | MapStruct・Testcontainers・WireMock・AWS SDK v2 の Boot 4 対応を P1 着手時に確認する。Anthropic Java SDK は Jackson 2 系を使うが、パッケージが `tools.jackson` と分離されているため共存可能（実装時に検証） |
| **Java 17 のブロッキング I/O によるスレッド枯渇** | 【将来】高負荷時の障害 | v1 では顕在化しない。専用スレッドプール + `@ConcurrencyLimit`。Spring Boot 4.1 は Java 26 まで対応するため、Java 21 への移行はフレームワーク据え置きで実施できる |
| **R2 の無料枠を超える** | 課金発生 | 1年分が収まる試算（[04 §4](./04-data-model.md)）。近づいたら古い動画から削除する運用 |

---

## 9. 着手前に決めること

| # | 項目 | 決めるべき内容 | 期限 |
|---|---|---|---|
| 1 | **Hedra の実測値** | 生成時間・クレジット単価・イラストで顔検出できるか | **P0** |
| 2 | **VOICEVOX の WAV 互換性** | Hedra がそのまま受けるか | **P0** |
| 3 | **VOICEVOX のキャラクター規約** | 煽り content が個別規約に抵触しないか。クレジット表記の文言 | **P0** |
| 4 | アバターのイラスト | 内製 / 生成 AI / 既存素材。**Character-3 が顔を認識できる構図であること** | P1 着手前 |
| 5 | ペルソナ 1体の確定 | `rival` / `senpai` / `osananajimi` のどれか。VOICEVOX の話者と画風を合わせる | P1 着手前 |
| 6 | OpenAI の比較対象モデル | 現行のモデル名と単価を公式ドキュメントで確認 | P5 着手前 |
| 7 | 支援リソースのリンク先 | セーフモード時に表示する公的相談窓口 | P4（安全要件） |
| 8 | 日次コスト上限の実値 | P0 の実測値をもとに $/日 を確定する | P3 |

---

## 10. 【将来】複数ユーザーに開く場合

v1 の設計は、以下の順で拡張できるようになっている。**どれもスキーマ変更を伴わない。**

| 手順 | 変更点 |
|---|---|
| 1. 認証 | `spring-boot-starter-security-oauth2-resource-server` へ差し替え。`users.external_sub` は v1 から存在する。Next.js に Auth.js を追加 |
| 2. ユーザー分離 | 全テーブルに `user_id` があり、所有者チェックも実装済み。**変更なし** |
| 3. レート制限 | インメモリ → Redis（Upstash 等）。`RateLimiter` のポート実装を差し替え |
| 4. ジョブキュー | DB ポーリング → 外部キュー。`RoastJobRepository` の実装を差し替え |
| 5. スレッド | Java 21 へ上げて `spring.threads.virtual.enabled=true`。**Spring Boot は据え置き** |
| 6. コスト | 動画を有料機能にするか、Hedra Live Avatars（$0.05/分）へ切替。`AvatarVideoGenerator` の実装を差し替え |
| 7. 通知 | Web Push、停滞検知バッチ、期限通知。`@Scheduled` の枠組みは復旧スケジューラで既に使っている |

**この 7 項目が全て「差し替え」で済むことが、ポート/アダプタを 3本引いたことの成果である。**
