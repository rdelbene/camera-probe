package com.example.uhdrcam

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Revelado RAW sin dependencias de Android (se puede probar en la JVM).
 *
 * Ideas tomadas del flujo HDR+ / GCam (Hasinoff et al. 2016, y los ajustes que expone AGC:
 * noise model, luma_denoise por niveles, sharpening por niveles), implementadas desde cero:
 *  - peso de fusión según el modelo de ruido del sensor (S·x + O) en vez de un umbral fijo
 *  - demosaico Malvar-He-Cutler (corrige con gradientes, mucho más nítido que el bilineal)
 *  - detalle multiescala en luma: cada nivel se limpia de ruido (encogimiento suave) y se realza
 */

/** Modelo de ruido por canal del mosaico: varianza = s·x + o, con x en [0,1] (0 = negro, 1 = blanco). */
class NoiseModel(val s: FloatArray, val o: FloatArray) {
    fun sigma(ch: Int, x: Float): Float = sqrt((s[ch] * x.coerceAtLeast(0f) + o[ch]).coerceAtLeast(1e-12f))

    companion object {
        /** Aproximación genérica para sensores de celular cuando la cámara no informa SENSOR_NOISE_PROFILE. */
        fun fallback(iso: Int): NoiseModel {
            val g = iso / 100f
            return NoiseModel(FloatArray(4) { 2.0e-5f * g }, FloatArray(4) { 2.0e-7f * g * g })
        }
    }
}

/**
 * Peso de un cuadro en un pixel según cuánto difiere de la referencia, en unidades del ruido esperado.
 * d: diferencia; varDiff: varianza de la diferencia (2·σ²). Con k=9, una diferencia de 3σ pesa 0.5.
 */
fun mergeWeight(d: Float, varDiff: Float, k: Float): Float {
    val z = d * d / varDiff
    if (z > 12f * k) return 0f
    return 1f / (1f + z / k)
}

/** Parámetros del realce de detalle. Gains: 1 = sin cambio. Umbrales en unidades de salida (0..1). */
class DetailParams(
    val gFine: Float,
    val gMid: Float,
    val gCoarse: Float,
    val tFine: Float,
    val tMid: Float,
    val maxDelta: Float = 0.22f
) {
    val isIdentity get() = gFine == 1f && gMid == 1f && gCoarse == 1f && tFine <= 0f && tMid <= 0f
}

/**
 * Demosaico Malvar-He-Cutler + matriz de color + curva de tono.
 * plane: mosaico lineal normalizado (balance y sombreado ya aplicados).
 * cfa: canal (0 R, 1 G, 2 B) de cada posición del bloque 2x2.
 * ccm: 3x3 por filas. lut: 4096 entradas indexadas por sqrt(valor lineal) -> 0..255.
 * cell (opcional): acumula la luminancia lineal por bloques de 4x4 para el gain map.
 */
