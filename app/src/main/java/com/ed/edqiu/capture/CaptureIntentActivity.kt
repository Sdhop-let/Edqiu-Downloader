package com.ed.edqiu.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ed.edqiu.EdqiuApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 接收系统分享和“处理文本”，保存完成后立即退出。 */
class CaptureIntentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    private fun handleIntent(sourceIntent: Intent) {
        val text = when (sourceIntent.action) {
            Intent.ACTION_SEND -> sourceIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT ->
                sourceIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }

        if (sourceIntent.action == Intent.ACTION_PROCESS_TEXT) {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            )
        }

        if (text.isNullOrBlank()) {
            finish()
            return
        }

        val coordinator =
            (application as EdqiuApplication).container.linkCaptureCoordinator
        lifecycleScope.launch(Dispatchers.IO) {
            coordinator.capture(text)
            withContext(Dispatchers.Main) { finish() }
        }
    }
}
