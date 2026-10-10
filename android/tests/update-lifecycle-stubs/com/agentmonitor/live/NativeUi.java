package com.agentmonitor.live;
import android.content.Context;
import android.view.View;
import android.widget.*;
/** Retains text, callbacks and children only; does not render an Android window. */
class NativeUi {
    int bg,muted;
    boolean dark;
    NativeUi(Context c) {
    }
    LinearLayout column() {
        return new LinearLayout();
    }
    LinearLayout row() {
        return new LinearLayout();
    }
    LinearLayout card() {
        return new LinearLayout();
    }
    Object background() {
        return new Object();
    }
    int dp(int n) {
        return n;
    }
    TextView text(String s,int n) {
        return new TextView(s);
    }
    TextView text(String s,int n,boolean b) {
        return new TextView(s);
    }
    View space(int n) {
        return new View();
    }
    View iconButton(String a,String s,Runnable r) {
        return button(s,false,r);
    }
    View button(String s,boolean b,Runnable r) {
        TextView t=new TextView(s);
        t.click=r;
        return t;
    }
}
