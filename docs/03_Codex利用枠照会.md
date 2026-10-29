# Codex利用枠照会の試行

`/gvd limit` から、Botと同じメールアドレスでログインしたCodexの利用枠を読み取る。設定例では有効。設定を省略した場合は無効のまま。回答生成のSIWC接続は変更しない。この連携にはCodex CLIの別途インストール・ログインが必要で、JARのデプロイだけでは有効にならない。既存の環境ファイルは設定例の変更では更新されないため、利用する環境でも `CODEX_USAGE_ENABLED=true` を設定する。

## 取得する情報と範囲

[公式App Server仕様](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt)の `account/rateLimits/read` を使用する。`rateLimitsByLimitId` があれば優先し、なければ互換用の `rateLimits` を読む。各枠の `primary` / `secondary` について、`100 - usedPercent` を残り％、`windowDurationMins` を枠の期間、`resetsAt` を回復日時として表示する。Discordの日時表示を使うため、読む人のタイムゾーンで表示される。取得時刻は照会完了時刻。表示は先頭4枠までで、省略時は明記する。

Botの接続メールと、Codexの `account/read` が返すメールを照会前後で比較する。メールアドレス自体は公開しない。メール一致はWorkspaceの同一性やSIWC側のアプリ制限との一致を証明しない。返された枠だけを表示し、ChatGPT全機能共通の残量やBotの残り回答回数とは扱わない。未提供の値・不正な数値は不明。429だけから枠全体の枯渇や回復を推測しない。

## VPSで準備

管理者がCodex CLIの実行ファイルをインストールし、実際の絶対パスを確認する。自動ダウンロードやPATHからの探索はしない。以下は `/usr/local/bin/codex` に配置した場合の例。Bot実行ユーザーが読める実行ファイルにする。

専用保存先を作成し、Bot共通のSIWCアカウントと同じアカウントでログインする。ログインのURL・一時コードは管理者の端末だけで扱い、Discordへ投稿しない。

```sh
sudo install -d -m 0700 -o chatgptviadiscord -g chatgptviadiscord /var/lib/chatgptviadiscord/codex-usage
sudo -u chatgptviadiscord env CODEX_HOME=/var/lib/chatgptviadiscord/codex-usage \
  /usr/local/bin/codex -c 'cli_auth_credentials_store="file"' login --device-auth
```

デバイス認証を利用できない場合は、同じユーザー・保存先・認証保存設定で `login` を実行し、Codexの案内に従う。CLIのバージョンによって認証手順やApp Server仕様が異なる可能性がある。専用保存先の設定・認証を他のCLI用途と共有しない。ホームディレクトリ全体をコピーしたり、既存のデスクトップ認証を転用したりしない。

`/etc/chatgptviadiscord.env` に以下を設定し、Botを再起動する。

```ini
CODEX_USAGE_ENABLED=true
CODEX_USAGE_EXECUTABLE=/usr/local/bin/codex
CODEX_USAGE_HOME=/var/lib/chatgptviadiscord/codex-usage
CODEX_USAGE_TIMEOUT_SECONDS=20
```

`--config` を使う環境では、同等の `codex.usage.enabled` / `codex.usage.executable` / `codex.usage.home` / `codex.usage.timeout.seconds` を設定できる。環境変数が優先。実行ファイル・保存先は絶対パス必須、期限は1〜60秒。`--check-config` は形式だけを検証し、ログインや通信の成功を確認しない。Codexの保存先はリポジトリ外とし、Bot以外から読めない権限を保つ。

## 確認と失敗時の対応

1. BotのSIWC認証・モデル一覧照会が成功する状態で、Discordから `/gvd limit` を実行する。
2. 実行チャンネルへの公開返信に「Codex連携の利用枠（試行）」と、提供された残り％・回復日時・取得時刻が表示されることを確認する。
3. 同じアカウント・WorkspaceのUsage画面と、表示された各利用枠を比較する。異なる枠なら同一と判断しない。
4. 別アカウントでのログイン・ログアウト・通信失敗で、数値が出ず不明になることを確認する。失敗した照会の過去値は再利用しない。

「未認証・別アカウント」なら専用保存先へ同じアカウントで再ログインする。「起動できません」ならパス・実行権限・保存先を確認する。「照会に失敗」「タイムアウト」ならバージョン・ログイン・外部通信を管理者の端末で確認する。上流のエラー本文、メール、トークン、子プロセスのstderrは公開返信やBotログへ転送しない。確認が済むまで推測値を表示しない。

照会ごとに短命な `codex app-server --listen stdio://` を起動する。送信するメソッドは `initialize` / `initialized` / `account/read` / `account/rateLimits/read` のみ。`thread/start` / `turn/start`、リセット権の消費、課金への切替は行わない。処理完了・失敗・期限切れで子プロセスを終了する。BotのトークンやAPIキーは子プロセスへ渡さず、Codex自身が専用保存先の認証を管理する。

このPRのローカル検証では、模擬App Serverを使うプロセス通信・数値整形・公開返信を含むJava 84件、Python 16件のテストとJARビルドが成功した。Windowsでは短い一時パスを指定して既存HTTPテストを実行した。実Codex CLI `0.164.0-alpha.11.1` でも、空の専用保存先で初期化・未認証の検出・数値を表示しない処理を確認した。実アカウントでの値取得、VPS、Discord表示、Usage画面との照合は別途確認が必要。無効に戻す場合は `CODEX_USAGE_ENABLED=false` として再起動すると、従来のAPIヘッダー観測表示へ戻る。
