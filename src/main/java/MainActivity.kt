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
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Lentes del Edge 50 Ultra: fisicos 3 (UGA), 2 (principal), 5 (tele) dentro de la logica 0, y la frontal. */
enum class Lens(val label: String, val zoom: Float, val physicalId: String, val front: Boolean = false) {
    UGA("0.5x", 0.5f, "3"),
    MAIN("1x", 1.0f, "2"),
    TELE("3x", 3.0f, "5"),
    FRONT("Frontal", 1.0f, "", true)
}

/**
 * Ajustes por lente. exp en pasos de 1/6 EV (-36..36).
 * nr/edge: 0=Auto (criterio del fabricante) 1=off 2=rapido 3=alta.
 * hdr: intensidad del gain map Ultra HDR en %.
 */
class Profile(var exp: Int = 0, var nr: Int = 0, var edge: Int = 0, var hdr: Int = 100)

class MainActivity : Activity() {

    // ------------------------------------------------------------------ interfaz
    private lateinit var texture: TextureView
    private lateinit var previewFrame: FrameLayout
    private lateinit var focusRing: View
    private lateinit var shutter: Button
    private lateinit var status: TextView
    private lateinit var expBar: SeekBar
    private lateinit var expLabel: TextView
    private lateinit var nrGroup: RadioGroup
    private lateinit var edgeGroup: RadioGroup
    private lateinit var hdrBar: SeekBar
    private lateinit var hdrLabel: TextView
    private lateinit var hlLabel: TextView
    private lateinit var photoBox: LinearLayout
    private lateinit var videoBox: LinearLayout
    private lateinit var nightBox: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var suppress = false

    // ------------------------------------------------------------------ modos
    private var lens = Lens.MAIN
    private var videoMode = false
    private var physicalMode = false
    /** Procesamiento HDR: 0 desactivado (1 foto), 1 activo (fusión de 8), 2 mejorado (fusión de 15). */
    private var hdrMode = 1
    /** Formato de archivo Ultra HDR (JPEG con gain map). Independiente del procesamiento. */
    private var ultraHdr = true
    private var nightMode = false
    private var nightFrames = 15
    /** Protección de luces altas en pasos de 1/3 EV (0..9). */
    private var hlSteps = 3
    private var v4k = false
    private var v60 = false

    private var vMfnr = false
    private var vShdr = false
    private var vZsl = false
    private var vMoto = false
    private var vendorKeys: Set<String> = emptySet()
    private var vendorLog = ""
    private var sessionNote = ""
    private val profiles = mutableMapOf<Lens, Profile>()

    private val camThread = HandlerThread("cam").apply { start() }
    private val camHandler by lazy { Handler(camThread.looper) }
    private val executor = Executor { camHandler.post(it) }
    private val mergeExec = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------ cámara
    private var camId = "0"
    private var frontId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var stillIsUltra = false
    private var sensorOrientation = 90

    private var expMin = 1_000L
    private var expMax = 1_000_000_000L
    private var isoMin = 50
    private var isoMax = 3200

    @Volatile private var lastT = 0L
    @Volatile private var lastIso = 0

    // Video
    private var recSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    @Volatile private var recording = false
    private var videoSize = Size(1920, 1080)
    private var videoFps = 30
    private var fpsRange = Range(30, 30)

    // Foco por toque
    @Volatile private var focusRegion: MeteringRectangle? = null
    @Volatile private var afWatching = false
    private var activeArray: Rect? = null
    private var maxAfRegions = 0
    private var maxAeRegions = 0

    // Opción experimental recién activada: si la cámara se cae antes de estabilizarse, se revierte
    private var riskySwitch: Switch? = null
    private var riskyName = ""
    private var riskyRevert: (() -> Unit)? = null
    @Volatile private var okFrames = 0