fun demosaicMhc(
    plane: FloatArray, w: Int, h: Int, cfa: IntArray, ccm: FloatArray, lut: IntArray,
    cell: FloatArray?, gw: Int, gh: Int
): IntArray {
    val out = IntArray(w * h)
    // Índices con borde espejado para no chequear límites en el bucle
    val cx = IntArray(w + 4) { i -> reflect(i - 2, w) }
    val ry = IntArray(h + 4) { i -> reflect(i - 2, h) * w }
    val c0 = ccm[0]; val c1 = ccm[1]; val c2 = ccm[2]
    val c3 = ccm[3]; val c4 = ccm[4]; val c5 = ccm[5]
    val c6 = ccm[6]; val c7 = ccm[7]; val c8 = ccm[8]

    for (y in 0 until h) {
        val rM2 = ry[y]; val rM1 = ry[y + 1]; val r0 = ry[y + 2]; val rP1 = ry[y + 3]; val rP2 = ry[y + 4]
        val py = (y and 1) * 2
        val gy = y shr 2
        for (x in 0 until w) {
            val xM2 = cx[x]; val xM1 = cx[x + 1]; val x0 = cx[x + 2]; val xP1 = cx[x + 3]; val xP2 = cx[x + 4]
            val c = plane[r0 + x0]
            val n = plane[rM1 + x0]; val s = plane[rP1 + x0]
            val e = plane[r0 + xP1]; val wv = plane[r0 + xM1]
            val nn = plane[rM2 + x0]; val ss = plane[rP2 + x0]
            val ee = plane[r0 + xP2]; val ww = plane[r0 + xM2]
            val diag = plane[rM1 + xM1] + plane[rM1 + xP1] + plane[rP1 + xM1] + plane[rP1 + xP1]
            val cross2 = nn + ss + ee + ww

            var r: Float; var g: Float; var b: Float
            when (cfa[py + (x and 1)]) {
                1 -> {
                    g = c
                    // Color en los vecinos horizontales y verticales de este verde
                    val hzRed = cfa[py + ((x + 1) and 1)] == 0
                    val horiz = (5f * c + 4f * (e + wv) - (ee + ww) - diag + 0.5f * (nn + ss)) * 0.125f
                    val vert = (5f * c + 4f * (n + s) - (nn + ss) - diag + 0.5f * (ee + ww)) * 0.125f
                    if (hzRed) { r = horiz; b = vert } else { r = vert; b = horiz }
                }
                0 -> {
                    r = c
                    g = (4f * c + 2f * (n + s + e + wv) - cross2) * 0.125f
                    b = (6f * c + 2f * diag - 1.5f * cross2) * 0.125f
                }
                else -> {
                    b = c
                    g = (4f * c + 2f * (n + s + e + wv) - cross2) * 0.125f
                    r = (6f * c + 2f * diag - 1.5f * cross2) * 0.125f
                }
            }
            r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
            val ro = (c0 * r + c1 * g + c2 * b).coerceIn(0f, 1f)
            val go = (c3 * r + c4 * g + c5 * b).coerceIn(0f, 1f)
            val bo = (c6 * r + c7 * g + c8 * b).coerceIn(0f, 1f)
            if (cell != null) {
                val gx = x shr 2
                if (gx < gw && gy < gh) cell[gy * gw + gx] += (0.2126f * ro + 0.7152f * go + 0.0722f * bo) / 16f
            }
            out[y * w + x] = (0xFF shl 24) or
                (lut[(sqrt(ro) * 4095f).toInt()] shl 16) or
                (lut[(sqrt(go) * 4095f).toInt()] shl 8) or
                lut[(sqrt(bo) * 4095f).toInt()]
        }
    }
    return out
}

/** Demosaico bilineal (el anterior), se deja para comparar en pruebas. */
fun demosaicBilinear(plane: FloatArray, w: Int, h: Int, cfa: IntArray): Array<FloatArray> {
    val R = FloatArray(w * h); val G = FloatArray(w * h); val B = FloatArray(w * h)
    for (y in 0 until h) {
        val ym = reflect(y - 1, h); val yp = reflect(y + 1, h)
        for (x in 0 until w) {
            val xm = reflect(x - 1, w); val xp = reflect(x + 1, w)
            val v = plane[y * w + x]
            var r: Float; var g: Float; var b: Float
            when (cfa[(y and 1) * 2 + (x and 1)]) {
                1 -> {
                    val hz = (plane[y * w + xm] + plane[y * w + xp]) * 0.5f
                    val vt = (plane[ym * w + x] + plane[yp * w + x]) * 0.5f
                    g = v
                    if (cfa[(y and 1) * 2 + (xm and 1)] == 0) { r = hz; b = vt } else { r = vt; b = hz }
                }
                0 -> {
                    r = v
                    g = (plane[y * w + xm] + plane[y * w + xp] + plane[ym * w + x] + plane[yp * w + x]) * 0.25f
                    b = (plane[ym * w + xm] + plane[ym * w + xp] + plane[yp * w + xm] + plane[yp * w + xp]) * 0.25f
                }
                else -> {
                    b = v
                    g = (plane[y * w + xm] + plane[y * w + xp] + plane[ym * w + x] + plane[yp * w + x]) * 0.25f
                    r = (plane[ym * w + xm] + plane[ym * w + xp] + plane[yp * w + xm] + plane[yp * w + xp]) * 0.25f
                }
            }
            R[y * w + x] = r; G[y * w + x] = g; B[y * w + x] = b
        }
    }
    return arrayOf(R, G, B)
}

