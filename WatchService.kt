package fr.cherif.gardwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class WatchService : Service() {

    companion object {
        const val CH_FG = "fg"
        const val CH_ALERT = "alert"
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"
        @Volatile var client: BleClient? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FG, "Liaison montre", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alerte déconnexion", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, CH_FG)
            .setContentTitle("Gard Watch")
            .setContentText("Liaison avec la montre active")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val prefs = getSharedPreferences("gw", MODE_PRIVATE)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                prefs.edit().putBoolean("auto", false).apply()
                client?.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra("mac") ?: prefs.getString("mac", null)
                if (mac != null) startClient(mac)
            }
            else -> {
                // redémarrage système : on se reconnecte si c'était actif
                val mac = prefs.getString("mac", null)
                if (mac != null && prefs.getBoolean("auto", false)) startClient(mac) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startClient(mac: String) {
        if (client == null) client = BleClient(applicationContext) { alertLost() }
        client?.connect(mac)
    }

    private fun alertLost() {
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle("Montre déconnectée")
            .setContentText("Le téléphone a perdu la liaison avec la montre.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(2, n)
    }
}
