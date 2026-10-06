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
    private var tv: TitanicTextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val tv = findViewById<TitanicTextView>(R.id.splash_titanic_text).also { this.tv = it }
        // Satisfy 手写体（assets/fonts/Satisfy-Regular.ttf），与 Titanic 官方示例一致
        tv.typeface = Typeface.createFromAsset(assets, "fonts/Satisfy-Regular.ttf")
        titanic.start(tv)

        // 2026-10-07：可见的「跳过」入口——整页点按跳过保留，另有右上角胶囊按钮
        //（不可发现的整页点按换成显式控件）。两处都走 enterMain()：不等品牌曝光
        // 满 1.5s，直接进主界面（引擎初始化在 Application 层继续，不受影响）
        findViewById<android.view.View>(R.id.splash_skip).setOnClickListener { enterMain() }

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
        // 2026-10 关键修复：立即停掉 Titanic 动画——此前的 cancel 只挂在 onDestroy，
        // 而本 Activity 被 Main 覆盖后长期处于 PAUSED（非 STOPPED）态，Titanic 的
        // 无限动画让应用永不过滤空闲，系统对 Splash 的 STOP/DESTROY 推迟 ~20s，
        // 迟到的生命周期批处理会与主界面 RESUME 乱序，把主窗口打进
        // mStopped=true（view_not_visible）——表现即"画面冻在半路/黑屏"
        titanic.cancel()
        tv?.animation = null
        tv?.clearAnimation()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onPause() {
        // 被覆盖/离开即停动画：同上，保证任务能尽快进入空闲让系统收尾生命周期
        titanic.cancel()
        tv?.clearAnimation()
        super.onPause()
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