/** Igual que demosaicMhc pero devuelve los planos lineales (para pruebas). */
fun demosaicMhcLinear(plane: FloatArray, w: Int, h: Int, cfa: IntArray): Array<FloatArray> {
    val R = FloatArray(w * h); val G = FloatArray(w * h); val B = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        fun p(dx: Int, dy: Int) = plane[reflect(y + dy, h) * w + reflect(x + dx, w)]
        val c = p(0, 0)
        val n = p(0, -1); val s = p(0, 1); val e = p(1, 0); val wv = p(-1, 0)
        val nn = p(0, -2); val ss = p(0, 2); val ee = p(2, 0); val ww = p(-2, 0)
        val diag = p(-1, -1) + p(1, -1) + p(-1, 1) + p(1, 1)
        val cross2 = nn + ss + ee + ww
        val py = (y and 1) * 2
        var r: Float; var g: Float; var b: Float
        when (cfa[py + (x and 1)]) {
            1 -> {
                g = c
                val hzRed = cfa[py + ((x + 1) and 1)] == 0
                val horiz = (5f * c + 4f * (e + wv) - (ee + ww) - diag + 0.5f * (nn + ss)) * 0.125f
                val vert = (5f * c + 4f * (n + s) - (nn + ss) - diag + 0.5f * (ee + ww)) * 0.125f
                if (hzRed) { r = horiz; b = vert } else { r = vert; b = horiz }
            }
            0 -> { r = c; g = (4f * c + 2f * (n + s + e + wv) - cross2) * 0.125f; b = (6f * c + 2f * diag - 1.5f * cross2) * 0.125f }
            else -> { b = c; g = (4f * c + 2f * (n + s + e + wv) - cross2) * 0.125f; r = (6f * c + 2f * diag - 1.5f * cross2) * 0.125f }
        }
        R[y * w + x] = r.coerceIn(0f, 1f); G[y * w + x] = g.coerceIn(0f, 1f); B[y * w + x] = b.coerceIn(0f, 1f)
    }
    return arrayOf(R, G, B)
}

/**
 * Realce de detalle multiescala sobre la luma de una imagen ARGB, en el lugar.
 *  fino  = Y  - B1   (B1: binomial 3x3 a resolución completa)    -> se limpia de ruido y se realza
 *  medio = B1 - B2   (B2: binomial sobre la mitad de resolución)  -> se limpia de ruido y se realza
 *  grueso= B2 - B4   (B4: binomial sobre un cuarto de resolución) -> contraste local suave
 * La diferencia de luma se suma igual a R, G y B para no alterar el color.
 */
