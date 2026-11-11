package com.example.uhdrcam

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * "Nivel HDR": ajusta la intensidad del Ultra HDR de fotos ya sacadas con la cámara stock,
 * sin tocar la imagen (solo los números del gain map, ver UltraHdrTuner).
 */
class HdrTunerActivity : Activity() {

    private val STOCK_BOOST = 4.93
    private val REQ_PERM = 10
    private val REQ_WRITE = 11

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<Long, Bitmap>(240)

    private val items = mutableListOf<Uri>()
    private val selected = LinkedHashSet<Uri>()
    private var sharedMode = false
    private var target = 2.5
    private var pendingReplace: List<Uri> = emptyList()

    private lateinit var levelLabel: TextView
    private lateinit var status: TextView
    private lateinit var grid: GridView
    private lateinit var btnReplace: TextView
    private lateinit var btnCopy: TextView
    private val adapter = ThumbAdapter()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        target = prefs.getFloat("tuneBoost", 2.5f).toDouble()
        buildUi()

        // ¿Vino desde "Compartir" en la galería?
        val shared = sharedUris(intent)
        if (shared.isNotEmpty()) {
            sharedMode = true
            items += shared
            selected += shared
            btnReplace.visibility = View.GONE
            status.text = "${shared.size} foto(s) compartida(s). Las fotos compartidas se guardan como copia."
            adapter.notifyDataSetChanged()
            updateButtons()
        } else if (hasReadPermission()) {
            loadRecent()
        } else {
            requestPermissions(readPermissions(), REQ_PERM)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaz

    private fun pillButton(text: String, fill: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(fill) }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(36), dp(12), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Nivel HDR"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Elegí fotos de la cámara de Motorola y cuánta luz extra querés que agregue el Ultra HDR. " +
                "La imagen no se toca: solo cambia la intensidad, y se puede volver a cambiar cuando quieras."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(10))
        })
        levelLabel = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        root.addView(levelLabel)
        // 100..493 -> ×1.00..×4.93
        root.addView(SeekBar(this).apply {
            max = 393
            progress = ((target - 1.0) * 100).roundToInt().coerceIn(0, 393)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { target = 1.0 + p / 100.0; updateLevel() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putFloat("tuneBoost", target.toFloat()).apply()
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateLevel()

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(0, 224, 150))
            setPadding(0, dp(6), 0, dp(6))
            text = "Tocá las fotos para elegirlas."
        }
        root.addView(status)

        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            adapter = this@HdrTunerActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val u = items[pos]
                if (!selected.remove(u)) selected += u
                this@HdrTunerActivity.adapter.notifyDataSetChanged()
                updateButtons()
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        btnCopy = pillButton("Guardar copia", Color.argb(255, 50, 50, 50), Color.WHITE) { apply(false) }
        btnReplace = pillButton("Reemplazar", Color.rgb(0, 224, 150), Color.BLACK) { apply(true) }
        row.addView(btnCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(btnReplace, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(row)
        setContentView(root)
        updateButtons()
    }

    private fun updateLevel() {
        val stops = Math.log(target) / Math.log(2.0)
        levelLabel.text = "Luces hasta ×%.2f  (%.1f pasos) · stock ×%.2f".format(target, stops, STOCK_BOOST) +
            when {
                target < 1.05 -> " · sin HDR"
                target >= STOCK_BOOST - 0.01 -> " · igual que la stock"
                else -> ""
            }
    }

    private fun updateButtons() {
        val n = selected.size
        btnCopy.alpha = if (n > 0) 1f else 0.4f
        btnReplace.alpha = if (n > 0) 1f else 0.4f
        btnReplace.text = if (n > 0) "Reemplazar ($n)" else "Reemplazar"
        btnCopy.text = if (n > 0) "Guardar copia ($n)" else "Guardar copia"
    }

    private inner class ThumbAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val size = (grid.width - dp(8)) / 3
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@HdrTunerActivity).apply {
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "img" },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(TextView(context).apply {
                    tag = "chk"; text = "✓"; gravity = Gravity.CENTER; textSize = 16f
                    setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0, 224, 150)) }
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            cell.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (size > 0) size else dp(110))
            val uri = items[position]
            val img = cell.findViewWithTag<ImageView>("img")
            val chk = cell.findViewWithTag<TextView>("chk")
            val sel = uri in selected
            chk.visibility = if (sel) View.VISIBLE else View.GONE
            img.alpha = if (sel) 0.7f else 1f
            val key = uri.hashCode().toLong()
            img.tag = "img"
            img.setImageBitmap(thumbs.get(key))
            if (thumbs.get(key) == null) {
                img.setTag(R_TAG_URI, uri)
                io.execute {
                    val bmp = try { contentResolver.loadThumbnail(uri, Size(300, 300), null) } catch (_: Exception) { null }
                    if (bmp != null) {
                        thumbs.put(key, bmp)
                        main.post { if (img.getTag(R_TAG_URI) == uri) img.setImageBitmap(bmp) }
                    }
                }
            }
            return cell
        }
    }

    // ------------------------------------------------------------------ fotos

    private fun readPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 34)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else arrayOf(Manifest.permission.READ_MEDIA_IMAGES)

    private fun hasReadPermission() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasReadPermission()) loadRecent() else status.text = "Sin permiso para ver las fotos."
    }

    /** Últimas fotos JPEG de DCIM/Camera (las de la cámara stock y las nuestras). */
    private fun loadRecent() {
        io.execute {
            val list = mutableListOf<Uri>()
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?",
                    arrayOf("DCIM/Camera%", "image/jpeg"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext() && list.size < 150) {
                        list += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    }
                }
            } catch (e: Exception) {
                main.post { status.text = "No pude leer la galería: ${e.message}" }
            }
            main.post {
                items.clear(); items += list
                adapter.notifyDataSetChanged()
                status.text = if (list.isEmpty()) "No encontré fotos en DCIM/Camera." else "Tocá las fotos para elegirlas (${list.size} recientes)."
            }
        }
    }

    private fun sharedUris(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        return when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ aplicar

    private fun apply(replace: Boolean) {
        val list = selected.toList()
        if (list.isEmpty()) { status.text = "Primero elegí una o más fotos."; return }
        if (replace) {
            // Android pide confirmación para modificar fotos de otra app
            try {
                val pi = MediaStore.createWriteRequest(contentResolver, list)
                pendingReplace = list
                startIntentSenderForResult(pi.intentSender, REQ_WRITE, null, 0, 0, 0)
            } catch (e: Exception) {
                status.text = "No se pudo pedir permiso para reemplazar: ${e.message}"
            }
        } else {
            process(list, false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WRITE) {
            if (resultCode == RESULT_OK) process(pendingReplace, true)
            else status.text = "No se reemplazó nada (permiso cancelado)."
        }
    }

    private fun process(list: List<Uri>, replace: Boolean) {
        val t = target
        status.text = "Procesando ${list.size} foto(s)..."
        io.execute {
            var ok = 0; var noHdr = 0; var fail = 0
            var before = 0.0
            for (u in list) {
                try {
                    val src = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IllegalStateException("sin datos")
                    val info = UltraHdrTuner.analyze(src)
                    if (info == null) { noHdr++; continue }
                    before = info.maxBoost
                    val out = UltraHdrTuner.setMaxBoost(src, t) ?: run { noHdr++; null } ?: continue
                    if (replace) {
                        contentResolver.openOutputStream(u, "wt")?.use { it.write(out) } ?: throw IllegalStateException("no se pudo escribir")
                    } else {
                        saveCopy(u, out, t)
                    }
                    ok++
                } catch (e: Exception) {
                    fail++
                }
            }
            main.post {
                status.text = buildString {
                    append(if (replace) "Reemplazadas: $ok" else "Copias guardadas: $ok")
                    if (ok > 0 && before > 0) append(" (antes ×%.2f, ahora ×%.2f)".format(before, t))
                    if (noHdr > 0) append(" · sin Ultra HDR: $noHdr")
                    if (fail > 0) append(" · con error: $fail")
                    if (replace && ok > 0) append("\nSi la galería muestra la versión vieja, cerrala y volvé a abrirla.")
                }
                if (!sharedMode) { selected.clear(); adapter.notifyDataSetChanged(); updateButtons() }
                if (!replace && ok > 0 && !sharedMode) loadRecent()
            }
        }
    }

    private fun saveCopy(src: Uri, bytes: ByteArray, t: Double) {
        var name = "IMG_HDR"
        var taken = System.currentTimeMillis()
        try {
            contentResolver.query(src, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it.substringBeforeLast('.') }
                    if (!c.isNull(1)) taken = c.getLong(1)
                }
            }
        } catch (_: Exception) {}
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${name}_HDRx%.1f.jpg".format(t))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Images.Media.DATE_TAKEN, taken)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("no se pudo crear la copia")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    companion object {
        /** Clave para guardar en la vista la foto que está cargando (cualquier id de recurso único sirve). */
        private const val R_TAG_URI = 0x7f0f0001
    }
}
