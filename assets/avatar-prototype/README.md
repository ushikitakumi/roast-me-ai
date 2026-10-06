# アバター試作用素材（A0）

確認日: 2026-10-04。固定素材を仮選定・確保した。モデルの描画、口パクの自然さ、音声の中性的/挑発的な印象、本人の採用判断はA1/A2で確認する。ステップ1の素材準備であり、アプリへの組み込み・本体TTS接続ではない。

## 選定結果

| 素材 | 仮採用 | 理由・制約 |
|---|---|---|
| モデル | met4citizen / TalkingHead のMPFBサンプル | 作者がファイル単位でCC0と明記。写実系の人間モデルを試す候補。GLBで口・まばたき・頭/首のデータあり。36,815,920 bytesで軽量化未実施 |
| 音声 | VOICEVOX Nemo 0.24.0、女声6（style 10006） | キャラクターに紐づかない日本語音声をローカルCPUで事前生成。落ち着きは速度/抑揚を調整した狙いで、聴感の合格は未確認 |
| 台詞 | `line.ja.txt` | 成果を認めて継続を促す2文。人格攻撃・脅迫・病気への攻撃なし |

モデルの性別表現と音声は試作の仮選定であり、最終キャラクターの性別や声の確定ではない。音声は約9.781秒、24 kHz / mono / PCM16 WAV。元の生成が約7.47秒だったため、speedScale=0.8で約10秒へ調整。intonationScale=0.85、前後無音0.3/0.4秒。声そのものの自然さ・中性的な印象は本人評価で判断する。

## 比較した候補

| 候補 | 判断 |
|---|---|
| TalkingHead MPFB GLB | 仮採用。既製のGLBと口/表情データが揃い、素材固有のCC0表記あり |
| 同リポジトリのReady Player Me brunette.glb | 見送り。作者の表記はCC BY-NC 4.0で非商用限定。今回は用途の制約が少ないMPFBを優先 |
| Microsoft Rocketbox | 見送り。公式はMITの写実系リグ付きモデル群。今回の既製GLBと比べ、モデル選定・変換・リグ調整の追加確認が必要 |
| VRM公式サンプル | 見送り。VRM形式の試験には向くが、素材別ライセンスと見た目の選定が必要。形式だけを理由に採用しない |

比較元: [TalkingHead README](https://github.com/met4citizen/TalkingHead/blob/b3e277b3b46f88e557bf28a2c5612a5b04e075c3/README.md)、[Rocketbox公式](https://github.com/microsoft/Microsoft-Rocketbox)、[VRM公式サンプル](https://github.com/vrm-c/vrm-specification/blob/master/samples/VRM1_Constraint_Twist_Sample/README.md)。候補以外のモデルは同梱していない。

## ファイルと再現性

- `rival-mpfb.glb`: 原本を改変せず改名したモデル。取得元は下記の固定コミット。
- `rival-ja.wav`: 固定台詞から事前生成した音声。モデル/音声をWeb公開するには、次のA1で既存認証の内側に配信経路を用意する。
- `line.ja.txt`: 表示用台詞。
- `audio-query.json`: 実際に合成へ渡した音声クエリ。
- `inspection.json`: モデル構造・音声形式・長さ・SHA-256の検査記録。
- `licenses/`: 出典・条件・話者ポリシー。

モデル原本: https://raw.githubusercontent.com/met4citizen/TalkingHead/b3e277b3b46f88e557bf28a2c5612a5b04e075c3/avatars/mpfb.glb

音声エンジンは[公式0.24.0リリース](https://github.com/VOICEVOX/voicevox_nemo_engine/releases/tag/0.24.0)の`voicevox_engine-macos-arm64-0.24.0.vvpp`を一時フォルダに展開して使用した。エンジンやモデル重みはこのリポジトリに同梱しない。話者UUID: `3490c392-30be-44c2-8379-b77df27fa65e`、音声ライブラリ版0.15.0。

再生成する場合は、同版のNemoを別途用意してローカルで起動する（runの場所は展開先に合わせる）。

```sh
/path/to/nemo/run --host 127.0.0.1 --port 50129 --cpu_num_threads 2 --disable_mutable_api
# 別ターミナルで、リポジトリのルートから
python3 scripts/synthesize-avatar-sample.py
python3 scripts/inspect-avatar-assets.py
```

再生成では同じ合成クエリを用いる。実行環境によるバイナリの完全一致は保証しない。検査スクリプトはネットワーク不要で、検査結果を標準出力へ出す。

## 次の試作で使うモデル設定

VRMではなくglTF 2.0のGLBを使う。three-vrmを必須にはせず、GLBを読み込む方式をA1で確認する。既存のアプリ依存は今回変更していない。

- 口開閉: `jawOpen`。顔だけでなく、同名ターゲットを持つ歯・舌等にも適用する。
- まばたき: `eyeBlinkLeft` / `eyeBlinkRight`。顔とまつげ/眉の対応ターゲットも反映する。
- 顔の動き: `Head` / `Neck`。元の姿勢を基準に小さな回転を加える。
- 全ての画像・バッファはGLB内部に含まれ、外部URIはない。特殊な必須拡張は宣言されていない。
- 必要ターゲットの名前だけでなく、実バイナリに非ゼロの変形があることを検査済み。自然に口が閉じるか、まぶたが正常に閉じるかは描画して確認する。
- カメラ・照明・肩/腕の姿勢・表情の強さはA1で調整する。成人らしさとライバルらしさも本人評価の対象。

## 利用条件・クレジット

モデルは[素材固有の出典記録](licenses/model-source.md)に従い、CC0に基づいて同梱する判断。作者の個別禁止表記は確認した宣言にはない。ランタイムのMITをモデルの許諾根拠にはしていない。

音声の表示クレジットは **VOICEVOX Nemo**。利用者へ[公式Nemo利用規約](https://voicevox.hiroshiba.jp/nemo/term/)と[話者ポリシー](licenses/nemo-voice-policy.md)を案内する。規約はクレジット付きの商用・非商用利用を認め、著しい公序良俗違反、権利侵害、提供者の信用等を著しく損なう利用、機械学習用途等を禁止している。音声をCC0やアプリのコードライセンスに含めない。

今回の固定台詞は成果への言及と継続への軽い挑発であり、この試作利用に適合すると判断した。権利者による個別承認ではなく、将来の自由生成文すべてへの包括的許可でもない。アプリの安全境界と併せて本体統合時に再確認する。

モデルの参考クレジット: Model: met4citizen / TalkingHead MPFB sample (CC0), created with Blender and MPFB.