fun detailEnhance(px: IntArray, w: Int, h: Int, p: DetailParams) {
    if (p.isIdentity || w < 8 || h < 8) return

    // Mitad de resolución (promedio 2x2) y cuarto de resolución
    val w2 = (w + 1) / 2; val h2 = (h + 1) / 2
    val half = FloatArray(w2 * h2)
    for (y in 0 until h2) for (x in 0 until w2) {
        var s = 0f; var c = 0
        for (dy in 0..1) {
            val yy = 2 * y + dy; if (yy >= h) continue
            for (dx in 0..1) {
                val xx = 2 * x + dx; if (xx >= w) continue
                s += lumaOf(px[yy * w + xx]); c++
            }
        }
        half[y * w2 + x] = s / c
    }
    val w4 = (w2 + 1) / 2; val h4 = (h2 + 1) / 2
    val quarter = FloatArray(w4 * h4)
    for (y in 0 until h4) for (x in 0 until w4) {
        var s = 0f; var c = 0
        for (dy in 0..1) {
            val yy = 2 * y + dy; if (yy >= h2) continue
            for (dx in 0..1) {
                val xx = 2 * x + dx; if (xx >= w2) continue
                s += half[yy * w2 + xx]; c++
            }
        }
        quarter[y * w4 + x] = s / c
    }
    val b2 = binomial3(binomial3(half, w2, h2), w2, h2)
    val b4 = binomial3(binomial3(quarter, w4, h4), w4, h4)

    // Interpolación bilineal precalculada por columna
    val x2i = IntArray(w); val x2f = FloatArray(w)
    val x4i = IntArray(w); val x4f = FloatArray(w)
    for (x in 0 until w) {
        val f2 = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (w2 - 1).toFloat())
        x2i[x] = min(f2.toInt(), w2 - 2).coerceAtLeast(0); x2f[x] = f2 - x2i[x]
        val f4 = ((x + 0.5f) / 4f - 0.5f).coerceIn(0f, (w4 - 1).toFloat())
        x4i[x] = min(f4.toInt(), w4 - 2).coerceAtLeast(0); x4f[x] = f4 - x4i[x]
    }

    // Tres filas de luma original (antes de modificar) para el binomial 3x3 a resolución completa
    val rows = Array(3) { FloatArray(w) }
    fun loadRow(dst: FloatArray, y: Int) { val b = reflect(y, h) * w; for (x in 0 until w) dst[x] = lumaOf(px[b + x]) }
    loadRow(rows[0], -1); loadRow(rows[1], 0); loadRow(rows[2], 1)

    val tf2 = p.tFine * p.tFine
    val tm2 = p.tMid * p.tMid
    for (y in 0 until h) {
        val up = rows[0]; val mid = rows[1]; val dn = rows[2]
        val f2y = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (h2 - 1).toFloat())
        val y2 = min(f2y.toInt(), h2 - 2).coerceAtLeast(0); val fy2 = f2y - y2
        val f4y = ((y + 0.5f) / 4f - 0.5f).coerceIn(0f, (h4 - 1).toFloat())
        val y4 = min(f4y.toInt(), h4 - 2).coerceAtLeast(0); val fy4 = f4y - y4
        val y2b = min(y2 + 1, h2 - 1); val y4b = min(y4 + 1, h4 - 1)
        val base = y * w
        for (x in 0 until w) {
            val xm = if (x > 0) x - 1 else 1
            val xp = if (x < w - 1) x + 1 else w - 2
            val yv = mid[x]
            val b1 = (up[xm] + 2f * up[x] + up[xp] + 2f * (mid[xm] + mid[xp]) + 4f * yv + dn[xm] + 2f * dn[x] + dn[xp]) * 0.0625f

            val ix2 = x2i[x]; val ix2b = min(ix2 + 1, w2 - 1); val fx2 = x2f[x]
            val t2 = b2[y2 * w2 + ix2] * (1 - fx2) + b2[y2 * w2 + ix2b] * fx2
            val u2 = b2[y2b * w2 + ix2] * (1 - fx2) + b2[y2b * w2 + ix2b] * fx2
            val bb2 = t2 * (1 - fy2) + u2 * fy2

            val ix4 = x4i[x]; val ix4b = min(ix4 + 1, w4 - 1); val fx4 = x4f[x]
            val t4 = b4[y4 * w4 + ix4] * (1 - fx4) + b4[y4 * w4 + ix4b] * fx4
            val u4 = b4[y4b * w4 + ix4] * (1 - fx4) + b4[y4b * w4 + ix4b] * fx4
            val bb4 = t4 * (1 - fy4) + u4 * fy4

            var dFine = yv - b1
            if (tf2 > 0f) dFine *= dFine * dFine / (dFine * dFine + tf2)
            var dMid = b1 - bb2
            if (tm2 > 0f) dMid *= dMid * dMid / (dMid * dMid + tm2)
            var yNew = bb4 + p.gCoarse * (bb2 - bb4) + p.gMid * dMid + p.gFine * dFine
            // sin halos: el resultado no puede pasarse mucho del mínimo/máximo de sus vecinos
            val mn = minOf(minOf(minOf(up[xm], up[x]), minOf(up[xp], mid[xm])), minOf(minOf(mid[xp], dn[xm]), minOf(minOf(dn[x], dn[xp]), yv)))
            val mx = maxOf(maxOf(maxOf(up[xm], up[x]), maxOf(up[xp], mid[xm])), maxOf(maxOf(mid[xp], dn[xm]), maxOf(maxOf(dn[x], dn[xp]), yv)))
            yNew = yNew.coerceIn(mn - 0.015f, mx + 0.015f)
            val delta = ((yNew - yv).coerceIn(-p.maxDelta, p.maxDelta) * 255f)

            val pix = px[base + x]
            val r = (((pix shr 16) and 255) + delta).toInt().coerceIn(0, 255)
            val g = (((pix shr 8) and 255) + delta).toInt().coerceIn(0, 255)
            val b = ((pix and 255) + delta).toInt().coerceIn(0, 255)
            px[base + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        // Rotar filas: la de arriba se recicla para la próxima de abajo
        val recycled = rows[0]
        rows[0] = rows[1]; rows[1] = rows[2]; rows[2] = recycled
        // La fila espejada del borde inferior ya fue modificada en px: se copia la original guardada
        if (y + 2 < h) loadRow(rows[2], y + 2) else rows[0].copyInto(rows[2])
    }
}

private fun lumaOf(p: Int): Float =
    (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) / (256f * 255f)

