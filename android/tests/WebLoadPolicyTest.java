package com.agentmonitor.live;

/** User-visible outcomes for network failures and SSL errors in independent resources. */
public final class WebLoadPolicyTest {
    private static int checks;
    private static void check(boolean condition, String scenario) {
        checks++;
        if (!condition) throw new AssertionError(scenario);
    }
    public static void main(String[] args) {
        String origin = NativeWebPolicy.ORIGIN;
        String tasks = origin + "/#/tasks";
        check(WebLoadPolicy.workbenchAction(tasks) == WebLoadPolicy.WorkbenchAction.RELOAD_CURRENT, "Newly installed login cookie must reload an existing identical workbench URL");
        check(WebLoadPolicy.workbenchAction(origin + "/#/task/local%3Acodex%3Atest") == WebLoadPolicy.WorkbenchAction.RELOAD_CURRENT, "A trusted detail route can reload and retain its route");
        check(WebLoadPolicy.workbenchAction(null) == WebLoadPolicy.WorkbenchAction.LOAD_CANONICAL, "A newly created WebView starts at the fixed canonical URL");
        check(WebLoadPolicy.workbenchAction("about:blank") == WebLoadPolicy.WorkbenchAction.LOAD_CANONICAL, "Cleared logout page must navigate back to the canonical workbench");
        check(WebLoadPolicy.workbenchAction("https://other.invalid/#/tasks") == WebLoadPolicy.WorkbenchAction.LOAD_CANONICAL, "Unknown origin must never be reloaded by the workbench action");
        check(WebLoadPolicy.workbenchAction("file:///workbench.html") == WebLoadPolicy.WorkbenchAction.LOAD_CANONICAL, "Local content cannot become a trusted reload target");
        check(WebLoadPolicy.networkMessage(-2, false).equals("找不到工作台地址"), "DNS failure must explain address lookup, not account status");
        check(WebLoadPolicy.networkMessage(-2, true).equals("网络未连接"), "No network is a clearer cause than secondary DNS failure");
        check(WebLoadPolicy.networkMessage(-6, true).equals("网络未连接"), "Connection failure while offline");
        check(WebLoadPolicy.networkMessage(-6, false).equals("暂时连不上工作台"), "Reachable network does not prove the service is reachable");
        check(WebLoadPolicy.networkMessage(-8, false).equals("连接超时，请重试"), "Timeout remains retryable");
        check(WebLoadPolicy.networkMessage(-11, true).equals("无法验证安全连接"), "TLS failure must not be disguised as an offline-only problem");
        check(WebLoadPolicy.networkMessage(-999, false).equals("工作台暂时无法打开"), "Unknown platform code has a safe fallback");
        check(WebLoadPolicy.httpMessage(401).equals("登录已失效，请重试"), "An expired login is distinct from a network failure");
        check(WebLoadPolicy.httpMessage(403).equals("暂时无法访问工作台"), "HTTP refusal does not falsely claim DNS failure");
        check(WebLoadPolicy.httpMessage(404).equals("工作台页面不存在"), "Missing document is explained");
        check(WebLoadPolicy.httpMessage(429).equals("访问较频繁，请稍后重试"), "Rate limit should encourage waiting");
        check(WebLoadPolicy.httpMessage(503).equals("工作台服务暂时不可用"), "Service outage is distinguished from authentication");
        check(WebLoadPolicy.httpMessage(504).equals("连接超时，请重试"), "Gateway timeout stays a timeout");

        check(WebLoadPolicy.sslAffectsDocument(origin + "/", tasks, false), "Main HTTPS document omits client-side fragment");
        check(WebLoadPolicy.sslAffectsDocument(origin, tasks, false), "Empty document path is the root path");
        check(WebLoadPolicy.sslAffectsDocument(origin + ":443/", tasks, false), "Default HTTPS port is the same fixed origin");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/assets/fonts/MiSans-Regular.ttf", tasks, false), "Font TLS failure must not hide loading workbench");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/api/me", tasks, false), "API TLS failure is not a main document failure");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/assets/app-icon-192.png", tasks, true), "Image TLS failure must not hide committed workbench");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/", tasks, true), "Even same-path resource cannot cover an already committed document");
        check(!WebLoadPolicy.sslAffectsDocument("https://other.invalid/", tasks, false), "Untrusted resource cannot claim a document match");
        check(!WebLoadPolicy.sslAffectsDocument(origin + ".other.invalid/", tasks, false), "Host prefix is not the fixed origin");
        check(!WebLoadPolicy.sslAffectsDocument("http://monitor.example.com/", tasks, false), "HTTP cannot match HTTPS");
        check(!WebLoadPolicy.sslAffectsDocument(origin + ":8443/", tasks, false), "Another port cannot match the document");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/?resource=1", tasks, false), "Query string is part of resource identity");
        check(WebLoadPolicy.sslAffectsDocument(origin + "/?v=1", origin + "/?v=1#/tasks", false), "Matching query with fragment removed");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/?v=%31", origin + "/?v=1", false), "Do not decode query into an inferred match");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/%2f", tasks, false), "Do not decode paths into a document match");
        check(!WebLoadPolicy.sslAffectsDocument(null, tasks, false), "Missing resource URL cannot cover the page");
        check(!WebLoadPolicy.sslAffectsDocument(origin + "/", null, false), "Missing document cannot establish a match");
        check(!WebLoadPolicy.sslAffectsDocument("not a URI", tasks, false), "Malformed URL cannot match");
        System.out.println("WebLoadPolicyTest: " + checks + " checks passed");
    }
}
