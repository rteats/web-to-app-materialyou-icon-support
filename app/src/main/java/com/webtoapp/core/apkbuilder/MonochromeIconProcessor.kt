package com.webtoapp.core.apkbuilder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.Xml
import androidx.core.graphics.PathParser
import com.webtoapp.data.model.MonochromeIconConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.xmlpull.v1.XmlPullParser

/**
 * Builds the alpha mask used by Android 13+ themed launcher icons.
 *
 * A custom SVG is rendered into the adaptive-icon safe zone when present. Otherwise the
 * normal launcher bitmap (including a fetched website favicon) is converted to a mask.
 * Opaque images are separated from their border background by colour distance; transparent
 * images use alpha as the signal. This avoids the common "whole square gets tinted" failure.
 */
internal object MonochromeIconProcessor {
    private const val MAX_SVG_BYTES = 1024 * 1024
    private const val SAFE_ZONE_DP = 72f
    private const val ADAPTIVE_ICON_DP = 108f
    private const val SOFT_THRESHOLD_HALF_WIDTH = 10

    private val numberRegex = Regex("""[-+]?(?:\d*\.?\d+)(?:[eE][-+]?\d+)?""")
    private val transformRegex = Regex("""([A-Za-z]+)\s*\(([^)]*)\)""")

    fun importSvg(context: Context, uri: Uri): String? {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SVG_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }

            val source = bytes.toString(Charsets.UTF_8)
            if (!source.contains("<svg", ignoreCase = true)) return null
            if (source.contains("<!DOCTYPE", ignoreCase = true) ||
                source.contains("<script", ignoreCase = true) ||
                source.contains("<image", ignoreCase = true)
            ) return null

            // Validate that the subset we support actually produces a drawable mask.
            val validation = renderSvgBytes(bytes, 432) ?: return null
            validation.recycle()

            val dir = File(context.filesDir, "monochrome_icons").apply { mkdirs() }
            val file = File(dir, "monochrome_${UUID.randomUUID()}.svg")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    fun createMonochromePng(
        source: Bitmap,
        config: MonochromeIconConfig,
        size: Int
    ): ByteArray {
        val custom = config.svgPath
            ?.takeIf { it.isNotBlank() }
            ?.let { renderSvgMask(it, size) }

        val mask = custom ?: createAutoMask(
            source = source,
            size = size,
            threshold = config.threshold,
            invert = config.invert
        )

        return ByteArrayOutputStream().use { out ->
            mask.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (mask !== source) mask.recycle()
            out.toByteArray()
        }
    }