/** Desenfoque binomial 3x3 ([1 2 1] separable). */
fun binomial3(src: FloatArray, w: Int, h: Int): FloatArray {
    val tmp = FloatArray(src.size)
    for (y in 0 until h) {
        val b = y * w
        for (x in 0 until w) {
            val xm = reflect(x - 1, w); val xp = reflect(x + 1, w)
            tmp[b + x] = (src[b + xm] + 2f * src[b + x] + src[b + xp]) * 0.25f
        }
    }
    val out = FloatArray(src.size)
    for (y in 0 until h) {
        val ym = reflect(y - 1, h) * w; val yp = reflect(y + 1, h) * w; val b = y * w
        for (x in 0 until w) out[b + x] = (tmp[ym + x] + 2f * tmp[b + x] + tmp[yp + x]) * 0.25f
    }
    return out
}

/** Índice con borde espejado sin repetir el borde (…2 1 0 1 2…). */
fun reflect(i: Int, n: Int): Int {
    if (n == 1) return 0
    var v = i
    if (v < 0) v = -v
    if (v >= n) v = 2 * n - 2 - v
    return v.coerceIn(0, n - 1)
}

/**
 * Elige los parámetros de detalle según el ruido que queda después de fusionar.
 * sigmaOut: desvío del ruido en la salida (0..1, tono medio). sharp/nr: 0 auto, 1 off, 2 rápido, 3 alta.
 */
fun detailParamsFor(
    sigmaOut: Float, night: Boolean, sharp: Int, nr: Int,
    sharpMul: Float = 1f, nrMul: Float = 1f
): DetailParams {
    val s = when (sharp) { 1 -> 0f; 3 -> 1.6f; else -> 1f } * sharpMul
    val n = when (nr) { 1 -> 0f; 3 -> 2f; else -> 1f } * nrMul
    val fine = if (night) 0.4f else 0.7f
    return DetailParams(
        gFine = 1f + fine * s,
        gMid = 1f + 0.25f * s,
        gCoarse = 1f,
        tFine = 2.5f * sigmaOut * n,
        tMid = 1.0f * sigmaOut * n
    )
}

/** Medida simple de nitidez (energía de gradiente) para pruebas y para elegir el cuadro de referencia. */
fun gradientEnergy(a: FloatArray, w: Int, h: Int): Double {
    var s = 0.0
    for (y in 1 until h) for (x in 1 until w) {
        val gx = a[y * w + x] - a[y * w + x - 1]
        val gy = a[y * w + x] - a[(y - 1) * w + x]
        s += abs(gx) + abs(gy)
    }
    return s / (w.toDouble() * h)
}

// ---------------------------------------------------------------------- perfiles medidos de la cámara stock

/**
 * Datos de cada lente medidos sobre 52 pares DNG/JPEG de la cámara stock (análisis del 03/10/2026).
 *  - noise: coeficientes del DNG: S = a·ISO + b, O = c·ISO² + d
 *  - shadingCorner: ganancia del sombreado del lente en las esquinas (centro = 1)
 *  - forward: ForwardMatrix1 (cámara con balance aplicado -> XYZ D50), por filas
 *  - wb: ganancias de balance medianas (R, G, B), por si la toma no las informa
 *  - look: objetivos de salida del JPEG stock (pendientes de la curva en log-log, saturación, acutancia)
 */
class StockProfile(
    val name: String,
    val noise: FloatArray,
    val shadingCorner: Float,
    val forward: FloatArray,
    val wb: FloatArray,
    val shadowSlope: Float,
    val midSlope: Float,
    val highSlope: Float,
    val saturation: Float,
    /** Multiplicadores del realce y de la limpieza de ruido respecto del principal (acutancia y denoise medidos). */
    val sharpMul: Float,
    val nrMul: Float
) {
    fun noiseModel(iso: Int): NoiseModel {
        val s = (noise[0] * iso + noise[1]).coerceAtLeast(1e-7f)
        val o = (noise[2] * iso.toFloat() * iso + noise[3]).coerceAtLeast(1e-9f)
        return NoiseModel(FloatArray(4) { s }, FloatArray(4) { o })
    }

    /** Matriz cámara (balanceada) -> sRGB lineal, a partir de la ForwardMatrix. Por filas. */
    fun ccm(): FloatArray = mul3(XYZ_D50_TO_SRGB, forward)

    /** Saturación según ISO: a ISO alto la stock baja el color (medido en el gran angular a ISO 3200). */
    fun saturationAt(iso: Int): Float {
        val hi = ((iso - 1600) / 1600f).coerceIn(0f, 1f)
        return saturation * (1f - 0.15f * hi)
    }
}

