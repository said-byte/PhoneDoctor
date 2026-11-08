package com.phonedoctor.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonedoctor.core.AppItem
import com.phonedoctor.core.DeviceState
import com.phonedoctor.core.Fixer
import com.phonedoctor.core.Prefs
import com.phonedoctor.core.Scanner
import com.phonedoctor.core.Shell
import com.phonedoctor.core.Tri
import com.phonedoctor.fix.ColorOsAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var apps by mutableStateOf<List<AppItem>>(emptyList())
        private set

    var device by mutableStateOf<DeviceState?>(null)
        private set

    var scanning by mutableStateOf(false)
        private set

    var working by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf("")

    var log by mutableStateOf<List<String>>(emptyList())
        private set

    private val scanner = Scanner(app)

    val visibleApps: List<AppItem>
        get() {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return apps
            return apps.filter {
                it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
            }
        }

    val problemsCount: Int get() = apps.count { !it.isClean }

    fun refresh() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!Shell.isGranted()) {
                    null
                } else {
                    val scan = scanner.scan()
                    val state = scanner.deviceState(scan)
                    scan to state
                }
            }
            if (result == null) {
                apps = emptyList()
                addLog("Shizuku не готов — сканер недоступен")
            } else {
                apps = result.first.apps
                device = result.second
                if (!result.first.shellOk) {
                    addLog("Сканер: ${result.first.shellErr.ifBlank { "ошибка shell" }}")
                }
                addLog("Найдено приложений: ${apps.size}, требуют внимания: $problemsCount")
            }
            scanning = false
        }
    }

    fun fixApp(item: AppItem) {
        if (working != null) return
        working = item.label
        viewModelScope.launch {
            val rep = withContext(Dispatchers.IO) { Fixer.fixApp(item.pkg) }
            addLog("${item.label}: " + describe(rep))

            // shell не может выдавать ни POST_NOTIFICATIONS, ни appop фона — доводим кликами
            runUiFix(rep, item)
            working = null
            refresh()
        }
    }

    /** Отдаёт пережившие shell задачи службе доступности и проверяет результат. */
    private suspend fun runUiFix(rep: Fixer.FixReport, item: AppItem) {
        if (!ColorOsAccessibilityService.isRunning()) {
            if (!rep.notificationsOk) {
                addLog("${item.label}: уведомления только вручную — служба «Спец. возможности» выключена")
            }
            if (!rep.backgroundOp) {
                addLog("${item.label}: фон только вручную — служба «Спец. возможности» выключена")
            }
            return
        }
        // уведомления
        if (!rep.notificationsOk) {
            ColorOsAccessibilityService.submit(
                ColorOsAccessibilityService.Task(
                    pkg = item.pkg,
                    label = item.label,
                    kind = ColorOsAccessibilityService.Kind.NOTIFICATIONS,
                ),
            )
            addLog("${item.label}: открываю настройки уведомлений…")
            waitNotifications(item)
        }
        // фоновая активность
        if (!rep.backgroundOp) {
            ColorOsAccessibilityService.submit(
                ColorOsAccessibilityService.Task(
                    pkg = item.pkg,
                    label = item.label,
                    kind = ColorOsAccessibilityService.Kind.BACKGROUND,
                ),
            )
            addLog("${item.label}: открываю настройки фона…")
            waitBackground(item)
        }
    }

    /**
     * Фон: явный режим «Разрешить работу в фоновом режиме» на ColorOS.
     * Запускается по кнопке «Фон», потому что состояние по умолчанию
     * приложений разрешено и гонять батарею по всем 118 не имеет смысла.
     */
    fun fixBackground(item: AppItem) {
        if (working != null) return
        working = item.label
        viewModelScope.launch {
            if (!ColorOsAccessibilityService.isRunning()) {
                addLog("${item.label}: фон только вручную — служба «Спец. возможности» выключена")
                working = null
                return@launch
            }
            ColorOsAccessibilityService.submit(
                ColorOsAccessibilityService.Task(
                    pkg = item.pkg,
                    label = item.label,
                    kind = ColorOsAccessibilityService.Kind.BACKGROUND,
                ),
            )
            addLog("${item.label}: открываю настройки фона…")
            waitBackground(item)
            working = null
            refresh()
        }
    }

    private suspend fun waitNotifications(item: AppItem) {
        for (i in 0 until 12) {
            delay(2000)
            when (withContext(Dispatchers.IO) { Fixer.notificationsGranted(item.pkg) }) {
                true -> { addLog("${item.label}: уведомления включены"); return }
                false -> if (i > 8) { addLog("${item.label}: уведомления не поддались — включите вручную"); return }
                null -> {}
            }
            if (!ColorOsAccessibilityService.isRunning()) return
        }
        addLog("${item.label}: уведомления не поддались — включите вручную")
    }

    private suspend fun waitBackground(item: AppItem) {
        for (i in 0 until 12) {
            delay(2000)
            when (withContext(Dispatchers.IO) { Fixer.backgroundAllowed(item.pkg) }) {
                true -> { addLog("${item.label}: фон разрешён"); return }
                false -> if (i > 8) { addLog("${item.label}: фон не поддался — включите вручную"); return }
                null -> {}
            }
            if (!ColorOsAccessibilityService.isRunning()) return
        }
        addLog("${item.label}: фон не поддался — включите вручную")
    }

    /**
     * Shell-часть для всех приложений, затем уведомления и фон через экран —
     * очередь службы обрабатывает их по очереди.
     */
    fun fixAll() {
        if (working != null) return
        val targets = apps.filter { !it.isClean }
        if (targets.isEmpty()) return
        working = "все приложения"
        viewModelScope.launch {
            var full = 0
            var partial = 0
            val needNotif = mutableListOf<AppItem>()
            val needBg = mutableListOf<AppItem>()
            for (item in targets) {
                val rep = withContext(Dispatchers.IO) { Fixer.fixApp(item.pkg) }
                if (rep.notificationsOk && rep.whitelist && rep.backgroundOp) full++
                else if (rep.anyOk) partial++
                if (!rep.notificationsOk) needNotif.add(item)
                if (!rep.backgroundOp) needBg.add(item)
            }
            addLog("Shell-часть: полностью $full, частично $partial из ${targets.size}")

            val uiTotal = needNotif.size
            if (uiTotal == 0) {
                working = null
                refresh()
                return@launch
            }
            if (!ColorOsAccessibilityService.isRunning()) {
                addLog("Служба «Спец. возможности» выключена: уведомления вручную у $uiTotal")
                working = null
                refresh()
                return@launch
            }

            addLog("Довожу уведомления через экран: $uiTotal приложений, на это нужно время…")
            for (item in needNotif) {
                if (!ColorOsAccessibilityService.isRunning()) break
                ColorOsAccessibilityService.submit(
                    ColorOsAccessibilityService.Task(
                        pkg = item.pkg,
                        label = item.label,
                        kind = ColorOsAccessibilityService.Kind.NOTIFICATIONS,
                    ),
                )
                addLog("${item.label}: уведомления…")
                waitNotifications(item)
            }
            if (needBg.isNotEmpty()) {
                addLog("Фон: последнее нажатие «Разрешить» за тобой — кнопка «Фон» у приложения")
            }
            working = null
            refresh()
        }
    }

    /** Честный отчёт: что сработало, а что ColorOS не даёт сделать без клика. */
    private fun describe(rep: Fixer.FixReport): String {
        val done = buildList {
            if (rep.whitelist) add("батарея")
            if (rep.standby) add("приоритет")
            if (rep.notificationsOk) add("уведомления")
            if (rep.backgroundOp) add("фон")
        }
        val manual = buildList {
            if (!rep.notificationsOk) add("уведомления")
            if (!rep.backgroundOp) add("фон")
        }
        return when {
            done.isEmpty() -> "ничего не удалось, нужен доступ Shizuku"
            manual.isEmpty() -> "сделано: ${done.joinToString(", ")}"
            else -> "сделано: ${done.joinToString(", ")}; руками: ${manual.joinToString(", ")}"
        }
    }

    fun prepareSelf() {
        val pkg = getApplication<Application>().packageName
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { Fixer.prepareSelf(pkg) }
            addLog(if (res.ok) "Приложение подготовлено" else "Ошибка: ${res.errorText}")
            refresh()
        }
    }

    fun requestShizuku() {
        Shell.requestPermission(1001)
        addLog("Запрошен доступ к Shizuku")
    }

    /**
     * Автозапуск: экран живёт в com.oplus.battery за signature-разрешением,
     * поэтому включить его можно только кликами. Служба сама проверяет
     * переключатель и только после этого пишет отметку — её и ждём.
     */
    fun fixAutostart(item: AppItem) {
        if (working != null) return
        working = item.label
        viewModelScope.launch {
            if (!ColorOsAccessibilityService.isRunning()) {
                addLog("${item.label}: автозапуск только вручную — служба «Спец. возможности» выключена")
                working = null
                return@launch
            }
            // Старую отметку убираем, иначе прошлый успех выдаст себя за текущий.
            withContext(Dispatchers.IO) {
                Prefs.clearAutostart(getApplication(), item.pkg)
            }
            val started = ColorOsAccessibilityService.submit(
                ColorOsAccessibilityService.Task(
                    pkg = item.pkg,
                    label = item.label,
                    kind = ColorOsAccessibilityService.Kind.AUTOSTART,
                ),
            )
            if (!started) {
                addLog("${item.label}: автозапуск только вручную — служба «Спец. возможности» выключена")
                working = null
                return@launch
            }
            addLog("${item.label}: открываю настройки автозапуска…")
            var state = Tri.UNKNOWN
            for (i in 0 until 26) {
                delay(1500)
                state = withContext(Dispatchers.IO) {
                    Prefs.autostartState(getApplication(), item.pkg)
                }
                if (state == Tri.YES) break
            }
            when (state) {
                Tri.YES -> addLog("${item.label}: автозапуск включён")
                else -> addLog(
                    "${item.label}: автозапуск включить не удалось — Настройки → Приложения → Автозапуск",
                )
            }
            working = null
            refresh()
        }
    }

    private fun addLog(message: String) {
        log = (log + message).takeLast(80)
    }

    fun shizukuReady(): Boolean = Shell.isGranted()

    fun triShizuku(): Tri = if (Shell.isGranted()) Tri.YES else Tri.NO
}
