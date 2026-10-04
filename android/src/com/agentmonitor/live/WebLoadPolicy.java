package com.agentmonitor.live;

import java.net.URI;
import java.util.Objects;

/** Error presentation only; this never grants permission to load a resource. */
public final class WebLoadPolicy {
    private WebLoadPolicy() {}
    public enum WorkbenchAction { RELOAD_CURRENT, LOAD_CANONICAL }

    public static WorkbenchAction workbenchAction(String currentUrl) {
        // Repeating loadUrl for the same fragment can stay in the old document.
        return NativeWebPolicy.trusted(currentUrl) ? WorkbenchAction.RELOAD_CURRENT : WorkbenchAction.LOAD_CANONICAL;
    }

    // Stable WebViewClient error constants, kept Android-free for boundary tests.
    public static String networkMessage(int code, boolean offline) {
        if (code == -11) return "无法验证安全连接";
        if (offline) return "网络未连接";
        switch (code) {
            case -2: return "找不到工作台地址";
            case -6: case -7: return "暂时连不上工作台";
            case -8: return "连接超时，请重试";
            case -4: return "登录已失效，请重试";
            case -15: return "访问较频繁，请稍后重试";
            case -16: return "已阻止不安全的页面";
            default: return "工作台暂时无法打开";
        }
    }

    public static String httpMessage(int status) {
        if (status == 401) return "登录已失效，请重试";
        if (status == 403) return "暂时无法访问工作台";
        if (status == 404) return "工作台页面不存在";
        if (status == 408 || status == 504) return "连接超时，请重试";
        if (status == 429) return "访问较频繁，请稍后重试";
        if (status >= 500 && status <= 599) return "工作台服务暂时不可用";
        return "工作台暂时无法打开";
    }

    /** SSL callbacks do not identify a frame. A different resource must not hide the document. */
    public static boolean sslAffectsDocument(String resourceUrl, String documentUrl, boolean documentCommitted) {
        if (documentCommitted || !NativeWebPolicy.trusted(resourceUrl) || !NativeWebPolicy.trusted(documentUrl)) return false;
        try {
            URI resource = new URI(resourceUrl), document = new URI(documentUrl);
            String resourcePath = resource.getRawPath(), documentPath = document.getRawPath();
            if (resourcePath == null || resourcePath.isEmpty()) resourcePath = "/";
            if (documentPath == null || documentPath.isEmpty()) documentPath = "/";
            // Fragments are not sent to the server; preserve raw path and query without decoding.
            return resourcePath.equals(documentPath) && Objects.equals(resource.getRawQuery(), document.getRawQuery());
        } catch (Exception ignored) { return false; }
    }
}