    internal fun renderSvgMask(path: String, size: Int): Bitmap? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0 || file.length() > MAX_SVG_BYTES) return null
        return runCatching { renderSvgBytes(file.readBytes(), size) }.getOrNull()
    }

    internal fun createAutoMask(
        source: Bitmap,
        size: Int,
        threshold: Int,
        invert: Boolean
    ): Bitmap {
        val normalized = fitInsideSafeZone(source, size)
        val background = estimateBorderBackground(source)
        val thresholdValue = threshold.coerceIn(0, 255)

        val pixels = IntArray(size * size)
        normalized.getPixels(pixels, 0, size, 0, 0, size, size)

        val bgR = background?.let { Color.red(it) } ?: 0
        val bgG = background?.let { Color.green(it) } ?: 0
        val bgB = background?.let { Color.blue(it) } ?: 0

        for (i in pixels.indices) {
            val color = pixels[i]
            val sourceAlpha = Color.alpha(color)
            if (sourceAlpha <= 2) {
                pixels[i] = Color.TRANSPARENT
                continue
            }

            val score = if (background == null) {
                sourceAlpha
            } else {
                val dr = Color.red(color) - bgR
                val dg = Color.green(color) - bgG
                val db = Color.blue(color) - bgB
                (sqrt((dr * dr + dg * dg + db * db).toDouble()) / sqrt(3.0))
                    .toInt()
                    .coerceIn(0, 255)
            }

            val alpha = softThreshold(score, thresholdValue, invert)
            val combinedAlpha = alpha * sourceAlpha / 255
            pixels[i] = if (combinedAlpha == 0) Color.TRANSPARENT
            else Color.argb(combinedAlpha, 255, 255, 255)
        }

        normalized.setPixels(pixels, 0, size, 0, 0, size, size)
        return normalized
    }

    private fun softThreshold(score: Int, threshold: Int, invert: Boolean): Int {
        val low = (threshold - SOFT_THRESHOLD_HALF_WIDTH).coerceAtLeast(0)
        val high = (threshold + SOFT_THRESHOLD_HALF_WIDTH).coerceAtMost(255)
        val forward = when {
            score <= low -> 0
            score >= high -> 255
            high == low -> if (score >= threshold) 255 else 0
            else -> ((score - low) * 255 / (high - low)).coerceIn(0, 255)
        }
        return if (invert) 255 - forward else forward
    }

    private fun fitInsideSafeZone(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val safe = size * SAFE_ZONE_DP / ADAPTIVE_ICON_DP
        val padding = (size - safe) / 2f
        val scale = minOf(safe / source.width, safe / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val left = padding + (safe - width) / 2f
        val top = padding + (safe - height) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), paint)
        return output
    }

    /**
     * Returns a representative opaque border colour, or null when transparency itself is the
     * background. Colours are quantised before voting so anti-aliasing/JPEG noise cannot split
     * one visual background into hundreds of one-off values.
     */
    private fun estimateBorderBackground(bitmap: Bitmap): Int? {
        val samples = mutableListOf<Int>()
        val steps = 32
        var transparent = 0
        for (i in 0 until steps) {
            val x = ((bitmap.width - 1) * i / (steps - 1)).coerceIn(0, bitmap.width - 1)
            val y = ((bitmap.height - 1) * i / (steps - 1)).coerceIn(0, bitmap.height - 1)
            val edge = intArrayOf(
                bitmap.getPixel(x, 0),
                bitmap.getPixel(x, bitmap.height - 1),
                bitmap.getPixel(0, y),
                bitmap.getPixel(bitmap.width - 1, y)
            )
            edge.forEach { color ->
                if (Color.alpha(color) < 96) transparent++ else samples += color
            }
        }
        if (transparent >= steps * 4 / 3 || samples.isEmpty()) return null

        val buckets = HashMap<Int, Int>()
        samples.forEach { color ->
            val r = Color.red(color) and 0xF0
            val g = Color.green(color) and 0xF0
            val b = Color.blue(color) and 0xF0
            val key = Color.rgb(r, g, b)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        return buckets.maxByOrNull { it.value }?.key
    }

    private data class SvgStyle(
        val fill: String = "black",
        val stroke: String = "none",
        val strokeWidth: Float = 1f,
        val opacity: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
        val evenOdd: Boolean = false,
        val visible: Boolean = true
    )

    private data class RenderState(
        val matrix: Matrix,
        val style: SvgStyle,
        val suppressed: Boolean = false
    )

    private fun renderSvgBytes(bytes: ByteArray, size: Int): Bitmap? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(bytes.inputStream(), Charsets.UTF_8.name())

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val states = ArrayDeque<RenderState>()
        var sawSvg = false
        var drewShape = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    if (tag == "svg") {
                        val viewBox = parseViewBox(attr(parser, "viewBox"))
                        val width = parseFirstNumber(attr(parser, "width")) ?: viewBox?.get(2) ?: 108f
                        val height = parseFirstNumber(attr(parser, "height")) ?: viewBox?.get(3) ?: 108f
                        val vb = viewBox ?: floatArrayOf(0f, 0f, width.coerceAtLeast(1f), height.coerceAtLeast(1f))
                        val base = safeZoneMatrix(vb, size)
                        states.addLast(RenderState(base, mergeStyle(SvgStyle(), parser)))
                        sawSvg = true
                    } else if (sawSvg && isContainer(tag)) {
                        val parent = states.peekLast() ?: RenderState(Matrix(), SvgStyle())
                        val matrix = Matrix(parent.matrix)
                        parseTransform(attr(parser, "transform"))?.let { matrix.postConcat(it) }
                        val suppressed = parent.suppressed || tag in setOf("defs", "clippath", "mask", "symbol")
                        states.addLast(RenderState(matrix, mergeStyle(parent.style, parser), suppressed))
                    } else if (sawSvg && isShape(tag)) {
                        val parent = states.peekLast() ?: RenderState(Matrix(), SvgStyle())
                        if (!parent.suppressed) {
                            val style = mergeStyle(parent.style, parser)
                            if (style.visible) {
                                val path = shapePath(tag, parser)
                                if (path != null && !path.isEmpty) {
                                    if (style.evenOdd) path.fillType = Path.FillType.EVEN_ODD
                                    val matrix = Matrix(parent.matrix)
                                    parseTransform(attr(parser, "transform"))?.let { matrix.postConcat(it) }
                                    path.transform(matrix)
                                    drawSvgPath(canvas, path, style, matrix)
                                    drewShape = true
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase()
                    if (tag == "svg" || (sawSvg && isContainer(tag))) {
                        if (states.isNotEmpty()) states.removeLast()
                    }
                }
            }
            parser.next()
        }

        if (!sawSvg || !drewShape) {
            output.recycle()
            return null
        }
        return output
    }

    private fun isContainer(tag: String): Boolean =
        tag in setOf("g", "defs", "clippath", "mask", "symbol")

    private fun isShape(tag: String): Boolean =
        tag in setOf("path", "rect", "circle", "ellipse", "line", "polygon", "polyline")

    private fun safeZoneMatrix(viewBox: FloatArray, size: Int): Matrix {
        val minX = viewBox[0]
        val minY = viewBox[1]
        val width = viewBox[2].coerceAtLeast(0.001f)
        val height = viewBox[3].coerceAtLeast(0.001f)
        val safe = size * SAFE_ZONE_DP / ADAPTIVE_ICON_DP
        val scale = minOf(safe / width, safe / height)
        val tx = (size - width * scale) / 2f - minX * scale
        val ty = (size - height * scale) / 2f - minY * scale
        return Matrix().apply {
            setValues(floatArrayOf(scale, 0f, tx, 0f, scale, ty, 0f, 0f, 1f))
        }
    }

    private fun shapePath(tag: String, parser: XmlPullParser): Path? = when (tag) {
        "path" -> attr(parser, "d")?.let { PathParser.createPathFromPathData(it) }
        "rect" -> {
            val x = numberAttr(parser, "x")
            val y = numberAttr(parser, "y")
            val w = numberAttr(parser, "width")
            val h = numberAttr(parser, "height")
            if (w <= 0f || h <= 0f) null else Path().apply {
                val rx = numberAttr(parser, "rx").coerceAtLeast(0f)
                val ryRaw = numberAttr(parser, "ry").coerceAtLeast(0f)
                val ry = if (ryRaw == 0f) rx else ryRaw
                addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
            }
        }
        "circle" -> Path().apply {
            addCircle(numberAttr(parser, "cx"), numberAttr(parser, "cy"), numberAttr(parser, "r"), Path.Direction.CW)
        }
        "ellipse" -> {
            val cx = numberAttr(parser, "cx")
            val cy = numberAttr(parser, "cy")
            val rx = numberAttr(parser, "rx")
            val ry = numberAttr(parser, "ry")
            if (rx <= 0f || ry <= 0f) null else Path().apply {
                addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
            }
        }
        "line" -> Path().apply {
            moveTo(numberAttr(parser, "x1"), numberAttr(parser, "y1"))
            lineTo(numberAttr(parser, "x2"), numberAttr(parser, "y2"))
        }
        "polygon", "polyline" -> {
            val values = numbers(attr(parser, "points"))
            if (values.size < 4) null else Path().apply {
                moveTo(values[0], values[1])
                var i = 2
                while (i + 1 < values.size) {
                    lineTo(values[i], values[i + 1])
                    i += 2
                }
                if (tag == "polygon") close()
            }
        }
        else -> null
    }

    private fun drawSvgPath(canvas: Canvas, path: Path, style: SvgStyle, matrix: Matrix) {
        if (!style.fill.equals("none", ignoreCase = true) && style.fillOpacity > 0f) {
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.style = Paint.Style.FILL
                alpha = (255 * style.opacity * style.fillOpacity).toInt().coerceIn(0, 255)
            })
        }

        if (!style.stroke.equals("none", ignoreCase = true) && style.strokeWidth > 0f && style.strokeOpacity > 0f) {
            val values = FloatArray(9)
            matrix.getValues(values)
            val sx = hypot(values[Matrix.MSCALE_X].toDouble(), values[Matrix.MSKEW_Y].toDouble()).toFloat()
            val sy = hypot(values[Matrix.MSKEW_X].toDouble(), values[Matrix.MSCALE_Y].toDouble()).toFloat()
            val scale = (sx + sy) / 2f
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.style = Paint.Style.STROKE
                strokeWidth = style.strokeWidth * scale
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                alpha = (255 * style.opacity * style.strokeOpacity).toInt().coerceIn(0, 255)
            })
        }
    }

    private fun mergeStyle(parent: SvgStyle, parser: XmlPullParser): SvgStyle {
        val inline = attr(parser, "style")
            ?.split(';')
            ?.mapNotNull { part ->
                val split = part.split(':', limit = 2)
                if (split.size == 2) split[0].trim().lowercase() to split[1].trim() else null
            }
            ?.toMap()
            .orEmpty()

        fun value(name: String): String? = attr(parser, name) ?: inline[name.lowercase()]
        fun opacity(name: String, fallback: Float): Float =
            value(name)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: fallback

        val display = value("display")
        val visibility = value("visibility")
        return parent.copy(
            fill = value("fill") ?: parent.fill,
            stroke = value("stroke") ?: parent.stroke,
            strokeWidth = parseFirstNumber(value("stroke-width")) ?: parent.strokeWidth,
            opacity = opacity("opacity", parent.opacity),
            fillOpacity = opacity("fill-opacity", parent.fillOpacity),
            strokeOpacity = opacity("stroke-opacity", parent.strokeOpacity),
            evenOdd = value("fill-rule")?.equals("evenodd", ignoreCase = true) ?: parent.evenOdd,
            visible = parent.visible &&
                !display.equals("none", ignoreCase = true) &&
                !visibility.equals("hidden", ignoreCase = true)
        )
    }

    private fun parseTransform(raw: String?): Matrix? {
        if (raw.isNullOrBlank()) return null
        val result = Matrix()
        var changed = false
        transformRegex.findAll(raw).forEach { match ->
            val name = match.groupValues[1].lowercase()
            val values = numbers(match.groupValues[2])
            val op = Matrix()
            when (name) {
                "matrix" -> if (values.size >= 6) {
                    op.setValues(floatArrayOf(
                        values[0], values[2], values[4],
                        values[1], values[3], values[5],
                        0f, 0f, 1f
                    ))
                } else return@forEach
                "translate" -> op.setTranslate(values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f })
                "scale" -> {
                    val sx = values.getOrElse(0) { 1f }
                    op.setScale(sx, values.getOrElse(1) { sx })
                }
                "rotate" -> {
                    val degrees = values.getOrElse(0) { 0f }
                    if (values.size >= 3) op.setRotate(degrees, values[1], values[2])
                    else op.setRotate(degrees)
                }
                "skewx" -> op.setSkew(tan(Math.toRadians(values.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                "skewy" -> op.setSkew(0f, tan(Math.toRadians(values.getOrElse(0) { 0f }.toDouble())).toFloat())
                else -> return@forEach
            }
            result.postConcat(op)
            changed = true
        }
        return if (changed) result else null
    }

    private fun parseViewBox(raw: String?): FloatArray? {
        val values = numbers(raw)
        return if (values.size >= 4 && values[2] > 0f && values[3] > 0f) {
            floatArrayOf(values[0], values[1], values[2], values[3])
        } else null
    }

    private fun numbers(raw: String?): List<Float> =
        raw?.let { numberRegex.findAll(it).mapNotNull { m -> m.value.toFloatOrNull() }.toList() }.orEmpty()

    private fun parseFirstNumber(raw: String?): Float? =
        raw?.let { numberRegex.find(it)?.value?.toFloatOrNull() }

    private fun numberAttr(parser: XmlPullParser, name: String): Float =
        parseFirstNumber(attr(parser, name)) ?: 0f

    private fun attr(parser: XmlPullParser, name: String): String? {
        parser.getAttributeValue(null, name)?.let { return it }
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).substringAfter(':').equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }
}
