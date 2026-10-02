package com.ed.edqiu

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.romainpiel.titanic.library.Titanic
import com.romainpiel.titanic.library.TitanicTextView

/**
 * 开屏动画（2026-09-30）：romainpiel/Titanic 水波掠过 "Edqiu Download"，固定 3s 后进入主页。
 * 每次冷启动都显示；首次安装的 LaunchSplash（首启引导层）逻辑不变，两者独立。
 */
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val titanic = Titanic()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val tv = findViewById<TitanicTextView>(R.id.splash_titanic_text)
        // Satisfy 手写体（assets/fonts/Satisfy-Regular.ttf），与 Titanic 官方示例一致
        tv.typeface = Typeface.createFromAsset(assets, "fonts/Satisfy-Regular.ttf")
        titanic.start(tv)

        // 点击任意位置可跳过等待（不愿等的用户点一下即进主页）
        findViewById<android.view.View>(android.R.id.content).setOnClickListener { enterMain() }

        // 2026-10 UX 短板补齐：开屏"就绪即走"——保底展示 MIN_SPLASH_MS 保证品牌曝光，
        // 引擎（yt-dlp/ffmpeg）就绪即提前进主页；就绪慢/失败则上限 SPLASH_DURATION_MS 兜底。
        // 旧实现固定睡满 3s，引擎早就绪时纯属干等。
        lifecycleScope.launch {
            val start = SystemClock.elapsedRealtime()
            val appInit = (application as? TwitterDownloaderApp)?.isInitialized
            while (isActive && !isFinishing) {
                val elapsed = SystemClock.elapsedRealtime() - start
                val engineReady = appInit?.value ?: true
                if (elapsed >= MIN_SPLASH_MS && (engineReady || elapsed >= SPLASH_DURATION_MS)) {
                    enterMain()
                    break
                }
                delay(80)
            }
        }
    }

    private fun enterMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        titanic.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 上限：引擎未就绪/初始化失败时的兜底展示时长 */
        const val SPLASH_DURATION_MS = 3000L
        /** 保底展示时长：品牌曝光不打折（就绪即走只提前、不缩短保底） */
        const val MIN_SPLASH_MS = 1500L
    }
}
