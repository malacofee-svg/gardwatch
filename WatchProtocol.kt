package fr.cherif.gardwatch

import java.io.ByteArrayOutputStream
import java.util.TimeZone

/** Trames du dialecte TLV "GloryFit Pro" (voir document de référence du protocole). */
object WatchProtocol {

    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Demande d'infos : micrologiciel, modèle, batterie. */
    val infoRequest = bytes(0x01, 0xa4, 0xaa, 0xaa)

    /** Active "Trouver mon téléphone" côté montre. */
    val enableFindPhone = bytes(0x01, 0xa5, 0xab, 0x0a, 0x01, 0x01)

    /** Heure : 01 a3 | 01 04 epoch(4) | 10 02 tz0 tz1 */
    fun setTime(nowMs: Long = System.currentTimeMillis()): ByteArray {
        val epoch = nowMs / 1000
        val offMin = TimeZone.getDefault().getOffset(nowMs) / 60000
        val a = Math.abs(offMin)
        val tz0 = (if (offMin >= 0) 0x80 else 0) or (a / 60)
        val tz1 = a % 60
        return bytes(
            0x01, 0xa3,
            0x01, 0x04,
            ((epoch shr 24) and 0xff).toInt(), ((epoch shr 16) and 0xff).toInt(),
            ((epoch shr 8) and 0xff).toInt(), (epoch and 0xff).toInt(),
            0x10, 0x02, tz0, tz1
        )
    }

    /** Notification (commande b0) : corps 03, titre 07, icône 08 (04 = générique), drapeau 09. */
    fun notification(title: String, body: String, iconId: Int = 0x04): List<ByteArray> {
        val t = title.replace('\n', ' ').take(30).toByteArray(Charsets.UTF_16BE)
        val b = body.replace('\n', ' ').take(80).toByteArray(Charsets.UTF_16BE)
        val p = ByteArrayOutputStream()
        p.write(0xab); p.write(0x03); p.write(b.size); p.write(b)
        p.write(0xab); p.write(0x07); p.write(t.size); p.write(t)
        p.write(0xab); p.write(0x08); p.write(1); p.write(iconId)
        p.write(0xab); p.write(0x09); p.write(1); p.write(1)
        return framed(0xb0, p.toByteArray())
    }

    /** Découpage + terminateur "fd" avec XOR de tous les octets envoyés (voir §3.3 et §3.4). */
    fun framed(cmd: Int, body: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var xor = 0
        var idx = 0
        var off = 0
        while (off < body.size || idx == 0) {
            val n = minOf(239, body.size - off)
            val f = ByteArray(5 + n)
            f[0] = 0x01; f[1] = cmd.toByte(); f[2] = 0xab.toByte(); f[3] = 0xab.toByte(); f[4] = idx.toByte()
            System.arraycopy(body, off, f, 5, n)
            for (x in f) xor = xor xor (x.toInt() and 0xff)
            frames.add(f)
            off += n
            idx++
        }
        frames.add(bytes(0x01, cmd, 0xab, 0xab, 0xfd, xor))
        return frames
    }
}
