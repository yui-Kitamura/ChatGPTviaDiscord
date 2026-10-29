# ChatGPTviaDiscord

個人用 Discord サーバーで雑談を続け、経緯と記憶を保ちながら分科会やプロジェクトへ育てる Bot。

構想と受け入れ条件は [MVP 要件定義書](docs/01_構想.md) を参照。

## Java とビルドの前提

Java 21 を使用する。CI は参照元に合わせて Maven を採用し、リポジトリ直下の `pom.xml` に対して次を実行する。

```sh
mvn --batch-mode --no-transfer-progress verify
```

`target/` に、依存ライブラリを含む `java -jar` で起動可能な JAR を 1 件生成する。sources、javadoc、tests、`original-*` の JAR は対象外。成果物は `chatgptviadiscord.jar` に統一する。

現時点では Java アプリケーションと `pom.xml` は未実装。workflow は先に配置したもので、アプリケーションを追加するまでは build が明示的に失敗する。

## CI と VPS デプロイ

[deployVps.yml](.github/workflows/deployVps.yml) は、[Discord2Html の workflow](https://github.com/yui-Kitamura/Discord2Html/blob/main/.github/workflows/deployVps.yml) を基に、build と deploy の 2 ジョブに分離する。

| 起点 | build | deploy |
| --- | --- | --- |
| main への push | 実行 | build 成功後に実行 |
| main 宛ての pull request | 実行 | 実行しない |
| 手動実行 | 選択した ref を build | main を選択した場合だけ実行 |

build は利用者が登録する Secrets を参照せず、外部の Discord・OpenAI・DB への接続を前提としない。GitHub が提供するトークンの権限は `contents: read` に限定し、checkout 後の Git 認証情報を保持しない。

JAR、コミット SHA、チェックサムを同一 run の artifact として渡す。deploy は再ビルドせず、その artifact を検証し、`appleboy/scp-action` で転送する。`appleboy/ssh-action` による反映・再起動も同じ deploy ジョブ内で行う。Actions は確認したリリースのコミット SHA に固定する。

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
- `/opt/chatgptviadiscord` と `releases/` はデプロイ用ユーザーが書き込めるようにする。
- `chatgptviadiscord.service` を事前に作成し、`/opt/chatgptviadiscord/current/chatgptviadiscord.jar` を起動する。専用の実行ユーザーと、書き込み可能な作業ディレクトリを使用する。
- 環境設定は例として `/etc/chatgptviadiscord.env`、履歴・記憶・認証の保存領域は `/var/lib/chatgptviadiscord/` など、release の外へ置く。実際の設定項目はアプリ実装時に決める。
- デプロイ用ユーザーに、このサービスの restart・stop だけをパスワードなしの `sudo -n` で許可する。

転送先は `/opt/chatgptviadiscord/releases/<コミットSHA>-<run ID>-<実行回数>/`。チェックサム確認後に `current` を切り替え、サービスを再起動する。再起動失敗、または 5 秒後に inactive なら、直前の release へ戻してジョブを失敗させる。初回で戻り先がない場合はサービスを停止する。

この確認で分かるのはサービスの稼働状態まで。Discord 接続・ChatGPT 認証・会話の成功は別途確認する。旧 release の世代削除と、会話データのバックアップは別途運用を決める。

RAM 2GB・ストレージ 100GB の VPS を初期想定とする。ビルドは GitHub runner で行い、VPS では Bot と保存・検索処理を動かす。JVM のヒープ上限と実メモリ使用量はアプリ実装後に測定する。
