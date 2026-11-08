package com.phonedoctor.fix

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.phonedoctor.core.Prefs
import com.phonedoctor.core.Shell
import java.io.File

/**
 * Служба спец. возможностей.
 *
 * Единственный способ включить в ColorOS «Автозапуск» и «Разрешить фоновую активность»:
 * эти данные лежат в com.oplus.battery за signature-разрешениями, shell их не видит.
 * Поэтому здесь — клики по интерфейсу.
 *
 * Пока идёт работа, служба складывает дамп экрана в
 *   Android/data/com.phonedoctor/files/ui/
 * Это нужно, чтобы подгонять селекторы под конкретную версию ColorOS.
 */
class ColorOsAccessibilityService : AccessibilityService() {

    enum class Kind {
        /** Автозапуск в com.oplus.battery. */
        AUTOSTART,

        /** Мастер-переключатель «Разрешить уведомления» в com.oplus.notificationmanager. */
        NOTIFICATIONS,

        /** «Разрешить работу в фоновом режиме» в батарее приложения (com.oplus.battery). */
        BACKGROUND,
    }

    data class Task(
        val pkg: String,
        val label: String,
        val kind: Kind = Kind.AUTOSTART,
    )

    companion object {
        private const val RETRY_LIMIT = 10
        private const val STEP_DELAY = 700L

        private const val OPPO_BATTERY = "com.oplus.battery"
        private const val OPPO_SETTINGS = "com.oplus.settings"

        /** Экран уведомлений на ColorOS ведёт отдельный пакет, а не Settings. */
        private const val OPPO_NOTIFICATIONS = "com.oplus.notificationmanager"
        private const val AOSP_SETTINGS = "com.android.settings"

        /** Подписи мастер-переключателя уведомлений (ru / en / zh). */
        private val ALLOW_TEXTS = listOf(
            "разрешить уведомления",
            "allow notifications",
            "允许通知",
        )

        /** Пункт «Автозапуск» в Настройки → Приложения. Закрыт signature-разрешением,
         * поэтому открываем только через UI, а не через startActivity. */
        private val AUTOSTART_TEXTS = listOf("автозапуск", "autostart", "自启动")

        /** Строка «Приложения» на главном экране настроек. */
        private val APPS_MENU_TEXTS = listOf("приложения", "apps", "应用")

        /** Строка «Использование батареи» на странице приложения в Настройках. */
        private val DETAILS_BATTERY_ROWS = listOf(
            "использование батареи",
            "battery usage",
            "耗电管理",
        )

        /** Название режима «Разрешить работу в фоновом режиме». */
        private val BG_ROW_TEXTS = listOf(
            "разрешить работу в фоновом режиме",
            "allow background activity",
            "允许后台运行",
        )

        /** Кнопка подтверждения в диалоге батареи. */
        private val BG_CONFIRM_TEXTS = listOf("разрешить", "allow", "允许")

        @Volatile
        private var ref: ColorOsAccessibilityService? = null

        fun isRunning(): Boolean = ref != null

        fun submit(task: Task): Boolean {
            val service = ref ?: run {
                android.util.Log.i("PhoneDoctor", "a11y submit: служба не запущена")
                return false
            }
            android.util.Log.i("PhoneDoctor", "a11y submit ${task.kind} ${task.pkg}")
            service.queue.addLast(task)
            service.pump()
            return true
        }

        /** Снять дамп текущего экрана — для подгонки селекторов. */
        fun dumpNow(): Boolean {
            val service = ref ?: return false
            service.dumpTree("manual")
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Отдельный канал для подтверждения диалога фона: не затирается onAccessibilityEvent. */
    private val confirmHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Task>()

    private var current: Task? = null
    private var attempts = 0
    private var busy = false

    /** Шаг сценария автозапуска: 0 — Настройки, 1 — «Приложения», 2 — поиск, 3 — переключатель. */
    private var stage = 0

    /** Панель поиска на экране автозапуска раскрыта кликом. */
    private var searchTapped = false

    /** Версия запланированного шага: устаревший колбэк не выполняем. */
    private var seq = 0

    /** Единственная точка планирования шага: любое новое расписание отменяет старое. */
    private fun schedule(delay: Long) {
        seq++
        val expected = seq
        android.util.Log.i("PhoneDoctor", "a11y sched==$expected in=${delay}ms busy=$busy")
        handler.postDelayed({ if (expected == seq && busy) step() }, delay)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref = this
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        dumpTree("connected")
    }

    override fun onDestroy() {
        ref = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!busy) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        android.util.Log.i("PhoneDoctor", "a11y event=${event.eventType} win=$pkg")
        handler.removeCallbacksAndMessages(null)
        schedule(STEP_DELAY)
    }

    private fun pump() {
        if (busy) return
        val task = queue.removeFirstOrNull() ?: return
        current = task
        attempts = 0
        seq++
        // APPLICATION_SETTINGS уже на экране «Приложения» — начинаем с шага «Автозапуск».
        stage = if (task.kind == Kind.AUTOSTART) 1 else 0
        searchTapped = false
        busy = true
        android.util.Log.i("PhoneDoctor", "a11y pump ${task.kind} ${task.pkg}")
        if (task.kind == Kind.NOTIFICATIONS) {
            openNotificationScreen(task.pkg)
        } else if (task.kind == Kind.BACKGROUND) {
            openBackgroundUi(task.pkg)
        } else {
            openAutostartUi()
        }
    }

    private fun finishTask() {
        handler.removeCallbacksAndMessages(null)
        confirmHandler.removeCallbacksAndMessages(null)
        current = null
        busy = false
        handler.postDelayed({ pump() }, 400)
    }

    // ---------- фон ----------

    /**
     * «Разрешить работу в фоновом режиме» в батарее приложения.
     * Весь сценарий делается shell-тапами: ColorOS замораживает процесс
     * приложения после ухода с нашего экрана, и Handler/события a11y
     * перестают доставляться, поэтому путь строится только на shell.
     *
     * Последнее нажатие «Разрешить» намеренно оставлено пользователю:
     * экран с открытым диалогом остаётся на месте.
     */
    private fun openBackgroundUi(pkg: String) {
        Thread {
            Shell.run("am force-stop $AOSP_SETTINGS; am force-stop $OPPO_BATTERY")
            val started = Shell.run(
                "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$pkg",
            ).ok
            android.util.Log.i("PhoneDoctor", "a11y openBackgroundUi $pkg started=$started")
            if (!started) {
                handler.post { finishTask() }
                return@Thread
            }
            // координаты валидны для PLG110 (1272x2772, ColorOS 17, ru)
            Thread.sleep(2500)
            Shell.run("input tap 469 2024")   // «Использование батареи»
            Thread.sleep(2000)
            Shell.run("input tap 636 712")    // «Разрешить работу в фоновом режиме»
            Thread.sleep(1800)
            handler.post {
                android.util.Log.i(
                    "PhoneDoctor",
                    "a11y background: диалог открыт для $pkg, подтверждение за пользователем",
                )
                android.widget.Toast.makeText(
                    this,
                    "Нажми «Разрешить» в открывшемся диалоге",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                // Экран с диалогом остаётся; в приложение не возвращаемся.
                current = null
                busy = false
                handler.postDelayed({ pump() }, 400)
            }
        }.start()
    }

    // ---------- автозапуск ----------

    /**
     * Экран автозапуска живёт в com.oplus.battery и закрыт `oplus.permission.OPLUS_COMPONENT_SAFE`,
     * поэтому открыть его напрямую нельзя даже с правами shell. Зато у системных «Настроек»
     * это разрешение есть: Настройки → Приложения → Автозапуск.
     * APPLICATION_SETTINGS открывает список приложений сразу, минуя главный экран.
     */
    private fun openAutostartUi() {
        // Настройки помнят последний открытый экран, поэтому начинаем с чистого листа.
        Thread {
            Shell.run("am force-stop $AOSP_SETTINGS; am force-stop $OPPO_BATTERY")
            handler.post {
                val intent = Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = startSafely(intent)
                android.util.Log.i("PhoneDoctor", "a11y openAutostartUi started=$ok")
                if (!ok) {
                    finishTask()
                    return@post
                }
                schedule(STEP_DELAY * 3)
            }
        }.start()
    }

    private fun stepAutostart(task: Task) {
        val root = rootInActiveWindow
        if (root == null) {
            retryOrGiveUp()
            return
        }
        val pkgName = root.packageName?.toString().orEmpty()

        when (pkgName) {
            AOSP_SETTINGS, OPPO_SETTINGS -> {
                if (stage == 0) {
                    val row = findExactText(root, APPS_MENU_TEXTS)
                    if (row == null) {
                        android.util.Log.i("PhoneDoctor", "a11y autostart: нет пункта «Приложения»")
                        retryOrGiveUp()
                        return
                    }
                    stage = 1
                    attempts = 0
                    clickNode(row)
                    schedule(STEP_DELAY * 2)
                    return
                }
                if (stage == 1) {
                    val row = findExactText(root, AUTOSTART_TEXTS)
                    if (row == null) {
                        android.util.Log.i("PhoneDoctor", "a11y autostart: нет пункта «Автозапуск»")
                        retryOrGiveUp()
                        return
                    }
                    stage = 2
                    attempts = 0
                    clickNode(row)
                    schedule(STEP_DELAY * 3)
                    return
                }
                retryOrGiveUp()
            }

            OPPO_BATTERY -> {
                if (stage < 2) stage = 2
                if (stage == 2) {
                    val field = findSearchField(root)
                    if (field == null) {
                        android.util.Log.i("PhoneDoctor", "a11y autostart: нет поля поиска")
                        retryOrGiveUp()
                        return
                    }
                    if (!searchTapped) {
                        // Панель поиска надо раскрыть настоящим кликом: без этого
                        // текст не запускает фильтр.
                        searchTapped = true
                        attempts = 0
                        clickNode(field)
                        schedule(STEP_DELAY)
                        return
                    }
                    typeLabel(task.label)
                    return
                }

                val toggle = findAutostartSwitch(root, task.label)
                android.util.Log.i("PhoneDoctor", "a11y autostart: toggle=${toggle?.isChecked}")
                if (toggle == null) {
                    retryOrGiveUp()
                    return
                }
                if (toggle.isChecked) {
                    doneAutostart(task)
                    return
                }
                clickNode(toggle)
                attempts++
                if (attempts > RETRY_LIMIT) {
                    dumpTree("giveup")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    finishTask()
                    return
                }
                schedule(STEP_DELAY * 3)
            }

            else -> {
                // процессы Настроек стартуют медленно, окно ещё наше — ждём подольше
                if (stage < 2) {
                    retryOrGiveUp(20)
                } else {
                    retryOrGiveUp()
                }
            }
        }
    }

    /**
     * Ввод Ctrl+A → DEL → `input text` через shell: ACTION_SET_TEXT в этом экране
     * меняет текст, но не запускает фильтр списка.
     */
    private fun typeLabel(label: String) {
        Thread {
            Shell.run("input keycombination 113 29; input keyevent 67")
            val escaped = label.trim().replace("'", "'\\''").replace(" ", "%s")
            val ok = Shell.run("input text '$escaped'").ok
            handler.post {
                if (ok) {
                    stage = 3
                    attempts = 0
                    schedule(STEP_DELAY * 2)
                } else {
                    retryOrGiveUp()
                }
            }
        }.start()
    }

    /** Успех: запоминаем и возвращаем пользователя в приложение. */
    private fun doneAutostart(task: Task) {
        Prefs.setAutostart(this, task.pkg, true)
        // ColorOS блокирует startActivity из фоновой службы, но shell старт разрешён.
        Thread {
            Shell.run("am start -n $packageName/.ui.MainActivity")
            handler.post {
                handler.removeCallbacksAndMessages(null)
                finishTask()
            }
        }.start()
    }

    private fun findExactText(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        val needles = texts.map { it.lowercase() }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 600) {
            visited++
            val node = stack.removeLast()
            val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
            if (t.isNotEmpty() && needles.contains(t)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /** Переключатель строки с нужным названием приложения на экране автозапуска. */
    private fun findAutostartSwitch(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var partial: AccessibilityNodeInfo? = null
        var visited = 0

        while (stack.isNotEmpty() && visited < 600) {
            visited++
            val node = stack.removeLast()
            val id = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()

            if (id.endsWith(":id/title") && text.isNotEmpty()) {
                val toggle = findToggleNear(node)
                if (toggle != null) {
                    if (text == needle) return toggle
                    if (partial == null && (text.contains(needle) || needle.contains(text))) partial = toggle
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return partial
    }

    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var fallback: AccessibilityNodeInfo? = null
        var visited = 0

        while (stack.isNotEmpty() && visited < 600) {
            visited++
            val node = stack.removeLast()
            val id = node.viewIdResourceName.orEmpty()
            if (id.endsWith(":id/search_src_text")) return node
            if (fallback == null && node.isEditable) fallback = node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return fallback
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (t: Throwable) {
        false
    }

    // ---------- уведомления ----------

    /**
     * Экран «Уведомления» конкретного приложения.
     *
     * На ColorOS 17 его показывает отдельный пакет com.oplus.notificationmanager.
     * Мастер-переключатель — обычный Switch с android:id/switch_widget, но сам он
     * не кликабельный: нажимать нужно строку-родителя.
     */
    private fun openNotificationScreen(pkg: String) {
        // ColorOS переиспользует уже открытый экран уведомлений и игнорирует пакет
        // в extra: показывается предыдущее приложение. Поэтому старый экран закрываем.
        Thread {
            Shell.run("am force-stop $OPPO_NOTIFICATIONS")
            handler.post { startNotificationScreen(pkg) }
        }.start()
    }

    private fun startNotificationScreen(pkg: String) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = startSafely(intent)
        android.util.Log.i("PhoneDoctor", "a11y openNotificationScreen $pkg started=$ok")
        if (!ok) {
            finishTask()
            return
        }
        schedule(STEP_DELAY * 2)
    }

    private fun stepNotifications(task: Task) {
        val root = rootInActiveWindow
        if (root == null) {
            android.util.Log.i("PhoneDoctor", "a11y step: нет root")
            retryOrGiveUp()
            return
        }

        val pkgName = root.packageName?.toString().orEmpty()
        if (pkgName != OPPO_NOTIFICATIONS && pkgName != AOSP_SETTINGS) {
            // ещё не перешли на нужный экран
            android.util.Log.i("PhoneDoctor", "a11y step: чужое окно $pkgName")
            retryOrGiveUp()
            return
        }

        // заголовок экрана должен совпасть с приложением, иначе ColorOS показал старое окно
        if (findLabelNode(root, task.label) == null) {
            android.util.Log.i("PhoneDoctor", "a11y step: на экране не ${task.label}")
            retryOrGiveUp()
            return
        }

        val master = findMasterSwitch(root)
        android.util.Log.i("PhoneDoctor", "a11y step: master=${master?.isChecked}")
        if (master == null) {
            retryOrGiveUp()
            return
        }

        if (master.isChecked) {
            Prefs.setNotifications(this, task.pkg, true)
            performGlobalAction(GLOBAL_ACTION_BACK)
            finishTask()
            return
        }

        // после клика внимательно ждём: если выкл. клик не прошёл — повторим шагом
        clickNode(master)
        attempts++
        if (attempts > RETRY_LIMIT) {
            dumpTree("giveup")
            performGlobalAction(GLOBAL_ACTION_BACK)
            finishTask()
            return
        }
        schedule(STEP_DELAY * 3)
    }

    private fun findMasterSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in ALLOW_TEXTS) {
            val title = findLabelNode(root, label) ?: continue
            findToggleNear(title)?.let { return it }
        }
        return topmostSwitch(root)
    }

    /** Запасной путь: верхний переключатель списка — он и есть мастер-выключатель. */
    private fun topmostSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        var visited = 0
        val rect = android.graphics.Rect()

        while (stack.isNotEmpty() && visited < 600) {
            visited++
            val node = stack.removeLast()
            val cls = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (node.isCheckable && cls.contains("Switch") && id.endsWith("switch_widget")) {
                node.getBoundsInScreen(rect)
                if (rect.top < bestTop) {
                    bestTop = rect.top
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    // ---------- основная логика ----------

    private fun step() {
        val task = current ?: return run { busy = false }
        when (task.kind) {
            Kind.NOTIFICATIONS -> stepNotifications(task)
            Kind.BACKGROUND -> Unit
            Kind.AUTOSTART -> stepAutostart(task)
        }
    }

    private fun retryOrGiveUp(limit: Int = RETRY_LIMIT) {
        attempts++
        if (attempts > limit) {
            dumpTree("giveup")
            performGlobalAction(GLOBAL_ACTION_BACK)
            finishTask()
            return
        }
        handler.postDelayed({ step() }, STEP_DELAY)
    }

    private fun findLabelNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return null

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 600) {
            visited++
            val node = stack.removeLast()

            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            if (text == needle || desc == needle) return node
            if (text.contains(needle) || desc.contains(needle)) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun findToggleNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var branch: AccessibilityNodeInfo? = node
        var depth = 0

        while (branch != null && depth < 6) {
            findToggle(branch)?.let { return it }
            branch = branch.parent
            depth++
        }
        return null
    }

    private fun findToggle(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 200) {
            visited++
            val node = stack.removeLast()
            val cls = node.className?.toString().orEmpty()
            if (node.isCheckable || cls.contains("Switch") || cls.contains("CheckBox")) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 4) {
            if (target.isClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            target = target.parent
            depth++
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---------- диагностика ----------

    private fun dumpTree(tag: String) {
        val root = rootInActiveWindow ?: return
        val dir = getExternalFilesDir("ui") ?: return
        if (!dir.exists()) dir.mkdirs()

        val builder = StringBuilder()
        builder.append("tag: ").append(tag).append('\n')
        builder.append("package: ").append(root.packageName).append('\n')
        builder.append("window: ")
            .append(root.window?.title?.toString().orEmpty())
            .append('\n')
        builder.append("---\n")
        writeNode(root, builder, 0)

        val name = "ui_" + System.currentTimeMillis() + "_" + tag + ".txt"
        runCatching { File(dir, name).writeText(builder.toString()) }
    }

    private fun writeNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 25) return
        repeat(depth) { sb.append("  ") }

        sb.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        node.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfterLast('/')) }
        node.text?.takeIf { it.isNotEmpty() }?.let { sb.append(" text=\"").append(it).append('"') }
        node.contentDescription?.takeIf { it.isNotEmpty() }
            ?.let { sb.append(" desc=\"").append(it).append('"') }
        if (node.isCheckable) sb.append(" checkable=").append(node.isChecked)
        if (node.isClickable) sb.append(" clickable")
        val b = android.graphics.Rect()
        node.getBoundsInScreen(b)
        sb.append(" [").append(b.left).append(',').append(b.top)
            .append(' ').append(b.right).append(',').append(b.bottom).append(']')
        sb.append('\n')

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { writeNode(it, sb, depth + 1) }
        }
    }
}