    // Captura en curso
    private var pendingLens = Lens.MAIN
    private var pendingHdr = 100
    private var pendingUltra = false
    @Volatile private var merger: Merger? = null

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
        hdrMode = prefs.getInt("hdrMode", 1).coerceIn(0, 2)
        ultraHdr = prefs.getBoolean("ultraHdr", true)
        nightFrames = prefs.getInt("nightFrames", 15).let { if (it in listOf(10, 15, 20)) it else 15 }
        hlSteps = prefs.getInt("hlSteps", 3).coerceIn(0, 9)
        v4k = prefs.getBoolean("v4k", false)
        v60 = prefs.getBoolean("v60", false)

        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        frontId = try {
            mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            }
        } catch (_: Exception) { null }

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

    override fun onDestroy() {
        try { recSurface?.release() } catch (_: Exception) {}
        mergeExec.shutdown()
        camThread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
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
        e.putInt("hdrMode", hdrMode).putBoolean("ultraHdr", ultraHdr).putInt("nightFrames", nightFrames)
            .putInt("hlSteps", hlSteps).putBoolean("v4k", v4k).putBoolean("v60", v60)
        e.apply()
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(24, 20, 24, 4)
    }

    private fun seek(maxValue: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = maxValue
        setPadding(48, 8, 48, 8)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun radioRow(options: List<String>, selected: Int, onPick: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        setPadding(24, 0, 24, 0)
        options.forEachIndexed { i, t ->
            addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = t
                tag = i
            })
        }
        if (selected in options.indices) (getChildAt(selected) as RadioButton).isChecked = true
        setOnCheckedChangeListener { g, id ->
            if (!suppress) {
                val b = g.findViewById<RadioButton>(id)
                if (b != null) onPick(b.tag as Int)
            }
        }
    }

    private fun switchRow(t: String, checked: Boolean, onSet: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        isChecked = checked
        setPadding(24, 12, 24, 8)
        setOnCheckedChangeListener { _, c -> if (!suppress) onSet(c) }
    }

    private fun previewHeight(): Int {
        val w = resources.displayMetrics.widthPixels
        return if (videoMode) w * 16 / 9 else w * 4 / 3
    }

    private fun buildUi() {
        val w = resources.displayMetrics.widthPixels
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 64)
        }

        // Visor + anillo de foco
        texture = TextureView(this)
        previewFrame = FrameLayout(this)
        previewFrame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ringPx = (resources.displayMetrics.density * 64).toInt()
        focusRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((resources.displayMetrics.density * 2).toInt(), Color.WHITE)
            }
            visibility = View.INVISIBLE
        }
        previewFrame.addView(focusRing, FrameLayout.LayoutParams(ringPx, ringPx))
        root.addView(previewFrame, LinearLayout.LayoutParams(w, previewHeight()))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y, texture.width, texture.height); return true
            }
            override fun onLongPress(e: MotionEvent) { resetFocus(true) }
        })
        texture.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        shutter = Button(this).apply {
            textSize = 18f
            setOnClickListener { onShutter() }
        }
        root.addView(shutter)

        status = TextView(this).apply { textSize = 12f; setPadding(24, 8, 24, 8) }
        root.addView(status)

        root.addView(label("Modo"))
        root.addView(radioRow(listOf("Foto", "Video"), 0) { setVideoMode(it == 1) })

        root.addView(label("Lente"))
        root.addView(radioRow(Lens.values().map { it.label }, lens.ordinal) { selectLens(Lens.values()[it]) })

        // ---------------- foto
        photoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        photoBox.addView(label("HDR (procesamiento)"))
        photoBox.addView(radioRow(listOf("Desactivado", "Activo", "Mejorado"), hdrMode) {
            hdrMode = it; save(); afterPhotoModeChange()
        })
        photoBox.addView(switchRow("Ultra HDR (archivo con gain map para pantallas HDR)", ultraHdr) {
            ultraHdr = it; save(); afterPhotoModeChange()
        })
        photoBox.addView(switchRow("Modo noche", nightMode) {
            nightMode = it
            nightBox.visibility = if (it) View.VISIBLE else View.GONE
            afterPhotoModeChange()
        })
        nightBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(label("Fotos por toma nocturna"))
            val opts = listOf(10, 15, 20)
            addView(radioRow(opts.map { "$it" }, opts.indexOf(nightFrames)) {
                nightFrames = opts[it]; save(); updateShutter()
            })
        }
        photoBox.addView(nightBox)
        hlLabel = label(hlText())
        photoBox.addView(hlLabel)
        photoBox.addView(seek(9) { p ->
            hlSteps = p; save(); hlLabel.text = hlText()
        }.apply { progress = hlSteps })
        hdrLabel = label("")
        photoBox.addView(hdrLabel)
        hdrBar = seek(150) { p ->
            prof().hdr = p
            hdrLabel.text = "Intensidad Ultra HDR: $p%"
            save()
        }
        photoBox.addView(hdrBar)
        root.addView(photoBox)

        // ---------------- video
        videoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(label("Resolución"))
            addView(radioRow(listOf("FHD 1080p", "4K"), if (v4k) 1 else 0) {
                v4k = it == 1; save(); updateShutter(); restartVideo()
            })
            addView(label("Cuadros por segundo"))
            addView(radioRow(listOf("30 fps", "60 fps"), if (v60) 1 else 0) {
                v60 = it == 1; save(); updateShutter(); restartVideo()
            })
        }
        root.addView(videoBox)

        // ---------------- ajustes por lente
        expLabel = label("")
        root.addView(expLabel)
        expBar = seek(72) { p ->
            prof().exp = p - 36
            expLabel.text = "Exposición: %+.1f EV".format(prof().exp / 6f)
            save(); updatePreview()
        }
        root.addView(expBar)

        root.addView(label("Reducción de ruido"))
        nrGroup = radioRow(listOf("Auto", "Off", "Rápido", "Alta"), -1) { prof().nr = it; save(); updatePreview() }
        root.addView(nrGroup)

        root.addView(label("Nitidez (edge)"))
        edgeGroup = radioRow(listOf("Auto", "Off", "Rápido", "Alta"), -1) { prof().edge = it; save(); updatePreview() }
        root.addView(edgeGroup)

        // ---------------- experimental
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
        vendorSwitch("SHDR: HDR escalonado") { vShdr = it }
        vendorSwitch("ZSL de Motorola") { vZsl = it }
        vendorSwitch("Marcar cliente como Moto Camera") { vMoto = it }

        root.addView(switchRow("Lente físico directo (experimental)", false) {
            physicalMode = it
            if (device != null) startSession()
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshControls()
        updateShutter()
    }

    private fun hlText(): String {
        val ev = hlSteps / 3f
        return "Protección de luces altas: %.1f EV".format(ev) +
            if (hlSteps == 0) "  (sin margen: no se genera Ultra HDR en Activo/Mejorado/Noche)" else ""
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

    private fun updateShutter() {
        shutter.text = when {
            videoMode && recording -> "■  DETENER"
            videoMode -> "●  GRABAR  (${if (v4k) "4K" else "FHD"} ${if (v60) 60 else 30} fps)"
            nightMode -> "FOTO  (noche, $nightFrames fotos" + (if (ultraHdr) " + Ultra HDR)" else ")")
            else -> "FOTO  (HDR " + arrayOf("desactivado", "activo", "mejorado")[hdrMode] +
                (if (ultraHdr) " + Ultra HDR)" else ")")
        }
    }

    private fun afterPhotoModeChange() {
        updateShutter()
        if (device != null) startSession()
    }

    private fun restartVideo() {
        if (videoMode && !recording && device != null) startSession()
    }

    private fun setVideoMode(v: Boolean) {
        if (recording) camHandler.post { stopRecording() }
        videoMode = v
        photoBox.visibility = if (v) View.GONE else View.VISIBLE
        videoBox.visibility = if (v) View.VISIBLE else View.GONE
        previewFrame.layoutParams = LinearLayout.LayoutParams(resources.displayMetrics.widthPixels, previewHeight())
        updateShutter()
        if (device != null) camHandler.post { startSession() }
    }

    private fun cameraIdFor(l: Lens) = if (l.front) (frontId ?: "1") else "0"
    private val usePhysical get() = physicalMode && !lens.front

    private fun selectLens(l: Lens) {
        if (recording) camHandler.post { stopRecording() }
        val newId = cameraIdFor(l)
        lens = l
        focusRegion = null
        afWatching = false
        focusRing.visibility = View.INVISIBLE
        refreshControls()
        if (newId != camId) {
            closeCamera()
            openCamera()
        } else if (usePhysical) startSession() else updatePreview()
    }

    private fun say(msg: String) = runOnUiThread { status.text = msg }

    private fun statusLine(): String {
        val where = when {
            lens.front -> "frontal"
            usePhysical -> "físico ${lens.physicalId}"
            else -> "lógico"
        }
        val what = when {
            videoMode && recording -> "● GRABANDO ${videoSize.height}p $videoFps fps"
            videoMode -> "video ${videoSize.height}p $videoFps fps"
            nightMode -> "noche $nightFrames fotos"
            else -> "HDR " + arrayOf("desactivado", "activo", "mejorado")[hdrMode]
        }
        return "${lens.label} · $where · $what$sessionNote$vendorLog"
    }

    // ------------------------------------------------------------------ foco por toque

    private fun focusAt(x: Float, y: Float, vw: Int, vh: Int) {
        val arr = activeArray ?: return
        if (session == null || vw <= 0 || vh <= 0) return
        if (maxAfRegions <= 0 && maxAeRegions <= 0) { say("Este lente no admite foco por toque"); return }
        var nx = (x / vw).coerceIn(0f, 1f)
        val ny = (y / vh).coerceIn(0f, 1f)
        if (lens.front) nx = 1f - nx // el visor de la frontal está espejado
        val (sx, sy) = when (sensorOrientation) {
            90 -> ny to 1f - nx
            180 -> 1f - nx to 1f - ny
            270 -> 1f - ny to nx
            else -> nx to ny
        }
        val aw = arr.width()
        val ah = arr.height()
        val size = (min(aw, ah) * 0.12f).toInt()
        val l = ((sx * aw).toInt() - size / 2).coerceIn(0, aw - size)
        val t = ((sy * ah).toInt() - size / 2).coerceIn(0, ah - size)
        focusRegion = MeteringRectangle(l, t, size, size, MeteringRectangle.METERING_WEIGHT_MAX)

        focusRing.animate().cancel()
        focusRing.translationX = x - focusRing.width / 2f
        focusRing.translationY = y - focusRing.height / 2f
        setRingColor(Color.WHITE)
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        camHandler.post { triggerAf() }
    }

    private fun setRingColor(c: Int) {
        (focusRing.background as GradientDrawable).setStroke((resources.displayMetrics.density * 2).toInt(), c)
    }

    private fun repeatingTargets(): List<Surface> =
        listOfNotNull(previewSurface, if (recording) recSurface else null)

    private fun repeatingTemplate() = if (videoMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

    private fun triggerAf() {
        val s = session ?: return
        if (previewSurface == null) return
        try {
            val cancel = buildRequest(repeatingTemplate(), repeatingTargets())
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(cancel.build(), null, camHandler)
            updatePreview()
            val start = buildRequest(repeatingTemplate(), repeatingTargets())
            start.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            afWatching = true
            s.capture(start.build(), previewCb, camHandler)
            say("Enfocando... (mantené apretado para volver a foco automático)")
        } catch (e: Exception) {
            say("Foco: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun resetFocus(announce: Boolean) {
        val had = focusRegion != null
        focusRegion = null
        afWatching = false
        runOnUiThread { focusRing.visibility = View.INVISIBLE }
        if (!had) return
        camHandler.post {
            val s = session
            if (s != null && previewSurface != null) {
                try {
                    val cancel = buildRequest(repeatingTemplate(), repeatingTargets())
                    cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                    s.capture(cancel.build(), null, camHandler)
                } catch (_: Exception) {}
            }
            updatePreview()
            if (announce) say("Foco automático")
        }
    }

    // ------------------------------------------------------------------ cámara

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!texture.isAvailable || device != null) return
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            camId = cameraIdFor(lens)
            sensorOrientation = mgr.getCameraCharacteristics(camId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession() }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) { device = null; session = null }
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device !== d) return
                    device = null; session = null
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
        if (recording) stopRecording()
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    private fun pickSize(sizes: Array<Size>?, aspectW: Int, aspectH: Int, maxW: Int): Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes.filter { it.width * aspectH == it.height * aspectW && it.width <= maxW }
            .maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
    }

    private fun startSession() {
        val d = device ?: return
        try {
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { reader?.close() } catch (_: Exception) {}
            reader = null
            try { recSurface?.release() } catch (_: Exception) {}
            recSurface = null
            sessionNote = ""

            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = mgr.getCameraCharacteristics(if (usePhysical) lens.physicalId else camId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            vendorKeys = chars.availableCaptureRequestKeys.map { it.name }.toSet()
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { expMin = it.lower; expMax = it.upper }
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoMin = it.lower; isoMax = it.upper }
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            focusRegion = null
            afWatching = false
            runOnUiThread { focusRing.visibility = View.INVISIBLE }

            val st = texture.surfaceTexture!!
            val surfaces = mutableListOf<Surface>()

            if (videoMode) {
                val want = if (v4k) Size(3840, 2160) else Size(1920, 1080)
                val recSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                val vs = if (recSizes.contains(want)) want else Size(1920, 1080)
                if (vs != want) sessionNote += "\n4K no disponible en este lente: grabo en FHD"
                var fps = if (v60) 60 else 30
                val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
                val minDur = try { map.getOutputMinFrameDuration(MediaRecorder::class.java, vs) } catch (_: Exception) { 0L }
                if (fps == 60 && (minDur > 16_800_000L || ranges.none { it.upper >= 60 })) {
                    fps = 30
                    sessionNote += "\n60 fps no disponible en ${vs.height}p con este lente: grabo a 30"
                }
                videoSize = vs
                videoFps = fps
                fpsRange = ranges.firstOrNull { it.lower == fps && it.upper == fps }
                    ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
                    ?: Range(fps, fps)

                val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 16, 9, 1920) ?: vs
                st.setDefaultBufferSize(prev.width, prev.height)
                val ps = Surface(st)
                previewSurface = ps
                val rs = MediaCodec.createPersistentInputSurface()
                createRecorder(rs, null).apply { prepare(); release() }
                recSurface = rs
                surfaces += ps
                surfaces += rs
            } else {
                var fmt = if (singleUltraWanted()) ImageFormat.JPEG_R else ImageFormat.JPEG
                var stillSize = pickSize(map.getOutputSizes(fmt), 4, 3, 4096)
                if (stillSize == null && fmt == ImageFormat.JPEG_R) {
                    fmt = ImageFormat.JPEG
                    stillSize = pickSize(map.getOutputSizes(fmt), 4, 3, 4096)
                    sessionNote += "\nEste lente no entrega Ultra HDR propio: guardo JPEG común"
                }
                stillIsUltra = fmt == ImageFormat.JPEG_R
                val ss = stillSize!!
                val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 4, 3, 1440)!!
                st.setDefaultBufferSize(prev.width, prev.height)
                val ps = Surface(st)
                previewSurface = ps
                val r = ImageReader.newInstance(ss.width, ss.height, fmt, 8)
                r.setOnImageAvailableListener({ rd -> onStill(rd) }, camHandler)
                reader = r
                surfaces += ps
                surfaces += r.surface
            }

            val outs = surfaces.map { s ->
                OutputConfiguration(s).also { if (usePhysical) it.setPhysicalCameraId(lens.physicalId) }
            }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        updatePreview()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        say("No se pudo configurar la sesión (${if (videoMode) "video ${videoSize.height}p $videoFps fps" else "foto"})")
                    }
                }
            )
            val sp = d.createCaptureRequest(repeatingTemplate())
            if (videoMode) sp.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            applyVendor(sp)
            cfg.setSessionParameters(sp.build())
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            say("Sesión: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Una sola foto con el Ultra HDR del fabricante: solo cuando no hay fusión. */
    private fun singleUltraWanted() = ultraHdr && hdrMode == 0 && !nightMode

    private fun buildRequest(template: Int, targets: List<Surface>): CaptureRequest.Builder {
        val b = device!!.createCaptureRequest(template)
        val p = prof()
        targets.forEach { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        val fr = focusRegion
        if (fr != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(fr))
            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(fr))
        } else {
            b.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (videoMode) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.exp)
        if (p.nr > 0) b.set(CaptureRequest.NOISE_REDUCTION_MODE, p.nr - 1)
        if (p.edge > 0) b.set(CaptureRequest.EDGE_MODE, p.edge - 1)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (usePhysical || lens.front) 1f else lens.zoom)
        if (videoMode) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        applyVendor(b)
        return b
    }

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastT = it }
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            if (riskySwitch != null && ++okFrames >= 30) { riskySwitch = null; riskyRevert = null }
            if (afWatching) {
                when (res.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                        afWatching = false
                        runOnUiThread { setRingColor(Color.GREEN); focusRing.animate().alpha(0.4f).setStartDelay(800).start() }
                        say("Foco fijado. Mantené apretado para volver a automático.")
                    }
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        afWatching = false
                        runOnUiThread { setRingColor(Color.RED); focusRing.animate().alpha(0.4f).setStartDelay(800).start() }
                        say("No pudo enfocar ahí. Probá otro punto o mantené apretado.")
                    }
                }
            }
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        if (previewSurface == null || device == null) return
        try {
            val req = buildRequest(repeatingTemplate(), repeatingTargets()).build()
            s.setRepeatingRequest(req, previewCb, camHandler)
            say(statusLine())
        } catch (e: Exception) {
            say("Visor: ${e.javaClass.simpleName} ${e.message}")
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
        go(vShdr, "SHDR", q + "EnableSHDR")
        go(vZsl, "ZSL Moto", m + "control.capture_zsl_mode")
        go(vMoto, "Cliente Moto", m + "clientapp.is_motcamera2")
        vendorLog = log.toString()
    }

    // ------------------------------------------------------------------ disparador

    private fun onShutter() {
        if (videoMode) {
            camHandler.post { if (recording) stopRecording() else startRecording() }
            return
        }
        if (merger != null) { say("Todavía estoy procesando la foto anterior..."); return }
        when {
            nightMode -> startMerge(nightFrames, true)
            hdrMode == 1 -> startMerge(8, false)
            hdrMode == 2 -> startMerge(15, false)
            else -> takeSingle()
        }
    }

    private fun onStill(rd: ImageReader) {
        val img = rd.acquireNextImage() ?: return
        val bytes = try {
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally {
            img.close()
        }
        val m = merger
        if (m != null && m.accepting()) m.offer(bytes) else onSinglePhoto(bytes)
    }

    // ------------------------------------------------------------------ foto simple

    private fun takeSingle() {
        val s = session ?: return say("Cámara no lista")
        val r = reader ?: return say("Cámara no lista")
        try {
            pendingLens = lens
            pendingHdr = prof().hdr
            pendingUltra = stillIsUltra
            val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, listOf(r.surface))
            b.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            b.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            s.capture(b.build(), null, camHandler)
            say("Capturando...")
        } catch (e: Exception) {
            say("Captura: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun onSinglePhoto(bytes: ByteArray) {
        val base = "IMG_${stamp()}_${pendingLens.name}"
        if (!pendingUltra) {
            saveImage(bytes, "$base.jpg")
            say("Guardada: $base.jpg (una foto, sin HDR)")
            return
        }
        val hdr = pendingHdr
        if (hdr == 100) {
            saveImage(bytes, "${base}_UHDR.jpg")
            say("Guardada: ${base}_UHDR.jpg (Ultra HDR del fabricante)")
            return
        }
        val adjusted = try { adjustGainmap(bytes, hdr / 100f) } catch (e: Throwable) { null }
        if (adjusted != null) {
            saveImage(adjusted, "${base}_UHDR$hdr.jpg")
            say("Guardada con Ultra HDR al $hdr%")
        } else {
            saveImage(bytes, "${base}_UHDR.jpg")
            say("Guardada la original. El ajuste del gain map falló en este equipo.")
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Escala el efecto del gain map del fabricante (k=0 sin HDR, 1 igual, >1 más fuerte). */
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

    // ------------------------------------------------------------------ fusión (HDR activo / mejorado / noche)

    /**
     * Ráfaga de N fotos idénticas y subexpuestas (estilo HDR+ de la GCam). Al promediarlas baja el ruido,
     * lo que permite subexponer para proteger las luces altas y después levantar las sombras.
     */
    private fun startMerge(n: Int, night: Boolean) {
        val s = session ?: return say("Cámara no lista")
        val r = reader ?: return say("Cámara no lista")
        val t0 = lastT
        val iso0 = lastIso
        if (t0 <= 0L || iso0 <= 0) return say("Esperá un segundo a que el visor mida la exposición")
        try {
            val u = hlSteps / 3f
            // Noche: +1 EV extra sobre lo que mide el visor, que en la oscuridad se queda corto
            val boost = if (night) 1.0 else 0.0
            val p = t0.toDouble() * iso0 * 2.0.pow(boost - u)
            val tMax = if (night) 125_000_000L else 33_000_000L
            val t = (p / isoMin).toLong().coerceAtMost(tMax).coerceIn(expMin, expMax)
            val iso = (p / t).roundToInt().coerceIn(isoMin, isoMax)

            pendingLens = lens
            val tag = if (night) "NOCHE" else if (hdrMode == 2) "HDRM" else "HDR"
            val m = Merger(n, u, night, ultraHdr, prof().hdr / 100f, tag, lens.name)
            merger = m

            val reqs = List(n) {
                val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, listOf(r.surface))
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(t, 33_333_333L))
                b.set(CaptureRequest.JPEG_ORIENTATION, 0)
                b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                b.build()
            }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(ses: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    m.frameFailed()
                }
            }, camHandler)
            // Red de seguridad: si algún cuadro nunca llega, fusiona con los que haya
            val waitMs = n * (max(t, 33_333_333L) / 1_000_000L + 400L) + 6_000L
            camHandler.postDelayed({ if (merger === m) m.forceFinish() }, waitMs)
            say("Toma de $n fotos (1/${(1e9 / t).roundToInt()} s, ISO $iso). Mantené quieto el celu...")
        } catch (e: Exception) {
            merger = null
            say("Ráfaga: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private inner class Merger(
        val total: Int,
        val u: Float,
        val night: Boolean,
        val makeGain: Boolean,
        val k: Float,
        val tag: String,
        val lensName: String
    ) {
        @Volatile private var received = 0
        @Volatile private var expected = total
        @Volatile private var finished = false
        private var processed = 0
        private var merged = 0

        // Solo se tocan desde mergeExec
        private var w = 0
        private var h = 0
        private var sumR: ShortArray? = null
        private var sumG: ShortArray? = null
        private var sumB: ShortArray? = null
        private var cnt: ByteArray? = null
        private var refL: ByteArray? = null
        private var refBits: ByteArray? = null
        private var refMask: ByteArray? = null
        private var sw = 0
        private var sh = 0

        fun accepting() = !finished && received < expected

        fun offer(bytes: ByteArray) {
            received++
            val got = received
            say("Capturando $got/$total... mantené quieto el celu")
            mergeExec.execute {
                try { add(bytes) } catch (_: Throwable) {}
                processed++
                checkDone()
            }
        }

        fun frameFailed() {
            expected--
            mergeExec.execute { checkDone() }
        }

        fun forceFinish() {
            expected = received
            mergeExec.execute { checkDone() }
        }

        private fun checkDone() {
            if (finished || processed < expected || received < expected) return
            finished = true
            finish()
        }

        private fun add(bytes: ByteArray) {
            // Alineación: mapa a 1/4 de resolución
            val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return
            val code = lumaCode(small)
            val smw = small.width
            val smh = small.height
            small.recycle()
            var dx = 0
            var dy = 0
            val th = median(code)
            if (refBits == null) {
                sw = smw; sh = smh
                refBits = mtb(code, th)
                refMask = mtbMask(code, th)
            } else {
                if (smw != sw || smh != sh) return
                val sft = estimateShift(refBits!!, refMask!!, mtb(code, th), mtbMask(code, th), sw, sh)
                dx = sft[0] * 4
                dy = sft[1] * 4
            }

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            if (sumR == null) {
                w = bmp.width; h = bmp.height
                val n = w * h
                sumR = ShortArray(n); sumG = ShortArray(n); sumB = ShortArray(n)
                cnt = ByteArray(n); refL = ByteArray(n)
            } else if (bmp.width != w || bmp.height != h) {
                bmp.recycle(); return
            }
            val sr = sumR!!; val sg = sumG!!; val sb = sumB!!; val c = cnt!!; val rl = refL!!
            val first = merged == 0
            val thr = if (night) 48 else 36
            val row = IntArray(w)
            for (y in 0 until h) {
                val yy = (y + dy).coerceIn(0, h - 1)
                bmp.getPixels(row, 0, w, 0, yy, w, 1)
                val base = y * w
                for (x in 0 until w) {
                    val px = row[(x + dx).coerceIn(0, w - 1)]
                    val r = (px shr 16) and 255
                    val g = (px shr 8) and 255
                    val b = px and 255
                    val l = (r + 2 * g + b) shr 2
                    val i = base + x
                    if (first) {
                        rl[i] = l.toByte()
                    } else if (abs(l - (rl[i].toInt() and 255)) > thr) {
                        continue // algo se movió: no se mezcla este punto (evita fantasmas)
                    }
                    sr[i] = (sr[i] + r).toShort()
                    sg[i] = (sg[i] + g).toShort()
                    sb[i] = (sb[i] + b).toShort()
                    c[i] = (c[i] + 1).toByte()
                }
            }
            bmp.recycle()
            merged++
        }

        private fun finish() {
            val name = "IMG_${stamp()}_${lensName}_$tag"
            try {
                if (merged == 0) { say("No llegó ninguna foto de la ráfaga"); return }
                say("Fusionando $merged fotos...")
                val out = render()
                saveImage(out, "$name.jpg")
                say("Guardada: $name.jpg ($merged fotos fusionadas)")
            } catch (t: Throwable) {
                say("Fusión: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                sumR = null; sumG = null; sumB = null; cnt = null; refL = null
                if (merger === this) merger = null
            }
        }

        /** Curva de tono: levanta las sombras por g = 2^u y comprime las luces (Reinhard extendido). */
        private fun tone(lin: Float, g: Float): Float {
            val x = lin * g
            return x * (1f + x / (g * g)) / (1f + x)
        }

        private fun render(): ByteArray {
            val g = 2f.pow(u)
            val sr = sumR!!; val sg = sumG!!; val sb = sumB!!; val c = cnt!!
            val lutSize = 255 * 16 + 1
            val linLut = FloatArray(lutSize)
            val toneLut = IntArray(lutSize)
            for (i in 0 until lutSize) {
                val code = i / (255f * 16f)
                val lin = srgbToLin(code)
                linLut[i] = lin
                toneLut[i] = (linToSrgb(tone(lin, g)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            fun idx(sum: Short, n: Int) = ((sum.toInt() * 16) / n).coerceIn(0, lutSize - 1)

            // Gain map a 1/4 de resolución: cuánto más brilla la escena real que la versión SDR
            val wantGain = makeGain && g > 1.01f
            val gw = w / 4
            val gh = h / 4
            var gmBytes: ByteArray? = null
            if (wantGain) {
                val eps = 1f / 64f
                val log2g = ln(g.toDouble()).toFloat() / ln(2f)
                val lg = FloatArray(gw * gh)
                for (by in 0 until gh) for (bx in 0 until gw) {
                    var acc = 0f
                    for (yy in 0 until 4) for (xx in 0 until 4) {
                        val i = (by * 4 + yy) * w + bx * 4 + xx
                        val n = (c[i].toInt() and 255).coerceAtLeast(1)
                        acc += 0.2126f * linLut[idx(sr[i], n)] + 0.7152f * linLut[idx(sg[i], n)] + 0.0722f * linLut[idx(sb[i], n)]
                    }
                    val lin = acc / 16f
                    val hdr = lin * g
                    val sdr = tone(lin, g)
                    val gain = (hdr + eps) / (sdr + eps)
                    lg[by * gw + bx] = ((ln(gain.toDouble()).toFloat() / ln(2f)) / log2g).coerceIn(0f, 1f)
                }
                val bl = boxBlur(lg, gw, gh, 1)
                gmBytes = ByteArray(gw * gh) { (bl[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
            }

            // Imagen SDR
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val row = IntArray(w)
            for (y in 0 until h) {
                val base = y * w
                for (x in 0 until w) {
                    val i = base + x
                    val n = (c[i].toInt() and 255).coerceAtLeast(1)
                    row[x] = (0xFF shl 24) or (toneLut[idx(sr[i], n)] shl 16) or
                        (toneLut[idx(sg[i], n)] shl 8) or toneLut[idx(sb[i], n)]
                }
                bmp.setPixels(row, 0, w, 0, y, w, 1)
            }
            sumR = null; sumG = null; sumB = null; cnt = null; refL = null

            val rot = if (w > h) sensorOrientation else 0
            val out = if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, w, h, m, true).also { bmp.recycle() }
            } else bmp

            val gm = gmBytes
            if (gm != null) {
                val rk = g.pow(k)
                if (rk > 1.01f) {
                    val (rb, rw, rh) = rotateBytes(gm, gw, gh, rot)
                    val gbmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
                    gbmp.copyPixelsFromBuffer(ByteBuffer.wrap(rb))
                    val eps = 1f / 64f
                    val gain = Gainmap(gbmp)
                    gain.setRatioMin(1f, 1f, 1f)
                    gain.setRatioMax(rk, rk, rk)
                    gain.setGamma(1f, 1f, 1f)
                    gain.setEpsilonSdr(eps, eps, eps)
                    gain.setEpsilonHdr(eps, eps, eps)
                    gain.setDisplayRatioForFullHdr(max(rk, 1.5f))
                    gain.setMinDisplayRatioForHdrTransition(1f)
                    out.setGainmap(gain)
                }
            }

            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            out.recycle()
            return bos.toByteArray()
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoBitrate() = when {
        videoSize.width >= 3840 && videoFps >= 60 -> 80_000_000
        videoSize.width >= 3840 -> 50_000_000
        videoFps >= 60 -> 30_000_000
        else -> 18_000_000
    }

    private fun createRecorder(surface: Surface, fd: FileDescriptor?): MediaRecorder {
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return MediaRecorder(this).apply {
            if (audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (fd != null) setOutputFile(fd) else setOutputFile(File(cacheDir, "dummy.mp4"))
            setVideoEncodingBitRate(videoBitrate())
            setVideoFrameRate(videoFps)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(192_000)
                setAudioChannels(2)
            }
            setInputSurface(surface)
            setOrientationHint(sensorOrientation)
        }
    }

    /** Se llama en camHandler. */
    private fun startRecording() {
        if (session == null || recSurface == null) return say("Cámara no lista para video")
        val rs = recSurface!!
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            }
            uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: return say("No pude crear el archivo de video")
            pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("sin descriptor")
            val rec = createRecorder(rs, pfd.fileDescriptor)
            rec.prepare()
            recording = true
            updatePreview()
            rec.start()
            recorder = rec
            recPfd = pfd
            recUri = uri
            runOnUiThread { updateShutter() }
        } catch (e: Exception) {
            recording = false
            try { pfd?.close() } catch (_: Exception) {}
            uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            updatePreview()
            runOnUiThread { updateShutter() }
            say("Video: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        val rec = recorder
        recorder = null
        var ok = rec != null
        try { rec?.stop() } catch (_: RuntimeException) { ok = false }
        try { rec?.release() } catch (_: Exception) {}
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        recUri?.let { uri ->
            try {
                if (ok) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) {}
        }
        recUri = null
        updatePreview()
        runOnUiThread { updateShutter() }
        say(if (ok) "Video guardado en DCIM/Camera" else "El video quedó vacío y se descartó")
    }

    // ------------------------------------------------------------------ guardado

    private fun saveImage(bytes: ByteArray, name: String) {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}

// ---------------------------------------------------------------------- utilidades de imagen

private fun srgbToLin(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linToSrgb(l: Float): Float {
    val x = l.coerceIn(0f, 1f)
    return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
}

private fun lumaCode(bmp: Bitmap): FloatArray {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    return FloatArray(w * h) { i ->
        val p = px[i]
        (0.2126f * ((p shr 16) and 255) + 0.7152f * ((p shr 8) and 255) + 0.0722f * (p and 255)) / 255f
    }
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
