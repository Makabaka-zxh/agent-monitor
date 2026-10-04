package com.agentmonitor.live;

public final class ServerOriginTest {
    private static int checks;
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("Origin check " + checks); }
    public static void main(String[] args) {
        check("https://monitor.example.com".equals(NativeWebPolicy.canonicalOrigin("https://MONITOR.example.com:443/")));
        for(String bad: new String[]{null,"","https://","http://monitor.example.com","https://user@monitor.example.com",
                "https://monitor.example.com:8443","https://monitor.example.com/path","https://monitor.example.com?x=1",
                "https://monitor.example.com#x","https://monitor.example.com.","https://monitor%2eexample.com",
                " https://monitor.example.com","https://monitor.example.com\n","https://monitor.example.com/../"})
            check(NativeWebPolicy.canonicalOrigin(bad).isEmpty());
        String before=NativeWebPolicy.ORIGIN;
        try {
            NativeWebPolicy.ORIGIN="";
            check(!NativeWebPolicy.trusted("https://monitor.example.com/#/tasks"));
            NativeWebPolicy.ORIGIN=NativeWebPolicy.canonicalOrigin("https://my.example.net/");
            check(NativeWebPolicy.trusted("https://my.example.net/#/tasks"));
            check(NativeWebPolicy.trusted("https://my.example.net:443/#/tasks"));
            for(String bad:new String[]{"https://monitor.example.com/#/tasks","https://my.example.net.evil.test/","https://my.example.net@evil.test/","http://my.example.net/","https://my.example.net:8443/"}) check(!NativeWebPolicy.trusted(bad));
        } finally { NativeWebPolicy.ORIGIN=before; }
        System.out.println("ServerOriginTest: " + checks + " checks passed");
    }
}
