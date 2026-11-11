package com.example.uhdrcam

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/*
 * Ajuste del nivel Ultra HDR de una foto ya sacada, sin tocar la imagen.
 *
 * Una foto Ultra HDR es un JPEG normal + un gain map (otra imagen chica) que le dice a la pantalla cuánto
 * iluminar cada zona. Cuánto como máximo lo fijan unos pocos números, guardados en dos lugares:
 *  - XMP del gain map (formato Google): hdrgm:GainMapMax y hdrgm:HDRCapacityMax (en pasos, log2)
 *  - bloque binario ISO 21496-1: gain_map_max y alternate_hdr_headroom (fracciones)
 * Se reescriben esos números en el mismo lugar y con el mismo largo: el archivo queda del mismo tamaño,
 * con la misma imagen, el mismo EXIF y el mismo gain map; solo cambia la intensidad.
 *
 * Como el gain map está normalizado entre el mínimo y el máximo, cambiar el máximo escala todas las
 * ganancias en la misma proporción (en pasos de luz). Por eso el ajuste es reversible: se puede subir
 * o bajar las veces que se quiera.
 */
object UltraHdrTuner {

    class Info(
        /** Máximo actual del gain map, en pasos (log2). La stock del Edge 50 Ultra pone 2.30 (×4.93). */
        val maxStops: Double,
        val hasXmp: Boolean,
        val hasIso: Boolean
    ) {
        val maxBoost get() = Math.pow(2.0, maxStops)
    }

