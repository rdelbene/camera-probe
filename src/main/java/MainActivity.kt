package com.example.uhdrcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.pow

/** Lentes de tu Edge 50 Ultra segun el diagnostico: fisicos 3 (UGA), 2 (principal), 5 (tele). */
enum class Lens(val label: String, val zoom: Float, val physicalId: String) {
    UGA("0.5x UGA", 0.5f, "3"),
    MAIN("1x Principal", 1.0f, "2"),
    TELE("3x Tele", 3.0f, "5")
}

/** Ajustes guardados por lente. exp en pasos de 1/6 EV (-36..36). nr/edge: 0=off 1=rapido 2=alta. hdr: % */
class Profile(var exp: Int = 0, var nr: Int = 2, var edge: Int = 2, var hdr: Int = 100)

class MainActivity : Activity() {

    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var expBar: SeekBar
    private lateinit var expLabel: TextView
    private lateinit var nrGroup: RadioGroup
    private lateinit var edgeGroup: RadioGroup
    private lateinit var hdrBar: SeekBar
    private lateinit var hdrLabel: TextView
    private lateinit var prefs: SharedPreferences

    private var lens = Lens.MAIN
    private var physicalMode = false
    private var suppress = false
    private val profiles = mutableMapOf<Lens, Profile>()

