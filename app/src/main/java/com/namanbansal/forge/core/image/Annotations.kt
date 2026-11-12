package com.namanbansal.forge.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Marks on a picture. Every coordinate is a fraction of the picture, every width a fraction of its shorter side, so they render at any size. */
@Serializable
sealed interface Mark {
    @Serializable @SerialName("arrow") data class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val colour: Int, val width: Float) : Mark
    @Serializable @SerialName("box") data class Box(val l: Float, val t: Float, val r: Float, val b: Float, val colour: Int, val width: Float) : Mark
    @Serializable @SerialName("free") data class Free(val pts: List<Float>, val colour: Int, val width: Float) : Mark
    @Serializable @SerialName("text") data class Text(val x: Float, val y: Float, val text: String, val colour: Int, val size: Float) : Mark
    /** A region that is irreversibly blurred (or pixelated) in the saved picture. */
    @Serializable @SerialName("blur") data class Blur(val l: Float, val t: Float, val r: Float, val b: Float, val pixelate: Boolean) : Mark
}

object Marks {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(l: List<Mark>): String = json.encodeToString(l)
    fun decode(s: String): List<Mark> = runCatching { json.decodeFromString<List<Mark>>(s) }.getOrDefault(emptyList())
}

object AnnotationRenderer {
    /** Draws [marks] in order onto [bmp] (must be mutable). Blur / pixelate overwrite the pixels, so the saved file holds no trace of the original there. */
    fun render(bmp: Bitmap, marks: List<Mark>, checkCancelled: () -> Unit = {}) {
        val w = bmp.width; val h = bmp.height
        val m = min(w, h).toFloat()
        val canvas = Canvas(bmp)
        for (mk in marks) {
            checkCancelled()
            when (mk) {
                is Mark.Blur -> {
                    val l = (mk.l * w).toInt(); val t = (mk.t * h).toInt(); val r = (mk.r * w).toInt(); val b = (mk.b * h).toInt()
                    if (mk.pixelate) Blur.pixelateRegion(bmp, l, t, r, b, max(8, (m / 40).toInt())) else Blur.blurRegion(bmp, l, t, r, b, max(8, (m / 30).toInt()))
                }
                is Mark.Box -> canvas.drawRect(mk.l * w, mk.t * h, mk.r * w, mk.b * h, stroke(mk.colour, mk.width * m))
                is Mark.Free -> {
                    val p = Path()
                    mk.pts.chunked(2).forEachIndexed { i, xy -> if (xy.size == 2) { if (i == 0) p.moveTo(xy[0] * w, xy[1] * h) else p.lineTo(xy[0] * w, xy[1] * h) } }
                    if (mk.pts.size == 2) p.lineTo(mk.pts[0] * w + 0.1f, mk.pts[1] * h)
                    canvas.drawPath(p, stroke(mk.colour, mk.width * m))
                }
                is Mark.Arrow -> {
                    val sw = mk.width * m
                    val p = stroke(mk.colour, sw)
                    val x1 = mk.x1 * w; val y1 = mk.y1 * h; val x2 = mk.x2 * w; val y2 = mk.y2 * h
                    val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    val head = sw * 4.5f
                    // Stop the shaft inside the head so the tip stays sharp.
                    canvas.drawLine(x1, y1, x2 - (cos(ang) * head * 0.6).toFloat(), y2 - (sin(ang) * head * 0.6).toFloat(), p)
                    val a1 = ang + Math.PI * 0.85; val a2 = ang - Math.PI * 0.85
                    val tri = Path().apply {
                        moveTo(x2, y2)
                        lineTo(x2 + (cos(a1) * head).toFloat(), y2 + (sin(a1) * head).toFloat())
                        lineTo(x2 + (cos(a2) * head).toFloat(), y2 + (sin(a2) * head).toFloat()); close()
                    }
                    canvas.drawPath(tri, Paint(p).apply { style = Paint.Style.FILL })
                }
                is Mark.Text -> {
                    val size = mk.size * h
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = size; color = 0xFF000000.toInt() or mk.colour }
                    val halo = Paint(tp).apply { style = Paint.Style.STROKE; strokeWidth = size * 0.14f; color = if (ColourFormat.onColour(mk.colour) == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(); strokeJoin = Paint.Join.ROUND }
                    mk.text.lines().forEachIndexed { i, line ->
                        canvas.drawText(line, mk.x * w, mk.y * h + size * (i + 1) * 1.05f, halo)
                        canvas.drawText(line, mk.x * w, mk.y * h + size * (i + 1) * 1.05f, tp)
                    }
                }
            }
        }
    }

    private fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = max(1f, width); color = 0xFF000000.toInt() or colour; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
}

/** Finds faces so they can be pre-selected for blurring. Rectangles are fractions of the picture. */
interface FaceFinder { suspend fun find(bmp: Bitmap): List<FRect> }

class MlKitFaceFinder : FaceFinder {
    override suspend fun find(bmp: Bitmap): List<FRect> {
        val det = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.05f).build())
        try {
            val faces = suspendCancellableCoroutine { cont ->
                det.process(InputImage.fromBitmap(bmp, 0)).addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resumeWithException(it) }
            }
            return faces.map { f ->
                val b = f.boundingBox
                val padX = b.width() * 0.15f; val padY = b.height() * 0.2f
                FRect(((b.left - padX) / bmp.width).coerceIn(0f, 1f), ((b.top - padY) / bmp.height).coerceIn(0f, 1f), ((b.right + padX) / bmp.width).coerceIn(0f, 1f), ((b.bottom + padY) / bmp.height).coerceIn(0f, 1f))
            }
        } finally { det.close() }
    }
}

object FaceFinders { @Volatile var factory: () -> FaceFinder = { MlKitFaceFinder() } }
