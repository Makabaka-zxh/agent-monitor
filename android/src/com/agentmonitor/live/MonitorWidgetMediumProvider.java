package com.agentmonitor.live;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 2×4 (4 columns × 2 rows) wide home screen widget provider. */
public final class MonitorWidgetMediumProvider extends AppWidgetProvider {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && MonitorWidgetUpdater.ACTION_WIDGET_REFRESH.equals(intent.getAction())) {
            MonitorWidgetUpdater.refreshFromNetworkAsync(context, true, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        MonitorWidgetUpdater.refreshFromNetworkAsync(context, false, goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        MonitorWidgetUpdater.refreshAll(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        MonitorWidgetUpdater.deleteWidgetModes(context, appWidgetIds);
    }
}
