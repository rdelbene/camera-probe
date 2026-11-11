package com.example.uhdrcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
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
    UGA("0.5", 0.5f, "3"),
    MAIN("1x", 1.0f, "2"),
    TELE("3", 3.0f, "5"),
    FRONT("Frontal", 1.0f, "", true)
}

/**
 * Modos del carrusel:
 *  FOTO  = la foto que procesa el chip de cámara con la afinación de Motorola + Ultra HDR con el nivel elegido
 *          (si el sistema lo permite, Ultra HDR nativo JPEG_R, el mismo mecanismo que usa la cámara stock)
 *  NOCHE = ráfaga RAW de 10/15/20 fotos fusionadas y reveladas por la app
 */
enum class Mode(val label: String) {
    VIDEO("Video"),
    FOTO("Foto"),
    NOCHE("Noche")
}

/**
 * Ajustes por lente. exp en pasos de 1/6 EV (-36..36).
 * nr/edge: 0=Auto (criterio del fabricante) 1=off 2=rapido 3=alta.
 * hdr: intensidad del gain map Ultra HDR en %.
 */
class Profile(var exp: Int = 0, var nr: Int = 0, var edge: Int = 0, var hdr: Int = 50)

/** Cuadrícula de tercios sobre el visor. */
class GridOverlay(ctx: Context) : View(ctx) {
    private val paint = Paint().apply {
        color = Color.argb(140, 255, 255, 255)
        strokeWidth = ctx.resources.displayMetrics.density
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            c.drawLine(w * i / 3, 0f, w * i / 3, h, paint)
            c.drawLine(0f, h * i / 3, w, h * i / 3, paint)
        }
    }
}

private val SHUTTER_NS = longArrayOf(
    0L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L,
    66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L
)
private val AWB_MODES = intArrayOf(
    CameraMetadata.CONTROL_AWB_MODE_AUTO,
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
)
private val AWB_LABELS = arrayOf("AWB", "DÍA", "NUB", "INC", "FLU")
private val AWB_NAMES = arrayOf("automático", "día", "nublado", "incandescente", "fluorescente")
private val TIMERS = intArrayOf(0, 3, 10)

private fun fmtT(ns: Long): String =
    if (ns >= 1_000_000_000L) "%.0f s".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()} s"

private val GREEN = Color.rgb(0, 224, 150)
/** Máximo que pone la cámara stock (1000 nits / 203 nits), medido en sus fotos. */
private const val STOCK_UHDR_MAX = 4.93f

private val DARK = Color.argb(200, 40, 40, 40)

class MainActivity : Activity(), SensorEventListener {

    // ------------------------------------------------------------------ interfaz
    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var previewFrame: FrameLayout
    private lateinit var grid: GridOverlay
    private lateinit var focusRing: View
    private lateinit var topBar: LinearLayout
    private lateinit var msgView: TextView
    private lateinit var infoPanel: TextView
    private lateinit var recTimer: TextView
    private lateinit var rightCol: LinearLayout
    private lateinit var btnEv: TextView
    private lateinit var btnHl: TextView
    private lateinit var btnTime: TextView
    private lateinit var sliderPanel: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var sliderBar: SeekBar
    private lateinit var lensRow: LinearLayout
    private lateinit var bottomArea: LinearLayout
    private lateinit var modesRow: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var shutterInner: View
    private lateinit var settingsSheet: FrameLayout
    private lateinit var prefs: SharedPreferences
    private var suppress = false
    private val main = Handler(Looper.getMainLooper())
    private val hideMsg = Runnable { msgView.visibility = View.GONE }

    // ------------------------------------------------------------------ modos y ajustes
    private var lens = Lens.MAIN
    private var lastBackLens = Lens.MAIN
    private var mode = Mode.FOTO
    private val videoMode get() = mode == Mode.VIDEO
    private val nightMode get() = mode == Mode.NOCHE
    private val stockMode get() = mode == Mode.FOTO
    /** Toma en curso del modo Stock (null si no hay). */
    @Volatile private var stockShot: BurstShot? = null
    /** Qué ofrece el celu a apps de terceros (extensiones, Ultra HDR nativo, reproceso). */
    @Volatile private var capsNote = ""
    /** Entrada para devolverle al chip la foto fusionada (reproceso YUV). */
    @Volatile private var imageWriter: android.media.ImageWriter? = null
    private var reprocSize: Size? = null
    private val reprocBlocked = mutableSetOf<String>()
    private fun busy() = merger != null || stockShot != null
    private var physicalMode = false
    private var nightFrames = 15
    /** Protección de luces altas en pasos de 1/3 EV (0..9). */
    private var hlSteps = 3
    /** Índice en SHUTTER_NS; 0 = automático. */
    private var shutterIdx = 0
    private var awbIdx = 0
    private var timerIdx = 0
    private var gridOn = true
    private var infoOn = false
    private var v4k = false
    private var v60 = false
    private var activeSlider = 0 // 0 ninguno, 1 EV, 2 luces altas, 3 tiempo

    private var vendorKeys: Set<String> = emptySet()
    private var vendorLog = ""
    private var sessionNote = ""
    private var lastNoteShown = ""
    @Volatile private var lastShotInfo = "Todavía no sacaste ninguna foto"
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
    private var recStart = 0L
    private var videoSize = Size(1920, 1080)
    private var videoFps = 30
    private var fpsRange = Range(30, 30)

    // Galería
    @Volatile private var lastUri: Uri? = null
    @Volatile private var lastMime = "image/jpeg"

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

    @Volatile private var merger: Merger? = null
    private var countdown = 0

    // RAW
    private var rawReader: ImageReader? = null
    @Volatile private var rawSupported = false
    private var rawInfo: RawInfo? = null
    private var shadingMapOk = false
    private val rawBlocked = mutableSetOf<String>()
    private var sessKey = ""
    private val rawHeld = AtomicInteger(0)
    private val rawMaxImages = 4

