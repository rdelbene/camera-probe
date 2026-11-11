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

// ---------------------------------------------------------------------- mapeo de tonos local (estilo HDR+)

/** Promedio en una ventana (2r+1)² con imagen integral, O(N). */
fun boxMean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val ii = DoubleArray((w + 1) * (h + 1))
    for (y in 0 until h) {
        var row = 0.0
        for (x in 0 until w) {
            row += src[y * w + x]
            ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
        }
    }
    val out = FloatArray(w * h)
    for (y in 0 until h) {
        val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
        for (x in 0 until w) {
            val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
            val s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
            out[y * w + x] = (s / ((y1 - y0) * (x1 - x0))).toFloat()
        }
    }
    return out
}

private fun srgbToLinF(c: Float): Float = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
private fun linToSrgbF(l: Float): Float { val x = l.coerceIn(0f, 1f); return if (x <= 0.0031308f) x * 12.92f else 1.055f * Math.pow(x.toDouble(), 1 / 2.4).toFloat() - 0.055f }

/**
 * Mapeo de tonos local sobre la luma (0..255, gamma sRGB), en el lugar. Separa "iluminación" (base, filtro guiado
 * sobre log-luminancia: respeta bordes y no deja halos) y "detalle"; comprime la base hacia las luces para subir
 * las sombras sin tocar las zonas claras, y vuelve a sumar el detalle. Devuelve la ganancia media de las sombras
 * (en pasos) para saber cuánto se amplificó el ruido.
 *  rangeTarget: rango de iluminación (en pasos) al que se comprime la escena; strength 0..1;
 *  maxLift: lo máximo que se sube una zona (en pasos), con tope suave.
 */
fun localToneMap(y: FloatArray, w: Int, h: Int, strength: Float = 1f, rangeTarget: Float = 3.2f, maxLift: Float = 1.8f, exposureComp: Float = 0f, noiseSigma: Float = 0f): Float {
    if (strength <= 0f) return 0f
    val n = w * h
    val lut = FloatArray(4096) { i -> srgbToLinF(i / 4095f) }
    // log2 de la luminancia lineal, con la compensación de la subexposición de la ráfaga (hombro suave en las luces)
    val ecGain = Math.pow(2.0, exposureComp.toDouble()).toFloat()
    // tabla: luma (0..255 en pasos de 1/64) -> log2 de la luminancia lineal compensada
    val logLut = FloatArray(16385) { i ->
        var lin = srgbToLinF(i / 16384f) * ecGain
        if (lin > 0.7f) lin = 0.7f + 0.3f * (1f - kotlin.math.exp(-(lin - 0.7f) / 0.3f))
        (Math.log((lin + 1e-4).toDouble()) / Math.log(2.0)).toFloat()
    }
    val l = FloatArray(n) { i -> logLut[(y[i] * (16384f / 255f) + 0.5f).toInt().coerceIn(0, 16384)] }
    // tabla de salida: log2 lineal (-14..1, pasos de 1/512) -> luma 0..255 en gamma sRGB
    val outLut = FloatArray(15 * 512 + 1) { i -> linToSrgbF(Math.pow(2.0, -14.0 + i / 512.0).toFloat()) * 255f }
    // filtro guiado rápido: coeficientes a resolución 1/4
    val s = 4
    val ws = (w + s - 1) / s; val hs = (h + s - 1) / s
    val small = FloatArray(ws * hs)
    for (yy in 0 until hs) for (xx in 0 until ws) {
        var acc = 0f; var c = 0
        for (dy in 0 until s) { val py = yy * s + dy; if (py >= h) break
            for (dx in 0 until s) { val px = xx * s + dx; if (px >= w) break; acc += l[py * w + px]; c++ } }
        small[yy * ws + xx] = acc / c
    }
    val r = maxOf(4, minOf(ws, hs) / 16)
    val eps = 0.35f // en pasos²: bordes de más de ~0.6 paso se respetan
    val mI = boxMean(small, ws, hs, r)
    val sq = FloatArray(ws * hs) { small[it] * small[it] }
    val mII = boxMean(sq, ws, hs, r)
    val a = FloatArray(ws * hs); val b = FloatArray(ws * hs)
    for (i in 0 until ws * hs) {
        val v = mII[i] - mI[i] * mI[i]
        a[i] = v / (v + eps); b[i] = mI[i] - a[i] * mI[i]
    }
    val ma = boxMean(a, ws, hs, r); val mb = boxMean(b, ws, hs, r)
    // percentiles de la base (iluminación)
    val baseSmall = FloatArray(ws * hs) { ma[it] * small[it] + mb[it] }
    val sorted = baseSmall.copyOf(); sorted.sort()
    val hi = sorted[(sorted.size * 0.995).toInt().coerceAtMost(sorted.size - 1)]
    val lo = sorted[(sorted.size * 0.02).toInt()]
    val range = (hi - lo).coerceAtLeast(0.1f)
    val c = (1f - strength * (1f - (rangeTarget / range).coerceIn(0.45f, 1f)))
    // aplicar a resolución completa (a y b interpolados); se guarda la suba de cada pixel (pasos × 64)
    val gainQ = if (noiseSigma > 0f) ByteArray(n) else null
    var liftAcc = 0.0; var liftN = 0
    for (yy in 0 until h) {
        val fy = ((yy + 0.5f) / s - 0.5f).coerceIn(0f, (hs - 1).toFloat())
        val y0 = fy.toInt().coerceAtMost(hs - 1); val y1 = minOf(y0 + 1, hs - 1); val ty = fy - y0
        for (xx in 0 until w) {
            val fx = ((xx + 0.5f) / s - 0.5f).coerceIn(0f, (ws - 1).toFloat())
            val x0 = fx.toInt().coerceAtMost(ws - 1); val x1 = minOf(x0 + 1, ws - 1); val tx = fx - x0
            fun bil(arr: FloatArray): Float {
                val t = arr[y0 * ws + x0] * (1 - tx) + arr[y0 * ws + x1] * tx
                val u = arr[y1 * ws + x0] * (1 - tx) + arr[y1 * ws + x1] * tx
                return t * (1 - ty) + u * ty
            }
            val i = yy * w + xx
            val base = bil(ma) * l[i] + bil(mb)
            val newBase = hi + (base - hi) * c
            // solo sube (nunca baja las luces) y con tope suave: las zonas muy oscuras no se vuelven grises
            val raw = (newBase - base).coerceAtLeast(0f)
            val gain = maxLift * kotlin.math.tanh(raw / maxLift)
            if (base < lo + range * 0.4f) { liftAcc += gain; liftN++ }
            if (gainQ != null) gainQ[i] = (gain * 64f).toInt().coerceIn(0, 127).toByte()
            y[i] = outLut[(((l[i] + gain) + 14f) * 512f).toInt().coerceIn(0, outLut.size - 1)]
        }
    }
    if (gainQ != null) shadowDenoise(y, w, h, gainQ, noiseSigma)
    return if (liftN > 0) (liftAcc / liftN).toFloat() else 0f
}

