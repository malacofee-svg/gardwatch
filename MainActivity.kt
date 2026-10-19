package fr.cherif.gardwatch

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var macEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gw", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        macEdit = EditText(this).apply {
            setText(prefs.getString("mac", "D8:0F:B5:17:20:F9"))
            hint = "Adresse Bluetooth de la montre"
            setSingleLine()
        }
        root.addView(macEdit)

        fun btn(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        btn("Connecter la montre") { connect(prefs) }
        btn("Déconnecter") {
            startService(Intent(this, WatchService::class.java).setAction(WatchService.ACTION_DISCONNECT))
        }
        btn("Infos de la montre") {
            val c = WatchService.client
            if (c == null) Logger.log("Pas de connexion") else c.requestInfo()
        }
        btn("Régler l'heure") {
            val c = WatchService.client
            if (c == null) Logger.log("Pas de connexion") else c.syncTime()
        }
        btn("Notification de test") {
            val c = WatchService.client
            if (c == null) Logger.log("Pas de connexion") else c.sendNotification("[Test] Gard Watch", "Bonjour depuis mon appli")
        }
        btn("Accès aux notifications (réglage Android)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("Copier le journal") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("journal", Logger.all()))
            Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
        }

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun connect(prefs: android.content.SharedPreferences) {
        val mac = macEdit.text.toString().trim().uppercase()
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)) {
            Toast.makeText(this, "Adresse invalide", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS), 1)
            Toast.makeText(this, "Autorise le Bluetooth puis réessaie", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("mac", mac).putBoolean("auto", true).apply()
        startForegroundService(
            Intent(this, WatchService::class.java)
                .setAction(WatchService.ACTION_CONNECT)
                .putExtra("mac", mac))
    }

    override fun onResume() {
        super.onResume()
        logView.text = Logger.all()
        Logger.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onPause() {
        super.onPause()
        Logger.listener = null
    }
}
