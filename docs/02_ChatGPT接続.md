# ChatGPT接続のセットアップ

Bot共通のChatGPTアカウントを接続する。許可ロールを持つ人の発言に、そのアカウントのChatGPTプランを使って応答する。利用者ごとのChatGPTログインは不要。

OpenAIが公開するOSS向け Sign in with ChatGPT と Responses API を使う。Codex CLI・Codexの認証ファイル・ChatGPTの内部APIは使わない。別途課金されるAPIキーへの自動切替もしない。利用可否は、実際にログインしたアカウント・Workspace・OpenAI側のポリシーによる。

## 先に行うこと

Discord Developer Portalで対象アプリを開き、左の **Bot** → **Privileged Gateway Intents** にある次のスイッチをONにする。招待URLの権限計算とは別の場所。

- **Server Members Intent**：許可ロールの判定・利用可能メンバーの一覧に使う。
- **Message Content Intent**：投稿の本文を読むために使う。

Presence Intentは不要。Botに必要なチャンネル権限は「チャンネルを見る」「メッセージを送信」「メッセージ履歴を読む」「リアクションの追加」「ファイルを添付」「スレッドでメッセージを送信」。管理者権限は不要。これらの権限bitの合計は `274878008384`。チャンネル側の上書きによる拒否にも注意する。

VPSで `sudoedit /etc/chatgptviadiscord.env` を開き、次を追加する。

```ini
CHATGPT_ENABLED=true
CHATGPT_CALLBACK_PORT=1455
CHATGPT_REQUEST_TIMEOUT_SECONDS=180
```

既定の `CHATGPT_ENABLED=false` では従来の定型返信を維持し、認証待受も開始しない。初回はIntent設定と環境変数を準備した後、この実装を含むmainのデプロイを行う。デプロイがサービスを再起動する。環境変数だけを後から変更した場合は `sudo systemctl restart chatgptviadiscord.service` で反映する。

コマンドはグローバル登録。今回追加する定義は既存の定義登録用workflowから登録する（`register_commands_only=true`）。定義登録だけでは稼働中のJARは変わらない。

## Discordで許可対象を設定する

- `/gvd accept role:@ロール`：許可ロールを追加する。
- `/gvd reject role:@ロール`：許可ロールを削除する。
- `/gvd accept`：登録ロールと、現在利用できる人を列挙する。

複数の許可ロールのうち、どれか1つを持つ人が対象。Botは対象外。追加・削除には **サーバー管理** 権限が必要。一覧はサーバー内で確認できる。コマンドの結果は実行者だけに表示し、メンション通知は発生させない。

設定は `BOT_STATE_DIRECTORY/ai-role.txt` に保存する。Botの再起動やリリース切替後も残る。`DISCORD_AI_ROLE_ID` は任意の初期値であり、ファイルが存在する場合はコマンドで保存した設定を優先する。許可ロールが未設定なら、AIへの投稿送信・認証を許可しない。

## PCからVPSへ認証の通り道を開く

