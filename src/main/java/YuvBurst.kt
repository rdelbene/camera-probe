package com.example.uhdrcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Ráfaga sobre la imagen ya procesada por el chip (como el modo NR de Open Camera): el chip hace exposición,
 * color y tono; la app alinea y fusiona varios cuadros para bajar el ruido sin el "lavado" de detalle que deja
 * la reducción de ruido fuerte de un solo cuadro. Sin dependencias de Android.
 */

/** Cuadro YUV 4:2:0 planar (Y completo, U y V a mitad de resolución), valores 0..255. */
class YuvFrame(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    val cw get() = (w + 1) / 2
    val ch get() = (h + 1) / 2
}

/** Luma reducida 4x4 (0..1) para alinear. */
fun yuvLumaCode(f: YuvFrame): Triple<FloatArray, Int, Int> {
    val sw = f.w / 4; val sh = f.h / 4
    val out = FloatArray(sw * sh)
    for (sy in 0 until sh) for (sx in 0 until sw) {
        var s = 0
        for (yy in 0 until 4) {
            val b = (sy * 4 + yy) * f.w + sx * 4
            for (xx in 0 until 4) s += f.y[b + xx].toInt() and 255
        }
        out[sy * sw + sx] = s / (16f * 255f)
    }
    return Triple(out, sw, sh)
}

/**
 * Fusión robusta: el primer cuadro es la referencia; cada pixel de los demás pesa según cuánto difiere de la
 * referencia en unidades del ruido medido en esa misma ráfaga (así no hace falta conocer el ruido del chip).
 */
class YuvMerger(val w: Int, val h: Int) {
    private val cw = (w + 1) / 2
    private val chh = (h + 1) / 2
    private var ref: YuvFrame? = null
    private var refCode: FloatArray? = null
    private var refBits: ByteArray? = null
    private var refMask: ByteArray? = null
    private var sw = 0
    private var sh = 0
    private val sumY = FloatArray(w * h)
    private val wY = FloatArray(w * h)
    private val sumU = FloatArray(cw * chh)
    private val sumV = FloatArray(cw * chh)
    private val wC = FloatArray(cw * chh)
    var frames = 0; private set
    var maxShift = 0; private set
    /** Desvío del ruido de un cuadro (0..255), medido comparando cuadros alineados. */
    var noiseSigma = 0f; private set
    private val sigmas = mutableListOf<Float>()