object StockProfiles {
    val MAIN = StockProfile(
        "principal x1",
        floatArrayOf(6.38032e-8f, 3.651935e-5f, 3.499952e-12f, -4.968624e-8f), 3.9f,
        floatArrayOf(0.640625f, 0.25f, 0.0703125f, 0.296875f, 0.828125f, -0.125f, 0.0390625f, -0.296875f, 1.078125f),
        floatArrayOf(2.083789f, 1f, 1.642351f),
        1.617f, 1.083f, 0.697f, 1.186f, 1.0f, 1.0f
    )
    val UGA = StockProfile(
        "gran angular x0.5",
        floatArrayOf(3.538032e-7f, 6.451935e-5f, 0f, 0f), 4.5f,
        floatArrayOf(0.6796875f, 0.21875f, 0.0625f, 0.1953125f, 0.953125f, -0.1484375f, -0.09375f, -0.4609375f, 1.3828125f),
        floatArrayOf(1.430168f, 1f, 1.822064f),
        // medido sólo a ISO 3200: la saturación 0.82 de ahí la reproduce saturationAt(); a ISO bajo se deja neutra
        1.731f, 0.770f, 0.734f, 1.0f, 1.3f, 1.6f
    )
    val TELE = StockProfile(
        "tele x3",
        floatArrayOf(6.38032e-7f, 3.651935e-5f, 0f, 0f), 3.1f,
        floatArrayOf(0.8046875f, -0.0234375f, 0.1796875f, 0.4375f, 0.6171875f, -0.0546875f, 0.1484375f, -0.6171875f, 1.296875f),
        floatArrayOf(1.452482f, 1f, 2.409412f),
        2.012f, 1.057f, 0.823f, 1.144f, 1.1f, 1.3f
    )
    val FRONT = StockProfile(
        "frontal",
        floatArrayOf(3.538032e-7f, 6.451935e-5f, 0f, 0f), 4.2f,
        floatArrayOf(0.640625f, 0.015625f, 0.3046875f, 0.2734375f, 0.578125f, 0.140625f, -0.015625f, -0.3671875f, 1.203125f),
        floatArrayOf(1.361705f, 1f, 1.921201f),
        1.198f, 1.578f, 0.643f, 0.969f, 0.45f, 0.4f
    )

    /** Por nombre del lente de la app (Lens.name). */
    fun forLens(lensName: String): StockProfile = when (lensName) {
        "UGA" -> UGA
        "TELE" -> TELE
        "FRONT" -> FRONT
        else -> MAIN
    }
}

/** XYZ (D50) -> sRGB lineal, con adaptación Bradford. */
val XYZ_D50_TO_SRGB = floatArrayOf(
    3.1338561f, -1.6168667f, -0.4906146f,
    -0.9787684f, 1.9161415f, 0.0334540f,
    0.0719453f, -0.2289914f, 1.4052427f
)

fun mul3(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
    val r = i / 3; val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}

/**
 * Sombreado del lente aproximado (cuando la toma no trae el mapa): ganancia 1 en el centro y `corner` en las
 * esquinas, creciendo con r². El promedio que da (≈ 1 + (corner-1)/3) coincide con el mapa medido de la stock.
 */
fun radialShading(corner: Float, x: Int, y: Int, w: Int, h: Int): Float {
    val dx = (2f * x / (w - 1) - 1f); val dy = (2f * y / (h - 1) - 1f)
    return 1f + (corner - 1f) * (dx * dx + dy * dy) * 0.5f
}

/**
 * Curva tonal tipo S estilo Motorola, como tabla de 256 valores sobre la salida en gamma.
 * Se arma con pendientes en log-log (que no cambian entre lineal y gamma): `mid` en el gris medio,
 * `shadow` desde 3 pasos abajo, `high` en el blanco. El gris medio queda fijo y el blanco vuelve a 1.
 * strength: 0 = sin cambio, 1 = pendientes medidas tal cual.
 */
