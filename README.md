# ChatGPTviaDiscord

個人用 Discord サーバーで雑談を続け、経緯と記憶を保ちながら分科会やプロジェクトへ育てる Bot。

構想と受け入れ条件は [MVP 要件定義書](docs/01_構想.md) を参照。

Discord サーバー内で会話する AI 向けの背景説明は [会話 AI 用 README](docs/discord-ai/README.md)、振る舞いの指針は [会話 AI 用 AGENTS.md](docs/discord-ai/AGENTS.md) を参照。開発用の文書とは分けて管理する。ChatGPT接続を有効にすると、同梱した原文を応答指示として読み込む。

## Java とビルドの前提

Java 21 を使用する。CI は参照元に合わせて Maven を採用し、リポジトリ直下の `pom.xml` に対して次を実行する。

```sh
mvn --batch-mode --no-transfer-progress verify
```

`target/` に、依存ライブラリを含む `java -jar` で起動可能な JAR を 1 件生成する。sources、javadoc、tests、`original-*` の JAR は対象外。成果物は `chatgptviadiscord.jar` に統一する。

現在のアプリケーションは Discord Gateway に接続し、指定サーバーへの参加を確認してオンラインを維持する最小構成。通常投稿への 👀 と通知を抑えた定型返信、チャンネル単位の応答停止・再開と `/gvd mode` の応答設定保存に対応する。`CHATGPT_ENABLED=true` で、Bot共通のChatGPT接続と直近会話の保存を有効にできる。設定・認証の手順は [ChatGPT接続](docs/02_ChatGPT接続.md) を参照。

## CI と VPS デプロイ

