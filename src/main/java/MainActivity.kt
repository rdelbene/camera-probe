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
import android.graphics.Gainmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Lentes de tu Edge 50 Ultra segun el diagnostico: fisicos 3 (UGA), 2 (principal), 5 (tele). */
enum class Lens(val label: String, val zoom: Float, val physicalId: String) {
    UGA("0.5x UGA", 0.5f, "3"),
    MAIN("1x Principal", 1.0f, "2"),
    TELE("3x Tele", 3.0f, "5")
}

/**
 * Ajustes por lente. exp en pasos de 1/6 EV (-36..36).
 * nr/edge: 0=Auto (no se toca, queda el criterio del fabricante) 1=off 2=rapido 3=alta.
 * hdr: intensidad del Ultra HDR en %.
 */
class Profile(var exp: Int = 0, var nr: Int = 0, var edge: Int = 0, var hdr: Int = 100)

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
    private var burstN = 3
    private var fusionMode = false
    private var vMfnr = false
    private var vMfhdr = false
    private var vShdr = false
    private var vZsl = false
    private var vMoto = false
    private var vendorKeys: Set<String> = emptySet()
    private var vendorLog = ""
    private var suppress = false

    // Opción experimental recién activada: si la cámara se cae antes de estabilizarse, se revierte
    private var riskySwitch: Switch? = null
    private var riskyName = ""
    private var riskyRevert: (() -> Unit)? = null
    @Volatile private var okFrames = 0
    private val profiles = mutableMapOf<Lens, Profile>()

    private val camThread = HandlerThread("cam").apply { start() }
    private val camHandler by lazy { Handler(camThread.looper) }
    private val executor = Executor { camHandler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation = 90

    // Capacidades del lente activo
    private var expMin = 1_000L
    private var expMax = 1_000_000_000L
    private var isoMin = 50
    private var isoMax = 3200

    // Ultima exposicion medida por el visor (para armar la ráfaga)
    @Volatile private var lastT = 0L
    @Volatile private var lastIso = 0

    private var pendingLens = Lens.MAIN
    private var pendingHdr = 100

    // Estado de la fusión
    private val fuseLock = Any()
    private val fuseImgs = arrayOfNulls<ByteArray>(5)
    private val fuseP = DoubleArray(5)
    private var fuseTotal = 3
    private var fuseImgCount = 0
    private var fuseResCount = 0

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Lens.values().forEach { l ->
            profiles[l] = Profile(
                prefs.getInt("${l.name}_exp", 0),
                prefs.getInt("${l.name}_nr2", 0),
                prefs.getInt("${l.name}_edge2", 0),
                prefs.getInt("${l.name}_hdr2", 100)
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
            e.putInt("${l.name}_exp", p.exp).putInt("${l.name}_nr2", p.nr)
                .putInt("${l.name}_edge2", p.edge).putInt("${l.name}_hdr2", p.hdr)
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
        listOf("Auto", "Off", "Rápido", "Alta").forEachIndexed { i, t ->
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

        // Modos de captura
        root.addView(Switch(this).apply {
            text = "Fusión HDR por ráfaga"
            setPadding(24, 12, 24, 8)
            setOnCheckedChangeListener { _, checked ->
                fusionMode = checked
                if (device != null) startSession()
            }
        })
        root.addView(Switch(this).apply {
            text = "Ráfaga de 5 fotos (más suave, tarda más)"
            setPadding(24, 8, 24, 8)
            setOnCheckedChangeListener { _, checked -> burstN = if (checked) 5 else 3 }
        })
        root.addView(label("Motor propio Qualcomm / Motorola (experimental)"))
        fun vendorSwitch(t: String, onSet: (Boolean) -> Unit) = root.addView(Switch(this).apply {
            text = t
            setPadding(24, 8, 24, 8)
            setOnCheckedChangeListener { sw, checked ->
                if (suppress) return@setOnCheckedChangeListener
                onSet(checked)
                if (checked) {
                    riskySwitch = sw as Switch
                    riskyName = t
                    riskyRevert = { onSet(false) }
                    okFrames = 0
                }
                if (device != null) startSession() else openCamera()
            }
        })
        vendorSwitch("MFNR: ruido multi-frame") { vMfnr = it }
        vendorSwitch("MFHDR: HDR multi-frame") { vMfhdr = it }
        vendorSwitch("SHDR: HDR escalonado") { vShdr = it }
        vendorSwitch("ZSL de Motorola") { vZsl = it }
        vendorSwitch("Marcar cliente como Moto Camera") { vMoto = it }

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
            hdrLabel.text = "Intensidad Ultra HDR: $p%"
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
        hdrLabel.text = "Intensidad Ultra HDR: ${p.hdr}%"
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
                    d.close(); device = null; session = null
                    val sw = riskySwitch
                    if (sw != null) {
                        riskyRevert?.invoke()
                        riskySwitch = null; riskyRevert = null
                        val n = riskyName
                        runOnUiThread {
                            suppress = true; sw.isChecked = false; suppress = false
                            status.text = "\"$n\" hizo caer la cámara (error $error). Lo desactivé y reabro."
                            texture.postDelayed({ openCamera() }, 800)
                        }
                    } else {
                        say("Error de cámara: $error")
                    }
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
            synchronized(fuseLock) { fuseImgCount = 0; fuseResCount = 0 }

            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(if (physicalMode) lens.physicalId else "0")
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            vendorKeys = chars.availableCaptureRequestKeys.map { it.name }.toSet()
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expMin = it.lower; expMax = it.upper }
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }

            val stillFormat = if (fusionMode) ImageFormat.JPEG else ImageFormat.JPEG_R
            val stillSize = map.getOutputSizes(stillFormat)
                .filter { it.width * 3 == it.height * 4 && it.width <= 4096 }
                .maxByOrNull { it.width * it.height }!!
            val prevSize = map.getOutputSizes(SurfaceTexture::class.java)
                .filter { it.width * 3 == it.height * 4 && it.width <= 1440 }
                .maxByOrNull { it.width * it.height }!!

            val st = texture.surfaceTexture!!
            st.setDefaultBufferSize(prevSize.width, prevSize.height)
            val pSurface = Surface(st)
            previewSurface = pSurface

            val r = ImageReader.newInstance(stillSize.width, stillSize.height, stillFormat, if (fusionMode) 7 else 2)
            r.setOnImageAvailableListener({ rd -> if (fusionMode) onFusionImage(rd) else onPhoto(rd) }, camHandler)
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
            val sp = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            applyVendor(sp)
            cfg.setSessionParameters(sp.build())
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            say("Sesión: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun buildRequest(template: Int, target: Surface, allowScene: Boolean): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        val p = prof()
        b.addTarget(target)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.exp)
        if (p.nr > 0) b.set(CaptureRequest.NOISE_REDUCTION_MODE, p.nr - 1)
        if (p.edge > 0) b.set(CaptureRequest.EDGE_MODE, p.edge - 1)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (physicalMode) 1f else lens.zoom)
        applyVendor(b)
        return b
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastT = it }
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            // Tras 30 frames sanos, la opción experimental se considera estable
            if (riskySwitch != null && ++okFrames >= 30) { riskySwitch = null; riskyRevert = null }
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val surf = previewSurface ?: return
        try {
            val req = buildRequest(CameraDevice.TEMPLATE_PREVIEW, surf, !fusionMode).build()
            s.setRepeatingRequest(req, previewCb, camHandler)
            say("${lens.label} · ${if (physicalMode) "físico ${lens.physicalId}" else "lógico"}" +
                (if (fusionMode) " · fusión" else "") + vendorLog)
        } catch (e: Exception) {
            say("Preview: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // ------------------------------------------------------------------ claves propias

    private fun setVendor(b: CaptureRequest.Builder, name: String, v: Int): String {
        if (name !in vendorKeys) return "no existe"
        try { b.set(CaptureRequest.Key<Byte>(name, Byte::class.javaObjectType), v.toByte()); return "ok(byte)" } catch (_: Exception) {}
        try { b.set(CaptureRequest.Key<Int>(name, Int::class.javaObjectType), v); return "ok(int)" } catch (_: Exception) {}
        try { b.set(CaptureRequest.Key<IntArray>(name, IntArray::class.java), intArrayOf(v)); return "ok(int[])" } catch (_: Exception) {}
        return "tipo no compatible"
    }

    private fun applyVendor(b: CaptureRequest.Builder) {
        val q = "org.codeaurora.qcamera3.sessionParameters."
        val m = "com.lenovo.moto."
        val log = StringBuilder()
        fun go(on: Boolean, label: String, key: String) {
            if (on) log.append("\n$label: ${setVendor(b, key, 1)}")
        }
        go(vMfnr, "MFNR", q + "enableMFNR")
        go(vMfhdr, "MFHDR", q + "EnableMFHDR")
        go(vShdr, "SHDR", q + "EnableSHDR")
        go(vZsl, "ZSL Moto", m + "control.capture_zsl_mode")
        go(vMoto, "Cliente Moto", m + "clientapp.is_motcamera2")
        vendorLog = log.toString()
    }

    // ------------------------------------------------------------------ captura simple (JPEG_R)

    private fun takePhoto() {
        if (fusionMode) { takeFusion(); return }
        val s = session ?: return say("Cámara no lista")
        val r = reader ?: return say("Cámara no lista")
        try {
            pendingLens = lens
            pendingHdr = prof().hdr
            val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, r.surface, true)
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
        val name = "UHDR_${stamp()}_${pendingLens.name}"
        val hdr = pendingHdr
        if (hdr == 100) {
            saveFile(bytes, "$name.jpg")
            say("Guardada: $name.jpg")
            return
        }
        val adjusted = try { adjustGainmap(bytes, hdr / 100f) } catch (e: Throwable) { null }
        if (adjusted != null) {
            saveFile(adjusted, "${name}_hdr$hdr.jpg")
            say("Guardada con HDR al $hdr%")
        } else {
            saveFile(bytes, "$name.jpg")
            say("Guardada la original. El ajuste del gain map falló en este equipo.")
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Escala el efecto HDR del gain map del fabricante (k=0 sin HDR, 1 igual, >1 más fuerte). */
    private fun adjustGainmap(src: ByteArray, k: Float): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
        val gm = bmp.gainmap ?: return null

        val mx = gm.ratioMax
        val mn = gm.ratioMin
        gm.setRatioMax(mx[0].pow(k), mx[1].pow(k), mx[2].pow(k))
        gm.setRatioMin(mn[0].pow(k), mn[1].pow(k), mn[2].pow(k))
        bmp.setGainmap(gm)

        val needsRotation = sensorOrientation % 180 != 0 && bmp.width > bmp.height
        val out = if (needsRotation) {
            val m = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } else bmp
        if (!out.hasGainmap()) return null

        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 96, bos)
        val result = bos.toByteArray()
        val check = BitmapFactory.decodeByteArray(result, 0, result.size)
        return if (check != null && check.hasGainmap()) result else null
    }

    // ------------------------------------------------------------------ fusión por ráfaga

    /**
     * Ráfaga de 3 exposiciones manuales: la medida por el visor (0 EV), -2 EV y -4 EV.
     * El frame base queda como imagen SDR; los oscuros recuperan las altas luces y con ellos
     * calculamos nosotros el gain map (Ultra HDR).
     */
    private fun takeFusion() {
        val s = session ?: return say("Cámara no lista")
        val r = reader ?: return say("Cámara no lista")
        val t0 = lastT
        val iso0 = lastIso
        if (t0 <= 0L || iso0 <= 0) return say("Esperá un segundo a que el visor mida la exposición")
        try {
            synchronized(fuseLock) {
                fuseImgCount = 0; fuseResCount = 0
                for (i in 0..4) { fuseImgs[i] = null; fuseP[i] = 0.0 }
                fuseTotal = burstN
            }
            pendingLens = lens
            pendingHdr = prof().hdr

            val evs = if (burstN == 5) floatArrayOf(0f, -1f, -2f, -3f, -4f) else floatArrayOf(0f, -2f, -4f)
            val reqs = evs.mapIndexed { i, ev ->
                val targetP = t0.toDouble() * iso0 * 2.0.pow(ev.toDouble())
                val t = (targetP / iso0).toLong().coerceIn(expMin, expMax)
                val iso = (targetP / t).roundToInt().coerceIn(isoMin, isoMax)
                val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, r.surface, false)
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(t, 33_333_333L))
                b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                b.setTag(i)
                b.build()
            }
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ses: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    val i = req.tag as Int
                    val t = res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val iso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    synchronized(fuseLock) {
                        fuseP[i] = t.toDouble() * iso
                        fuseResCount++
                        maybeFuse()
                    }
                }
                override fun onCaptureFailed(ses: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    say("Falló un frame de la ráfaga")
                }
            }
            s.captureBurst(reqs, cb, camHandler)
            say("Ráfaga en curso, mantené quieto el celu...")
        } catch (e: Exception) {
            say("Ráfaga: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun onFusionImage(rd: ImageReader) {
        val img = rd.acquireNextImage() ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        synchronized(fuseLock) {
            if (fuseImgCount < fuseTotal) {
                fuseImgs[fuseImgCount] = bytes
                fuseImgCount++
            }
            maybeFuse()
        }
    }

    /** Se llama con fuseLock tomado. Cuando están todos los frames y todos los resultados, procesa en otro hilo. */
    private fun maybeFuse() {
        if (fuseImgCount < fuseTotal || fuseResCount < fuseTotal) return
        val n = fuseTotal
        val frames = Array(n) { fuseImgs[it]!! }
        val p = fuseP.copyOf(n)
        val tag = pendingLens.name
        val hdr = pendingHdr
        fuseImgCount = 0
        fuseResCount = 0
        Thread {
            say("Fusionando...")
            val name = "UHDRFUS_${stamp()}_$tag"
            try {
                val out = fuseToUltraHdr(frames, p, hdr / 100f)
                if (out != null) {
                    saveFile(out, "$name.jpg")
                    say("Fusión lista: $name.jpg")
                } else {
                    saveFile(frames[0], "${name}_base.jpg")
                    say("La fusión falló; guardé solo el frame base (sin Ultra HDR)")
                }
            } catch (t: Throwable) {
                saveFile(frames[0], "${name}_base.jpg")
                say("Fusión: ${t.javaClass.simpleName} ${t.message}. Guardé el frame base.")
            }
        }.start()
    }

    private fun fuseToUltraHdr(frames: Array<ByteArray>, p: DoubleArray, k: Float): ByteArray? {
        if (p.any { it <= 0.0 }) return null

        // 1) Versiones reducidas (1/4) para calcular el gain map
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val small = frames.map { BitmapFactory.decodeByteArray(it, 0, it.size, opts) ?: return null }
        val w = small[0].width
        val h = small[0].height
        if (small.any { it.width != w || it.height != h }) return null
        val luma = small.map { toLuma(it) }
        small.forEach { it.recycle() }

        // 2) Alineación global (traslación) de los frames oscuros contra el base
        val th = luma.map { median(it.code) }
        val bits = luma.mapIndexed { i, l -> mtb(l.code, th[i]) }
        val mask = luma.mapIndexed { i, l -> mtbMask(l.code, th[i]) }
        val nf = frames.size
        val dx = IntArray(nf)
        val dy = IntArray(nf)
        for (f in 1 until nf) {
            val s = estimateShift(bits[0], mask[0], bits[f], mask[f], w, h)
            dx[f] = s[0]; dy[f] = s[1]
        }

        // 3) Fusión de radiancia y gain map (en log2)
        val eps = 1f / 64f
        val e = DoubleArray(nf) { p[it] / p[0] }
        val n = w * h
        val lg = FloatArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var num = 0.0
                var den = 0.0
                for (f in 0 until nf) {
                    val xx = (x + dx[f]).coerceIn(0, w - 1)
                    val yy = (y + dy[f]).coerceIn(0, h - 1)
                    val j = yy * w + xx
                    val c = luma[f].code[j]
                    val wt = max(1f - abs(2f * c - 1f), 0.01f).toDouble()
                    num += wt * luma[f].lin[j] / e[f]
                    den += wt
                }
                val sdr = luma[0].lin[i]
                val r = if (den > 1e-6) (num / den).toFloat() else sdr
                val g = (r + eps) / (sdr + eps)
                val m = smoothstep(0.5f, 0.85f, luma[0].code[i])
                val gain = max(1f + m * (g - 1f), 1f)
                lg[i] = (ln(gain.toDouble()) / ln(2.0)).toFloat()
            }
        }
        val blurred = boxBlur(lg, w, h, 2)

        // 4) Normalización: ratio máximo = percentil 99.8 de la ganancia, entre 1.5x y 16x
        val sorted = blurred.copyOf().also { it.sort() }
        val maxLog = sorted[(n * 0.998f).toInt().coerceIn(0, n - 1)].coerceIn(0.585f, 4f) // 2^0.585 = 1.5x
        val ratioMax = 2f.pow(maxLog)
        val gmBytes = ByteArray(n) { i -> ((blurred[i] / maxLog).coerceIn(0f, 1f) * 255f).roundToInt().toByte() }

        // 5) Imagen base a resolución completa + rotación
        val baseOpts = BitmapFactory.Options().apply { inMutable = true }
        val base0 = BitmapFactory.decodeByteArray(frames[0], 0, frames[0].size, baseOpts) ?: return null
        val rot = if (sensorOrientation % 180 != 0 && base0.width > base0.height) sensorOrientation else 0
        val base = if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            Bitmap.createBitmap(base0, 0, 0, base0.width, base0.height, m, true).also { base0.recycle() }
        } else base0

        val (rotBytes, gw, gh) = rotateBytes(gmBytes, w, h, rot)
        val gbmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ALPHA_8)
        gbmp.copyPixelsFromBuffer(ByteBuffer.wrap(rotBytes))

        val rk = ratioMax.pow(k).coerceAtLeast(1.01f)
        val gm = Gainmap(gbmp)
        gm.setRatioMin(1f, 1f, 1f)
        gm.setRatioMax(rk, rk, rk)
        gm.setGamma(1f, 1f, 1f)
        gm.setEpsilonSdr(eps, eps, eps)
        gm.setEpsilonHdr(eps, eps, eps)
        gm.setDisplayRatioForFullHdr(max(rk, 1.5f))
        gm.setMinDisplayRatioForHdrTransition(1f)
        base.setGainmap(gm)

        val bos = ByteArrayOutputStream()
        base.compress(Bitmap.CompressFormat.JPEG, 96, bos)
        val result = bos.toByteArray()
        val check = BitmapFactory.decodeByteArray(result, 0, result.size)
        return if (check != null && check.hasGainmap()) result else null
    }

    // ------------------------------------------------------------------ guardado

    private fun saveFile(bytes: ByteArray, name: String) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}

