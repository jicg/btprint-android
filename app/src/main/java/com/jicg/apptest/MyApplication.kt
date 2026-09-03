package com.jicg.apptest

import android.app.Application
import android.content.Context
import com.jicg.btprint.PrintUtils

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // SDK 初始化：持有 Application 引用，进程级单例无泄漏风险
        PrintUtils.init(this)
    }

    companion object {
        private lateinit var instance: MyApplication

        fun getInstance(): MyApplication {
            return instance
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }
} 