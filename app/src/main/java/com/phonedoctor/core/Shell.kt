package com.phonedoctor.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

data class ShellResult(
    val ok: Boolean,
    val out: String,
    val err: String,
) {
    val errorText: String get() = if (err.isNotBlank()) err.trim() else "команда вернула ошибку"
}

/**
 * Единственная точка запуска команд с правами shell (uid 2000) через Shizuku.
 *
 * Важно: Shizuku НЕ даёт root и не даёт доступ к приватным провайдерам ColorOS.
 * Всё, что защищено signature-разрешениями oplus.*, здесь принципиально недоступно.
 */
object Shell {

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun isGranted(): Boolean = try {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (isBinderAlive() && !isGranted()) Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            // пользователь ещё не установил Shizuku — молча ждём
        }
    }

    fun run(cmd: String): ShellResult {
        if (!isGranted()) {
            return ShellResult(false, "", "Нет доступа к Shizuku")
        }
        return try {
            val factory = processFactory
                ?: return ShellResult(false, "", "Эта версия Shizuku не умеет запускать команды")
            val process = factory.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            ShellResult(code == 0, out, err)
        } catch (t: Throwable) {
            android.util.Log.w("PhoneDoctor", "shell fail: ${cmd.take(60)}: $t")
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            ShellResult(false, "", cause.message ?: cause.javaClass.simpleName)
        }
    }

    /**
     * Shizuku.newProcess объявлен private в api-13.1.5, хотя внутри делает ровно то, что нужно:
     * запускает процесс от имени shell. Достаём его рефлексией.
     */
    private val processFactory: java.lang.reflect.Method? by lazy {
        try {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }
}
