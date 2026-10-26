package com.rajudivakar66.photoeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Bitmap operations. Every function returns a new bitmap and leaves its input alone. */
object ImageOps {

    /** Longest side kept after loading. Bigger photos are scaled down to save memory. */
    private const val MAX_SIDE = 2048

    fun load(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software memory so the pixels can be read and drawn on.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
        // Wide-gamut photos decode as RGBA_F16; the editor works in ARGB_8888.
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun applyMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap =
        transform(src, Matrix().apply { postRotate(degrees) })

    fun flip(src: Bitmap, horizontal: Boolean): Bitmap =
        transform(src, Matrix().apply { if (horizontal) postScale(-1f, 1f) else postScale(1f, -1f) })

    fun crop(src: Bitmap, rect: Rect): Bitmap =
        Bitmap.createBitmap(src, rect.left, rect.top, rect.width(), rect.height())

    /** Scaled-down copy for the filter thumbnails. */
    fun thumbnail(src: Bitmap, side: Int): Bitmap {
        val scale = side.toFloat() / max(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * Draws [text] centred on ([x], [y]) in bitmap pixels.
     * [sizeFraction] is the text height as a fraction of the image width, so the
     * text looks the same on a small or a large photo.
     */
    fun drawText(src: Bitmap, text: String, x: Float, y: Float, sizeFraction: Float, color: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val size = src.width * sizeFraction
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            // A soft shadow keeps the text readable on busy backgrounds.
            val shadow = if (color == Color.BLACK) Color.argb(160, 255, 255, 255) else Color.argb(160, 0, 0, 0)
            setShadowLayer(size * 0.08f, 0f, size * 0.04f, shadow)
        }
        val lines = text.split('\n')
        val lineHeight = paint.fontSpacing
        val metrics = paint.fontMetrics
        // Baseline of the first line so that the whole block is centred on y.
        var baseline = y - lineHeight * (lines.size - 1) / 2f - (metrics.ascent + metrics.descent) / 2f
        val canvas = Canvas(out)
        for (line in lines) {
            canvas.drawText(line, x, baseline, paint)
            baseline += lineHeight
        }
        return out
    }

    /** Saves to Pictures/PhotoEditor as a JPEG and returns its content Uri. */
    fun save(context: Context, src: Bitmap): Uri {
        val name = "PhotoEditor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoEditor")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create the file")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Could not open the file")
            stream.use { flattenAlpha(src).compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun transform(src: Bitmap, matrix: Matrix): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)

    /** JPEG has no transparency; without this, transparent pixels would turn black. */
    private fun flattenAlpha(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }
}
