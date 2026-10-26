package com.rajudivakar66.photoeditor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the photo fitted to the view, with an optional colour preview,
 * a crop frame in [Mode.CROP], and tap-to-place in [Mode.TEXT].
 */
class EditorView(context: Context) : View(context) {

    enum class Mode { VIEW, CROP, TEXT }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            updateImageRect()
            resetCrop()
            invalidate()
        }

    /** Drawn over the photo without changing it, for live adjustment and filter previews. */
    var previewMatrix: ColorMatrix? = null
        set(value) {
            field = value
            imagePaint.colorFilter = value?.let { ColorMatrixColorFilter(it) }
            invalidate()
        }

    var mode = Mode.VIEW
        set(value) {
            field = value
            resetCrop()
            invalidate()
        }

    /** Crop width / height, or null for a free crop. */
    var cropAspect: Float? = null
        set(value) {
            field = value
            resetCrop()
            invalidate()
        }

    /** Called in [Mode.TEXT] with the tapped point in bitmap pixels. */
    var onImageTap: ((Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val padding = 16 * density
    private val handleRadius = 8 * density
    private val touchRadius = 28 * density
    private val minCrop = 48 * density

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.WHITE
    }
    private val gridPaint = Paint().apply {
        strokeWidth = 1 * density
        color = Color.argb(110, 255, 255, 255)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }

    /** Where the photo is drawn, in view coordinates. */
    private val imageRect = RectF()
    private val crop = RectF()

    private enum class Drag { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Puts the crop frame back to its starting size. */
    fun resetCropFrame() {
        resetCrop()
        invalidate()
    }

    /** The crop frame converted to bitmap pixels. */
    fun cropRectInBitmap(): Rect? {
        val bmp = bitmap ?: return null
        val scale = bmp.width / imageRect.width()
        val left = ((crop.left - imageRect.left) * scale).roundToInt().coerceIn(0, bmp.width - 1)
        val top = ((crop.top - imageRect.top) * scale).roundToInt().coerceIn(0, bmp.height - 1)
        val right = ((crop.right - imageRect.left) * scale).roundToInt().coerceIn(left + 1, bmp.width)
        val bottom = ((crop.bottom - imageRect.top) * scale).roundToInt().coerceIn(top + 1, bmp.height)
        return Rect(left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateImageRect()
        resetCrop()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap
        if (bmp == null) {
            canvas.drawText("Tap Open to choose a photo", width / 2f, height / 2f, hintPaint)
            return
        }
        canvas.drawBitmap(bmp, null, imageRect, imagePaint)
        if (mode == Mode.CROP) drawCropFrame(canvas)
    }

    private fun drawCropFrame(canvas: Canvas) {
        // Darken everything outside the frame.
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, crop.top, dimPaint)
        canvas.drawRect(imageRect.left, crop.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, crop.top, crop.left, crop.bottom, dimPaint)
        canvas.drawRect(crop.right, crop.top, imageRect.right, crop.bottom, dimPaint)

        // Rule-of-thirds grid.
        for (i in 1..2) {
            val x = crop.left + crop.width() * i / 3f
            val y = crop.top + crop.height() * i / 3f
            canvas.drawLine(x, crop.top, x, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, y, crop.right, y, gridPaint)
        }

        canvas.drawRect(crop, framePaint)
        for ((x, y) in corners()) canvas.drawCircle(x, y, handleRadius, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        return when (mode) {
            Mode.CROP -> handleCropTouch(event)
            Mode.TEXT -> handleTap(event)
            Mode.VIEW -> false
        }
    }

    private fun handleTap(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot(event.x - downX, event.y - downY)
                if (moved < touchRadius && imageRect.contains(event.x, event.y)) {
                    val bmp = bitmap ?: return true
                    val scale = bmp.width / imageRect.width()
                    onImageTap?.invoke((event.x - imageRect.left) * scale, (event.y - imageRect.top) * scale)
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleCropTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = pickDrag(event.x, event.y)
                lastX = event.x
                lastY = event.y
                if (drag != Drag.NONE) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                when (drag) {
                    Drag.NONE -> {}
                    Drag.MOVE -> moveCrop(event.x - lastX, event.y - lastY)
                    else -> resizeCrop(drag, event.x, event.y)
                }
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun corners() = listOf(
        crop.left to crop.top,
        crop.right to crop.top,
        crop.right to crop.bottom,
        crop.left to crop.bottom,
    )

    private fun pickDrag(x: Float, y: Float): Drag {
        val handles = listOf(Drag.TOP_LEFT, Drag.TOP_RIGHT, Drag.BOTTOM_RIGHT, Drag.BOTTOM_LEFT)
        var best = Drag.NONE
        var bestDistance = touchRadius
        corners().forEachIndexed { i, (cx, cy) ->
            val d = hypot(x - cx, y - cy)
            if (d < bestDistance) {
                best = handles[i]
                bestDistance = d
            }
        }
        if (best != Drag.NONE) return best
        return if (crop.contains(x, y)) Drag.MOVE else Drag.NONE
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val clampedDx = dx.coerceIn(imageRect.left - crop.left, imageRect.right - crop.right)
        val clampedDy = dy.coerceIn(imageRect.top - crop.top, imageRect.bottom - crop.bottom)
        crop.offset(clampedDx, clampedDy)
    }

    /** Moves one corner; the opposite corner stays where it is. */
    private fun resizeCrop(corner: Drag, x: Float, y: Float) {
        val px = x.coerceIn(imageRect.left, imageRect.right)
        val py = y.coerceIn(imageRect.top, imageRect.bottom)
        val (anchorX, anchorY, signX, signY) = when (corner) {
            Drag.TOP_LEFT -> Quad(crop.right, crop.bottom, -1f, -1f)
            Drag.TOP_RIGHT -> Quad(crop.left, crop.bottom, 1f, -1f)
            Drag.BOTTOM_RIGHT -> Quad(crop.left, crop.top, 1f, 1f)
            else -> Quad(crop.right, crop.top, -1f, 1f)
        }
        var w = (px - anchorX) * signX
        var h = (py - anchorY) * signY
        cropAspect?.let { aspect ->
            if (w / h > aspect) w = h * aspect else h = w / aspect
        }
        if (w < minCrop || h < minCrop) return
        val left = if (signX < 0) anchorX - w else anchorX
        val top = if (signY < 0) anchorY - h else anchorY
        crop.set(left, top, left + w, top + h)
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    private fun updateImageRect() {
        val bmp = bitmap
        if (bmp == null || width == 0 || height == 0) {
            imageRect.setEmpty()
            return
        }
        val availableW = width - 2 * padding
        val availableH = height - 2 * padding
        val scale = min(availableW / bmp.width, availableH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageRect.set(left, top, left + w, top + h)
    }

    /** Full image for a free crop, or the largest centred frame with the chosen aspect. */
    private fun resetCrop() {
        crop.set(imageRect)
        val aspect = cropAspect ?: return
        if (imageRect.isEmpty) return
        var w = imageRect.width()
        var h = w / aspect
        if (h > imageRect.height()) {
            h = imageRect.height()
            w = h * aspect
        }
        val left = imageRect.centerX() - w / 2f
        val top = imageRect.centerY() - h / 2f
        crop.set(left, top, left + w, top + h)
    }
}