[deployVps.yml](.github/workflows/deployVps.yml) は、[Discord2Html の workflow](https://github.com/yui-Kitamura/Discord2Html/blob/main/.github/workflows/deployVps.yml) を基に、build と deploy の 2 ジョブに分離する。

| 起点 | build | deploy |
| --- | --- | --- |
| main への push | 実行 | build 成功後に実行 |
| main 宛ての pull request | 実行 | 実行しない |
| 手動実行（通常） | 選択した ref を build | main を選択した場合だけ実行 |
| 手動実行（定義登録のみ） | 選択した ref を build | グローバルコマンド定義だけ登録し、Bot を再起動しない |

build は利用者が登録する Secrets を参照せず、外部の Discord・OpenAI・DB への接続を前提としない。GitHub が提供するトークンの権限は `contents: read` に限定し、checkout 後の Git 認証情報を保持しない。

JAR、systemd unit、コマンド登録スクリプト、コミット SHA、チェックサムを同一 run の artifact として渡す。deploy は再ビルドせず、その artifact を検証し、`appleboy/scp-action` で転送する。`appleboy/ssh-action` による反映・再起動も同じ deploy ジョブ内で行う。Actions は確認したリリースのコミット SHA に固定する。

### デプロイ用設定

参照元の接続方式に合わせ、次の Repository Secrets を deploy だけで利用する。

| 名前 | 内容 |
| --- | --- |
| `VPS_HOST` | 接続先 |
| `VPS_USERNAME` | デプロイ用 SSH ユーザー |
| `VPS_PASSWORD` | SSH パスワード。sudo 用パスワードの既定値にも使う。 |
| `VPS_SUDO_PASSWORD` | 任意。sudo のパスワードが SSH と異なる場合に指定する。 |
| `VPS_HOST_FINGERPRINT` | 接続先 SSH ホスト鍵の SHA256 fingerprint |

必要な Secrets の存在・fingerprint の形式を先に確認する。SSH の接続先・ホスト鍵・ログイン認証・sudo 認証の確認は、次の Prepare ステップで行う。fingerprint は実際に接続で選択されるホスト鍵の値を登録し、鍵種を Ed25519 に固定して取得しない。

Repository Variable `VPS_PORT` は省略時 22。Bot トークンや ChatGPT の認証情報はこれらの設定と分け、VPS 側で管理する。JAR や Actions artifact に埋め込まない。

### VPS の事前準備

- Linux、Java 21、`tar`、`sha256sum`、systemd を用意する。
- `/etc/chatgptviadiscord.env` を事前に配置する。CI は内容を表示・上書きせず、所有者 root:root・権限 600 を適用する。
- デプロイ用 SSH ユーザーに、専用ディレクトリ・ユーザー・unit の作成、権限設定、systemctl、journalctl を実行する sudo 権限が必要。パスワード不要の sudo 設定は必須としない。
- Java 21 の java コマンドを `/usr/bin/java` に用意する。CI は JRE のインストールや OS 更新は行わない。

root 接続では直接コマンドを実行する。一般ユーザー接続では `sudo -S` にパスワードを標準入力で渡す。既定は `VPS_PASSWORD`、別のパスワードなら `VPS_SUDO_PASSWORD` を使う。パスワードを sudo のコマンド引数や永続ファイルへ書かず、build へも渡さない。認証・コマンドエラーは失敗として返す。

deploy が `/opt/chatgptviadiscord/releases/` を準備し、専用ユーザーと systemd unit を作成・更新する。unit の `StateDirectory` により `/var/lib/chatgptviadiscord` を作り、履歴や認証などは release の外に保持する。

転送先は `/opt/chatgptviadiscord/releases/<コミットSHA>-<run ID>-<実行回数>/`。チェックサム確認後に `current` を切り替え、サービスを再起動する。その起動固有の systemd InvocationID に属する journal から `BOT_READY` を最大約 325 秒待ち、Discord READY と対象サーバーへの参加確認が済んだことを判定する。

プロセス停止・再起動ループ・READY 未確認の場合は、直前の JAR の release への復旧を試み、ジョブを失敗させる。初回で戻り先がない場合はサービスを停止する。これは起動時の確認であり、Discord クライアント上の表示や、ChatGPTでの認証・応答成功を検証するものではない。旧 release の世代削除と、会話データのバックアップは別途運用を決める。

RAM 2GB・ストレージ 100GB の VPS を初期想定とする。ビルドは GitHub runner で行い、VPS では Bot と保存・検索処理を動かす。JVM のヒープ上限と実メモリ使用量はアプリ実装後に測定する。

## Bot の設定と起動

Discord Developer Portal で作成した Bot を、対象サーバーへ招待する。対象チャンネルで「チャンネルを見る」「メッセージ履歴を読む」「リアクションの追加」「メッセージを送信」「スレッドでメッセージを送信」を許可する。管理者権限は不要。既定の定型返信では本文を参照せず、privileged intent は不要。ChatGPT接続を有効にする場合は、Developer PortalのBot設定で **Message Content Intent** と **Server Members Intent** をONにする。ファイル添付権限も必要。

| 環境変数 | properties のキー | 内容 |
| --- | --- | --- |
| DISCORD_BOT_TOKEN | discord.bot.token | 必須。Bot の生トークン。Bot という接頭辞は付けない。 |
| DISCORD_GUILD_ID | discord.guild.id | 必須。参加先サーバーの ID。 |
| CHATGPT_ENABLED | chatgpt.enabled | 既定false。trueで認証とAI返信を有効にする。 |
| CHATGPT_CALLBACK_PORT | chatgpt.callback.port | 127.0.0.1上の認証待受。既定1455。 |
| CHATGPT_REQUEST_TIMEOUT_SECONDS | chatgpt.request.timeout.seconds | 応答期限。既定180秒、10〜300秒。 |
| DISCORD_AI_ROLE_ID | discord.ai.role.id | 任意の初期許可ロール。通常は /gvd accept で設定する。 |
| BOT_STATE_DIRECTORY | bot.state.directory | 設定保存ディレクトリ。既定は作業ディレクトリ配下の state。 |
| BOT_STARTUP_TIMEOUT_SECONDS | bot.startup.timeout.seconds | READY を待つ秒数。既定 60、指定範囲 5〜300。 |

環境変数を優先する。ローカルでファイルを使う場合は、config/application.properties.example を config/application.properties にコピーして、本人だけが読める設定にする。このファイルは Git 対象外。実際のトークンはチャット・コマンド引数・シェル履歴へ貼らず、エディタで入力する。

```sh
# 設定の形式だけを検証。Discord には接続しない。
java -jar target/chatgptviadiscord.jar --config config/application.properties --check-config

# Gateway に接続して常駐する。終了は Ctrl+C。
java -Xmx256m -jar target/chatgptviadiscord.jar --config config/application.properties
```

環境変数を使う場合は --config を省略する。READY を受信し、指定サーバーへの参加も確認できると、BOT_READY と Bot ID・サーバー ID を出力する。指定サーバーに未参加、認証失敗、起動タイムアウトでは終了コード 1 で停止する。オンライン表示の確認では、このログと Discord 上の対象 Bot の表示を確認する。設定検証の成功をオンライン確認とは扱わない。

### VPS でのトークン保持

Bot トークンは VPS の /etc/chatgptviadiscord.env に保持し、root:root・権限 600 にする。ファイルは sudoedit 等で編集する。systemd が EnvironmentFile として読み、Bot 自体は専用の一般ユーザーで実行する。GitHub Secrets に置くデプロイ用 SSH 情報と、Bot の実行時認証は分ける。

ops/chatgptviadiscord.env.example と ops/chatgptviadiscord.service を配置例として用意している。CI 経由では専用ユーザーと unit の配置を deploy ジョブが行う。手動起動時もこの定義に合わせ、実設定を用意した後に起動する。

GitHub Secrets は保存後の値を読み出せない。オンライン確認のためにこちらから VPS へ接続する場合は、別途利用できる SSH 接続設定が必要。アプリと設定を準備しただけでは実接続確認済みとしない。

## グローバルコマンドの先行登録

手動実行で register_commands_only を true にすると、指定 ref の /gvd 定義だけを登録する。通常の main 限定デプロイとは別の経路であり、current の切替・unit の更新・Bot の再起動は行わない。既に準備済みの VPS と Python 3 が必要。

登録内容は次の 8 つ。グローバル登録し、使用コンテキストはギルドに限定する。

- /gvd skipch ch:チャンネル
- /gvd readch ch:チャンネル
- /gvd mode mode:thread または qa
- /gvd accept role:@ロール（省略時は今使える人を一覧）
- /gvd reject role:@ロール
- /gvd auth action:login/status/use/logout account:保存名（省略時login/default）
- /gvd model name:モデルslug（省略時一覧）
- /gvd limit

VPS の既存 EnvironmentFile から Bot トークンを読み、Discord REST API のグローバルコマンド作成・更新エンドポイントに gvd だけを upsert する。他のコマンドをまとめて上書きしない。トークンは GitHub runner や成果物へ取り出さない。

コマンド定義の登録とハンドラーのデプロイは別操作。定義だけを登録しても、実行にはハンドラーを含む JAR が必要。通常起動ではコマンドを再登録しない。認証・許可ロール・モデルの操作は [日本語セットアップ手順](docs/02_ChatGPT接続.md) を参照。

## AI の利用枠・残量確認

`/gvd limit` は実行チャンネルに全員が見える通常メッセージで返す。Bot 共通の ChatGPT 接続で必要に応じてトークンを更新し、モデル一覧の取得によって接続を確認する。回答生成のリクエストや API キー課金への切替は行わない。無効化・未認証・同意不足・接続失敗を区別して案内する。

選択中のモデルについて、直近の Responses 応答に [レート制限ヘッダー](https://developers.openai.com/api/docs/guides/rate-limits) があれば、残リクエスト数・残トークン数・上限・観測時点からリセットまでの時間を観測日時付きで表示する。HTTP エラー・ストリーム内の利用枠エラーも記録する。値はメモリ内のみで、再起動・アクセストークンの更新・モデル変更後に観測がなければ「観測情報なし」。ヘッダーが返らない項目は不明とし、過去の観測値から現在の残量や回復を推測しない。

これは短時間の API 制限の観測であり、ChatGPT プラン全体の残量・回復時刻や課金クレジット残高ではない。今回確認した公開 SIWC 仕様ではプラン残量の数値照会方法を確認できていないため、全体の利用状況は [ChatGPT の設定 → Usage](https://chatgpt.com/settings/usage) へ案内する。[公式のエラー処理](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery) に従い、利用枠エラーだけでアカウント全体が空になったと扱わない。メールアドレス・認証トークン・上流の任意テキストは公開返信に含めない。

定義だけの先行登録では稼働中の Bot の処理は変わらない。`limit` の実行には、この変更を含む JAR のデプロイと再起動も必要。Discord 上での登録・表示・公開返信は別途実機確認する。

## 投稿への応答とチャンネル設定

`CHATGPT_ENABLED=false` では、指定サーバーの通常のユーザー投稿（返信を含む）に 👀 を付け、元投稿へのリプライで `botが読みました` と返す。返信先のユーザーをメンションせず、通知抑制フラグを設定する。Discord の未読表示までは抑制しない。Bot（自身を含む）・Webhook・システム投稿・DM・対象外サーバーには応答しない。この定型返信モードでは本文の取得・保存や AI 呼び出しは行わない。`CHATGPT_ENABLED=true` では、許可ロールの投稿に共通のChatGPT接続で応答する。初回の認証・モデル選択後に利用でき、定型返信をAI回答に置き換える。

- `/gvd skipch ch:チャンネル`：👀 と返信を両方停止する。
- `/gvd readch ch:チャンネル`：そのチャンネルの停止設定を解除する。
- 成功時はサーバー設定の「システムメッセージのチャンネル」（デフォルトログチャンネル）へ通常チャットで `#chName ではROMります`（skipch：応答停止）／`#chName ではROMりません`（readch：応答再開）と投稿する。対象チャンネルはチャンネルメンションで表示し、ユーザー通知は発生させない。実行者には投稿完了を非公開で通知する。カテゴリは指定できない。
- ログチャンネル未設定・投稿失敗時も保存済みの設定変更は維持し、投稿できなかったことを実行者に非公開で通知する。設定保存に失敗した場合は設定を変更せず、ログにも成功を投稿しない。
- 親チャンネルの停止設定は配下のスレッドにも適用する。親が停止中なら、スレッド側の readch だけでは再開せず、親の readch が必要。

設定は `BOT_STATE_DIRECTORY/channel-response.properties` に保存する。既定の systemd unit では `/var/lib/chatgptviadiscord/state/channel-response.properties` になり、再起動・リリース切替後も残る。保存に成功してから応答方針を変更するため、保存失敗時は直前の設定を維持する。設定ファイルが壊れている場合は起動を中止し、全チャンネル応答へ戻さない。ファイルが存在しない初回は、閲覧可能な対象サーバーの全チャンネルに応答する。

## 応答モードと動的キャラ

`/gvd mode thread` / `/gvd mode qa` は実行したチャンネルまたはスレッドの設定を保存する。初期値は qa。親チャンネルと子スレッドは独立しており、親の変更を継承しない。切替には実行場所のチャンネル管理権限が必要。設定は `BOT_STATE_DIRECTORY/response-<guild ID>.properties` に原子的に保存し、再起動後も使う。保存失敗時は変更成功を返さない。

- qa: 通常の1入力1アンサー。キャラロールへのメンションがあれば、その専門性や文脈を反映する。
- thread: 1入力に対し、複数の AI キャラが連番・キャラ名・`>>番号` の参照を使って掛け合う掲示板形式。8〜16レス・3〜6キャラを目安に、話題に応じて説明・観察・ツッコミを交える。Discord のスレッドを自動作成する機能ではない。

キャラ一覧は固定しない。応答生成側が話題に応じて `CharacterSpec(name, persona)` を提案し、`CharacterRoles.ensureCharacters` が必要な Discord ロールを作成する。同名キャラは保存済みの性格・話し方・ロールIDを再利用する。ロールが削除されていれば再作成する。既存の無関係な同名ロールは引き取らない。名前は Unicode NFKC で正規化し、1応答8キャラ・保存64キャラまで。上限時は既存キャラを再利用する。

ロール名は `GvD: <キャラ名>`。権限なし・メンション可能・メンバーへの自動付与なしで、Bot に「ロールの管理」権限と適切なロール階層が必要。ロールをメンションするのは利用者で、AI 出力では通知を発生させない。これらは AI が演じるキャラの文脈ラベルであり、Discord ユーザーや別 Bot を作成する機能ではない。

**接続済みの範囲:** mode 切替の結果には ChatGPT の有効化・ログイン・プラン利用同意・モデル選択の設定状態を表示する。qa は通常のテキスト回答、thread はキャラ定義と発言・参照番号を持つ JSON 応答を検証し、`CharacterRoles` で必要なロールを作成・再利用してから「AI による掛け合い」として表示する。1回答8キャラ・16レスまで。キャラの新規作成は thread 応答時に行う。

保存済みのキャラ定義と、投稿でメンションされたキャラロールを生成側へ渡す。qa でもメンションされたキャラの視点を回答に反映する。thread のレス番号はモデルに任せず Bot が採番し、チャンネル・共通アカウント別の会話履歴へ次番号を保存する。再起動後も続きから採番し、qa へ切り替えた期間は番号を進めない。履歴には AI の元の JSON を保存し、表示用の整形結果と分ける。

構文・キャラ名・参照先を検証してから Discord を変更する。未来レス参照・未定義キャラ・不正 JSON はロールを作らず失敗を案内する。生成後と各ロール処理の直前に利用許可・共通アカウント・チャンネル設定を再確認する。ロール作成・保存が途中で失敗した場合、既に保存できたキャラは保持するが、回答・レス番号を確定せずエラーを返す。複数応答からのロール作成はギルドの応答キューで直列化する。

共有チャットからは掲示板形式だけを参照し、個人的な日報本文・画像をソースやテストへ転載していない。