    private val XMP_MAX = "hdrgm:GainMapMax=\"".toByteArray()
    private val XMP_MIN = "hdrgm:GainMapMin=\"".toByteArray()
    private val XMP_CAP = "hdrgm:HDRCapacityMax=\"".toByteArray()
    private val ISO_TAG = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.ISO_8859_1)

    /** Devuelve los datos Ultra HDR de la foto, o null si no tiene gain map. */
    fun analyze(b: ByteArray): Info? {
        val xmp = findValue(b, XMP_MAX)?.let { (s, e) -> String(b, s, e - s).toDoubleOrNull() }
        val iso = isoBlocks(b).firstNotNullOfOrNull { isoMax(b, it) }
        val max = xmp ?: iso ?: return null
        return Info(max, xmp != null, iso != null)
    }

    /**
     * Pone el máximo del Ultra HDR en [targetBoost] veces (1 = sin HDR, 4.93 = como la stock).
     * Devuelve un arreglo nuevo del mismo tamaño, o null si la foto no es Ultra HDR.
     */
    fun setMaxBoost(src: ByteArray, targetBoost: Double): ByteArray? {
        val info = analyze(src) ?: return null
        val b = src.copyOf()
        val newMax = ln(targetBoost.coerceAtLeast(1.0)) / ln(2.0)
        val ratio = if (info.maxStops > 1e-6) newMax / info.maxStops else 0.0
        // la capacidad máxima tiene que quedar por encima de la mínima (0) para que el archivo sea válido
        val newCap = newMax.coerceAtLeast(0.01)

        var touched = 0
        // XMP (texto): mismo largo de número
        for (key in listOf(XMP_MAX, XMP_CAP, XMP_MIN)) {
            var from = 0
            while (true) {
                val r = findValue(b, key, from) ?: break
                val (s, e) = r
                val old = String(b, s, e - s).toDoubleOrNull()
                if (old != null) {
                    val v = when (key) {
                        XMP_MAX -> newMax
                        XMP_CAP -> newCap
                        else -> old * ratio
                    }
                    val txt = fixedWidth(v, e - s) ?: throw IllegalStateException("no entra el número en el XMP")
                    System.arraycopy(txt.toByteArray(), 0, b, s, e - s)
                    touched++
                }
                from = e
            }
        }
        // ISO 21496-1 (binario)
        for (blk in isoBlocks(b)) {
            if (patchIso(b, blk, newMax, newCap, ratio)) touched++
        }
        return if (touched > 0) b else null
    }

    // ------------------------------------------------------------------ XMP

    /** Busca `clave="valor"` y devuelve el rango del valor. */
    private fun findValue(b: ByteArray, key: ByteArray, from: Int = 0): Pair<Int, Int>? {
        val i = indexOf(b, key, from)
        if (i < 0) return null
        val s = i + key.size
        var e = s
        while (e < b.size && b[e] != '"'.code.toByte() && e - s < 32) e++
        return if (e < b.size && b[e] == '"'.code.toByte()) s to e else null
    }

    /** Escribe v con exactamente `width` caracteres (ej. 9 -> "1.3219281"). */
    fun fixedWidth(v: Double, width: Int): String? {
        val intPart = abs(v).toLong().toString().length + (if (v < 0) 1 else 0)
        val dec = width - intPart - 1
        val s = when {
            dec >= 1 -> String.format(java.util.Locale.US, "%.${dec}f", v)
            dec == 0 || dec == -1 -> String.format(java.util.Locale.US, "%d", Math.round(v))
            else -> return null
        }
        return when {
            s.length == width -> s
            s.length < width && !s.contains('.') -> (s + ".").padEnd(width, '0')
            else -> null
        }
    }

    // ------------------------------------------------------------------ ISO 21496-1

    /** Posiciones de los datos de cada bloque APP2 ISO 21496-1 que tenga metadatos completos. */
    private fun isoBlocks(b: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = indexOf(b, ISO_TAG, from)
            if (i < 2) break
            val segLen = ((b[i - 2].toInt() and 255) shl 8) or (b[i - 1].toInt() and 255)
            val dataStart = i + ISO_TAG.size
            val dataLen = segLen - 2 - ISO_TAG.size
            if (dataLen >= 59) out += dataStart // los de 4 bytes (solo versión) están en la imagen principal
            from = i + ISO_TAG.size
        }
        return out
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 255) shl 24) or ((b[p + 1].toLong() and 255) shl 16) or
            ((b[p + 2].toLong() and 255) shl 8) or (b[p + 3].toLong() and 255)

    private fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }

    private fun isoMax(b: ByteArray, d: Int): Double? {
        // gain_map_max del primer canal: numerador en d+29, denominador en d+33
        val den = u32(b, d + 33)
        if (den == 0L) return null
        return u32(b, d + 29).toInt().toDouble() / den
    }

    /**
     * Estructura: versión mínima (2) + de escritura (2) + flags (1) + headroom base (4+4) + headroom alternativo (4+4)
     * y por canal: gain_map_min (4+4), gain_map_max (4+4), gamma (4+4), base_offset (4+4), alternate_offset (4+4).
     */
    private fun patchIso(b: ByteArray, d: Int, newMax: Double, newCap: Double, ratio: Double): Boolean {
        val flags = b[d + 4].toInt() and 255
        val channels = if (flags and 0x80 != 0) 3 else 1
        // headroom alternativo
        val altDen = u32(b, d + 13 + 4)
        if (altDen == 0L) return false
        putU32(b, d + 13, (newCap * altDen).roundToLong().coerceIn(0, 0x7fffffff))
        for (c in 0 until channels) {
            val base = d + 21 + c * 40
            // mínimo: se escala igual que el máximo
            val minDen = u32(b, base + 4)
            if (minDen != 0L) {
                val minV = u32(b, base).toInt().toDouble() / minDen
                putU32(b, base, (minV * ratio * minDen).roundToLong().toInt().toLong() and 0xffffffffL)
            }
            val maxDen = u32(b, base + 12)
            if (maxDen == 0L) return false
            putU32(b, base + 8, (newMax * maxDen).roundToLong().toInt().toLong() and 0xffffffffL)
        }
        return true
    }

    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int): Int {
        outer@ for (i in from.coerceAtLeast(0)..b.size - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }
}
