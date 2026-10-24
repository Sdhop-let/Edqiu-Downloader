package com.ed.edqiu

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.ed.edqiu.ui.splash.SplashContent

/**
 * 开屏（2026-10-24 重设计：液态玻璃舞台，替代 2026-09-30 Titanic 水波字）。
 *
 * 内容见 [SplashContent]：壁纸舞台 + 玻璃徽记 + 一次性入场动画（无 infinite——
 * Titanic 时代教训：常驻动画让应用永不过滤空闲，系统推迟 Activity 收尾，
 * 与主界面 RESUME 乱序触发 mStopped 冻结）。Compose 动画全部有限时长，
 * 最长 1.3s 自然结束，空闲过滤不受阻。
 *
 * 节奏沿用「就绪即走」：保底 MIN_SPLASH_MS 品牌曝光，引擎（yt-dlp/ffmpeg）
 * 就绪即提前进主页；就绪慢/失败则上限 SPLASH_DURATION_MS 兜底。
 * 跳过：右上角胶囊 + 整页点按（SplashContent 内处理）。
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SplashContent(onSkip = ::enterMain)
        }

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

    private companion object {
        /** 上限：引擎未就绪/初始化失败时的兜底展示时长 */
        const val SPLASH_DURATION_MS = 3000L
        /** 保底展示时长：品牌曝光不打折（就绪即走只提前、不缩短保底） */
        const val MIN_SPLASH_MS = 1500L
    }
}
