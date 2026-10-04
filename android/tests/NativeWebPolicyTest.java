package com.agentmonitor.live;

/** Negative navigation and user-action cases exercise the production command validator. */
public final class NativeWebPolicyTest {
    private static int checks;
    private static final String PAGE = NativeWebPolicy.ORIGIN + "/#/tasks";
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static NativeWebPolicy.Command command(String url, String currentTask) { return NativeWebPolicy.command(PAGE, PAGE, true, true, url, currentTask); }
    public static void main(String[] args) {
        check(NativeWebPolicy.trusted(PAGE), "real workbench origin accepted");
        check(NativeWebPolicy.trusted(NativeWebPolicy.ORIGIN + ":443/#/profile"), "explicit HTTPS default port retains same origin");
        for (String value : new String[]{"http://monitor.example.com", "https://monitor.example.com.evil.test", "https://monitor.example.com@evil.test", "https://evil.test@monitor.example.com", "https://monitor.example.com:444", "https://monitor.example.com.", "https://monitor%2eexample.com", "file:///data/local/tmp/a", "content://private/a", "intent://launch", "javascript:alert(1)", "data:text/html,test", "about:blank", null}) check(!NativeWebPolicy.trusted(value), "reject untrusted origin/navigation");
        String track = "agentmonitor://track?task_id=local%3Acodex%3Aabc-123";
        check(command(track, "") != null && command(track, "").taskId.equals("local:codex:abc-123"), "decode task ID once");
        check(NativeWebPolicy.command(PAGE, PAGE, false, true, track, "") == null, "iframe cannot start service");
        check(NativeWebPolicy.command(PAGE, PAGE, true, false, track, "") == null, "script navigation without gesture cannot start service");
        check(NativeWebPolicy.command("https://evil.test", PAGE, true, true, track, "") == null, "untrusted committed page rejected");
        check(NativeWebPolicy.command(PAGE, "https://evil.test", true, true, track, "") == null, "navigation changed current origin");
        for (String url : new String[]{"agentmonitor://track?task_id=a&task_id=b", "agentmonitor://track?task_id=a&command=delete", "agentmonitor://track?task_id=a;command=delete", "agentmonitor://track?task_id=a%26command%3Ddelete", "agentmonitor://track?task_id=%252e%252e", "agentmonitor://track?task_id=%2Fetc%2Fpasswd", "agentmonitor://track?task_id=%00", "agentmonitor://track?task_id=a+b", "agentmonitor://track?task%5Fid=a", "agentmonitor://track?task_id=", "agentmonitor://track?task_id=%GG", "agentmonitor://track/path?task_id=a", "agentmonitor://track?task_id=a#fragment", "agentmonitor://user@track?task_id=a", "agentmonitor://track:123?task_id=a", "agentmonitor://execute?task_id=a", "agentmonitor://connect?extra=1", "agentmonitor://connect?", "agentmonitor://google-profile?url=https://evil.test"}) check(command(url, "a") == null, "reject malformed or expanded command");
        for (String action : new String[]{"connect", "settings", "logout", "google-profile"}) check(command("agentmonitor://" + action, "") != null, "fixed command accepted");
        check(command("agentmonitor://stop?task_id=A", "A") != null, "current task can be stopped");
        check(command("agentmonitor://stop?task_id=A", "B") == null, "old stop link cannot cancel newly tracked task");
        check(command("agentmonitor://stop?task_id=A", "") == null, "old stop after termination has no effect");
        String font = NativeWebPolicy.ORIGIN + "/assets/fonts/MiSans-Regular.ttf";
        check(NativeWebPolicy.bundledFont("GET", font), "exact fixed-origin GET uses unchanged bundled font");
        check(!NativeWebPolicy.bundledFont("POST", font), "POST must not be replaced with local font bytes");
        check(!NativeWebPolicy.bundledFont("HEAD", font), "only GET is intercepted");
        check(!NativeWebPolicy.bundledFont("GET", font + "?v=1"), "font URL with query goes through regular resource policy");
        check(!NativeWebPolicy.bundledFont("GET", font + "#part"), "font mapping accepts the exact URL only");
        check(!NativeWebPolicy.bundledFont("GET", "https://other.invalid/assets/fonts/MiSans-Regular.ttf"), "external origin cannot access bundled font mapping");
        check(!NativeWebPolicy.bundledFont("GET", NativeWebPolicy.ORIGIN + "/assets/fonts/%4DiSans-Regular.ttf"), "encoded path does not expand the local mapping");
        check(!NativeWebPolicy.bundledFont("GET", NativeWebPolicy.ORIGIN + "/assets/fonts/other.ttf"), "font mapping cannot read other packaged files");
        System.out.println("NativeWebPolicyTest: " + checks + " checks passed");
    }
}