// ---------------------------------------------------------------------- utilidades de imagen

private class Luma(val code: FloatArray, val lin: FloatArray)

private val SRGB_LUT = FloatArray(256) { i ->
    val c = i / 255f
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}

private fun toLuma(bmp: Bitmap): Luma {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val code = FloatArray(w * h)
    val lin = FloatArray(w * h)
    for (i in px.indices) {
        val p = px[i]
        val r = (p shr 16) and 255
        val g = (p shr 8) and 255
        val b = p and 255
        code[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        lin[i] = 0.2126f * SRGB_LUT[r] + 0.7152f * SRGB_LUT[g] + 0.0722f * SRGB_LUT[b]
    }
    return Luma(code, lin)
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun median(a: FloatArray): Float {
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

private fun mtb(a: FloatArray, th: Float) = ByteArray(a.size) { if (a[it] > th) 1 else 0 }
private fun mtbMask(a: FloatArray, th: Float) = ByteArray(a.size) { if (abs(a[it] - th) > 0.02f) 1 else 0 }

/** Busca la traslación (dx, dy) que mejor alinea img con ref, con bitmaps de umbral por mediana. */
private fun estimateShift(refB: ByteArray, refM: ByteArray, imgB: ByteArray, imgM: ByteArray, w: Int, h: Int): IntArray {
    val range = 12
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
                    x += 2
                }
                y += 2
            }
            if (err < best) { best = err; bx = dx; by = dy }
        }
    }
    return intArrayOf(bx, by)
}

