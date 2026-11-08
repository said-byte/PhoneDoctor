package com.phonedoctor.core

/** Трёхзначная логика: состояние может быть неизвестно (ColorOS его не отдаёт). */
enum class Tri { YES, NO, UNKNOWN }

data class AppItem(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val notifications: Tri = Tri.UNKNOWN,
    val battery: Tri = Tri.UNKNOWN,
    val background: Tri = Tri.UNKNOWN,
    val autostart: Tri = Tri.UNKNOWN,
) {
    val problems: Int
        get() = listOf(notifications, battery, background, autostart).count { it == Tri.NO }

    val isClean: Boolean get() = problems == 0
}

/** Общее состояние телефона: то, от чего зависит вся доставка уведомлений. */
data class DeviceState(
    val shizuku: Tri = Tri.UNKNOWN,
    val accessibility: Tri = Tri.UNKNOWN,
    val gmsEnabled: Boolean = false,
    val gmsWhitelisted: Tri = Tri.UNKNOWN,
    val romName: String = "",
    val androidVersion: String = "",
    val a11yAutoOff: Boolean = false,
    val autostartCanBeFixed: Boolean = false,
)
