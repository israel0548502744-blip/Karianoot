package com.karianoot.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.RemoteViews

/**
 * "הדבקה וקריינות": copy a text anywhere, then one tap (the app icon's long-press menu, or the home-screen widget)
 * opens the studio with it and starts the narration with the last voice.
 */
object Shortcuts {
    const val ACTION_PASTE = "com.karianoot.PASTE_AND_NARRATE"

    fun pasteIntent(ctx: Context) = Intent(ctx, MainActivity::class.java).setAction(ACTION_PASTE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The long-press shortcut on the app icon (dynamic, so it works for both package names). */
    fun install(ctx: Context) {
        runCatching {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
            val s = ShortcutInfo.Builder(ctx, "paste")
                .setShortLabel("הדבקה וקריינות")
                .setLongLabel("קריינות לטקסט שהעתקתי")
                .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_launcher))
                .setIntent(pasteIntent(ctx))
                .build()
            sm.dynamicShortcuts = listOf(s)
        }
    }
}

/** Home-screen widget: one button, "הדבקה וקריינות". */
class PasteWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(ctx, 7, Shortcuts.pasteIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(ctx.packageName, R.layout.widget_paste).apply { setOnClickPendingIntent(R.id.widget_root, pi) }
        for (id in ids) mgr.updateAppWidget(id, views)
    }

    companion object {
        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, PasteWidget::class.java))
            if (ids.isNotEmpty()) PasteWidget().onUpdate(ctx, m, ids)
        }
    }
}
