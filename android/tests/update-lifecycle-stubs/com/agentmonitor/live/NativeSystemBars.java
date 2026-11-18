package com.agentmonitor.live;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
final class NativeSystemBars {
    static NativeSystemBars attach(Window window,View root,int topColor,int bottomColor) {
        return new NativeSystemBars();
    }
    void onInsets(WindowInsets insets) {
    }
}
