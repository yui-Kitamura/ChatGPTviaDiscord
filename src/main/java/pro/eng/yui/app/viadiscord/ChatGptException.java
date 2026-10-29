package pro.eng.yui.app.viadiscord;

import java.io.IOException;

/** Never contains upstream response bodies, URLs, credentials, or user text. */
final class ChatGptException extends IOException {
    final String code;
    final int status;
    String requestId="";
    enum DescriptionHint { NOT_PROVIDED, PKCE, REDIRECT_URI, AUTHORIZATION_CODE, CLIENT_BINDING, OTHER }
    DescriptionHint descriptionHint=DescriptionHint.NOT_PROVIDED;
    ChatGptException(String code, int status) { super("ChatGPT request failed (" + code + ", HTTP " + status + ")."); this.code=code; this.status=status; }
    void log() {System.err.printf("GVD_OPENAI_FAILED http_status=%d code=%s request_id=%s%n",status,code,requestId);}
    String userMessage() {
        return switch (code) {
            case "subscription_sharing_usage_limit_exceeded" -> "このアプリのChatGPT利用枠に達しました。https://chatgpt.com/settings/usage で確認し、時間を置いてください。";
            case "subscription_sharing_user_not_eligible" -> "このChatGPTアカウント・Workspaceではプラン利用が許可されていません。";
            case "login_required", "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused", "subscription_sharing_invalid_user" -> "ChatGPTへの再ログインが必要です。/gvd auth を実行してください。";
            case "permission_missing" -> "ChatGPTプラン利用への同意がありません。/gvd auth から同意を確認してください。";
            case "model_required" -> "先に /gvd model で利用できるモデルを確認し、name を指定して選んでください。";
            default -> status == 403 ? "ChatGPT側の権限・ポリシーにより接続できません。管理者が設定を確認してください。"
                    : "ChatGPTの応答を完了できませんでした。時間を置いて再投稿してください。自動再試行はしていません。";
        };
    }
}
