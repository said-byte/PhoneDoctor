package com.phonedoctor.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Снимает состояние телефона одним заходом в shell.
 * Один запуск sh = секунды, вместо сотни отдельных обращений к Shizuku.
 */
class Scanner(private val context: Context) {

    data class Result(
        val apps: List<AppItem>,
        val whitelist: Set<String>,
        val a11ySwitchValue: String,
        val shellOk: Boolean = true,
        val shellErr: String = "",
    )

    fun scan(): Result {
        val res = Shell.run(SCRIPT)
        val out = res.out

        val packages = section(out, "APPS")
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .distinct()

        val whitelist = section(out, "WHITE")
            .filter { it.count { ch -> ch == ',' } >= 2 }
            .mapNotNull { it.split(",").getOrNull(1)?.trim() }
            .toSet()

        val states = HashMap<String, Pair<Tri, Tri>>()
        section(out, "OPS").forEach { line ->
            if (!line.startsWith("@@")) return@forEach
            val parts = line.removePrefix("@@").split("|")
            if (parts.size < 3) return@forEach
            val pkg = parts[0].trim()
            states[pkg] = parseNotifications(parts[1]) to parseBackground(parts[2])
        }

        val a11yValue = section(out, "SET").firstOrNull().orEmpty().ifBlank { "unknown" }

        val apps = packages.map { pkg ->
            val (notif, bg) = states[pkg] ?: (Tri.UNKNOWN to Tri.UNKNOWN)
            AppItem(
                pkg = pkg,
                label = labelOf(pkg),
                system = false,
                notifications = notif,
                battery = if (pkg in whitelist) Tri.YES else Tri.NO,
                background = bg,
                autostart = Prefs.autostartState(context, pkg),
            )
        }.sortedBy { it.label.lowercase() }

        return Result(apps, whitelist, a11yValue, res.ok, res.err)
    }

    /** Пакеты GMS проверяем отдельно: именно через них идут все FCM-пуши. */
    fun gmsInstalled(): Boolean = try {
        context.packageManager.getApplicationInfo(GMS, 0)
        true
    } catch (t: Throwable) {
        false
    }

    fun deviceState(result: Result): DeviceState = DeviceState(
        shizuku = if (Shell.isGranted()) Tri.YES else Tri.NO,
        accessibility = if (com.phonedoctor.fix.ColorOsAccessibilityService.isRunning()) Tri.YES else Tri.NO,
        gmsEnabled = gmsInstalled(),
        gmsWhitelisted = if (GMS in result.whitelist) Tri.YES else Tri.NO,
        romName = Build.DISPLAY,
        androidVersion = Build.VERSION.RELEASE,
        a11yAutoOff = result.a11ySwitchValue != "0",
        autostartCanBeFixed = com.phonedoctor.fix.ColorOsAccessibilityService.isRunning(),
    )

    private fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    companion object {
        const val GMS = "com.google.android.gms"

        private val SCRIPT = listOf(
            "echo '##APPS'",
            "pm list packages -3",
            "echo '##WHITE'",
            "dumpsys deviceidle whitelist",
            "echo '##SET'",
            "settings get secure accessibility_turn_off_switch",
            "echo '##OPS'",
            "for p in \$(pm list packages -3 | sed 's/package://'); do",
            "  n=\$(cmd appops get \$p POST_NOTIFICATION 2>/dev/null | grep -o 'POST_NOTIFICATION: [a-z]*')",
            "  if [ -z \"\$n\" ]; then n=\$(dumpsys package \$p 2>/dev/null | grep -m1 -o 'POST_NOTIFICATIONS: granted=[a-z]*'); fi",
            "  b=\$(cmd appops get \$p RUN_ANY_IN_BACKGROUND 2>/dev/null | grep -o 'RUN_ANY_IN_BACKGROUND: [a-z]*')",
            "  echo \"@@\$p|\$n|\$b\"",
            "done",
            "echo '##END'",
        ).joinToString("\n")

        private fun section(out: String, name: String): List<String> {
            val marker = "##$name"
            val start = out.indexOf(marker)
            if (start < 0) return emptyList()
            val rest = out.substring(start + marker.length)
            val nextMarker = Regex("\n##[A-Z]+").find(rest)
            val body = if (nextMarker != null) rest.substring(0, nextMarker.range.first) else rest
            return body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun parseNotifications(raw: String): Tri = when {
            raw.contains("granted=true") || raw.endsWith(" allow") -> Tri.YES
            raw.contains("granted=false") || raw.endsWith(" deny") || raw.endsWith(" ignore") -> Tri.NO
            else -> Tri.UNKNOWN
        }

        /** Пустой appop = режим по умолчанию = фон разрешён (под «ловлю» только явный ignore). */
        private fun parseBackground(raw: String): Tri = when {
            raw.endsWith(" ignore") || raw.endsWith(" deny") -> Tri.NO
            else -> Tri.YES
        }

        fun pmCompat(context: Context): PackageManager = context.packageManager
    }
}
