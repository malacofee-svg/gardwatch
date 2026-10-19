package fr.cherif.gardwatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleClient(private val ctx: Context, private val onLostLink: () -> Unit) {

    companion object {
        fun u(s: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", s))
        val SVC_DATA: UUID = u(0x56ff)
        val CH_WRITE: UUID = u(0x34f1)
        val CH_NOTIFY: UUID = u(0x34f2)
        val SVC_CMD: UUID = u(0x55ff)
        val CH_CMD_NOTIFY: UUID = u(0x35f2)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeCh: BluetoothGattCharacteristic? = null
    private val ops = ArrayDeque<() -> Unit>()
    private var busy = false
    private var opId = 0
    private var wantConnected = false
    private var mac = ""

    @Volatile
    var connected = false
        private set

    // ---------- connexion ----------

    fun connect(address: String) {
        mac = address
        wantConnected = true
        open()
    }

    fun disconnect() {
        wantConnected = false
        closeGatt()
        Logger.log("Déconnecté (demandé)")
    }

    private fun open() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Logger.log("Bluetooth éteint")
            scheduleRetry()
            return
        }
        closeGatt()
        Logger.log("Connexion à $mac …")
        try {
            val dev = adapter.getRemoteDevice(mac)
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Logger.log("Erreur de connexion : ${e.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!wantConnected) return
        main.postDelayed({ if (wantConnected && !connected) open() }, 6000)
    }

    private fun closeGatt() {
        ops.clear()
        busy = false
        writeCh = null
        connected = false
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    // ---------- file d'opérations GATT ----------

    private fun enqueue(op: () -> Unit) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        if (busy) return
        val op = ops.removeFirstOrNull() ?: return
        busy = true
        val my = ++opId
        main.postDelayed({
            if (busy && opId == my) { Logger.log("délai dépassé"); done() }
        }, 4000)
        try { op() } catch (e: Exception) { Logger.log("erreur : ${e.message}"); done() }
    }

    private fun done() {
        busy = false
        pump()
    }

    private fun enableNotify(ch: BluetoothGattCharacteristic?) {
        if (ch == null) return
        enqueue {
            val g = gatt
            val d = ch.getDescriptor(CCCD)
            if (g == null || d == null) {
                done()
            } else {
                g.setCharacteristicNotification(ch, true)
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(d)) done()
            }
        }
    }

    fun write(data: ByteArray) {
        enqueue {
            val g = gatt
            val ch = writeCh
            if (g == null || ch == null) {
                Logger.log("Pas connecté")
                done()
            } else {
                val canWrite = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                ch.writeType = if (canWrite) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = data
                Logger.log("→ " + data.hex())
                val my = opId
                if (!g.writeCharacteristic(ch)) {
                    Logger.log("écriture refusée")
                    done()
                } else if (!canWrite) {
                    main.postDelayed({ if (opId == my) done() }, 40)
                }
            }
        }
    }

    // ---------- commandes ----------

    fun requestInfo() = write(WatchProtocol.infoRequest)
    fun syncTime() = write(WatchProtocol.setTime())

    fun sendNotification(title: String, body: String) {
        if (!connected) {
            Logger.log("Notification ignorée : montre non connectée")
            return
        }
        Logger.log("Notif : $title / $body")
        WatchProtocol.notification(title, body).forEach { write(it) }
    }

    // ---------- callbacks ----------

    private fun handle(uuid: UUID, value: ByteArray) {
        Logger.log("← " + uuid.toString().substring(4, 8) + " " + value.hex())
    }

    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Logger.log("Lien établi (statut $status)")
                    main.postDelayed({ gatt?.discoverServices() }, 500)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Logger.log("Lien coupé (statut $status)")
                    val was = connected
                    closeGatt()
                    if (wantConnected) {
                        if (was) onLostLink()
                        scheduleRetry()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                for (s in g.services) {
                    Logger.log("service ${s.uuid}")
                    for (c in s.characteristics) Logger.log("    car. ${c.uuid} propriétés=${c.properties}")
                }
                val svc = g.getService(SVC_DATA)
                val wc = svc?.getCharacteristic(CH_WRITE)
                if (svc == null || wc == null) {
                    Logger.log("Service 56ff / caractéristique 34f1 absents : protocole différent")
                    return@post
                }
                writeCh = wc
                connected = true
                enableNotify(svc.getCharacteristic(CH_NOTIFY))
                enableNotify(g.getService(SVC_CMD)?.getCharacteristic(CH_CMD_NOTIFY))
                enqueue { if (!g.requestMtu(247)) done() }
                write(WatchProtocol.infoRequest)
                write(WatchProtocol.enableFindPhone)
                write(WatchProtocol.setTime())
                Logger.log("Montre prête")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { Logger.log("MTU $mtu"); done() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (status != 0) Logger.log("abonnement statut $status"); done() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (status != 0) Logger.log("écriture statut $status"); done() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: ByteArray(0)
            val id = c.uuid
            main.post { handle(id, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val id = c.uuid
            main.post { handle(id, value) }
        }
    }
}
