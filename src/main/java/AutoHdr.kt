package com.example.uhdrcam

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile

/*
 * Nivel HDR automático: cada foto nueva de la cámara (la stock de Motorola u otra) se ajusta sola al nivel
 * elegido, segundos después de guardarse. Android avisa cuando cambia la galería (JobScheduler con disparador
 * de contenido); el ajuste reescribe en el mismo archivo solo los ~20 bytes de la intensidad del Ultra HDR,
 * así que la imagen queda idéntica. Necesita el permiso "Acceso a todos los archivos" para escribir en
 * fotos que guardó otra app.
 */
object AutoHdr {
    private const val JOB_ID = 4931
    private const val PREFS = "cfg"

    fun isOn(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoOn", false)
    fun level(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("tuneBoost", 2.5f).toDouble()
    fun canWrite() = Environment.isExternalStorageManager()

    /** Prende o apaga. Al prender, solo cuentan las fotos que se saquen desde ahora. */
    fun setOn(ctx: Context, on: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("autoOn", on)
        if (on) p.putLong("autoLastId", maxId(ctx))
        p.apply()
        if (on) schedule(ctx) else ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    /** Se llama al abrir la app: si estaba prendido, se asegura de que el aviso siga programado. */
    fun ensure(ctx: Context) { if (isOn(ctx)) schedule(ctx) }

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoHdrJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(3000)
            .build()
        js.schedule(job)
    }

    private fun maxId(ctx: Context): Long {
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, "${MediaStore.Images.Media._ID} DESC")?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0L
    }

    /** Ajusta las fotos nuevas de DCIM/Camera. Devuelve cuántas cambió. */
    fun processNew(ctx: Context): Int {
        if (!isOn(ctx) || !canWrite()) return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var lastId = prefs.getLong("autoLastId", Long.MAX_VALUE)
        val target = level(ctx)
        var changed = 0
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.IS_PENDING,
            MediaStore.Images.Media.OWNER_PACKAGE_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.RELATIVE_PATH)
        val paths = mutableListOf<String>()
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media._ID} > ?", arrayOf(lastId.toString()), "${MediaStore.Images.Media._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(2) != 0) break // la cámara todavía lo está guardando: se vuelve a mirar en el próximo aviso
                val path = c.getString(1)
                val owner = c.getString(3)
                val mime = c.getString(4)
                val rel = c.getString(5) ?: ""
                val ours = owner == ctx.packageName // las de UHDR Cam ya salen con su propio nivel
                if (!ours && mime == "image/jpeg" && rel.startsWith("DCIM/") && path != null) {
                    try { if (adjustFile(File(path), target)) { changed++; paths += path } } catch (_: Exception) {}
                }
                lastId = id
            }
        }
        prefs.edit().putLong("autoLastId", lastId).apply()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
        if (changed > 0) prefs.edit().putInt("autoCount", prefs.getInt("autoCount", 0) + changed).apply()
        return changed
    }

    /** Reescribe en el lugar solo los bytes que cambian. */
    fun adjustFile(f: File, target: Double): Boolean {
        // esperar a que el archivo deje de crecer (la cámara puede seguir escribiendo)
        var size = f.length()
        for (k in 0 until 10) {
            Thread.sleep(300)
            val s2 = f.length()
            if (s2 == size && s2 > 0) break
            size = s2
        }
        val src = f.readBytes()
        val info = UltraHdrTuner.analyze(src) ?: return false
        if (kotlin.math.abs(info.maxBoost - target) < 0.02) return false
        val out = UltraHdrTuner.setMaxBoost(src, target) ?: return false
        if (out.size != src.size) return false
        RandomAccessFile(f, "rw").use { raf ->
            var i = 0
            while (i < src.size) {
                if (src[i] != out[i]) {
                    var j = i
                    while (j < src.size && src[j] != out[j]) j++
                    raf.seek(i.toLong()); raf.write(out, i, j - i)
                    i = j
                } else i++
            }
        }
        return true
    }
}

class AutoHdrJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { AutoHdr.processNew(applicationContext) } catch (_: Throwable) {}
            // el disparador de contenido sirve una sola vez: se vuelve a programar
            if (AutoHdr.isOn(applicationContext)) AutoHdr.schedule(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
