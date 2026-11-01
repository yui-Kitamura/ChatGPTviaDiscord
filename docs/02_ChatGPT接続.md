# ChatGPT接続のセットアップ

Bot共通のChatGPTアカウントを接続する。許可ロールを持つ人の発言に、そのアカウントのChatGPTプランを使って応答する。利用者ごとのChatGPTログインは不要。

OpenAIが公開するOSS向け Sign in with ChatGPT と Responses API を使う。Codex CLI・Codexの認証ファイル・ChatGPTの内部APIは使わない。別途課金されるAPIキーへの自動切替もしない。公開案内のプラン利用対象は適格なPlus/Pro。Business Standardの利用枠へ同じ方式で接続できることは未確認。利用可否は、実際にログインしたアカウント・Workspace・OpenAI側のポリシーによる。

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
- 応答待ちは既定180秒、待ち行列は16件。本文は16,000文字まで。1,900文字を超える回答は全文をテキスト添付にする。thread では生成されたキャラ定義から Discord ロールを自動作成・再利用する。Bot の「ロールの管理」権限とロール階層を確認する。キャラのロールをメンションすると、qa/thread の回答にその設定が反映される。レス番号はチャンネル・共通アカウント別に保存する。
- Responsesは `store=false`・`stream=true`。`response.completed` まで確認してから回答を送信する。途中終了・利用枠不足は成功扱いせず、自動で課金方式を切り替えない。`/gvd limit` は共通接続の確認と、直近の AI 応答で観測したレート制限情報を実行チャンネルへ公開表示する。数値が API から提供されない場合は不明とし、プラン全体の残量・回復時刻は [ChatGPT設定](https://chatgpt.com/settings/usage) で確認する。照会で回答生成は行わない。

## 動作確認

許可メンバーの投稿、無許可メンバーの投稿、Bot・Webhook、skip/read、Bot再起動後の会話継続、長文添付、ログアウト後の投稿をそれぞれ確認する。CIの偽サーバーによる検証と、実アカウントでの同意・モデル取得・Discord返信は別の確認。

## 認証失敗のログ

VPSで次を実行して待ち受け、Discordで新しく `/gvd auth` を実行して再現する。

```sh
sudo journalctl -u chatgptviadiscord.service -f -o cat
```

認証失敗は `GVD_AUTH_FAILED`、接続完了は `GVD_AUTH_CONNECTED` を探す。失敗行には `stage`（処理段階）、`reason`（IDトークン検証理由）、`http_status`、既知の `oauth_code`、OpenAIの `request_id` を出す。callback URL・コード・トークン・state・nonce・メール・例外本文は記録しない。

`description_hint` はOpenAIのエラー説明に含まれる語から分類した補助情報。`PKCE`・`REDIRECT_URI`・`AUTHORIZATION_CODE`・`CLIENT_BINDING`・`OTHER`・`NOT_PROVIDED` の固定値だけを記録し、説明本文は保存しない。原因の確定情報ではなく、`AUTHORIZATION_CODE` だけでは期限切れ・使用済みなどを区別できない。

コード交換で `invalid_grant` が返った場合、使用したコードは再送しない。発行済みクライアントIDを未検証の登録情報として保護ファイルに保持し、`GVD_AUTH_RETRY registration_retained=true fresh_authorization_required=true` を記録する。Discordで **新しく `/gvd auth` を実行して発行されたリンク**を使う。Bot再起動後も登録情報は残るが、本人確認とトークン検証が成功するまではAI接続として有効化しない。保持に失敗した場合は `REGISTRATION_SAVE` の失敗ログが出る。

- `TOKEN_EXCHANGE`：OpenAIへの認証コード交換で失敗。
- `SIGNING_KEYS`：公開署名鍵の取得で失敗。
- `ID_TOKEN_VERIFY`：IDトークンの検証で失敗。`NONCE`・`SIGNATURE`・`ISSUER`・`AUDIENCE` などで理由を示す。
- `CREDENTIAL_SAVE`：検証後の認証情報保存で失敗。

OpenAIのログイン画面内で止まりcallbackへ戻っていない場合は、Bot側にcallback失敗ログは出ない。OpenAI画面の診断情報と区別する。

## 参照した公開仕様

- [OSS向けChatGPTプラン利用](https://developers.openai.com/siwc/token-sharing-open-source)
- [登録・ログイン](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [VPS向け手順](https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms)
- [モデル一覧・Responses](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [トークン更新・ログアウト](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [プレビューの制約](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

## Web 検索と URL 参照

Responses リクエストに `tools: [{"type":"web_search","external_web_access":true}]` と `tool_choice: "auto"` を渡し、全応答モードで Web 検索を利用可能にする。URL の参照・検索の依頼や最新情報の確認ではツールで調べるよう指示する。各回に検索するかはモデルが判断する。Bot 側の追加 API キーや検索サービスの登録は不要で、既存の ChatGPT 接続を使う。

検索の引用注釈が返った場合、応答の末尾に出典 URL を表示する。掛け合いモードでは JSON の検証・表示変換後に付けるため、JSON の外に出典を追記して解析を壊さない。長文は従来どおり添付になる。

URL 本文の取得を保証する機能ではない。認証が必要なページ・共有ページ・取得不可のサイトなどは読めない場合がある。取得できた情報と未取得のページを区別する。長期記憶検索や外部サービスへの書き込みは追加しない。

[公式 Web 検索仕様](https://developers.openai.com/api/docs/guides/tools-web-search)に従う。選択モデルと ChatGPT 接続での実際のツール利用可否は、稼働環境で検索の依頼と公開 URL の参照を試し、取得結果と出典で確認する。API がツール非対応などで失敗した場合は、ツールを外した再試行や別の課金方式への切り替えを行わず既存のエラー処理で知らせる。ローカルの模擬 SSE テストは実アカウントでの検索成功やデプロイ完了を証明しない。
