package com.phonedoctor.core

/**
 * Все автоматические правки, которые возможны без кликов по экрану.
 * Работает только через Shizuku (uid 2000), root не нужен.
 *
 * Важно про ColorOS 16/17: у shell отобраны `GRANT_RUNTIME_PERMISSIONS` и
 * `MANAGE_APP_OPS_MODES` (это и есть «контроль разрешений» ColorOS). Поэтому
 * `pm grant` и `appops set` молча падают, а `dumpsys deviceidle whitelist` и
 * `am set-standby-bucket` работают. Отчёт ниже показывает реальный результат
 * по каждому пункту, а не «команда не упала».
 */
object Fixer {

    data class FixReport(
        val whitelist: Boolean,
        val standby: Boolean,
        val alreadyGranted: Boolean,
        val notificationPermission: Boolean,
        val notificationOp: Boolean,
        val backgroundOp: Boolean,
    ) {
        /** Уведомления реально включены, а не просто «мы попробовали». */
        val notificationsOk: Boolean
            get() = alreadyGranted || notificationPermission || notificationOp

        /** Что удалось сделать без участия пользователя. */
        val anyOk: Boolean get() = whitelist || standby || notificationsOk || backgroundOp

        /** Нужны клики по экрану ColorOS (уведомления и/или фон). */
        val needsUi: Boolean get() = !notificationsOk || !backgroundOp
    }

    fun fixApp(pkg: String): FixReport {
        val res = Shell.run(
            listOf(
                "if cmd appops get $pkg POST_NOTIFICATION 2>/dev/null | grep -q 'POST_NOTIFICATION: ignore'; " +
                    "then echo \"CUR:NO\"; " +
                    "elif dumpsys package $pkg 2>/dev/null | grep -m1 'POST_NOTIFICATIONS:' | grep -q 'granted=true'; " +
                    "then echo \"CUR:YES\"; else echo \"CUR:NO\"; fi",
                "dumpsys deviceidle whitelist +$pkg >/dev/null 2>&1; echo \"WL=\$?\"",
                "am set-standby-bucket $pkg active >/dev/null 2>&1; echo \"SB=\$?\"",
                "pm grant $pkg android.permission.POST_NOTIFICATIONS >/dev/null 2>&1; echo \"GR=\$?\"",
                "appops set $pkg POST_NOTIFICATION allow >/dev/null 2>&1; echo \"OP=\$?\"",
                "appops set $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; echo \"BG=\$?\"",
                "if cmd appops get $pkg RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -q 'RUN_ANY_IN_BACKGROUND: ignore'; then echo \"BGR=1\"; else echo \"BGR=0\"; fi",
            ).joinToString("\n"),
        )
        val out = res.out
        fun ok(tag: String) = Regex("(?m)^$tag=0\\s*$").containsMatchIn(out)
        return FixReport(
            whitelist = ok("WL"),
            standby = ok("SB"),
            alreadyGranted = Regex("(?m)^CUR:YES\\s*$").containsMatchIn(out),
            notificationPermission = ok("GR"),
            notificationOp = ok("OP"),
            backgroundOp = ok("BGR"),
        )
    }

    /**
     * Реальная проверка после кликов по экрану: выдано ли разрешение.
     * null — состояние прочитать не удалось.
     */
    fun notificationsGranted(pkg: String): Boolean? {
        val res = Shell.run(
            "if cmd appops get $pkg POST_NOTIFICATION 2>/dev/null | grep -q 'POST_NOTIFICATION: ignore'; " +
                "then echo NO; " +
                "elif dumpsys package $pkg 2>/dev/null | grep -m1 'POST_NOTIFICATIONS:' | grep -q 'granted=true'; " +
                "then echo YES; else echo NO; fi",
        )
        return when {
            res.out.contains("YES") -> true
            res.out.contains("NO") -> false
            else -> null
        }
    }

    /**
     * Подготовка собственного приложения.
     *
     * ACCESS_RESTRICTED_SETTINGS — блок Android «ограниченные настройки», из-за которого
     * нельзя включить Accessibility у APK не из магазина.
     * accessibility_turn_off_switch=0 — запрет ColorOS выключать нашу службу.
     * Служба добавляется к уже включённым, чужие не отключаются.
     */
    fun prepareSelf(ownPackage: String): ShellResult {
        val component = "$ownPackage/com.phonedoctor.fix.ColorOsAccessibilityService"
        return Shell.run(
            listOf(
                "appops set $ownPackage ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null",
                "settings put secure accessibility_turn_off_switch 0",
                "cur=\$(settings get secure enabled_accessibility_services)",
                "case \":\$cur:\" in",
                "  *\":$component:\"*) ;;",
                "  *) if [ -z \"\$cur\" ] || [ \"\$cur\" = \"null\" ]; then",
                "       settings put secure enabled_accessibility_services \"$component\"",
                "     else",
                "       settings put secure enabled_accessibility_services \"\$cur:$component\"",
                "     fi ;;",
                "esac",
                "settings put secure accessibility_enabled 1",
            ).joinToString("\n"),
        )
    }

    fun backgroundAllowed(pkg: String): Boolean? {
        val res = Shell.run(
            "cmd appops get $pkg RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -q 'RUN_ANY_IN_BACKGROUND: allow'; echo \"RC=\$?\"",
        )
        return when {
            res.out.contains("RC=0") -> true
            res.out.contains("RC=1") -> false
            else -> null
        }
    }

    fun batteryWhitelist(pkg: String): ShellResult =
        Shell.run("dumpsys deviceidle whitelist +$pkg")

    fun batteryWhitelistRemove(pkg: String): ShellResult =
        Shell.run("dumpsys deviceidle whitelist -$pkg")
}