    fun add(f: YuvFrame) {
        require(f.w == w && f.h == h) { "cuadro de otro tamaño" }
        val (code, cw4, ch4) = yuvLumaCode(f)
        val r = ref
        if (r == null) {
            ref = f; refCode = code; sw = cw4; sh = ch4
            val th = median(code)
            refBits = mtb(code, th); refMask = mtbMask(code, th)
            for (i in 0 until w * h) { sumY[i] = (f.y[i].toInt() and 255).toFloat(); wY[i] = 1f }
            for (i in 0 until cw * chh) {
                sumU[i] = (f.u[i].toInt() and 255).toFloat(); sumV[i] = (f.v[i].toInt() and 255).toFloat(); wC[i] = 1f
            }
            frames = 1
            return
        }
        // alineación: global + por zonas
        val th = median(code)
        val g = estimateShift(refBits!!, refMask!!, mtb(code, th), mtbMask(code, th), sw, sh)
        val tiles = tileShifts(refCode!!, code, sw, sh, g[0], g[1])
        val sm = buildShiftMap(tiles, w, h, 4, false)
        refineShifts(sm, r, f)
        maxShift = max(maxShift, sm.dx.maxOf { abs(it) }.coerceAtLeast(sm.dy.maxOf { abs(it) }))

        // ruido: diferencia típica entre la referencia y este cuadro ya alineado (MAD robusta)
        val hist = IntArray(256)
        var n = 0
        var yy = 8
        while (yy < h - 8) {
            var xx = 8
            while (xx < w - 8) {
                val bi = sm.idx(xx, yy)
                val sx = xx + sm.dx[bi]; val sy = yy + sm.dy[bi]
                if (sx in 0 until w && sy in 0 until h) {
                    val d = abs((f.y[sy * w + sx].toInt() and 255) - (r.y[yy * w + xx].toInt() and 255))
                    hist[d.coerceAtMost(255)]++; n++
                }
                xx += 7
            }
            yy += 7
        }
        var acc = 0; var med = 0
        for (i in 0..255) { acc += hist[i]; if (acc >= n / 2) { med = i; break } }
        val sd = max(1.0f, 1.4826f * med.toFloat()) // desvío de la diferencia
        sigmas += sd / sqrt(2f)
        noiseSigma = sigmas.average().toFloat()
        val varDiff = sd * sd

        // pesos precalculados por diferencia absoluta (0..255)
        val wLut = FloatArray(256) { mergeWeight(it.toFloat(), varDiff, 9f) }
        val fy = f.y; val ry = r.y; val fu = f.u; val fv = f.v
        val bw = sm.bw
        for (y in 0 until h) {
            val row = y * w
            val brow = (y shr 3) * bw
            val evenRow = (y and 1) == 0
            val crow = (y shr 1) * cw
            var x = 0
            while (x < w) {
                val bi = brow + (x shr 3)
                val dx = sm.dx[bi]; val sy = y + sm.dy[bi]
                val xEnd = minOf(x + 8, w)
                if (sy < 0 || sy >= h) { x = xEnd; continue }
                val srow = sy * w
                val scrow = (sy shr 1) * cw
                while (x < xEnd) {
                    val sx = x + dx
                    if (sx in 0 until w) {
                        val v = fy[srow + sx].toInt() and 255
                        val d = v - (ry[row + x].toInt() and 255)
                        val wgt = wLut[if (d < 0) -d else d]
                        if (wgt > 0f) {
                            val i = row + x
                            sumY[i] += wgt * v; wY[i] += wgt
                            if (evenRow && (x and 1) == 0) {
                                val ci = crow + (x shr 1)
                                val sci = scrow + (sx shr 1)
                                sumU[ci] += wgt * (fu[sci].toInt() and 255)
                                sumV[ci] += wgt * (fv[sci].toInt() and 255)
                                wC[ci] += wgt
                            }
                        }
                    }
                    x++
                }
            }
        }
        frames++
    }

    /**
     * La alineación gruesa se hace sobre la imagen reducida 4x (precisión de 4 pixeles). Acá se ajusta cada
     * bloque de 32x32 a resolución completa buscando ±3 pixeles alrededor de la estimación gruesa.
     */
    private fun refineShifts(sm: ShiftMap, r: YuvFrame, f: YuvFrame) {
        val bw = sm.bw
        val bh = (h + 7) / 8
        val R = 3
        var gy = 0
        while (gy < bh) {
            var gx = 0
            while (gx < bw) {
                val x0 = gx * 8; val y0 = gy * 8
                val x1 = minOf(x0 + 32, w); val y1 = minOf(y0 + 32, h)
                val bi = gy * bw + gx
                val bdx = sm.dx[bi]; val bdy = sm.dy[bi]
                // textura del bloque: sin textura no hay con qué alinear
                var mean = 0f; var m2 = 0f; var cnt = 0
                var y = y0
                while (y < y1) { var x = x0; while (x < x1) { val v = (r.y[y * w + x].toInt() and 255).toFloat(); mean += v; m2 += v * v; cnt++; x += 2 }; y += 2 }
                if (cnt > 0 && m2 / cnt - (mean / cnt) * (mean / cnt) > 4f) {
                    var best = Long.MAX_VALUE; var bx = bdx; var by = bdy
                    for (ddy in -R..R) for (ddx in -R..R) {
                        val dx = bdx + ddx; val dy = bdy + ddy
                        var sad = 0L; var n = 0
                        var yy = y0
                        while (yy < y1) {
                            val sy = yy + dy
                            if (sy in 0 until h) {
                                var xx = x0
                                while (xx < x1) {
                                    val sx = xx + dx
                                    if (sx in 0 until w) {
                                        sad += abs((r.y[yy * w + xx].toInt() and 255) - (f.y[sy * w + sx].toInt() and 255)); n++
                                    }
                                    xx += 4
                                }
                            }
                            yy += 2
                        }
                        if (n > 0) {
                            val norm = sad * 1024 / n
                            if (norm < best) { best = norm; bx = dx; by = dy }
                        }
                    }
                    for (sy in gy until minOf(gy + 4, bh)) for (sx in gx until minOf(gx + 4, bw)) {
                        sm.dx[sy * bw + sx] = bx; sm.dy[sy * bw + sx] = by
                    }
                }
                gx += 4
            }
            gy += 4
        }
    }

