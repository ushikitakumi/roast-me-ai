# ROAST ME

ライバルに目標を宣言し、行動で言い返す、本人専用の習慣化アプリ。

現在は無料コアループと、外部通信をしないスタブ動画パイプラインを実装中です。AIキャラクター・実音声・有料動画生成は未接続です。全体の仕様と進捗は [設計ドキュメント](docs/README.md) を参照してください。

## ローカル起動

必要: JDK 25（Java 17向けにコンパイル）、Node.js 22、Docker Desktop。GradleはWrapperを使います。

```sh
python3 scripts/dev-env.py
docker compose up -d --wait
npm ci --prefix frontend
bash scripts/backend-dev.sh
```

別のターミナルで:

```sh
bash scripts/frontend-dev.sh
```

[http://localhost:3000](http://localhost:3000) を開き、生成された `.env` の `FRONTEND_USER` / `FRONTEND_PASSWORD` でログインします。`.env` はGit対象外で、既存ファイルを生成スクリプトが上書きすることはありません。値を共有・コミットしないでください。バックエンドは `127.0.0.1:8080`、DBは `127.0.0.1:54329` です。

通常投稿と達成は定型文で応答し、外部APIキーなしで利用できます。Dockerのデータは専用ボリュームに保存します。終了時は各サーバをCtrl+Cで止め、`docker compose stop` でDBを停止できます。

## スタブ動画の検証

実サービスの登録は不要です。通常利用では無効です。検証するときだけバックエンドの起動前に次を設定します。

```sh
STUB_VIDEO_ENABLED=true DAILY_LIMIT_MICRO_USD=1000000 bash scripts/backend-dev.sh
```

動画は「STUB VIDEO - NO AI CALLS」と表示する2秒のテスト素材、音声は1秒の無音です。AIアバターが話す動画ではありません。見込み20万micro USDを予約して状態遷移を検証し、スタブの実費は0として精算します。未知の結果は見込み予約を保持します。実サービス用の料金見積もりではありません。

バックエンドの `build/media/` はローカル検証用の保存先で、R2未接続です。`clean` で消えるため、本番の履歴保存には使用しないでください。

## 検証

```sh
(cd backend && ./gradlew test bootJar)
npm --prefix frontend run typecheck
npm --prefix frontend test
npm --prefix frontend run build
```

バックエンド統合テストはTestcontainersで専用PostgreSQLを起動します。通常利用のDBを操作しません。

ブラウザテストは上記のローカルサーバを起動後、以下で実行します。テスト名付きの目標をローカルDBに作成するため、専用の開発DBで実施してください。

```sh
(cd frontend && npx playwright install chromium && npx playwright test)
```

## API契約の更新

バックエンド起動後:

```sh
python3 scripts/export-openapi.py
frontend/node_modules/.bin/openapi-typescript backend/openapi.json -o frontend/src/lib/schema.d.ts
```

`backend/openapi.json` と生成した型を一緒に更新します。API仕様は [03-api-design.md](docs/03-api-design.md) を参照してください。

## 外部サービス準備後

Hedra・LLM・R2の現行API、料金、話者条件をS0で確認してから実アダプタを接続します。日次予算や結果待ち期限の実測値がない状態で有料機能を有効にしません。デプロイ・実サービスの生成は、まだ行っていません。
