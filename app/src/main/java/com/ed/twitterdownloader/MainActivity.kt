package com.ed.twitterdownloader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.ed.twitterdownloader.navigation.AppNavigation
import com.ed.twitterdownloader.ui.screens.SplashCoverScreen
import com.ed.twitterdownloader.ui.theme.TwitterDownloaderTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val pendingRequest = mutableStateOf<ExternalDownloadRequest?>(null)
    private var nextRequestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 三重保险（防 ColorOS 覆盖）：enableEdgeToEdge + setDecorFitsSystemWindows + window 显式透明
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (savedInstanceState == null) enqueueDownloadIntent(intent)

        setContent {
            TwitterDownloaderTheme {
                var showCover by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(1300L)
                    showCover = false
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showCover) {
                        SplashCoverScreen()
                    } else {
                        AppNavigation(
                            externalDownloadRequest = pendingRequest.value,
                            onExternalDownloadConsumed = ::consumeRequest
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueDownloadIntent(intent)
    }

    private fun enqueueDownloadIntent(intent: Intent?) {
        val url = DownloadIntentContract.extractUrl(intent) ?: return
        nextRequestId += 1L
        pendingRequest.value = ExternalDownloadRequest(nextRequestId, url)
    }

    private fun consumeRequest(requestId: Long) {
        if (pendingRequest.value?.requestId == requestId) {
            pendingRequest.value = null
        }
    }
}