    // Pulso (giroscopio) y distancia focal en pixeles para estimar el movido
    @Volatile private var gyroW = 0f
    private var focalPx = 3000f

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Lens.values().forEach { l ->
            profiles[l] = Profile(
                prefs.getInt("${l.name}_exp", 0),
                0,
                0,
                prefs.getInt("${l.name}_hdr4", 50).coerceIn(0, 100)
            )
        }
        mode = Mode.values().getOrNull(prefs.getInt("mode4", Mode.FOTO.ordinal)) ?: Mode.FOTO
        nightFrames = prefs.getInt("nightFrames", 15).let { if (it in listOf(10, 15, 20)) it else 15 }
        hlSteps = 3 // protección fija de 1 EV para la ráfaga de Noche
        shutterIdx = prefs.getInt("shutterIdx", 0).coerceIn(0, SHUTTER_NS.size - 1)
        awbIdx = prefs.getInt("awbIdx", 0).coerceIn(0, AWB_MODES.size - 1)
        timerIdx = prefs.getInt("timerIdx", 0).coerceIn(0, TIMERS.size - 1)
        gridOn = prefs.getBoolean("grid", true)
        v4k = prefs.getBoolean("v4k", false)
        v60 = prefs.getBoolean("v60", false)

        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        frontId = try {
            mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            }
        } catch (_: Exception) { null }

        buildUi()
        loadLastThumb()
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        closeCamera()
        super.onPause()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        val wNow = kotlin.math.sqrt(x * x + y * y + z * z)
        gyroW = gyroW * 0.9f + wNow * 0.1f
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        try { recSurface?.release() } catch (_: Exception) {}
        mergeExec.shutdown()
        camThread.quitSafely()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsSheet.visibility == View.VISIBLE) { settingsSheet.visibility = View.GONE; return }
        if (activeSlider != 0) { closeSlider(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        else say("Sin permiso de cámara")
    }

    // ------------------------------------------------------------------ utilidades de interfaz

    private fun prof() = profiles.getValue(lens)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun save() {
        val e = prefs.edit()
        profiles.forEach { (l, p) ->
            e.putInt("${l.name}_exp", p.exp).putInt("${l.name}_nr2", p.nr)
                .putInt("${l.name}_edge2", p.edge).putInt("${l.name}_hdr4", p.hdr)
        }
        e.putInt("mode4", mode.ordinal).putInt("nightFrames", nightFrames)
            .putInt("hlSteps", hlSteps).putInt("shutterIdx", shutterIdx).putInt("awbIdx", awbIdx)
            .putInt("timerIdx", timerIdx).putBoolean("grid", gridOn)
            .putBoolean("v4k", v4k).putBoolean("v60", v60)
        e.apply()
    }

    private fun oval(fill: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun pill(fill: Int) = GradientDrawable().apply {
        cornerRadius = dp(28).toFloat()
        setColor(fill)
    }

    private fun circleBtn(text: String, sizeDp: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        background = oval(DARK)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun setOn(v: TextView, on: Boolean) {
        v.background = oval(if (on) GREEN else DARK)
        v.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun sheetLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(4))
    }

    private fun seek(maxValue: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        max = maxValue
        setPadding(dp(24), dp(6), dp(24), dp(6))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun radioRow(options: List<String>, selected: Int, onPick: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        setPadding(dp(12), 0, dp(12), 0)
        options.forEachIndexed { i, t ->
            addView(RadioButton(context).apply {
                id = View.generateViewId()
                text = t
                tag = i
                setTextColor(Color.WHITE)
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

    private fun previewTop() = if (videoMode) 0 else dp(64)
    private fun previewHeight(): Int {
        val w = resources.displayMetrics.widthPixels
        return if (videoMode) w * 16 / 9 else w * 4 / 3
    }

    private fun LinearLayout.kids(): List<View> = (0 until childCount).map { getChildAt(it) }

    // ------------------------------------------------------------------ armado de la interfaz

    private fun buildUi() {
        val sw = resources.displayMetrics.widthPixels
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // Visor
        previewFrame = FrameLayout(this)
        texture = TextureView(this)
        previewFrame.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        grid = GridOverlay(this).apply { visibility = if (gridOn) View.VISIBLE else View.GONE }
        previewFrame.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        focusRing = View(this).apply {
            background = oval(Color.TRANSPARENT, dp(2), Color.WHITE)
            visibility = View.INVISIBLE
        }
        previewFrame.addView(focusRing, FrameLayout.LayoutParams(dp(64), dp(64)))
        root.addView(previewFrame, FrameLayout.LayoutParams(sw, previewHeight()).apply { topMargin = previewTop() })

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (activeSlider != 0) { closeSlider(); return true }
                focusAt(e.x, e.y, texture.width, texture.height); return true
            }
            override fun onLongPress(e: MotionEvent) { resetFocus(true) }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 800f) {
                    val next = mode.ordinal + if (vx < 0) 1 else -1
                    Mode.values().getOrNull(next)?.let { setMode(it) }
                    return true
                }
                return false
            }
        })
        texture.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        // Barra superior
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
        }
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP))

        // Contador de video, mensajes y panel de información
        recTimer = TextView(this).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        root.addView(recTimer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(72); leftMargin = dp(16)
        })
        infoPanel = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(200, 0, 0, 0)) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        root.addView(infoPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(72); leftMargin = dp(10); rightMargin = dp(10)
        })
        msgView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.argb(190, 0, 0, 0)) }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        root.addView(msgView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(120); leftMargin = dp(24); rightMargin = dp(24)
        })

        // Botones rápidos a la derecha del visor
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        btnEv = circleBtn("EV", 48) { openSlider(1) }
        btnHl = circleBtn("LUZ", 48) { openSlider(2) }
        btnTime = circleBtn("1/s", 48) { openSlider(3) }
        listOf(btnEv, btnHl, btnTime).forEach {
            rightCol.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(10) })
        }
        root.addView(rightCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(14)
        })

        // Panel deslizable (EV / luces altas / tiempo)
        sliderLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        }
        sliderBar = seek(10) { p -> onSlider(p) }
        sliderPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.argb(190, 0, 0, 0)) }
            setPadding(dp(8), dp(8), dp(8), dp(4))
            addView(sliderLabel)
            addView(sliderBar)
            visibility = View.GONE
        }
        root.addView(sliderPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dp(14); rightMargin = dp(76)
        })

        // Lentes
        lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pill(Color.argb(140, 0, 0, 0))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        listOf(Lens.UGA, Lens.MAIN, Lens.TELE).forEach { l ->
            lensRow.addView(circleBtn(l.label, 44) { selectLens(l) }.apply { tag = l },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
        root.addView(lensRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        // Zona inferior: modos, disparador, ajustes
        bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(10))
        }
        modesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        Mode.values().forEach { m ->
            modesRow.addView(TextView(this).apply {
                text = m.label
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                tag = m
                setOnClickListener { setMode(m) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        bottomArea.addView(modesRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(10))
        }
        fun slot(v: View, size: Int) = FrameLayout(this).apply {
            addView(v, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
        }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = oval(DARK, dp(2), Color.WHITE)
            clipToOutline = true
            setOnClickListener { openLast() }
        }
        val shutter = FrameLayout(this).apply {
            background = oval(Color.TRANSPARENT, dp(4), Color.WHITE)
            setOnClickListener { onShutter() }
        }
        shutterInner = View(this)
        shutter.addView(shutterInner, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        val flip = circleBtn("⟲", 56) { flipCamera() }.apply { textSize = 24f }
        shutterRow.addView(slot(thumb, 56), LinearLayout.LayoutParams(0, dp(84), 1f))
        shutterRow.addView(slot(shutter, 84), LinearLayout.LayoutParams(0, dp(84), 1f))
        shutterRow.addView(slot(flip, 56), LinearLayout.LayoutParams(0, dp(84), 1f))
        bottomArea.addView(shutterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        bottomRow.addView(circleBtn("⚙", 52) { openSettings() }.apply { textSize = 22f })
        bottomArea.addView(bottomRow)
        root.addView(bottomArea, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        buildSettingsSheet()
        root.addView(settingsSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        updateModeUi()
        root.post { relayout() }
    }

    private fun buildSettingsSheet() {
        settingsSheet = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(240, 12, 12, 12))
            visibility = View.GONE
            isClickable = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(48))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
                text = "Ajustes"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                if (!ver.isNullOrEmpty()) append("  ·  build ${ver.substringAfterLast('.')}")
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(circleBtn("✕", 44) { settingsSheet.visibility = View.GONE }.apply { textSize = 18f })
        }
        col.addView(header)

        col.addView(TextView(this).apply {
            text = "Ajustar el Ultra HDR de fotos sacadas con la cámara de Motorola  ›"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { startActivity(Intent(this@MainActivity, HdrTunerActivity::class.java)) }
        })
        col.addView(sheetLabel("Fotos por toma en modo Noche"))
        val opts = listOf(10, 15, 20)
        col.addView(radioRow(opts.map { "$it" }, opts.indexOf(nightFrames)) {
            nightFrames = opts[it]; save(); updateModeUi()
        })
        col.addView(sheetLabel(
            "Foto: la procesa el chip de cámara con la afinación de Motorola (ruido, nitidez y color automáticos). " +
                "El botón HDR elige cuánta luz agrega el Ultra HDR: 100 % es como la cámara stock, 0 % lo apaga.\n\n" +
                "Noche: junta 10 a 20 fotos RAW para bajar el ruido; mantené quieto el celu."
        ).apply { textSize = 13f; setTextColor(Color.LTGRAY) })
        settingsSheet.addView(ScrollView(this).apply { addView(col) })
    }

    private fun openSettings() {
        closeSlider()
        refreshControls()
        settingsSheet.visibility = View.VISIBLE
    }

    /** Ubica los elementos que dependen del tamaño del visor. */
    private fun relayout() {
        val sw = resources.displayMetrics.widthPixels
        val top = previewTop()
        val ph = previewHeight()
        previewFrame.layoutParams = FrameLayout.LayoutParams(sw, ph).apply { topMargin = top }
        val bottomLimit = if (root.height > 0) root.height - bottomArea.height else top + ph
        val visBottom = min(top + ph, bottomLimit)
        (lensRow.layoutParams as FrameLayout.LayoutParams).topMargin = visBottom - dp(64)
        (rightCol.layoutParams as FrameLayout.LayoutParams).topMargin = visBottom - dp(64) - dp(186)
        (sliderPanel.layoutParams as FrameLayout.LayoutParams).topMargin = visBottom - dp(64) - dp(100)
        lensRow.requestLayout()
        rightCol.requestLayout()
        sliderPanel.requestLayout()
        previewFrame.requestLayout()
    }

    private fun rebuildTopBar() {
        topBar.removeAllViews()
        topBar.addView(circleBtn("i", 40) {
            infoOn = !infoOn
            infoPanel.visibility = if (infoOn) View.VISIBLE else View.GONE
            refreshInfo()
            rebuildTopBar()
        }.apply { setOn(this, infoOn); textSize = 16f; typeface = Typeface.SERIF })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fun add(v: TextView) = topBar.addView(v, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })

        if (videoMode) {
            add(circleBtn(if (v4k) "4K" else "FHD", 46) {
                if (!recording) { v4k = !v4k; save(); rebuildTopBar(); restartVideo() }
            })
            add(circleBtn(if (v60) "60" else "30", 46) {
                if (!recording) { v60 = !v60; save(); rebuildTopBar(); restartVideo() }
            })
        } else {
            if (nightMode) {
                add(circleBtn("$nightFrames", 46) {
                    nightFrames = when (nightFrames) { 10 -> 15; 15 -> 20; else -> 10 }
                    save(); rebuildTopBar(); say("Modo noche: $nightFrames fotos por toma")
                })
            }
            add(circleBtn(if (TIMERS[timerIdx] == 0) "⏱" else "${TIMERS[timerIdx]}s", 46) {
                timerIdx = (timerIdx + 1) % TIMERS.size; save(); rebuildTopBar()
                say(if (TIMERS[timerIdx] == 0) "Temporizador apagado" else "Temporizador: ${TIMERS[timerIdx]} s")
            }.apply { setOn(this, TIMERS[timerIdx] > 0); textSize = if (TIMERS[timerIdx] == 0) 18f else 12f })
        }
        add(circleBtn(AWB_LABELS[awbIdx], 46) {
            awbIdx = (awbIdx + 1) % AWB_MODES.size; save(); rebuildTopBar(); updatePreview()
            say("Balance de blancos: ${AWB_NAMES[awbIdx]}")
        }.apply { setOn(this, awbIdx != 0); textSize = 11f })
        add(circleBtn("#", 46) {
            gridOn = !gridOn; save()
            grid.visibility = if (gridOn) View.VISIBLE else View.GONE
            rebuildTopBar()
        }.apply { setOn(this, gridOn); textSize = 18f })
    }

    private fun updateModeUi() {
        modesRow.kids().forEach { v ->
            val sel = v.tag == mode
            (v as TextView).apply {
                background = if (sel) pill(GREEN) else null
                setTextColor(if (sel) Color.BLACK else Color.WHITE)
            }
        }
        rebuildTopBar()
        btnHl.visibility = if (videoMode) View.GONE else View.VISIBLE
        btnTime.visibility = if (nightMode) View.VISIBLE else View.GONE
        btnHl.text = "HDR"
        if (!nightMode && activeSlider == 3) closeSlider()
        if (videoMode && activeSlider >= 2) closeSlider()
        updateQuickButtons()
        updateLensRow()
        updateShutterLook()
        recTimer.visibility = if (videoMode) View.VISIBLE else View.GONE
        if (videoMode && !recording) recTimer.text = "00:00"
        relayout()
        refreshInfo()
    }

    private fun updateLensRow() {
        lensRow.visibility = if (lens.front) View.GONE else View.VISIBLE
        lensRow.kids().forEach { v ->
            val sel = v.tag == lens
            (v as TextView).apply {
                setOn(this, false)
                setTextColor(if (sel) GREEN else Color.WHITE)
                textSize = if (sel) 15f else 12f
            }
        }
    }

    private fun updateShutterLook() {
        shutterInner.background = when {
            videoMode && recording -> GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(230, 30, 30)) }
            videoMode -> oval(Color.rgb(230, 30, 30), dp(10), Color.WHITE)
            else -> oval(Color.WHITE)
        }
        val lp = shutterInner.layoutParams as FrameLayout.LayoutParams
        val s = if (videoMode && recording) dp(34) else dp(66)
        lp.width = s; lp.height = s
        shutterInner.layoutParams = lp
    }

    private fun updateQuickButtons() {
        setOn(btnEv, prof().exp != 0)
        setOn(btnHl, prof().hdr != 50)
        setOn(btnTime, shutterIdx != 0)
        btnTime.text = if (shutterIdx == 0) "1/s" else fmtT(SHUTTER_NS[shutterIdx]).replace(" s", "")
        btnTime.textSize = if (shutterIdx == 0) 12f else 10f
    }

    // ------------------------------------------------------------------ deslizadores rápidos

    private fun openSlider(kind: Int) {
        if (activeSlider == kind) { closeSlider(); return }
        activeSlider = kind
        when (kind) {
            1 -> { sliderBar.max = 72; sliderBar.progress = prof().exp + 36 }
            2 -> { sliderBar.max = 100; sliderBar.progress = prof().hdr }
            3 -> { sliderBar.max = SHUTTER_NS.size - 1; sliderBar.progress = shutterIdx }
        }
        sliderLabel.text = sliderText()
        sliderPanel.visibility = View.VISIBLE
    }

    private fun closeSlider() {
        activeSlider = 0
        sliderPanel.visibility = View.GONE
    }

    private fun onSlider(p: Int) {
        when (activeSlider) {
            1 -> { prof().exp = p - 36; save(); updatePreview() }
            2 -> { prof().hdr = p; save() }
            3 -> { shutterIdx = p; save() }
        }
        sliderLabel.text = sliderText()
        updateQuickButtons()
    }

    private fun sliderText(): String = when (activeSlider) {
        1 -> "Exposición (${lens.label}): %+.1f EV".format(prof().exp / 6f)
        2 -> {
            val h = prof().hdr
            // ojo: sin String.format sobre el texto con "%" (ese era el cierre de la app)
            "Ultra HDR (" + lens.label + "): " + h + " %  ·  " + when {
                h == 0 -> "apagado"
                h == 100 -> "como la cámara stock"
                else -> "luces hasta ×" + String.format(Locale.US, "%.1f", stockBoost())
            }
        }
        3 -> {
            val ns = SHUTTER_NS[shutterIdx]
            if (ns == 0L) "Tiempo por foto: automático"
            else "Tiempo por foto: ${fmtT(ns)}" +
                (if (ns > expMax) "\neste lente llega hasta ${fmtT(expMax)}" else "") +
                (if (ns >= 125_000_000L) "\nusá trípode o apoyá el celu" else "")
        }
        else -> ""
    }

    private fun refreshControls() {}

    // ------------------------------------------------------------------ mensajes e información

    private fun say(msg: String) = runOnUiThread {
        msgView.text = msg
        msgView.visibility = View.VISIBLE
        main.removeCallbacks(hideMsg)
        main.postDelayed(hideMsg, 3500)
    }

    private fun statusLine(): String {
        val where = when {
            lens.front -> "frontal"
            usePhysical -> "físico ${lens.physicalId}"
            else -> "lógico"
        }
        val what = when {
            videoMode -> "video ${videoSize.height}p $videoFps fps"
            nightMode -> "noche, $nightFrames fotos"
            else -> "Foto: ráfaga de 5 a 9 cuadros + Ultra HDR ${prof().hdr} %"
        }
        return "${if (lens.front) "Frontal" else lens.label} · $where · $what"
    }

    private fun refreshInfo() = runOnUiThread {
        if (infoOn) {
            val live = if (lastT > 0) "Visor: ${fmtT(lastT)} · ISO $lastIso" else "Visor: midiendo..."
            val lim = "Lente: ${fmtT(expMin.coerceAtLeast(1L))} a ${fmtT(expMax)} · ISO $isoMin a $isoMax · " +
                (if (rawSupported) "RAW sí" else "RAW no") + " · pulso %.1f°/s".format(Math.toDegrees(gyroW.toDouble()))
            infoPanel.text = listOf(statusLine(), live, lim, lastShotInfo, capsNote, (sessionNote + vendorLog).trim())
                .filter { it.isNotEmpty() }.joinToString("\n")
        }
    }

    // ------------------------------------------------------------------ galería

    private fun setThumb(bmp: Bitmap?) = runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }

    private fun loadLastThumb() {
        mergeExec.execute {
            try {
                val cols = arrayOf(MediaStore.MediaColumns._ID)
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/Camera%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                        lastUri = uri; lastMime = "image/jpeg"
                        setThumb(contentResolver.loadThumbnail(uri, Size(256, 256), null))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun openLast() {
        val uri = lastUri ?: return say("Todavía no hay fotos")
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, lastMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            say("No encontré una galería para abrirla")
        }
    }

    // ------------------------------------------------------------------ modos y lentes

    private fun setMode(m: Mode) {
        if (m == mode) return
        if (busy()) { say("Esperá a que termine de procesar la foto"); return }
        if (recording) camHandler.post { stopRecording() }
        val wasVideo = videoMode
        val wasStock = stockMode
        closeSlider()
        mode = m
        save()
        updateModeUi()
        if ((wasVideo != videoMode || wasStock != stockMode) && device != null) camHandler.post { startSession() }
    }

    private fun restartVideo() {
        if (videoMode && !recording && device != null) camHandler.post { startSession() }
    }

    private fun cameraIdFor(l: Lens) = if (l.front) (frontId ?: "1") else "0"
    private val usePhysical get() = physicalMode && !lens.front

    private fun flipCamera() {
        if (recording) { say("Detené la grabación para girar la cámara"); return }
        if (lens.front) selectLens(lastBackLens) else selectLens(Lens.FRONT)
    }

    private fun selectLens(l: Lens) {
        if (l == lens) return
        if (busy()) { say("Esperá a que termine de procesar la foto"); return }
        if (recording) camHandler.post { stopRecording() }
        if (!l.front) lastBackLens = l
        val newId = cameraIdFor(l)
        lens = l
        focusRegion = null
        afWatching = false
        focusRing.visibility = View.INVISIBLE
        updateLensRow()
        updateQuickButtons()
        if (activeSlider == 1) sliderBar.progress = prof().exp + 36
        if (activeSlider != 0) sliderLabel.text = sliderText()
        if (newId != camId) {
            closeCamera()
            openCamera()
        } else if (usePhysical) startSession() else updatePreview()
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
        (focusRing.background as GradientDrawable).setStroke(dp(2), c)
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
                            say("\"$n\" hizo caer la cámara (error $error). Lo desactivé y reabro.")
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
        try { rawReader?.close() } catch (_: Exception) {}
        rawReader = null
        try { imageWriter?.close() } catch (_: Exception) {}
        imageWriter = null
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
            try { rawReader?.close() } catch (_: Exception) {}
            rawReader = null
            rawSupported = false
            rawInfo = null
            try { imageWriter?.close() } catch (_: Exception) {}
            imageWriter = null
            reprocSize = null
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
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 6f
            val physW = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 9.6f
            focalPx = focal / physW * 4096f * (if (usePhysical || lens.front) 1f else lens.zoom)
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
                sessKey = "$camId/${if (usePhysical) lens.physicalId else "L"}/${lens.name}"
                capsNote = probeCaps(mgr, chars, map)
                val prev = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 4, 3, 1440)!!
                st.setDefaultBufferSize(prev.width, prev.height)
                val ps = Surface(st)
                previewSurface = ps
                surfaces += ps
                if (stockMode) {
                    // Foto: ráfaga de cuadros YUV ya procesados por el chip, a resolución completa
                    val ys = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 4, 3, 4096)!!
                    val r = ImageReader.newInstance(ys.width, ys.height, ImageFormat.YUV_420_888, 3)
                    r.setOnImageAvailableListener({ rd -> onYuv(rd) }, camHandler)
                    // ¿se le puede devolver al chip la foto fusionada? (reproceso YUV -> YUV del mismo tamaño)
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    reprocSize = try {
                        if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING) &&
                            sessKey !in reprocBlocked &&
                            map.inputFormats.contains(ImageFormat.YUV_420_888) &&
                            map.getInputSizes(ImageFormat.YUV_420_888)?.contains(ys) == true &&
                            map.getValidOutputFormatsForInput(ImageFormat.YUV_420_888).contains(ImageFormat.YUV_420_888)
                        ) ys else null
                    } catch (_: Exception) { null }
                    reader = r
                    surfaces += r.surface
                } else {
                    val ss = pickSize(map.getOutputSizes(ImageFormat.JPEG), 4, 3, 4096)!!
                    val r = ImageReader.newInstance(ss.width, ss.height, ImageFormat.JPEG, 8)
                    r.setOnImageAvailableListener({ rd -> onStill(rd) }, camHandler)
                    reader = r
                    surfaces += r.surface
                }
                if (nightMode && sessKey !in rawBlocked) setupRaw(chars, map)?.let { surfaces += it }
            }
            runOnUiThread { if (!videoMode) rebuildTopBar() }

            val outs = surfaces.map { s ->
                OutputConfiguration(s).also { if (usePhysical) it.setPhysicalCameraId(lens.physicalId) }
            }
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        if (s.isReprocessable) s.inputSurface?.let {
                            try { imageWriter = android.media.ImageWriter.newInstance(it, 2) } catch (_: Exception) {}
                        }
                        updatePreview()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (reprocSize != null) {
                            reprocBlocked += sessKey
                            camHandler.post { startSession() }
                            return
                        }
                        if (rawReader != null) {
                            rawBlocked += sessKey
                            say("Este lente no acepta RAW junto al visor: sigo con JPEG")
                            camHandler.post { startSession() }
                            return
                        }
                        say("No se pudo configurar la sesión (${if (videoMode) "video ${videoSize.height}p $videoFps fps" else "foto"})")
                    }
                }
            )
            reprocSize?.let { cfg.inputConfiguration = android.hardware.camera2.params.InputConfiguration(it.width, it.height, ImageFormat.YUV_420_888) }
            val sp = d.createCaptureRequest(repeatingTemplate())
            if (videoMode) sp.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            cfg.setSessionParameters(sp.build())
            d.createCaptureSession(cfg)
        } catch (e: Exception) {
            say("Sesión: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Diagnóstico: qué le ofrece el celu a una app de terceros en este lente. */
    private fun probeCaps(mgr: CameraManager, chars: CameraCharacteristics, map: android.hardware.camera2.params.StreamConfigurationMap): String = try {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val repro = when {
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING) -> "sí (YUV)"
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING) -> "sí (privado)"
            else -> "no"
        }
        val ext = try {
            val ec = mgr.getCameraExtensionCharacteristics(camId)
            val names = mapOf(0 to "auto", 1 to "retoque", 2 to "bokeh", 3 to "HDR", 4 to "noche")
            ec.supportedExtensions.joinToString(", ") { e ->
                val jr = if (Build.VERSION.SDK_INT >= 35) ec.getExtensionSupportedSizes(e, ImageFormat.JPEG_R).isNotEmpty() else false
                (names[e] ?: "$e") + if (jr) " (Ultra HDR)" else ""
            }.ifEmpty { "ninguna" }
        } catch (_: Exception) { "ninguna" }
        "Celu para apps: extensiones $ext · Ultra HDR nativo ${if (supportsJpegR(chars, map)) "sí" else "no"} · reproceso $repro"
    } catch (e: Exception) { "" }

    /** ¿El lente puede entregar Ultra HDR nativo? Necesita salida de 10 bits HLG y el formato JPEG_R. */
    private fun supportsJpegR(chars: CameraCharacteristics, map: android.hardware.camera2.params.StreamConfigurationMap): Boolean = try {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val tenBit = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
        val hlg = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            ?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) == true
        tenBit && hlg && !map.getOutputSizes(ImageFormat.JPEG_R).isNullOrEmpty()
    } catch (_: Exception) { false }

    /** Prepara el lector RAW si el lente lo permite. Devuelve su superficie o null. */
    private fun setupRaw(chars: CameraCharacteristics, map: android.hardware.camera2.params.StreamConfigurationMap): Surface? {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return null
        val cfaCode = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: return null
        val cfa = when (cfaCode) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(0, 1, 1, 2)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0, 2, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(1, 2, 0, 1)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(2, 1, 1, 0)
            else -> return null
        }
        val size = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.filter { it.width * it.height <= 17_000_000 }
            ?.maxByOrNull { it.width * it.height } ?: return null
        val blp = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = FloatArray(4) { i -> blp?.getOffsetForIndex(i % 2, i / 2)?.toFloat() ?: 64f }
        val white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        shadingMapOk = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
            ?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true
        val rr = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, rawMaxImages)
        rr.setOnImageAvailableListener({ drainRaw() }, camHandler)
        rawReader = rr
        rawInfo = RawInfo(cfa, black, white)
        rawHeld.set(0)
        rawSupported = true
        return rr.surface
    }

    /** Toma cuadros RAW de a pocos: cada uno se libera al terminar de fusionarlo, así no se llena la memoria. */
    private fun drainRaw() {
        val rr = rawReader ?: return
        while (rawHeld.get() < rawMaxImages) {
            val img = try { rr.acquireNextImage() } catch (_: Exception) { null } ?: return
            rawHeld.incrementAndGet()
            val m = merger
            if (m != null && m.accepting()) {
                m.offerRaw(img) { rawHeld.decrementAndGet(); camHandler.post { drainRaw() } }
            } else {
                img.close(); rawHeld.decrementAndGet()
            }
        }
    }

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
        b.set(CaptureRequest.CONTROL_AWB_MODE, AWB_MODES[awbIdx])
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, p.exp)
        if (p.nr > 0) b.set(CaptureRequest.NOISE_REDUCTION_MODE, p.nr - 1)
        if (p.edge > 0) b.set(CaptureRequest.EDGE_MODE, p.edge - 1)
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, if (usePhysical || lens.front) 1f else lens.zoom)
        if (videoMode) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        return b
    }

    private var infoTick = 0

    private val previewCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastT = it }
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            if (riskySwitch != null && ++okFrames >= 30) { riskySwitch = null; riskyRevert = null }
            if (infoOn && ++infoTick % 15 == 0) refreshInfo()
            if (afWatching) {
                when (res.get(CaptureResult.CONTROL_AF_STATE)) {
                    CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                        afWatching = false
                        runOnUiThread { setRingColor(GREEN); focusRing.animate().alpha(0.4f).setStartDelay(800).start() }
                    }
                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                        afWatching = false
                        runOnUiThread { setRingColor(Color.RED); focusRing.animate().alpha(0.4f).setStartDelay(800).start() }
                        say("No pudo enfocar ahí. Mantené apretado para volver a automático.")
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
            val note = (sessionNote + vendorLog).trim()
            if (note.isNotEmpty() && note != lastNoteShown) say(note)
            lastNoteShown = note
            refreshInfo()
        } catch (e: Exception) {
            say("Visor: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    // ------------------------------------------------------------------ claves propias

    // ------------------------------------------------------------------ disparador

    private fun onShutter() {
        if (videoMode) {
            camHandler.post { if (recording) stopRecording() else startRecording() }
            return
        }
        if (busy() || countdown > 0) { say("Todavía estoy procesando la foto anterior..."); return }
        val secs = TIMERS[timerIdx]
        if (secs == 0) { shoot(); return }
        countdown = secs
        val tick = object : Runnable {
            override fun run() {
                if (countdown <= 0) { shoot(); return }
                say("$countdown")
                countdown--
                main.postDelayed(this, 1000)
            }
        }
        main.post(tick)
    }

    private fun shoot() {
        countdown = 0
        when (mode) {
            Mode.NOCHE -> startMerge(nightFrames, true)
            else -> shootStock()
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
        if (m != null && m.accepting()) m.offer(bytes)
    }

    // ------------------------------------------------------------------ modo Foto (ráfaga sobre la imagen del chip)

    /** Máximo del Ultra HDR para el nivel elegido: 100 % = ×4.93 como la stock, 50 % = ×2.2, 0 % = sin HDR. */
    private fun stockBoost(): Float = STOCK_UHDR_MAX.pow(prof().hdr / 100f)

    private fun onYuv(rd: ImageReader) {
        val img = rd.acquireNextImage() ?: return
        val ts = img.timestamp
        val frame = try { toYuvFrame(img) } catch (_: Exception) { null } finally { img.close() }
        val st = stockShot
        if (st != null && frame != null) {
            if (st.awaitingReprocess) st.onReprocessed(frame) else st.offer(frame, ts)
        }
    }

    /** Escribe un cuadro YUV planar en una imagen de la cámara (respetando sus pasos de fila y pixel). */
    private fun fillYuvImage(img: Image, f: YuvFrame) {
        val yp = img.planes[0]; val yb = yp.buffer
        for (r in 0 until f.h) { yb.position(r * yp.rowStride); yb.put(f.y, r * f.w, f.w) }
        val cw = f.cw; val ch = f.ch
        for ((pi, src) in listOf(1 to f.u, 2 to f.v)) {
            val p = img.planes[pi]; val b = p.buffer; val ps = p.pixelStride; val rs = p.rowStride
            val lim = b.limit()
            for (r in 0 until ch) {
                val base = r * rs
                for (c in 0 until cw) { val k = base + c * ps; if (k < lim) b.put(k, src[r * cw + c]) }
            }
        }
    }

    /** Copia un Image YUV_420_888 (con los pasos de fila y pixel que use el chip) a planos compactos. */
    private fun toYuvFrame(img: Image): YuvFrame {
        val w = img.width; val h = img.height
        val yp = img.planes[0]
        val y = ByteArray(w * h)
        val yb = yp.buffer
        for (r in 0 until h) { yb.position(r * yp.rowStride); yb.get(y, r * w, w) }
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        fun plane(i: Int): ByteArray {
            val p = img.planes[i]; val b = p.buffer; val ps = p.pixelStride; val rs = p.rowStride
            val out = ByteArray(cw * ch)
            val row = ByteArray(rs)
            for (r in 0 until ch) {
                b.position(r * rs)
                val n = minOf(rs, b.remaining())
                b.get(row, 0, n)
                for (c in 0 until cw) { val k = c * ps; if (k < n) out[r * cw + c] = row[k] }
            }
            return out
        }
        return YuvFrame(w, h, y, plane(1), plane(2))
    }

    /**
     * Foto: el chip procesa cada cuadro (color y tono de Motorola) sin su reducción de ruido ni su nitidez;
     * la ráfaga va 0.5 a 1 paso subexpuesta para proteger las luces (como HDR+). La app alinea y fusiona
     * 5 a 9 cuadros, sube las sombras con un mapeo de tonos local, limpia el ruido de color, aplica nitidez
     * sin halos según el ruido que quedó y arma el Ultra HDR con una toma 3 pasos más oscura.
     */
    private fun shootStock() {
        val s = session ?: return say("Cámara no lista")
        val target = reader?.surface ?: return say("Cámara no lista")
        val t0 = lastT
        val iso0 = lastIso
        if (t0 <= 0L || iso0 <= 0) return say("Esperá un segundo a que el visor mida la exposición")
        val boost = stockBoost()
        val withUnder = prof().hdr > 0
        try {
            val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val nrModes = mgr.getCameraCharacteristics(camId).get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
            // como Open Camera: sin la reducción de ruido del chip (borra detalle); el ruido lo baja la fusión
            val nr = when {
                nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
                nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
            }
            // como HDR+: la ráfaga va subexpuesta para no quemar las luces; después se compensa al revelar.
            // Con poca luz se subexpone menos y se juntan más cuadros.
            val ecStops = if (iso0 < 800) 1.0f else 0.5f
            val frames = when { iso0 < 400 -> 5; iso0 < 1600 -> 7; else -> 9 }
            val total = t0.toDouble() * iso0 / Math.pow(2.0, ecStops.toDouble())
            val t = (total / isoMin).toLong().coerceAtMost(minOf(t0, 66_666_667L)).coerceIn(expMin, expMax)
            val iso = (total / t).roundToInt().coerceIn(isoMin, isoMax)
            val shot = BurstShot(frames, withUnder, boost, lens.name, ecStops)
            stockShot = shot
            fun req(tt: Long, ii: Int, tag: String) = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, listOf(target)).apply {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, tt)
                set(CaptureRequest.SENSOR_SENSITIVITY, ii)
                set(CaptureRequest.SENSOR_FRAME_DURATION, max(tt, 33_333_333L))
                set(CaptureRequest.NOISE_REDUCTION_MODE, nr)
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                setTag(tag)
            }.build()
            val reqs = MutableList(frames) { req(t, iso, "n") }
            if (withUnder) {
                // toma para el Ultra HDR: 3 pasos por debajo de lo que midió el visor
                val total = t0.toDouble() * iso0 / 8.0
                val tu = (total / isoMin).toLong().coerceAtMost(t).coerceIn(expMin, expMax)
                val iu = (total / tu).roundToInt().coerceIn(isoMin, isoMax)
                reqs += req(tu, iu, "under")
            }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ses: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    shot.onResult(req.tag as? String, res)
                }
                override fun onCaptureFailed(ses: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    shot.onFailed()
                }
            }, camHandler)
            camHandler.postDelayed({ if (stockShot === shot) shot.giveUp() }, 10_000L)
            say("Mantené quieto el celu...")
        } catch (e: Exception) {
            stockShot = null
            say("Foto: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    inner class BurstShot(val n: Int, val withUnder: Boolean, val boost: Float, val lensName: String, val ecStops: Float) {
        private val expected = n + if (withUnder) 1 else 0
        private val tagByTs = java.util.concurrent.ConcurrentHashMap<Long, String>()
        @Volatile private var got = 0
        @Volatile private var normalExposure = 0.0
        @Volatile private var underExposure = 0.0
        @Volatile private var info = ""
        @Volatile private var done = false
        private var merger: YuvMerger? = null
        @Volatile private var under: YuvFrame? = null
        @Volatile private var refResult: TotalCaptureResult? = null
        @Volatile var awaitingReprocess = false
        @Volatile private var reprocFrame: YuvFrame? = null
        private val reprocLatch = java.util.concurrent.CountDownLatch(1)

        fun onReprocessed(f: YuvFrame) {
            reprocFrame = f
            awaitingReprocess = false
            reprocLatch.countDown()
        }
        private val started = System.currentTimeMillis()

        fun offer(f: YuvFrame, ts: Long) {
            if (done) return
            val idx = got++
            val tag = tagByTs[ts]
            if (withUnder && (tag == "under" || (tag == null && idx >= n))) {
                under = f
            } else {
                mergeExec.execute {
                    try {
                        val m = merger ?: YuvMerger(f.w, f.h).also { merger = it }
                        m.add(f)
                    } catch (_: Throwable) {}
                }
                say("Foto ${minOf(idx + 1, n)}/$n")
            }
            if (got >= expected) finish()
        }

        fun onResult(tag: String?, r: TotalCaptureResult) {
            r.get(CaptureResult.SENSOR_TIMESTAMP)?.let { if (tag != null) tagByTs[it] = tag }
            val t = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val boostRaw = (r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100) / 100.0
            val e = t.toDouble() * iso * boostRaw
            if (tag == "under") underExposure = e else if (normalExposure == 0.0) { normalExposure = e; refResult = r; info = "${fmtT(t)} · ISO $iso" }
        }

        fun onFailed() {
            got++
            if (got >= expected) finish()
        }

        fun giveUp() { if (!done) finish() }

        private fun finish() {
            if (done) return
            done = true
            mergeExec.execute {
                try {
                    val m = merger ?: run { say("No llegaron las fotos"); return@execute }
                    say("Procesando ${m.frames} fotos...")
                    var waited = 0
                    while ((normalExposure == 0.0 || (under != null && underExposure == 0.0)) && waited < 1000) { Thread.sleep(20); waited += 20 }
                    val w = m.w; val h = m.h
                    val eff = m.effectiveFrames().coerceAtLeast(1f)
                    // Reproceso: la foto fusionada vuelve al chip, que aplica la reducción de ruido y la nitidez
                    // de Motorola sabiendo que ahora tiene ~eff veces menos ruido.
                    var reprocNote = "sin reproceso"
                    var planes: Triple<FloatArray, ByteArray, ByteArray>? = null
                    val wr = imageWriter; val ref = refResult; val sess = session; val tgt = reader?.surface; val dev = device
                    if (wr != null && ref != null && sess != null && tgt != null && dev != null && sess.isReprocessable) {
                        try {
                            val merged = m.result(false)
                            val img = wr.dequeueInputImage()
                            fillYuvImage(img, merged)
                            img.timestamp = ref.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                            awaitingReprocess = true
                            wr.queueInputImage(img)
                            val rb = dev.createReprocessCaptureRequest(ref)
                            rb.addTarget(tgt)
                            rb.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                            rb.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                            rb.set(CaptureRequest.REPROCESS_EFFECTIVE_EXPOSURE_FACTOR, eff)
                            sess.capture(rb.build(), null, camHandler)
                            if (reprocLatch.await(6, java.util.concurrent.TimeUnit.SECONDS)) {
                                reprocFrame?.let { planes = it.planes(); reprocNote = "reproceso del chip ✓" }
                            } else reprocNote = "reproceso sin respuesta"
                        } catch (e: Exception) {
                            reprocNote = "reproceso falló: ${e.javaClass.simpleName}"
                        } finally {
                            awaitingReprocess = false
                        }
                    }
                    val reprocessed = planes != null
                    val (yF, uB, vB) = planes ?: m.resultPlanes()
                    // revelado: compensa la subexposición y sube las sombras localmente (sin halos)
                    val (px, lift) = toneMapToArgb(yF, uB, vB, w, h, ecStops, 1.6f)
                    // ruido que queda (0..1); en las sombras levantadas es mayor
                    val sigmaOut = (m.noiseSigma / 255f / kotlin.math.sqrt(eff) * (1f + 0.5f * lift)).coerceIn(0f, 0.05f)
                    val stock = StockProfiles.forLens(lensName)
                    // si el chip ya hizo la reducción de ruido y la nitidez, solo un toque para compensar la suba de sombras
                    val dp = if (reprocessed) DetailParams(1.1f, 1.05f, 1f, sigmaOut * 1.5f, 0f)
                        else detailParamsFor(sigmaOut, false, 0, 0, stock.sharpMul, stock.nrMul)
                    detailEnhance(px, w, h, dp)

                    // Ultra HDR: toma oscura contra la fusionada
                    val u = under
                    // la foto final equivale a la exposición de la ráfaga compensada (+ecStops)
                    val ratio = if (u != null && underExposure > 0.0 && normalExposure > 0.0)
                        (normalExposure / underExposure * Math.pow(2.0, ecStops.toDouble())).toFloat() else 0f
                    var gm: ByteArray? = null
                    var gw = 0; var gh = 0
                    if (u != null && ratio > 1f && u.w == w && u.h == h) {
                        val (sa, sw, sh) = argbSmall(px, w, h, 4)
                        val (ub, _, _) = yuvToArgbSmall(u, 4)
                        gm = stockGainMap(sa, ub, sw, sh, ratio, boost)
                        gw = sw; gh = sh
                    }
                    val out = encodeUltraHdr(px, w, h, gm, gw, gh, boost)
                    val uri = saveImage(out, "IMG_${stamp()}_$lensName.jpg")
                    if (uri != null) { lastUri = uri; lastMime = "image/jpeg" }
                    setThumb(BitmapFactory.decodeByteArray(out, 0, out.size, BitmapFactory.Options().apply { inSampleSize = 16 }))
                    val secs = (System.currentTimeMillis() - started) / 1000.0
                    lastShotInfo = "Última: FOTO · $info (−%.1f EV) · ${m.frames} cuadros (efectivos %.1f) · desplaz. máx ${m.maxShift} px\n".format(ecStops, eff) +
                        "$reprocNote · sombras +%.1f pasos · ".format(lift) +
                        "ruido por cuadro %.1f → %.1f · nitidez ×%.2f/×%.2f · ".format(m.noiseSigma, m.noiseSigma / kotlin.math.sqrt(eff), dp.gFine, dp.gMid) +
                        (if (gm != null) "Ultra HDR ×%.1f".format(boost) else if (withUnder) "sin luces que realzar" else "Ultra HDR apagado") +
                        " · %.1f s".format(secs)
                    refreshInfo()
                    say("Guardada")
                } catch (t: Throwable) {
                    say("Foto: ${t.javaClass.simpleName} ${t.message}")
                } finally {
                    merger = null; under = null
                    if (stockShot === this@BurstShot) stockShot = null
                }
            }
        }
    }

    /** Gira según el sensor, adjunta el gain map (si hay) y comprime. */
    private fun encodeUltraHdr(px: IntArray, w: Int, h: Int, gm: ByteArray?, gw: Int, gh: Int, boost: Float): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val rot = if (w > h) sensorOrientation else 0
        val img = if (rot != 0) {
            Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        if (gm != null && boost > 1.01f) {
            val (rb, rw, rh) = rotateBytes(gm, gw, gh, rot)
            val gbmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ALPHA_8)
            gbmp.copyPixelsFromBuffer(ByteBuffer.wrap(rb))
            val eps = 1f / 64f
            val gain = Gainmap(gbmp)
            gain.setRatioMin(1f, 1f, 1f)
            gain.setRatioMax(boost, boost, boost)
            gain.setGamma(1f, 1f, 1f)
            gain.setEpsilonSdr(eps, eps, eps)
            gain.setEpsilonHdr(eps, eps, eps)
            gain.setDisplayRatioForFullHdr(boost)
            gain.setMinDisplayRatioForHdrTransition(1f)
            img.setGainmap(gain)
        }
        val bos = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        img.recycle()
        return bos.toByteArray()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------------ fusión (Foto / Mejorado / Noche)

    /**
     * Ráfaga de N fotos idénticas y subexpuestas (estilo HDR+ de la GCam). Al promediarlas baja el ruido,
     * lo que permite subexponer para proteger las luces altas y después levantar las sombras.
     */
    private fun startMerge(n: Int, night: Boolean) {
        val s = session ?: return say("Cámara no lista")
        val useRaw = rawSupported && rawReader != null
        val target = (if (useRaw) rawReader else reader)?.surface ?: return say("Cámara no lista")
        val t0 = lastT
        val iso0 = lastIso
        if (t0 <= 0L || iso0 <= 0) return say("Esperá un segundo a que el visor mida la exposición")
        try {
            val u = hlSteps / 3f
            // Noche: +1 EV extra sobre lo que mide el visor, que en la oscuridad se queda corto
            val boost = if (night) 1.0 else 0.0
            val p = t0.toDouble() * iso0 * 2.0.pow(boost - u)

            // Tiempo máximo según el pulso: el movido no debería pasar de ~2 pixeles
            val omega = gyroW
            val steady = omega < 0.01f
            val tMotion = if (omega < 0.002f) 4_000_000_000L else (2.0 / (omega * focalPx) * 1e9).toLong()
            val manual = SHUTTER_NS[shutterIdx]
            val t = if (manual > 0L) {
                manual.coerceIn(expMin, expMax)
            } else {
                val cap = if (night) (if (steady) 1_000_000_000L else 333_000_000L) else 33_000_000L
                val tMax = min(cap, tMotion).coerceAtLeast(500_000L)
                (p / isoMin).toLong().coerceAtMost(tMax).coerceIn(expMin, expMax)
            }
            val iso = (p / t).roundToInt().coerceIn(isoMin, isoMax)
            val motionNote = "pulso %.1f°/s%s".format(Math.toDegrees(omega.toDouble()), if (steady) " (apoyado)" else "")

            val tag = "NOCHE"
            val m = Merger(n, u, night, prof().hdr > 0, prof().hdr / 100f, tag, lens.name, t, iso,
                if (useRaw) rawInfo else null, prof().exp / 6f, motionNote, 0, 0, true)
            merger = m

            val reqs = List(n) {
                val b = buildRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, listOf(target))
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(t, 33_333_333L))
                if (useRaw) {
                    if (shadingMapOk) b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                } else {
                    b.set(CaptureRequest.JPEG_ORIENTATION, 0)
                    b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }
                b.build()
            }
            s.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ses: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    m.onResult(res)
                }
                override fun onCaptureFailed(ses: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    m.frameFailed()
                }
            }, camHandler)
            // Red de seguridad: si algún cuadro nunca llega, fusiona con los que haya
            val waitMs = n * (max(t, 33_333_333L) / 1_000_000L + (if (useRaw) 900L else 400L)) + 10_000L
            camHandler.postDelayed({ if (merger === m) m.forceFinish() }, waitMs)
            say("$n fotos${if (useRaw) " RAW" else ""} · ${fmtT(t)} · ISO $iso" +
                (if (t >= 125_000_000L) "\nno muevas el celu (${"%.1f".format(n * max(t, 33_333_333L) / 1e9)} s)" else "\nmantené quieto el celu"))
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
        val lensName: String,
        val shotT: Long,
        val shotIso: Int,
        val raw: RawInfo?,
        val evComp: Float,
        val motionNote: String,
        /** Ajustes "Nitidez" y "Reducción de ruido" del lente: 0 auto, 1 off, 2 rápido, 3 alta. */
        val sharp: Int,
        val nrLevel: Int,
        val look: Boolean
    ) {
        /** Datos medidos de la stock para este lente (ruido, sombreado, color, estilo). */
        private val stock = StockProfiles.forLens(lensName)
        private var detailNote = ""
        @Volatile private var received = 0
        @Volatile private var expected = total
        @Volatile private var finished = false
        @Volatile private var firstResult: TotalCaptureResult? = null
        private val started = System.currentTimeMillis()
        private var processed = 0
        private var merged = 0
        private var maxShift = 0
        private var tilesMoved = 0
        private var tilesTotal = 0

        // Alineación (solo mergeExec)
        private var refSmall: FloatArray? = null
        private var refBits: ByteArray? = null
        private var refMask: ByteArray? = null
        private var sw = 0
        private var sh = 0
        private var w = 0
        private var h = 0

        // Acumuladores JPEG
        private var sumR: ShortArray? = null
        private var sumG: ShortArray? = null
        private var sumB: ShortArray? = null
        private var refL: ByteArray? = null
        // Acumuladores RAW: suma ponderada y suma de pesos (fusión según el modelo de ruido)
        private var rawSum: FloatArray? = null
        private var rawW: FloatArray? = null
        private var rawRef: ShortArray? = null
        private var cnt: ByteArray? = null

        /** Modelo de ruido que informa el sensor para esta toma; si no lo informa, uno genérico según el ISO. */
        private fun noiseModel(): NoiseModel {
            val prof = firstResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
            if (prof.isNullOrEmpty()) return stock.noiseModel(shotIso)
            return NoiseModel(
                FloatArray(4) { prof[min(it, prof.size - 1)].first.toFloat() },
                FloatArray(4) { prof[min(it, prof.size - 1)].second.toFloat() }
            )
        }

        fun accepting() = !finished && received < expected

        fun onResult(r: TotalCaptureResult) { if (firstResult == null) firstResult = r }

        fun offer(bytes: ByteArray) {
            received++
            say("Capturando $received/$total...")
            mergeExec.execute {
                try { addJpeg(bytes) } catch (_: Throwable) {}
                processed++
                checkDone()
            }
        }

        fun offerRaw(img: Image, release: () -> Unit) {
            received++
            say("Capturando $received/$total (RAW)...")
            mergeExec.execute {
                try { addRaw(img) } catch (_: Throwable) {} finally {
                    try { img.close() } catch (_: Throwable) {}
                    release()
                }
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

        /** Devuelve el mapa de desplazamientos de este cuadro respecto del primero (null para el primero). */
        private fun align(code: FloatArray, smw: Int, smh: Int, fw: Int, fh: Int, even: Boolean): ShiftMap? {
            val th = median(code)
            if (refSmall == null) {
                sw = smw; sh = smh
                refSmall = code
                refBits = mtb(code, th)
                refMask = mtbMask(code, th)
                return null
            }
            if (smw != sw || smh != sh) throw IllegalStateException("cuadro de otro tamaño")
            val g = estimateShift(refBits!!, refMask!!, mtb(code, th), mtbMask(code, th), sw, sh)
            val tiles = tileShifts(refSmall!!, code, sw, sh, g[0], g[1])
            tilesMoved += tiles.moved
            tilesTotal += tiles.dx.size
            val m = buildShiftMap(tiles, fw, fh, 4, even)
            maxShift = max(maxShift, m.maxAbs)
            return m
        }

        private fun addJpeg(bytes: ByteArray) {
            val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return
            val code = lumaCode(small)
            val smw = small.width
            val smh = small.height
            small.recycle()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val fw = bmp.width
            val fh = bmp.height
            if (sumR != null && (fw != w || fh != h)) { bmp.recycle(); return }
            val sm = align(code, smw, smh, fw, fh, false)
            val px = IntArray(fw * fh)
            bmp.getPixels(px, 0, fw, 0, 0, fw, fh)
            bmp.recycle()
            if (sumR == null) {
                w = fw; h = fh
                val n = w * h
                sumR = ShortArray(n); sumG = ShortArray(n); sumB = ShortArray(n)
                cnt = ByteArray(n); refL = ByteArray(n)
            }
            val sr = sumR!!; val sg = sumG!!; val sb = sumB!!; val c = cnt!!; val rl = refL!!
            val thr = if (night) 48 else 36
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sx = x
                    var sy = y
                    if (sm != null) {
                        val bi = sm.idx(x, y)
                        sx += sm.dx[bi]; sy += sm.dy[bi]
                        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                    }
                    val p = px[sy * w + sx]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    val l = (r + 2 * g + b) shr 2
                    val i = y * w + x
                    if (sm == null) {
                        rl[i] = l.toByte()
                    } else if (abs(l - (rl[i].toInt() and 255)) > thr) {
                        continue // algo se movió: no se mezcla este punto
                    }
                    sr[i] = (sr[i] + r).toShort()
                    sg[i] = (sg[i] + g).toShort()
                    sb[i] = (sb[i] + b).toShort()
                    c[i] = (c[i] + 1).toByte()
                }
            }
            merged++
        }

        private fun addRaw(img: Image) {
            val ri = raw ?: return
            val fw = img.width
            val fh = img.height
            if (rawSum != null && (fw != w || fh != h)) return
            val pl = img.planes[0]
            val sbuf = pl.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val rs = pl.rowStride / 2
            val blackAvg = ri.black.average().toFloat()
            val range = (ri.white - blackAvg).coerceAtLeast(1f)

            // Luma reducida (bloques de 4x4 del mosaico) para alinear
            val smw = fw / 4
            val smh = fh / 4
            val code = FloatArray(smw * smh)
            for (sy in 0 until smh) {
                for (sx in 0 until smw) {
                    var s = 0
                    for (yy in 0 until 4) {
                        val base = (sy * 4 + yy) * rs + sx * 4
                        for (xx in 0 until 4) s += sbuf.get(base + xx).toInt() and 0xFFFF
                    }
                    val v = ((s / 16f - blackAvg) / range).coerceIn(0f, 1f)
                    code[sy * smw + sx] = kotlin.math.sqrt(v)
                }
            }
            val sm = align(code, smw, smh, fw, fh, true)
            if (rawSum == null) {
                w = fw; h = fh
                val n = w * h
                rawSum = FloatArray(n); rawW = FloatArray(n); rawRef = ShortArray(n)
            }
            val sum = rawSum!!; val wt = rawW!!; val ref = rawRef!!
            // Varianza esperada en unidades crudas: S·range·(v-negro) + O·range², por canal del mosaico
            val nm = noiseModel()
            val sR = FloatArray(4) { nm.s[it] * range }
            val oR = FloatArray(4) { nm.o[it] * range * range }
            val k = if (night) 12f else 9f
            for (y in 0 until h) {
                val py = (y and 1) * 2
                for (x in 0 until w) {
                    val i = y * w + x
                    if (sm == null) {
                        val v = sbuf.get(y * rs + x).toInt() and 0xFFFF
                        ref[i] = v.toShort()
                        sum[i] = v.toFloat()
                        wt[i] = 1f
                    } else {
                        val bi = sm.idx(x, y)
                        val sx = x + sm.dx[bi]
                        val sy = y + sm.dy[bi]
                        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                        val v = (sbuf.get(sy * rs + sx).toInt() and 0xFFFF).toFloat()
                        val rv = (ref[i].toInt() and 0xFFFF).toFloat()
                        val pos = py + (x and 1)
                        val varDiff = 2f * (sR[pos] * (rv - blackAvg).coerceAtLeast(0f) + oR[pos])
                        val wgt = mergeWeight(v - rv, varDiff, k)
                        if (wgt > 0f) { sum[i] += wgt * v; wt[i] += wgt }
                    }
                }
            }
            merged++
        }

        private fun finish() {
            val name = "IMG_${stamp()}_${lensName}_$tag"
            try {
                if (merged == 0) { say("No llegó ninguna foto de la ráfaga"); return }
                say("Procesando $merged fotos${if (raw != null) " RAW" else ""}...")
                val out = if (raw != null) renderRaw() else renderJpeg()
                val uri = saveImage(out, "$name.jpg")
                if (uri != null) { lastUri = uri; lastMime = "image/jpeg" }
                setThumb(BitmapFactory.decodeByteArray(out, 0, out.size, BitmapFactory.Options().apply { inSampleSize = 16 }))
                val secs = (System.currentTimeMillis() - started) / 1000.0
                val tilesPct = if (tilesTotal > 0) 100 * tilesMoved / tilesTotal else 0
                lastShotInfo = "Última: $tag ${if (raw != null) "RAW" else "JPEG"} · $merged/$total fotos · ${fmtT(shotT)} · ISO $shotIso\n" +
                    "desplaz. máx $maxShift px · zonas corregidas $tilesPct% · $motionNote\n" +
                    "%.1f s de proceso · %s".format(secs, if (lastGain) "Ultra HDR" else "JPEG común") +
                    (if (detailNote.isNotEmpty()) "\n$detailNote" else "")
                refreshInfo()
                say("Guardada ($merged fotos${if (raw != null) " RAW" else ""})")
            } catch (t: Throwable) {
                say("Fusión: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                sumR = null; sumG = null; sumB = null; refL = null
                rawSum = null; rawW = null; rawRef = null; cnt = null
                if (merger === this) merger = null
            }
        }

        private var lastGain = false

        private fun renderJpeg(): ByteArray {
            val g = 2f.pow(u)
            val tone = { lin: Float -> reinhard(lin, g) }
            val sr = sumR!!; val sg = sumG!!; val sb = sumB!!; val c = cnt!!
            val lutSize = 255 * 16 + 1
            val linLut = FloatArray(lutSize)
            val toneLut = IntArray(lutSize)
            for (i in 0 until lutSize) {
                val lin = srgbToLin(i / (255f * 16f))
                linLut[i] = lin
                toneLut[i] = (linToSrgb(tone(lin)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            fun idx(s: Short, n: Int) = ((s.toInt() * 16) / n).coerceIn(0, lutSize - 1)

            val gw = w / 4
            val gh = h / 4
            var gm: ByteArray? = null
            if (makeGain && g > 1.01f) {
                val cell = FloatArray(gw * gh)
                for (by in 0 until gh) for (bx in 0 until gw) {
                    var acc = 0f
                    for (yy in 0 until 4) for (xx in 0 until 4) {
                        val i = (by * 4 + yy) * w + bx * 4 + xx
                        val n = (c[i].toInt() and 255).coerceAtLeast(1)
                        acc += 0.2126f * linLut[idx(sr[i], n)] + 0.7152f * linLut[idx(sg[i], n)] + 0.0722f * linLut[idx(sb[i], n)]
                    }
                    cell[by * gw + bx] = acc / 16f
                }
                gm = gainBytes(cell, gw, gh, g, tone)
            }

            val px = IntArray(w * h)
            for (i in 0 until w * h) {
                val n = (c[i].toInt() and 255).coerceAtLeast(1)
                px[i] = (0xFF shl 24) or (toneLut[idx(sr[i], n)] shl 16) or
                    (toneLut[idx(sg[i], n)] shl 8) or toneLut[idx(sb[i], n)]
            }
            sumR = null; sumG = null; sumB = null; cnt = null; refL = null
            // Los JPEG ya vienen con nitidez del procesador del celu: realce suave, sin limpieza de ruido
            val dParams = when (sharp) {
                1 -> null
                3 -> DetailParams(1.25f, 1.35f, 1.1f, 0f, 0f)
                else -> DetailParams(1.1f, 1.2f, 1.06f, 0f, 0f)
            }
            if (dParams != null) {
                detailEnhance(px, w, h, dParams)
                detailNote = "realce JPEG ×%.2f/×%.2f".format(dParams.gFine, dParams.gMid)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            return encode(bmp, gm, gw, gh, g.pow(k))
        }

        /** Revelado del RAW fusionado: negro/blanco, sombreado del lente, balance, demosaico, matriz de color y tono. */
        private fun renderRaw(): ByteArray {
            val ri = raw!!
            val res = firstResult
            val wbv = res?.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val wb = floatArrayOf(wbv?.red ?: stock.wb[0], wbv?.greenEven ?: 1f, wbv?.greenOdd ?: 1f, wbv?.blue ?: stock.wb[2])
            val ccmT = res?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            val ccm = if (ccmT != null) FloatArray(9) { i -> ccmT.getElement(i % 3, i / 3).toFloat() } else stock.ccm()
            val black = res?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) ?: ri.black
            val white = (res?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: ri.white).toFloat()
            val lsm = res?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            val sum = rawSum!!; val wt = rawW!!

            // Sombreado del lente (viñeteo y color en las esquinas)
            val cols = lsm?.columnCount ?: 1
            val rows = lsm?.rowCount ?: 1
            val grids = Array(4) { ch -> FloatArray(cols * rows) { gi -> lsm?.getGainFactor(ch, gi % cols, gi / cols) ?: 1f } }
            fun shade(ch: Int, x: Int, y: Int): Float {
                if (lsm == null) return radialShading(stock.shadingCorner, x, y, w, h)
                val gx = if (cols > 1) x * (cols - 1f) / (w - 1) else 0f
                val gy = if (rows > 1) y * (rows - 1f) / (h - 1) else 0f
                val x0 = gx.toInt().coerceAtMost(cols - 1); val x1 = (x0 + 1).coerceAtMost(cols - 1)
                val y0 = gy.toInt().coerceAtMost(rows - 1); val y1 = (y0 + 1).coerceAtMost(rows - 1)
                val fx = gx - x0; val fy = gy - y0
                val gr = grids[ch]
                val a = gr[y0 * cols + x0] * (1 - fx) + gr[y0 * cols + x1] * fx
                val b = gr[y1 * cols + x0] * (1 - fx) + gr[y1 * cols + x1] * fx
                return a * (1 - fy) + b * fy
            }

            // Se normaliza en el mismo arreglo de la suma para no ocupar otros 50 MB
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val pos = (y and 1) * 2 + (x and 1)
                    val col = ri.cfa[pos]
                    val ch = when (col) { 0 -> 0; 2 -> 3; else -> if ((y and 1) == 0) 1 else 2 }
                    val wv = wt[i]
                    val avg = if (wv > 0f) sum[i] / wv else black[pos]
                    val v = (avg - black[pos]) / (white - black[pos])
                    sum[i] = (v * shade(ch, x, y) * wb[ch]).coerceAtLeast(0f)
                }
            }
            val plane = sum
            rawSum = null; rawW = null; rawRef = null

            // Brillo automático (clave de la escena) y compensación del usuario
            var acc = 0.0
            var ns = 0
            var y0 = 0
            while (y0 < h - 1) {
                var x0 = 0
                while (x0 < w - 1) {
                    var r = 0f; var g = 0f; var b = 0f
                    for (dy in 0..1) for (dx in 0..1) {
                        val v = plane[(y0 + dy) * w + x0 + dx]
                        when (ri.cfa[dy * 2 + dx]) { 0 -> r += v; 2 -> b += v; else -> g += v * 0.5f }
                    }
                    acc += ln((0.25f * r + 0.5f * g + 0.25f * b).toDouble() + 1e-4)
                    ns++
                    x0 += 16
                }
                y0 += 16
            }
            val geo = kotlin.math.exp(acc / max(ns, 1)).toFloat()
            val g0 = 2f.pow(u)
            val target = if (night) 0.09f else 0.16f
            val bigG = (target / geo).coerceIn(g0 * 0.6f, g0 * 3f) * 2f.pow(evComp)
            val tone = { lin: Float -> if (bigG > 1f) reinhard(lin, bigG) else lin * bigG }

            // LUT de salida indexada por raíz del valor lineal (más precisión en sombras)
            val lut = IntArray(4096) { i ->
                val lin = (i / 4095f).let { it * it }
                (linToSrgb(tone(lin)) * 255f + 0.5f).toInt().coerceIn(0, 255)
            }
            val gw = w / 4
            val gh = h / 4
            val wantGain = makeGain && bigG > 1.01f
            val cell = if (wantGain) FloatArray(gw * gh) else null

            // Demosaico Malvar-He-Cutler + color + tono
            val px = demosaicMhc(plane, w, h, ri.cfa, ccm, lut, cell, gw, gh)

            // Ruido que queda tras la fusión, llevado a la salida (tono medio) para calibrar el realce
            val nm = noiseModel()
            val xm = (0.18f / bigG).coerceIn(0.002f, 0.5f)
            val sigLin = nm.sigma(1, xm) / kotlin.math.sqrt(max(merged, 1).toFloat()) * 1.3f
            val sigmaOut = (linToSrgb(tone(xm + sigLin)) - linToSrgb(tone(xm))).coerceIn(0f, 0.05f)
            if (look) applyLook(px, stockToneLut(stock, 0.6f), stock.saturationAt(shotIso))
            val dParams = detailParamsFor(sigmaOut, night, sharp, nrLevel, stock.sharpMul, stock.nrMul)
            detailEnhance(px, w, h, dParams)
            detailNote = "realce ×%.1f/×%.1f · ruido %.3f · %s%s%s".format(dParams.gFine, dParams.gMid, sigmaOut, stock.name,
                if (look) " · estilo Motorola" else "",
                (if (lsm == null) " · sombreado estimado" else "") + (if (ccmT == null) " · color de la stock" else ""))

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val gm = if (cell != null) gainBytes(cell, gw, gh, bigG, tone) else null
            return encode(bmp, gm, gw, gh, bigG.pow(k))
        }

        /** Gira, adjunta el gain map (si hay) y comprime. */
        private fun encode(bmp: Bitmap, gm: ByteArray?, gw: Int, gh: Int, rk: Float): ByteArray {
            val bw = bmp.width
            val bh = bmp.height
            val rot = if (bw > bh) sensorOrientation else 0
            val out = if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bw, bh, m, true).also { bmp.recycle() }
            } else bmp
            lastGain = false
            if (gm != null && rk > 1.01f) {
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
                lastGain = true
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

    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (System.currentTimeMillis() - recStart) / 1000
            recTimer.text = "● %02d:%02d".format(s / 60, s % 60)
            main.postDelayed(this, 500)
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
            recStart = System.currentTimeMillis()
            runOnUiThread { updateShutterLook(); main.post(recTick) }
        } catch (e: Exception) {
            recording = false
            try { pfd?.close() } catch (_: Exception) {}
            uri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            updatePreview()
            runOnUiThread { updateShutterLook() }
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
        val uri = recUri
        recUri = null
        if (uri != null) {
            try {
                if (ok) {
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    lastUri = uri; lastMime = "video/mp4"
                    setThumb(try { contentResolver.loadThumbnail(uri, Size(256, 256), null) } catch (_: Exception) { null })
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) {}
        }
        updatePreview()
        runOnUiThread { updateShutterLook(); recTimer.text = "00:00" }
        say(if (ok) "Video guardado" else "El video quedó vacío y se descartó")
    }

    // ------------------------------------------------------------------ guardado

    private fun saveImage(bytes: ByteArray, name: String): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
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

// ---------------------------------------------------------------------- RAW y alineación por zonas

/** Datos fijos del sensor para revelar RAW. cfa: canal (0 R, 1 G, 2 B) de cada posición del bloque 2x2. */
class RawInfo(val cfa: IntArray, val black: FloatArray, val white: Int)



private fun reinhard(lin: Float, g: Float): Float {
    val x = lin * g
    return x * (1f + x / (g * g)) / (1f + x)
}



/** Gain map: relación entre la escena real (lineal * G) y la versión SDR con tono, en escala log2 normalizada. */
private fun gainBytes(cell: FloatArray, gw: Int, gh: Int, g: Float, tone: (Float) -> Float): ByteArray {
    val eps = 1f / 64f
    val log2g = ln(g.toDouble()).toFloat() / ln(2f)
    val lg = FloatArray(gw * gh) { i ->
        val lin = cell[i]
        val gain = (lin * g + eps) / (tone(lin) + eps)
        ((ln(gain.toDouble()).toFloat() / ln(2f)) / log2g).coerceIn(0f, 1f)
    }
    val bl = boxBlur(lg, gw, gh, 1)
    return ByteArray(gw * gh) { (bl[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }
}
