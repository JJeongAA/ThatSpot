package com.thatspot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.thatspot.app.R

/**
 * 내비를 앞에 띄워 둔 채로도 계속 지켜보고 말할 수 있게 앱 프로세스를 붙잡아 두는 서비스.
 * 판단은 AppController 가 하고, 이 서비스는 알림 한 줄만 띄운다.
 * 위치 권한이 없으면 안드로이드가 시작을 막는다. 그때도 앱이 화면에 있는 동안은 그대로 동작한다.
 */
class DriveWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행 중 지켜보기", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("내가 놀랐던 곳에 가까워지면 말로 알려 드려요")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, n)
        }.onFailure { Log.w(TAG, "포그라운드 시작 실패 (위치 권한 확인)", it); stopSelf() }
        return START_STICKY
    }

    companion object {
        private const val TAG = "DriveWatchService"
        private const val CHANNEL = "watch"
        private const val ID = 7

        /** 위치 권한이 있을 때만 시작한다 (없이 시작하면 startForeground 가 거부되고 앱이 종료될 수 있다). */
        fun start(context: Context) {
            val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) { Log.i(TAG, "위치 권한이 없어 백그라운드 지켜보기를 켜지 않아요"); return }
            runCatching { context.startForegroundService(Intent(context, DriveWatchService::class.java)) }
                .onFailure { Log.w(TAG, "서비스 시작 실패", it) }
        }
    }
}
