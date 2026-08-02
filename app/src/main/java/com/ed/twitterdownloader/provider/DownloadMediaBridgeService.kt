package com.ed.twitterdownloader.provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.ed.edqiu.R

/**
 * 鐭殏鍚姩涓嬭浇鍣ㄨ繘绋嬶紝渚?Edqiu 璇诲彇 Android/data 涓嬭浇鐩綍銆? * 鏌ヨ缁撴潫鍚?Edqiu 浼氬仠姝㈣鏈嶅姟锛涜嫢璋冪敤鏂瑰紓甯搁€€鍑猴紝鏈嶅姟涔熶笉浼氬父椹汇€? */
class DownloadMediaBridgeService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "涓嬭浇鐩綍鍚屾",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "鍏佽 Edqiu 璇诲彇涓嬭浇鍣ㄧ殑 Android/data 涓嬭浇璁板綍"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("姝ｅ湪鍚屾涓嬭浇鐩綍")
            .setContentText("Edqiu 姝ｅ湪璇诲彇 Android/data 涓嬭浇璁板綍")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "download_media_bridge"
        private const val NOTIFICATION_ID = 21401
    }
}