private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
    val tmp = FloatArray(src.size)
    val out = FloatArray(src.size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var s = 0f
            var c = 0
            for (k in -r..r) {
                val xx = x + k
                if (xx in 0 until w) { s += src[y * w + xx]; c++ }
            }
            tmp[y * w + x] = s / c
        }
    }
    for (y in 0 until h) {
        for (x in 0 until w) {
            var s = 0f
            var c = 0
            for (k in -r..r) {
                val yy = y + k
                if (yy in 0 until h) { s += tmp[yy * w + x]; c++ }
            }
            out[y * w + x] = s / c
        }
    }
    return out
}

/** Gira (sentido horario) un mapa de 1 byte por pixel. Devuelve los bytes y el nuevo ancho y alto. */
private fun rotateBytes(src: ByteArray, w: Int, h: Int, deg: Int): Triple<ByteArray, Int, Int> {
    return when (deg) {
        90 -> {
            val out = ByteArray(src.size)
            for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = src[y * w + x]
            Triple(out, h, w)
        }
        270 -> {
            val out = ByteArray(src.size)
            for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = src[y * w + x]
            Triple(out, h, w)
        }
        180 -> {
            val out = ByteArray(src.size)
            for (y in 0 until h) for (x in 0 until w) out[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
            Triple(out, w, h)
        }
        else -> Triple(src, w, h)
    }
}