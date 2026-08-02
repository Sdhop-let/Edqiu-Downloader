package com.ed.edqiu

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.ed.edqiu.data.preferences.FirstLaunchManager
import com.ed.edqiu.ui.navigation.EdqiuApp
import com.ed.edqiu.ui.splash.LaunchSplash

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val container = (application as EdqiuApplication).container
        val showSplash = FirstLaunchManager.isFirstLaunch(this)

        setContent {
            if (showSplash) {
                // remember 必须！否则每次重组 splashDone 重置为 false → 永远停在开屏
                var splashDone by remember { mutableStateOf(false) }
                if (!splashDone) {
                    LaunchSplash {
                        splashDone = true
                        FirstLaunchManager.markShown(this@MainActivity)
                    }
                } else {
                    EdqiuApp(container = container)
                }
            } else {
                EdqiuApp(container = container)
            }
        }
    }
}
