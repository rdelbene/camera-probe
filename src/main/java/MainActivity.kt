package com.example.cameraprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = try {
            probeCameras(this)
        } catch (e: Exception) {
            "Error: $e"
        }

        val button = Button(this).apply {
            text = "Copiar reporte"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("reporte", report))
                Toast.makeText(this@MainActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }

        val textView = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(this).apply { addView(textView) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button)
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)
    }
}

fun probeCameras(context: Context): String {
    val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val sb = StringBuilder()
    sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    sb.appendLine("IDs visibles: ${mgr.cameraIdList.toList()}")
    sb.appendLine("Legend NR/Edge: 0=off 1=fast 2=high quality 3=minimal 4=zsl")
    sb.appendLine()

    for (id in mgr.cameraIdList) {
        val c = mgr.getCameraCharacteristics(id)
        sb.appendLine("=== Camara $id ===")
        sb.append(describe(c))

        val physical = c.physicalCameraIds
        if (physical.isNotEmpty()) {
            sb.appendLine("Lentes fisicos: $physical")
            for (pid in physical) {
                sb.appendLine()
                sb.appendLine("  --- fisico $pid ---")
                try {
                    sb.append(describe(mgr.getCameraCharacteristics(pid)).prependIndent("  "))
                } catch (e: Exception) {
                    sb.appendLine("  No accesible: ${e.javaClass.simpleName}")
                }
            }
        }
        sb.appendLine()
    }
    return sb.toString()
}

private fun describe(c: CC): String {
    val sb = StringBuilder()

    val facing = when (c.get(CC.LENS_FACING)) {
        CC.LENS_FACING_BACK -> "trasera"
        CC.LENS_FACING_FRONT -> "frontal"
        else -> "otra"
    }
    sb.appendLine("Orientacion: $facing")

    val focals = c.get(CC.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    val size = c.get(CC.SENSOR_INFO_PHYSICAL_SIZE)
    val f = focals?.firstOrNull()
    if (f != null && size != null) {
        val equiv = f * 43.27f / hypot(size.width, size.height)
        sb.appendLine("Focal: %.2f mm (~%.0f mm equiv. 35mm)".format(f, equiv))
    } else {
        sb.appendLine("Focal: ${focals?.toList()}")
    }
    sb.appendLine("Sensor mm: $size | pixeles: ${c.get(CC.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")

    if (Build.VERSION.SDK_INT >= 30) {
        sb.appendLine("Zoom ratio: ${c.get(CC.CONTROL_ZOOM_RATIO_RANGE)}")
    }

    val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
    val formats = map?.outputFormats?.toSet().orEmpty()
    val jpegR = Build.VERSION.SDK_INT >= 34 && ImageFormat.JPEG_R in formats
    sb.appendLine("JPEG_R (Ultra HDR): ${if (jpegR) "SI" else "no"}")
    if (jpegR) {
        val sizes = map?.getOutputSizes(ImageFormat.JPEG_R)?.take(3)
        sb.appendLine("  tamanos JPEG_R: $sizes")
    }
    sb.appendLine("JPEG: ${ImageFormat.JPEG in formats} | RAW: ${ImageFormat.RAW_SENSOR in formats} | YUV: ${ImageFormat.YUV_420_888 in formats}")

    sb.appendLine("Comp. exposicion: ${c.get(CC.CONTROL_AE_COMPENSATION_RANGE)} paso ${c.get(CC.CONTROL_AE_COMPENSATION_STEP)}")
    sb.appendLine("Noise reduction: ${c.get(CC.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList()}")
    sb.appendLine("Edge: ${c.get(CC.EDGE_AVAILABLE_EDGE_MODES)?.toList()}")
    sb.appendLine("Tonemap: ${c.get(CC.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList()}")
    sb.appendLine("Hot pixel: ${c.get(CC.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toList()}")
    return sb.toString()
}
