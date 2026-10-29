# ChatGPTviaDiscord

個人用 Discord サーバーで雑談を続け、経緯と記憶を保ちながら分科会やプロジェクトへ育てる Bot。

構想と受け入れ条件は [MVP 要件定義書](docs/01_構想.md) を参照。

## Java とビルドの前提

Java 21 を使用する。CI は参照元に合わせて Maven を採用し、リポジトリ直下の `pom.xml` に対して次を実行する。

```sh
mvn --batch-mode --no-transfer-progress verify
```

`target/` に、依存ライブラリを含む `java -jar` で起動可能な JAR を 1 件生成する。sources、javadoc、tests、`original-*` の JAR は対象外。成果物は `chatgptviadiscord.jar` に統一する。

現在のアプリケーションは Discord Gateway に接続し、指定サーバーへの参加を確認してオンラインを維持する最小構成。ChatGPT 応答、メッセージ取得、履歴保存はまだ実装していない。

## CI と VPS デプロイ

[deployVps.yml](.github/workflows/deployVps.yml) は、[Discord2Html の workflow](https://github.com/yui-Kitamura/Discord2Html/blob/main/.github/workflows/deployVps.yml) を基に、build と deploy の 2 ジョブに分離する。

| 起点 | build | deploy |
| --- | --- | --- |
| main への push | 実行 | build 成功後に実行 |
| main 宛ての pull request | 実行 | 実行しない |
| 手動実行 | 選択した ref を build | main を選択した場合だけ実行 |

build は利用者が登録する Secrets を参照せず、外部の Discord・OpenAI・DB への接続を前提としない。GitHub が提供するトークンの権限は `contents: read` に限定し、checkout 後の Git 認証情報を保持しない。

JAR、systemd unit、コミット SHA、チェックサムを同一 run の artifact として渡す。deploy は再ビルドせず、その artifact を検証し、`appleboy/scp-action` で転送する。`appleboy/ssh-action` による反映・再起動も同じ deploy ジョブ内で行う。Actions は確認したリリースのコミット SHA に固定する。

### デプロイ用設定

参照元の接続方式に合わせ、次の Repository Secrets を deploy だけで利用する。

| 名前 | 内容 |
| --- | --- |
| `VPS_HOST` | 接続先 |
| `VPS_USERNAME` | デプロイ用 SSH ユーザー |
| `VPS_PASSWORD` | SSH パスワード |
| `VPS_HOST_FINGERPRINT` | 接続先 SSH ホスト鍵の SHA256 fingerprint |

Repository Variable `VPS_PORT` は省略時 22。Bot トークンや ChatGPT の認証情報はこれらの設定と分け、VPS 側で管理する。JAR や Actions artifact に埋め込まない。

### VPS の事前準備

- Linux、Java 21、`tar`、`sha256sum`、systemd を用意する。
- `/etc/chatgptviadiscord.env` を事前に配置する。CI は内容を表示・上書きせず、所有者 root:root・権限 600 を適用する。
- デプロイ用 SSH ユーザーが、パスワードなしの `sudo -n` で専用ディレクトリ・ユーザー・unit の作成、権限設定、systemctl、journalctl を実行できるようにする。
- Java 21 の java コマンドを `/usr/bin/java` に用意する。CI は JRE のインストールや OS 更新は行わない。

deploy が `/opt/chatgptviadiscord/releases/` を準備し、専用ユーザーと systemd unit を作成・更新する。unit の `StateDirectory` により `/var/lib/chatgptviadiscord` を作り、履歴や認証などは release の外に保持する。

転送先は `/opt/chatgptviadiscord/releases/<コミットSHA>-<run ID>-<実行回数>/`。チェックサム確認後に `current` を切り替え、サービスを再起動する。その起動固有の systemd InvocationID に属する journal から `BOT_READY` を最大約 325 秒待ち、Discord READY と対象サーバーへの参加確認が済んだことを判定する。

プロセス停止・再起動ループ・READY 未確認の場合は、直前の JAR の release への復旧を試み、ジョブを失敗させる。初回で戻り先がない場合はサービスを停止する。これは起動時の確認であり、Discord クライアント上の表示や、未実装の ChatGPT 応答を検証するものではない。旧 release の世代削除と、会話データのバックアップは別途運用を決める。

RAM 2GB・ストレージ 100GB の VPS を初期想定とする。ビルドは GitHub runner で行い、VPS では Bot と保存・検索処理を動かす。JVM のヒープ上限と実メモリ使用量はアプリ実装後に測定する。

## Bot の設定と起動

Discord Developer Portal で作成した Bot を、対象サーバーへ招待する。今回の接続確認では管理者権限や Message Content Intent などの privileged intent は不要。Bot はメッセージを送信・取得しない。

| 環境変数 | properties のキー | 内容 |
| --- | --- | --- |
| DISCORD_BOT_TOKEN | discord.bot.token | 必須。Bot の生トークン。Bot という接頭辞は付けない。 |
| DISCORD_GUILD_ID | discord.guild.id | 必須。参加先サーバーの ID。 |
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
