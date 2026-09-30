package com.ed.edqiu

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
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

        handler.postDelayed(::enterMain, SPLASH_DURATION_MS)
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
        const val SPLASH_DURATION_MS = 3000L
    }
}
