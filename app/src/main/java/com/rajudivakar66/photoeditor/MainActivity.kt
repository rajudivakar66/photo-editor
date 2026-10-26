package com.rajudivakar66.photoeditor

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Tool(val label: String) {
        ADJUST("Adjust"), FILTERS("Filters"), CROP("Crop"), ROTATE("Rotate"), TEXT("Text")
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var editor: EditorView
    private lateinit var panel: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var undoButton: Button

    /** The photo as first opened, for Reset. */
    private var original: Bitmap? = null
    private val undoStack = ArrayDeque<Bitmap>()
    private var tool = Tool.ADJUST
    private var busy = false

    // Text tool state, kept while switching tabs.
    private var textColor = Color.WHITE
    /** Text height as a fraction of the photo width. */
    private var textSizeFraction = 0.08f
    private var textInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(buildLayout())
        selectTool(Tool.ADJUST)
        incomingImage(intent)?.let { open(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingImage(intent)?.let { open(it) }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { open(it) }
        }
    }

    // ---- Layout -----------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        topBar.addView(barButton("Open") { pickImage() })
        undoButton = barButton("Undo") { undo() }
        topBar.addView(undoButton)
        topBar.addView(barButton("Reset") { reset() })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(barButton("Save", ACCENT) { save() })
        root.addView(topBar)

        editor = EditorView(this).apply {
            onImageTap = { x, y -> placeText(x, y) }
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel = FrameLayout(this).apply {
            setBackgroundColor(SURFACE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(SURFACE)
        }
        for (t in Tool.entries) {
            val tab = TextView(this).apply {
                text = t.label
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(0, dp(14), 0, dp(14))
                tag = t
                setOnClickListener { selectTool(t) }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        // The window draws behind the system bars (see drawBehindSystemBars); keep the
        // controls clear of them and of the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        updateUndo()
        return root
    }

    /**
     * Android 15 always draws apps behind the status and navigation bars. Doing the same
     * on older versions means the insets below are handled one way everywhere.
     */
    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun selectTool(t: Tool) {
        tool = t
        editor.previewMatrix = null
        editor.cropAspect = null
        editor.mode = when (t) {
            Tool.CROP -> EditorView.Mode.CROP
            Tool.TEXT -> EditorView.Mode.TEXT
            else -> EditorView.Mode.VIEW
        }
        for (i in 0 until tabs.childCount) {
            val tab = tabs.getChildAt(i) as TextView
            tab.setTextColor(if (tab.tag == t) ACCENT else MUTED)
        }
        panel.removeAllViews()
        textInput = null
        panel.addView(
            when (t) {
                Tool.ADJUST -> adjustPanel()
                Tool.FILTERS -> filtersPanel()
                Tool.CROP -> cropPanel()
                Tool.ROTATE -> rotatePanel()
                Tool.TEXT -> textPanel()
            }
        )
    }

    private fun adjustPanel(): View {
        val column = vertical()
        val brightness = slider()
        val contrast = slider()
        val saturation = slider()

        fun current() = Filters.adjust(
            brightness = (brightness.progress - 100).toFloat(),
            contrast = 0.5f + contrast.progress / 200f,
            saturation = saturation.progress / 100f,
        )

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) editor.previewMatrix = current()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        }
        for ((label, bar) in listOf("Brightness" to brightness, "Contrast" to contrast, "Saturation" to saturation)) {
            bar.setOnSeekBarChangeListener(listener)
            column.addView(labelled(label, bar))
        }

        fun resetSliders() {
            brightness.progress = 100
            contrast.progress = 100
            saturation.progress = 100
            editor.previewMatrix = null
        }
        column.addView(applyRow(
            onCancel = { resetSliders() },
            onApply = {
                val matrix = current()
                resetSliders()
                applyMatrix(matrix)
            },
        ))
        return column
    }

    private fun filtersPanel(): View {
        val column = vertical()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var chosen: ColorMatrix? = null
        val source = editor.bitmap?.let { ImageOps.thumbnail(it, dp(72)) }

        for (preset in Filters.presets) {
            val item = vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(SURFACE_HIGH)
                clipToOutline = true
                if (source != null) {
                    setImageBitmap(preset.matrix?.let { ImageOps.applyMatrix(source, it) } ?: source)
                }
            }
            item.addView(thumb, LinearLayout.LayoutParams(dp(64), dp(64)))
            item.addView(TextView(this).apply {
                text = preset.name
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, 0)
            })
            item.setOnClickListener {
                if (editor.bitmap == null) return@setOnClickListener needPhoto()
                chosen = preset.matrix
                editor.previewMatrix = preset.matrix
            }
            row.addView(item)
        }
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        column.addView(applyRow(
            onCancel = {
                chosen = null
                editor.previewMatrix = null
            },
            onApply = {
                val matrix = chosen
                editor.previewMatrix = null
                chosen = null
                if (matrix != null) applyMatrix(matrix)
            },
        ))
        return column
    }

    private fun cropPanel(): View {
        val column = vertical()
        val ratios = listOf<Pair<String, Float?>>(
            "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, ratio) in ratios) {
            row.addView(chip(label) { editor.cropAspect = ratio })
        }
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        column.addView(applyRow(
            onCancel = { editor.resetCropFrame() },
            onApply = {
                val rect = editor.cropRectInBitmap() ?: return@applyRow needPhoto()
                edit { ImageOps.crop(it, rect) }
            },
        ))
        return column
    }

    private fun rotatePanel(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(chip("Rotate left") { edit { ImageOps.rotate(it, -90f) } })
        row.addView(chip("Rotate right") { edit { ImageOps.rotate(it, 90f) } })
        row.addView(chip("Flip ↔") { edit { ImageOps.flip(it, horizontal = true) } })
        row.addView(chip("Flip ↕") { edit { ImageOps.flip(it, horizontal = false) } })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun textPanel(): View {
        val column = vertical()
        val input = EditText(this).apply {
            hint = "Type your text"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
        }
        textInput = input
        column.addView(input)

        val colours = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val swatches = mutableListOf<View>()
        fun refreshSwatches() {
            for (s in swatches) {
                val c = s.tag as Int
                s.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(if (c == textColor) 3 else 1), if (c == textColor) ACCENT else MUTED)
                }
            }
        }
        for (c in TEXT_COLOURS) {
            val swatch = View(this).apply {
                tag = c
                setOnClickListener {
                    textColor = c
                    refreshSwatches()
                }
            }
            swatches += swatch
            colours.addView(swatch, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
        }
        refreshSwatches()
        column.addView(colours)

        val size = SeekBar(this).apply {
            max = 18
            progress = (textSizeFraction * 100).toInt() - 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    textSizeFraction = (progress + 2) / 100f
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        column.addView(labelled("Size", size))
        column.addView(TextView(this).apply {
            text = "Tap on the photo to place the text."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, 0)
        })
        return column
    }

    // ---- Editing ----------------------------------------------------------------

    private fun pickImage() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
        }
        startActivityForResult(intent, PICK_IMAGE)
    }

    private fun incomingImage(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_EDIT -> intent.data
            else -> null
        }
    }

    private fun open(uri: Uri) {
        runInBackground("Could not open this photo", { ImageOps.load(this, uri) }) { bitmap ->
            original = bitmap
            undoStack.clear()
            editor.bitmap = bitmap
            selectTool(tool)
            updateUndo()
        }
    }

    private fun applyMatrix(matrix: ColorMatrix) = edit { ImageOps.applyMatrix(it, matrix) }

    private fun placeText(x: Float, y: Float) {
        val text = textInput?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("Type some text first")
            return
        }
        val colour = textColor
        val size = textSizeFraction
        edit { ImageOps.drawText(it, text, x, y, size, colour) }
    }

    /** Runs [operation] on the current photo off the main thread and keeps the old one for Undo. */
    private fun edit(operation: (Bitmap) -> Bitmap) {
        val current = editor.bitmap ?: return needPhoto()
        runInBackground("Something went wrong", { operation(current) }) { result ->
            pushUndo(current)
            editor.bitmap = result
            if (tool == Tool.FILTERS) selectTool(Tool.FILTERS) // refresh the thumbnails
        }
    }

    private fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        editor.bitmap = previous
        if (tool == Tool.FILTERS) selectTool(Tool.FILTERS)
        updateUndo()
    }

    private fun reset() {
        val first = original ?: return needPhoto()
        val current = editor.bitmap
        if (current == null || current === first) return
        pushUndo(current)
        editor.bitmap = first
        selectTool(tool)
    }

    private fun save() {
        val bitmap = editor.bitmap ?: return needPhoto()
        runInBackground("Could not save the photo", { ImageOps.save(this, bitmap) }) {
            toast("Saved to Pictures/PhotoEditor")
        }
    }

    private fun pushUndo(bitmap: Bitmap) {
        undoStack.addLast(bitmap)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        updateUndo()
    }

    private fun updateUndo() {
        undoButton.isEnabled = undoStack.isNotEmpty()
        undoButton.alpha = if (undoStack.isEmpty()) 0.4f else 1f
    }

    private fun <T> runInBackground(errorMessage: String, work: () -> T, onDone: (T) -> Unit) {
        if (busy) return
        busy = true
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                result.onSuccess(onDone).onFailure { toast(errorMessage) }
            }
        }
    }

    private fun needPhoto() = toast("Open a photo first")

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ---- Small view helpers -----------------------------------------------------

    private fun dp(value: Int) = (value * density).toInt()

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun slider() = SeekBar(this).apply {
        max = 200
        progress = 100
    }

    private fun labelled(label: String, bar: SeekBar): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 13f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun applyRow(onCancel: () -> Unit, onApply: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(4), 0, 0)
        addView(barButton("Cancel") { onCancel() })
        addView(barButton("Apply", ACCENT) { onApply() })
    }

    private fun barButton(label: String, colour: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(colour)
            setOnClickListener { onClick() }
        }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        background = rounded(SURFACE_HIGH)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun rounded(colour: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(colour)
    }

    companion object {
        private const val PICK_IMAGE = 1
        private const val MAX_UNDO = 8

        private val BACKGROUND = Color.rgb(18, 18, 18)
        private val SURFACE = Color.rgb(30, 30, 30)
        private val SURFACE_HIGH = Color.rgb(48, 48, 48)
        private val ACCENT = Color.rgb(130, 150, 255)
        private val MUTED = Color.rgb(170, 170, 170)

        private val TEXT_COLOURS = listOf(
            Color.WHITE, Color.BLACK, Color.rgb(244, 67, 54), Color.rgb(255, 213, 79),
            Color.rgb(66, 165, 245), Color.rgb(102, 187, 106), Color.rgb(236, 64, 122),
        )
    }
}
