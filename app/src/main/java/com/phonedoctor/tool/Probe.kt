package com.phonedoctor.tool

import android.os.Process

/**
 * Диагностический запуск ИЗ SHELL (uid 2000), а не из UI приложения:
 *
 *   adb shell "CLASSPATH=$(pm path com.phonedoctor | cut -d: -f2) \
 *       app_process /system/bin com.phonedoctor.tool.Probe"
 *
 * Без аргументов — только читает состояние «контроля разрешений» ColorOS.
 * С аргументом `off` — пытается выключить его (это меняет системную настройку!).
 *
 * Нужно потому, что на ColorOS 17 у shell отобраны GRANT_RUNTIME_PERMISSIONS и
 * MANAGE_APP_OPS_MODES, и без выключения контроля разрешений команды `pm grant`
 * и `appops set` не работают.
 */
object Probe {

    @JvmStatic
    fun main(args: Array<String>) {
        println("PROBE uid=" + Process.myUid())

        val clazz = try {
            Class.forName("android.app.OplusActivityManager")
        } catch (t: Throwable) {
            println("ERROR class: ${t.javaClass.name}: ${t.message}")
            return
        }

        val manager = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (t: Throwable) {
            println("ERROR ctor: ${t.javaClass.name}: ${t.message}")
            return
        }

        val getter = try {
            clazz.getMethod("isPermissionInterceptEnabled")
        } catch (t: Throwable) {
            println("ERROR getter: ${t.javaClass.name}: ${t.message}")
            return
        }

        val before = try {
            getter.invoke(manager)
        } catch (t: Throwable) {
            println("ERROR read: ${t.javaClass.name}: ${t.message}")
            var c = t.cause
            while (c != null) {
                println("CAUSE: ${c.javaClass.name}: ${c.message}")
                c = c.cause
            }
            return
        }
        println("INTERCEPT=$before")

        // same — записать то же значение: проверка прав без изменения состояния.
        // off  — выключить контроль разрешений.
        // on   — включить обратно.
        if (args.isEmpty()) return
        val target = when (args[0]) {
            "off" -> false
            "on" -> true
            "same" -> before as? Boolean ?: return
            else -> return
        }

        val setter = try {
            clazz.getMethod("setPermissionInterceptEnable", Boolean::class.javaPrimitiveType)
        } catch (t: Throwable) {
            println("ERROR setter: ${t.javaClass.name}: ${t.message}")
            return
        }

        try {
            setter.invoke(manager, target)
            println("SET=ok (target=$target)")
            println("INTERCEPT_AFTER=" + getter.invoke(manager))
        } catch (t: Throwable) {
            println("ERROR write: ${t.javaClass.name}: ${t.message}")
            var c = t.cause
            while (c != null) {
                println("CAUSE: ${c.javaClass.name}: ${c.message}")
                c = c.cause
            }
        }
    }
}
