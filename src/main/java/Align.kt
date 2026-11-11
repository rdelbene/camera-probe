package com.example.uhdrcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Alineación de ráfagas (sin dependencias de Android): desplazamiento global con bitmaps de umbral por mediana
 * y ajuste por zonas de ±2 pixeles sobre una imagen reducida.
 */

internal fun median(a: FloatArray): Float {
    val hist = IntArray(256)
    for (v in a) hist[(v * 255f).toInt().coerceIn(0, 255)]++
    var c = 0
    val half = a.size / 2
    for (i in 0..255) {
        c += hist[i]
        if (c >= half) return i / 255f
    }
    return 0.5f
}

internal fun mtb(a: FloatArray, th: Float) = ByteArray(a.size) { if (a[it] > th) 1 else 0 }

internal fun mtbMask(a: FloatArray, th: Float) = ByteArray(a.size) { if (abs(a[it] - th) > 0.02f) 1 else 0 }

/** Busca la traslación (dx, dy) que mejor alinea img con ref, con bitmaps de umbral por mediana. */
internal fun estimateShift(refB: ByteArray, refM: ByteArray, imgB: ByteArray, imgM: ByteArray, w: Int, h: Int): IntArray {
    val range = 10
    val step = 3
    var best = Int.MAX_VALUE
    var bx = 0
    var by = 0
    for (dy in -range..range) {
        for (dx in -range..range) {
            var err = 0
            var y = range
            while (y < h - range) {
                var x = range
                while (x < w - range) {
                    val i = y * w + x
                    val j = (y + dy) * w + (x + dx)
                    if (refM[i].toInt() == 1 && imgM[j].toInt() == 1 && refB[i] != imgB[j]) err++
                    x += step
                }
                y += step
            }
            if (err < best) { best = err; bx = dx; by = dy }
        }
    }
    return intArrayOf(bx, by)
}

/**
 * Alineación por zonas: parte del desplazamiento global y busca, en cada zona de 32x32 de la imagen reducida,
 * un ajuste de ±2 pixeles. Solo se acepta si mejora claramente; las zonas sin textura usan el global.
 */
internal fun tileShifts(ref: FloatArray, img: FloatArray, w: Int, h: Int, gdx: Int, gdy: Int): TileShifts {
    val tile = 32
    val ntx = (w + tile - 1) / tile
    val nty = (h + tile - 1) / tile
    val tdx = FloatArray(ntx * nty) { gdx.toFloat() }
    val tdy = FloatArray(ntx * nty) { gdy.toFloat() }
    var moved = 0
    for (ty in 0 until nty) for (tx in 0 until ntx) {
        val x0 = tx * tile; val x1 = min(x0 + tile, w)
        val y0 = ty * tile; val y1 = min(y0 + tile, h)
        var mean = 0f; var m2 = 0f; var cntT = 0
        var y = y0
        while (y < y1) { var x = x0; while (x < x1) { val v = ref[y * w + x]; mean += v; m2 += v * v; cntT++; x += 2 }; y += 2 }
        if (cntT == 0) continue
        mean /= cntT
        if (m2 / cntT - mean * mean < 2e-4f) continue // sin textura: queda el global
        fun sad(dx: Int, dy: Int): Float {
            var s = 0f; var c = 0
            var yy = y0
            while (yy < y1) {
                val sy = yy + dy
                if (sy in 0 until h) {
                    var xx = x0
                    while (xx < x1) {
                        val sx = xx + dx
                        if (sx in 0 until w) { s += abs(ref[yy * w + xx] - img[sy * w + sx]); c++ }
                        xx += 2
                    }
                }
                yy += 2
            }
            return if (c > cntT / 2) s / c else Float.MAX_VALUE
        }
        val base = sad(gdx, gdy)
        var best = base; var bx = gdx; var by = gdy
        for (ddy in -2..2) for (ddx in -2..2) {
            if (ddx == 0 && ddy == 0) continue
            val v = sad(gdx + ddx, gdy + ddy)
            if (v < best) { best = v; bx = gdx + ddx; by = gdy + ddy }
        }
        if (best < base * 0.97f) {
            tdx[ty * ntx + tx] = bx.toFloat(); tdy[ty * ntx + tx] = by.toFloat(); moved++
        }
    }
    return TileShifts(tile, ntx, nty, tdx, tdy, moved)
}

/** Interpola los desplazamientos de las zonas a bloques de 8x8 de la imagen completa. even: múltiplos de 2 (RAW). */
internal fun buildShiftMap(t: TileShifts, fw: Int, fh: Int, scale: Int, even: Boolean): ShiftMap {
    val bw = (fw + 7) / 8
    val bh = (fh + 7) / 8
    val dx = IntArray(bw * bh)
    val dy = IntArray(bw * bh)
    var mx = 0
    for (by in 0 until bh) for (bx in 0 until bw) {
        val cx = (bx * 8 + 4f) / scale / t.tile - 0.5f
        val cy = (by * 8 + 4f) / scale / t.tile - 0.5f
        val txc = cx.coerceIn(0f, (t.ntx - 1).toFloat())
        val tyc = cy.coerceIn(0f, (t.nty - 1).toFloat())
        val x0 = txc.toInt(); val x1 = min(x0 + 1, t.ntx - 1)
        val y0 = tyc.toInt(); val y1 = min(y0 + 1, t.nty - 1)
        val fx = txc - x0; val fy = tyc - y0
        fun lerp(a: FloatArray): Float {
            val top = a[y0 * t.ntx + x0] * (1 - fx) + a[y0 * t.ntx + x1] * fx
            val bot = a[y1 * t.ntx + x0] * (1 - fx) + a[y1 * t.ntx + x1] * fx
            return (top * (1 - fy) + bot * fy) * scale
        }
        val vx = lerp(t.dx); val vy = lerp(t.dy)
        val ix = if (even) (vx / 2f).roundToInt() * 2 else vx.roundToInt()
        val iy = if (even) (vy / 2f).roundToInt() * 2 else vy.roundToInt()
        dx[by * bw + bx] = ix; dy[by * bw + bx] = iy
        mx = max(mx, max(abs(ix), abs(iy)))
    }
    return ShiftMap(bw, dx, dy, mx)
}

class TileShifts(val tile: Int, val ntx: Int, val nty: Int, val dx: FloatArray, val dy: FloatArray, val moved: Int)

/** Desplazamiento por bloques de 8x8 pixeles a resolución completa. */
class ShiftMap(val bw: Int, val dx: IntArray, val dy: IntArray, val maxAbs: Int) {
    fun idx(x: Int, y: Int) = (y shr 3) * bw + (x shr 3)
}
