# エージェント作業方針

## 指示の確認

- ユーザーから実装指示を受けたら、作業を始める前に readback を返す。依頼の理解、実施範囲、期待する結果を簡潔に述べてから作業に入る。
- readback 自体を承認待ちにはしない。実装に必要な判断材料が不足する場合は、確認できる調査を進め、必要な点だけ質問する。
- ユーザーの明示的な指示を優先し、未確定の仕様を確定事項として実装しない。

## ブランチとコミット

- 作業開始時に、現在のブランチ、HEAD、作業ツリーの変更を確認する。ユーザーの既存変更を上書きしたり、依頼と無関係な変更を混ぜたりしない。
- 原則として `main` の HEAD、または会話の文脈で指定されたブランチの HEAD から、新しい作業ブランチ（workingBranch）を作成する。基点としたブランチ・コミットを確認する。
- ローカル HEAD とリモートの最新状態を区別する。取得が必要なら GitHub App 認証で fetch し、既存変更を破壊する reset や履歴の書き換えを行わない。
- 作業ブランチで依頼の変更を実施し、適切な確認後、対象ファイルだけをコミットする。
- コミットログ（件名と、必要に応じた本文）は日本語で記述する。
- push と PR 作成はユーザーの指示に応じて実施する。ローカルコミットの作成だけを理由に、自動で push や PR 作成をしない。
- 完了報告には、変更内容、確認結果、ブランチとコミット、push・PR の実施状況を含める。未実施の確認を成功扱いしない。

## GitHub App bot としての操作

- 対象リポジトリは `yui-Kitamura/ChatGPTviaDiscord`。
- GitHub 操作は GitHub App `YuiKitamuraWithCodex` の bot として行う。個人アカウントの認証へ黙って切り替えない。
- `git`、`gh` コマンド、GitHub API の使用を認める。`gh` もこの App の installation token で認証し、個人の既存ログインに依存しない。
- App ID は `5333025`。秘密鍵の参照先は `D:/toolSW/githubapp/yuiKitamuraWithCodex/yuikitamurawithcodex.2026-10-15.private-key.pem`。対象リポジトリの installation を確認し、必要な権限だけを持つ短期トークンを発行する。
- App 認証で Git 操作をする際は、個人の credential helper が使われないようにする。トークンは必要なプロセスにだけ渡す。
- エージェントが新規作成するコミットには、次の bot 名義を使う。既存コミットの著作者は変更しない。
  - 名前: `yuikitamurawithcodex[bot]`
  - メール: `343089506+yuikitamurawithcodex[bot]@users.noreply.github.com`
- 秘密鍵、JWT、installation token をリポジトリ、チャット、ログに出力・保存しない。
- App の認証や権限が不足している場合は、阻害要因を報告する。秘密情報をチャットへ貼るよう依頼しない。
- push・PR 作成後は、対象ブランチ、リモート HEAD、PR の内容を確認して報告する。