fun stockToneLut(p: StockProfile, strength: Float): IntArray {
    val a = 0.46f // gris medio (18 %) en gamma sRGB
    fun s(lx: Float): Float { // lx = ln(x / a)
        val sh = 1f + strength * (p.shadowSlope - 1f)
        val md = 1f + strength * (p.midSlope - 1f)
        val hi = 1f + strength * (p.highSlope - 1f)
        val lowEnd = -kotlin.math.ln(8f)
        val highEnd = -kotlin.math.ln(a)
        return when {
            lx <= lowEnd -> sh
            lx < 0f -> sh + (md - sh) * (lx - lowEnd) / -lowEnd
            lx < highEnd -> md + (hi - md) * lx / highEnd
            else -> hi
        }
    }
    fun f(x: Float): Float {
        if (x <= 0f) return 0f
        val lx = kotlin.math.ln(x / a)
        // integración numérica de la pendiente desde el gris medio
        val steps = 64
        var acc = 0f
        val du = lx / steps
        for (k in 0 until steps) acc += s(du * (k + 0.5f)) * du
        return a * kotlin.math.exp(acc)
    }
    val f1 = f(1f)
    return IntArray(256) { i ->
        val x = i / 255f
        var y = f(x)
        if (x > a) y = a + (y - a) * (1f - a) / (f1 - a) // el blanco vuelve a 1
        (y * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}

/** Aplica la curva (sobre la luma, conservando el color) y la saturación, en el lugar. */
fun applyLook(px: IntArray, toneLut: IntArray, sat: Float) {
    for (i in px.indices) {
        val p = px[i]
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        val y = (77 * r + 150 * g + 29 * b) shr 8
        val yo = toneLut[y].toFloat()
        val k = if (y > 0) yo / y else 1f
        // color con la luma nueva y la saturación pedida
        fun ch(c: Int): Int {
            val cv = c * k
            return (yo + sat * (cv - yo) + 0.5f).toInt().coerceIn(0, 255)
        }
        px[i] = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

// ---------------------------------------------------------------------- modo Stock: Ultra HDR sobre la foto del celu

private fun toLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

private fun Double.pow(e: Double) = Math.pow(this, e)

private fun lumaLinear(p: Int, lut: FloatArray): Float =
    0.2126f * lut[(p shr 16) and 255] + 0.7152f * lut[(p shr 8) and 255] + 0.0722f * lut[p and 255]

/**
 * Gain map para el modo Stock. sdr: la foto normal del procesador del celu; under: una toma igual pero
 * más oscura; ambas reducidas al mismo tamaño (ARGB). ratio: cuántas veces más luz recibió sdr que under
 * (tiempo × ISO de cada una). maxBoost: cuánto se pueden iluminar como máximo las luces (2 = 1 paso).
 *
 * En cada punto se compara la luz real (la oscura × ratio) con la que quedó en la foto normal. Solo se
 * tiene en cuenta en las zonas claras, porque en el resto la curva del procesador hace que no sean comparables.
 * Devuelve bytes 0..255 = log2(ganancia) / log2(maxBoost), o null si la escena no tiene luces que realzar.
 */
fun stockGainMap(sdr: IntArray, under: IntArray, w: Int, h: Int, ratio: Float, maxBoost: Float): ByteArray? {
    if (maxBoost <= 1.01f || ratio <= 1f || sdr.size != under.size) return null
    val lut = FloatArray(256) { toLinear(it / 255f) }
    val logMax = kotlin.math.ln(maxBoost) / kotlin.math.ln(2f)
    val lg = FloatArray(w * h)
    var peak = 0f
    for (i in 0 until w * h) {
        val ys = lumaLinear(sdr[i], lut)
        val p = sdr[i]
        val yGamma = (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) / (256f * 255f)
        // peso: 0 por debajo de 0.55 (gamma), 1 desde 0.9
        val t = ((yGamma - 0.55f) / 0.35f).coerceIn(0f, 1f)
        val wgt = t * t * (3f - 2f * t)
        if (wgt <= 0f) continue
        val yh = lumaLinear(under[i], lut) * ratio
        val g = yh / ys.coerceAtLeast(1e-4f)
        if (g <= 1f) continue
        val v = (kotlin.math.ln(g) / kotlin.math.ln(2f) * wgt).coerceAtMost(logMax)
        lg[i] = v
        if (v > peak) peak = v
    }
    if (peak < 0.11f) return null // menos de ~8 % de realce: no vale la pena
    val bl = binomial3(binomial3(lg, w, h), w, h)
    return ByteArray(w * h) { (bl[it] / logMax * 255f).roundToIntSafe().coerceIn(0, 255).toByte() }
}

private fun Float.roundToIntSafe(): Int = (this + 0.5f).toInt()
