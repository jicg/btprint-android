package com.jicg.apptest

import android.content.Context

/** 打印方式：只用蓝牙 / 只用 WiFi / 两者都支持 */
enum class PrintMode {
    BLUETOOTH,
    WIFI,
    BOTH
}

/**
 * 打印方式配置（持久化到 SharedPreferences，默认两者都支持）
 */
object PrintModeStore {
    private const val PREF_NAME = "app_settings"
    private const val KEY_PRINT_MODE = "print_mode"

    fun get(context: Context): PrintMode {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PRINT_MODE, PrintMode.BOTH.name)
        return PrintMode.entries.firstOrNull { it.name == name } ?: PrintMode.BOTH
    }

    fun set(context: Context, mode: PrintMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRINT_MODE, mode.name)
            .apply()
    }
}