    private val camThread = HandlerThread("cam").apply { start() }
    private val camHandler by lazy { Handler(camThread.looper) }
    private val executor = Executor { camHandler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation = 90

    private var pendingLens = Lens.MAIN
    private var pendingHdr = 100

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Lens.values().forEach { l ->
            profiles[l] = Profile(
                prefs.getInt("${l.name}_exp", 0),
                prefs.getInt("${l.name}_nr", 2),
                prefs.getInt("${l.name}_edge", 2),
                prefs.getInt("${l.name}_hdr", 100)
            )
        }
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) openCamera()
        else say("Sin permiso de cámara")
    }

    // ------------------------------------------------------------------ interfaz

    private fun prof() = profiles.getValue(lens)

    private fun save() {
        val e = prefs.edit()
        profiles.forEach { (l, p) ->
            e.putInt("${l.name}_exp", p.exp).putInt("${l.name}_nr", p.nr)
                .putInt("${l.name}_edge", p.edge).putInt("${l.name}_hdr", p.hdr)
        }
        e.apply()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(24, 20, 24, 4)
    }

    private fun seek(max: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max
        setPadding(48, 8, 48, 8)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun modeGroup(onPick: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        setPadding(24, 0, 24, 0)
        listOf("Off", "Rápido", "Alta").forEachIndexed { i, t ->
            addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = t
                tag = i
            })
        }
        setOnCheckedChangeListener { g, id ->
            if (!suppress) {
                val b = g.findViewById<RadioButton>(id)
                if (b != null) onPick(b.tag as Int)
            }
        }
    }

    private fun buildUi() {
        val w = resources.displayMetrics.widthPixels
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 64)
        }

        texture = TextureView(this)
        root.addView(texture, LinearLayout.LayoutParams(w, w * 4 / 3))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        root.addView(Button(this).apply {
            text = "FOTO  (Ultra HDR)"
            textSize = 18f
            setOnClickListener { takePhoto() }
        })

        status = TextView(this).apply { textSize = 12f; setPadding(24, 8, 24, 8) }
        root.addView(status)

        // Selector de lente
        root.addView(label("Lente"))
        val lensGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(24, 0, 24, 0)
        }
        Lens.values().forEach { l ->
            lensGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = l.label
                tag = l
            })
        }
        (lensGroup.getChildAt(Lens.MAIN.ordinal) as RadioButton).isChecked = true
        lensGroup.setOnCheckedChangeListener { g, id ->
            val b = g.findViewById<RadioButton>(id)
            if (b != null) selectLens(b.tag as Lens)
        }
        root.addView(lensGroup)

        root.addView(Switch(this).apply {
            text = "Lente físico directo (experimental)"
            setPadding(24, 8, 24, 8)
            setOnCheckedChangeListener { _, checked ->
                physicalMode = checked
                if (device != null) startSession()
            }
        })

        // Exposicion
        expLabel = label("")
        root.addView(expLabel)
        expBar = seek(72) { p ->
            prof().exp = p - 36
            expLabel.text = "Exposición: %+.1f EV".format(prof().exp / 6f)
            save(); updatePreview()
        }
        root.addView(expBar)

        // Reduccion de ruido y nitidez
        root.addView(label("Reducción de ruido"))
        nrGroup = modeGroup { prof().nr = it; save(); updatePreview() }
        root.addView(nrGroup)

        root.addView(label("Nitidez (edge)"))
        edgeGroup = modeGroup { prof().edge = it; save(); updatePreview() }
        root.addView(edgeGroup)

        // Intensidad del gain map
        hdrLabel = label("")
        root.addView(hdrLabel)
        hdrBar = seek(150) { p ->
            prof().hdr = p
            hdrLabel.text = "Intensidad Ultra HDR (post-captura): $p%"
            save()
        }
        root.addView(hdrBar)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshControls()
    }

    private fun refreshControls() {
        val p = prof()
        suppress = true
        expBar.progress = p.exp + 36
        expLabel.text = "Exposición: %+.1f EV".format(p.exp / 6f)
        (nrGroup.getChildAt(p.nr) as RadioButton).isChecked = true
        (edgeGroup.getChildAt(p.edge) as RadioButton).isChecked = true
        hdrBar.progress = p.hdr
        hdrLabel.text = "Intensidad Ultra HDR (post-captura): ${p.hdr}%"
        suppress = false
    }

    private fun selectLens(l: Lens) {
        lens = l
        refreshControls()
        if (physicalMode) startSession() else updatePreview()
    }

    private fun say(msg: String) = runOnUiThread { status.text = msg }

    // ------------------------------------------------------------------ cámara

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (!texture.isAvailable || device != null) return
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            sensorOrientation = mgr.getCameraCharacteristics("0")
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            mgr.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession() }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null; say("Error de cámara: $error")
                }
            }, camHandler)
        } catch (e: Exception) {
            say("No abre la cámara: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    private fun startSession() {
        val d = device ?: return
        try {
            session?.close(); session = null
            reader?.close(); reader = null

            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(if (physicalMode) lens.physicalId else "0")
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            val jpegSize = map.getOutputSizes(ImageFormat.JPEG_R)
                .filter { it.width * 3 == it.height * 4 }
                .maxByOrNull { it.width * it.height }!!
            val prevSize = map.getOutputSizes(SurfaceTexture::class.java)
                .filter { it.width * 3 == it.height * 4 && it.width <= 1440 }
                .maxByOrNull { it.width * it.height }!!

            val st = texture.surfaceTexture!!
            st.setDefaultBufferSize(prevSize.width, prevSize.height)
            val pSurface = Surface(st)
            previewSurface = pSurface

            val r = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG_R, 2)
            r.setOnImageAvailableListener({ rd -> onPhoto(rd) }, camHandler)
            reader = r

            val outs = listOf(pSurface, r.surface).map { s ->
                OutputConfiguration(s).also {
                    if (physicalMode) it.setPhysicalCameraId(lens.physicalId)
                }
            }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        updatePreview()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        say("No se pudo configurar (${if (physicalMode) "modo físico" else "modo lógico"})")
                    }
                }
            )
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            say("Sesión: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun buildRequest(template: Int, target: Surface): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        val p = prof()
        b.addTarget(target)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.exp)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, p.nr)
        b.set(CaptureRequest.EDGE_MODE, p.edge)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (physicalMode) 1f else lens.zoom)
        return b
    }

    private fun updatePreview() {
        val s = session ?: return
        val surf = previewSurface ?: return
        try {
            s.setRepeatingRequest(buildRequest(CameraDevice.TEMPLATE_PREVIEW, surf).build(), null, camHandler)
            say("${lens.label} · ${if (physicalMode) "físico ${lens.physicalId}" else "lógico"}")
        } catch (e: Exception) {
            say("Preview: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // ------------------------------------------------------------------ captura y guardado

    private fun takePhoto() {
        val s = session ?: return say("Cámara no lista")
        val r = reader ?: return say("Cámara no lista")
        try {
            pendingLens = lens
            pendingHdr = prof().hdr
            val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, r.surface)
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            s.capture(b.build(), null, camHandler)
            say("Capturando...")
        } catch (e: Exception) {
            say("Captura: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun onPhoto(rd: ImageReader) {
        val img = rd.acquireNextImage() ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "UHDR_${stamp}_${pendingLens.name}"
        saveFile(bytes, "$name.jpg")

        val hdr = pendingHdr
        if (hdr == 100) {
            say("Guardada: $name.jpg")
            return
        }
        val adjusted = try { adjustGainmap(bytes, hdr / 100f) } catch (e: Throwable) { null }
        if (adjusted != null) {
            saveFile(adjusted, "${name}_hdr$hdr.jpg")
            say("Guardada original + versión con HDR al $hdr%")
        } else {
            say("Guardada la original. El ajuste del gain map falló en este equipo.")
        }
    }

    /**
     * Escala el efecto HDR: multiplica en escala logaritmica los ratios min/max del gain map.
     * k=0 -> sin HDR, k=1 -> igual a la original, k>1 -> mas fuerte.
     * Devuelve null si algo no se pudo (la original ya esta guardada).
     */
    private fun adjustGainmap(src: ByteArray, k: Float): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
        val gm = bmp.gainmap ?: return null

        val mx = gm.ratioMax
        val mn = gm.ratioMin
        gm.setRatioMax(mx[0].pow(k), mx[1].pow(k), mx[2].pow(k))
        gm.setRatioMin(mn[0].pow(k), mn[1].pow(k), mn[2].pow(k))
        bmp.setGainmap(gm)

        // BitmapFactory ignora el EXIF: si quedó apaisada, la giramos nosotros.
        val needsRotation = sensorOrientation % 180 != 0 && bmp.width > bmp.height
        val out = if (needsRotation) {
            val m = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } else bmp
        if (!out.hasGainmap()) return null

        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 96, bos)
        val result = bos.toByteArray()

        // Verificamos que el resultado siga siendo Ultra HDR
        val check = BitmapFactory.decodeByteArray(result, 0, result.size)
        return if (check != null && check.hasGainmap()) result else null
    }

    private fun saveFile(bytes: ByteArray, name: String) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UHDRCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}