ChatGPTはログイン後、ブラウザを動かしているPCの `127.0.0.1` に認証結果を返す。VPSの待受へ届けるため、自分のPCのターミナルで次を実行し、そのまま開いておく。山括弧の部分は普段のSSH設定に置き換える。

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:1455:127.0.0.1:1455 -p <SSH_PORT> <VPS_USER>@<VPS_HOST>
```

このコマンドはPCの1455番をVPSの1455番へ転送する。**VPSの1455番をファイアウォールで外部公開する必要はない**。Botは `127.0.0.1` のみで待ち受ける。通常のSSH用ポートだけを使う。

PC側で1455番を別のアプリが使用している場合は、VPSの `CHATGPT_CALLBACK_PORT` を1456などへ変更して再起動し、SSHコマンド内の両方の1455も同じ番号へ変える。認証リンクを開くブラウザは、SSHトンネルを開いた同じPC上で使う。

## ChatGPTへログインする

許可ロールとサーバー管理権限を持つ人が行う。

1. `/gvd auth` を実行する。本人だけに表示された **Continue with ChatGPT** のリンクを開く。10分以内に完了する。
2. OpenAIのログイン画面で、Botに使わせるChatGPTアカウントとWorkspaceを選び、このアプリによるプラン利用を許可する。ChatGPTのパスワード・トークンをDiscordに貼らない。
3. ブラウザに完了表示が出たらDiscordへ戻る。Botの接続完了表示を確認する。
4. `/gvd model` で、そのアカウントが利用できるモデルとslugを確認する。
5. `/gvd model name:<slug>` でモデルを保存する。モデルはアカウントごとに保存し、勝手に別モデルへ代替しない。
6. 雑談チャンネルへ通常のテキストを投稿し、👀と通知を抑えたAIの返信を確認する。

`/gvd auth action:status` で現在の共通接続を確認できる。別のアカウント・Workspaceを保存するなら `/gvd auth account:personal` のように別の保存名を指定する。保存名は英数字・`_`・`-` の1〜32文字。`action:use account:<保存名>` で保存済みの共通接続を選ぶ。新しいログインはIDトークンの署名・issuer・audience・有効期限・nonce・既存の本人IDを検証してから有効にする。

`/gvd auth action:logout account:<保存名>` はその接続を停止する。OpenAI側のセッション失効を試み、ローカルのトークンを消す。ネットワーク障害などで失効を確認できなければ、その旨を表示する。登録済みclient IDとホストIDは次回ログイン用に保持する。ChatGPT側からアプリ接続を解除することもできる。

## 保存と実装範囲

- 認証情報は `BOT_STATE_DIRECTORY/chatgpt/accounts.json`。Linuxでは所有者だけが読み書きできる0600で原子的に保存する。systemdの状態ディレクトリは0700、UMaskは0077。Git・Actions・ログへ入れない。
- 1つの状態ディレクトリを複数プロセスで同時使用しないよう、ファイルロックを取る。アクセストークンの期限が近づいたら直列に更新し、交換されたrefresh tokenも一緒に保存する。
- 会話はチャンネル・ChatGPT登録ごとに、直近40メッセージ・約64,000文字を目安として `recent-chat/` に保存する。同じ共通接続を使う許可メンバーは、この文脈を共有する。再起動後も読み戻す。
- これは最近の会話の継続用。全文アーカイブ・長期記憶検索・チャンネル間の引き継ぎ・共有URL本文取得は未実装。ChatGPT公式の会話履歴やメモリがログインによって移ることもない。
- 会話AI向けの `docs/discord-ai/README.md` と `AGENTS.md` をJARに含め、応答指示として使う。原文は書き換えず、実装済みの能力を追加の指示で伝える。
- `skipch` はAI応答も停止する。Bot・Webhook・システム投稿・DM・他サーバー・許可ロールを持たない人の本文は送信しない。処理待ち中の許可変更も再確認する。送信済みのAPIリクエストは取り消せないが、許可を失った後の回答送信は止める。
- 応答待ちは既定180秒、待ち行列は16件。本文は16,000文字まで。1,900文字を超える回答は全文をテキスト添付にする。スレッドモードのキャラロール自動作成・連番永続化はまだ接続していない。
- Responsesは `store=false`・`stream=true`。`response.completed` まで確認してから回答を送信する。途中終了・利用枠不足は成功扱いせず、自動で課金方式を切り替えない。利用枠は [ChatGPT設定](https://chatgpt.com/settings/usage) で確認する。

## 動作確認

許可メンバーの投稿、無許可メンバーの投稿、Bot・Webhook、skip/read、Bot再起動後の会話継続、長文添付、ログアウト後の投稿をそれぞれ確認する。CIの偽サーバーによる検証と、実アカウントでの同意・モデル取得・Discord返信は別の確認。

## 参照した公開仕様

- [OSS向けChatGPTプラン利用](https://developers.openai.com/siwc/token-sharing-open-source)
- [登録・ログイン](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [VPS向け手順](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)
- [モデル一覧・Responses](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [トークン更新・ログアウト](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [プレビューの制約](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
