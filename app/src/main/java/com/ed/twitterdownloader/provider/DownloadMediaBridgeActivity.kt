package com.ed.twitterdownloader.provider

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager

/**
 * 鐭殏鍞よ捣涓嬭浇鍣ㄨ繘绋嬶紝璁?Edqiu 鑳借鍙?Android/data 涓嬭浇鐩綍銆? * 绐楀彛閫忔槑涓斾粎 1脳1 鍍忕礌锛屼笉杩涘叆鏈€杩戜换鍔★紝瀹屾垚鏌ヨ鍚庤嚜鍔ㄥ叧闂€? */
class DownloadMediaBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            width = 1
            height = 1
            gravity = Gravity.BOTTOM or Gravity.END
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        Handler(Looper.getMainLooper()).postDelayed(::finish, FINISH_DELAY_MS)
    }

    companion object {
        private const val FINISH_DELAY_MS = 3_000L
    }
}

