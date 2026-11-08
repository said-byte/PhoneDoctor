package com.phonedoctor.core

import android.content.Context

/**
 * Локальная память приложения.
 *
 * Зачем: ColorOS не отдаёт состояние автозапуска никому, кроме своих системных приложений
 * (провайдер закрыт signature-разрешением). Поэтому «автозапуск включён» — это то,
 * что мы сами включили через Accessibility и запомнили.
 */
object Prefs {

    private const val FILE = "phonedoctor"
    private const val KEY_AUTOSTART = "autostart_"
    private const val KEY_NOTIF = "notifications_"

    fun autostartState(context: Context, pkg: String): Tri {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_AUTOSTART + pkg)) return Tri.UNKNOWN
        return if (prefs.getBoolean(KEY_AUTOSTART + pkg, false)) Tri.YES else Tri.NO
    }

    fun setAutostart(context: Context, pkg: String, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOSTART + pkg, value)
            .apply()
    }

    /** Забыть отметку: состояние снова неизвестно. */
    fun clearAutostart(context: Context, pkg: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_AUTOSTART + pkg)
            .apply()
    }

    /** Отметка «мы включили уведомления через экран настроек». */
    fun setNotifications(context: Context, pkg: String, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIF + pkg, value)
            .apply()
    }

    fun notificationsState(context: Context, pkg: String): Tri {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_NOTIF + pkg)) return Tri.UNKNOWN
        return if (prefs.getBoolean(KEY_NOTIF + pkg, false)) Tri.YES else Tri.NO
    }
}