/**
 * Limpieza de ruido proporcional a cuánto se subió cada zona: al levantar las sombras también se levanta su
 * ruido, así que el umbral crece con la suba (encogimiento suave del detalle fino respecto de un 3x3 binomial).
 */
private fun shadowDenoise(y: FloatArray, w: Int, h: Int, gainQ: ByteArray, sigma: Float) {
    val tLut = FloatArray(128) { q -> val t = 2f * sigma * Math.pow(2.0, 0.7 * q / 64.0).toFloat(); t * t }
    val prev = FloatArray(w); val cur = FloatArray(w); val next = FloatArray(w)
    System.arraycopy(y, 0, cur, 0, w)
    System.arraycopy(y, if (h > 1) w else 0, prev, 0, w) // espejo arriba
    for (r in 0 until h) {
        System.arraycopy(y, (if (r + 1 < h) r + 1 else r - 1).coerceAtLeast(0) * w, next, 0, w)
        val base = r * w
        for (x in 0 until w) {
            val xm = if (x > 0) x - 1 else 1; val xp = if (x < w - 1) x + 1 else w - 2
            val b = (prev[xm] + 2f * prev[x] + prev[xp] + 2f * (cur[xm] + cur[xp]) + 4f * cur[x] + next[xm] + 2f * next[x] + next[xp]) * 0.0625f
            val d = cur[x] - b
            val t2 = tLut[gainQ[base + x].toInt()]
            y[base + x] = b + d * (d * d / (d * d + t2))
        }
        System.arraycopy(cur, 0, prev, 0, w); System.arraycopy(next, 0, cur, 0, w)
    }
}


/** Resultado de la fusión en planos: luma en float (0..255) y croma (suavizada) en bytes. */
fun YuvMerger.resultPlanes(): Triple<FloatArray, ByteArray, ByteArray> {
    val r = result(true)
    return Triple(FloatArray(r.w * r.h) { (r.y[it].toInt() and 255).toFloat() }, r.u, r.v)
}

/**
 * Mapeo de tonos local + conversión a ARGB. El croma se escala con la luma para que el color no se apague
 * en las sombras levantadas. Devuelve los pixeles y cuánto se subieron las sombras (pasos).
 */
fun toneMapToArgb(y: FloatArray, u: ByteArray, v: ByteArray, w: Int, h: Int, exposureComp: Float, maxLift: Float, noiseSigma: Float = 0f): Pair<IntArray, Float> {
    val old = y.copyOf()
    val lift = localToneMap(y, w, h, 1f, 3.2f, maxLift, exposureComp, noiseSigma)
    val cw = (w + 1) / 2
    val out = IntArray(w * h)
    val kLut = FloatArray(1025) { Math.pow(it / 256.0, 0.6).toFloat().coerceIn(0.5f, 4f) } // relación 0..4
    for (yy in 0 until h) {
        val crow = (yy shr 1) * cw
        for (xx in 0 until w) {
            val i = yy * w + xx
            val yn = y[i]
            val k = if (old[i] > 1f) kLut[((yn / old[i]) * 256f).toInt().coerceIn(0, 1024)] else 1f
            val uu = ((u[crow + (xx shr 1)].toInt() and 255) - 128f) * k
            val vv = ((v[crow + (xx shr 1)].toInt() and 255) - 128f) * k
            val r = (yn + 1.402f * vv).toInt().coerceIn(0, 255)
            val g = (yn - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
            val b = (yn + 1.772f * uu).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return out to lift
}


/** Planos para el revelado a partir de un cuadro (por ejemplo, el que devuelve el reproceso del chip). */
fun YuvFrame.planes(): Triple<FloatArray, ByteArray, ByteArray> =
    Triple(FloatArray(w * h) { (y[it].toInt() and 255).toFloat() }, u, v)


/** Mezcla dos cuadros del mismo tamaño: a + alfa·(b − a). */
fun blendYuv(a: YuvFrame, b: YuvFrame, alpha: Float): YuvFrame {
    fun mix(x: ByteArray, z: ByteArray) = ByteArray(x.size) { i ->
        val p = (x[i].toInt() and 255).toFloat(); val q = (z[i].toInt() and 255).toFloat()
        (p + alpha * (q - p) + 0.5f).toInt().coerceIn(0, 255).toByte()
    }
    return YuvFrame(a.w, a.h, mix(a.y, b.y), mix(a.u, b.u), mix(a.v, b.v))
}