    /** Resultado fusionado; el croma se suaviza un poco (el ruido de color es el que más se nota). */
    fun result(chromaSmooth: Boolean = true): YuvFrame {
        val yOut = ByteArray(w * h) { i -> (sumY[i] / wY[i] + 0.5f).toInt().coerceIn(0, 255).toByte() }
        var u = FloatArray(cw * chh) { sumU[it] / wC[it] }
        var v = FloatArray(cw * chh) { sumV[it] / wC[it] }
        if (chromaSmooth) { u = binomial3(u, cw, chh); v = binomial3(v, cw, chh) }
        return YuvFrame(w, h, yOut,
            ByteArray(cw * chh) { (u[it] + 0.5f).toInt().coerceIn(0, 255).toByte() },
            ByteArray(cw * chh) { (v[it] + 0.5f).toInt().coerceIn(0, 255).toByte() })
    }

    /** Cuántos cuadros aportaron en promedio (después de descartar lo que se movió). */
    fun effectiveFrames(): Float {
        var s = 0.0; var c = 0
        var i = 0
        while (i < wY.size) { s += wY[i]; c++; i += 97 }
        return (s / max(c, 1)).toFloat()
    }
}

/** YUV (JFIF, rango completo, BT.601) a ARGB. */
fun yuvToArgb(f: YuvFrame): IntArray {
    val out = IntArray(f.w * f.h)
    val cw = f.cw
    for (y in 0 until f.h) {
        val crow = (y shr 1) * cw
        for (x in 0 until f.w) {
            val yv = (f.y[y * f.w + x].toInt() and 255).toFloat()
            val u = (f.u[crow + (x shr 1)].toInt() and 255) - 128f
            val v = (f.v[crow + (x shr 1)].toInt() and 255) - 128f
            val r = (yv + 1.402f * v).toInt().coerceIn(0, 255)
            val g = (yv - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
            val b = (yv + 1.772f * u).toInt().coerceIn(0, 255)
            out[y * f.w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return out
}

/** ARGB reducido (cada `step` pixeles) de un cuadro YUV, para armar el gain map. */
fun yuvToArgbSmall(f: YuvFrame, step: Int): Triple<IntArray, Int, Int> {
    val sw = f.w / step; val sh = f.h / step
    val out = IntArray(sw * sh)
    for (sy in 0 until sh) for (sx in 0 until sw) {
        val x = sx * step; val y = sy * step
        val yv = (f.y[y * f.w + x].toInt() and 255).toFloat()
        val ci = (y shr 1) * f.cw + (x shr 1)
        val u = (f.u[ci].toInt() and 255) - 128f
        val v = (f.v[ci].toInt() and 255) - 128f
        val r = (yv + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (yv - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
        val b = (yv + 1.772f * u).toInt().coerceIn(0, 255)
        out[sy * sw + sx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Triple(out, sw, sh)
}

/** Reduce una imagen ARGB tomando un pixel cada `step`. */
fun argbSmall(px: IntArray, w: Int, h: Int, step: Int): Triple<IntArray, Int, Int> {
    val sw = w / step; val sh = h / step
    return Triple(IntArray(sw * sh) { i -> px[(i / sw) * step * w + (i % sw) * step] }, sw, sh)
}
