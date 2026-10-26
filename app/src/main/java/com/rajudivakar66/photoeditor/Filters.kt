package com.rajudivakar66.photoeditor

import android.graphics.ColorMatrix

/** Colour adjustments and preset filters, all expressed as colour matrices. */
object Filters {

    class Preset(val name: String, val matrix: ColorMatrix?)

    /**
     * @param brightness -100..100, added to every channel
     * @param contrast   0.5..1.5, scaled around mid-grey
     * @param saturation 0..2, where 1 leaves colours unchanged
     */
    fun adjust(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(contrastBrightness(contrast, brightness))
        return matrix
    }

    val presets: List<Preset> = listOf(
        Preset("Original", null),
        Preset("B&W", ColorMatrix().apply { setSaturation(0f) }),
        Preset("Sepia", sepia()),
        Preset("Vintage", sepia().apply { postConcat(contrastBrightness(0.85f, 18f)) }),
        Preset("Warm", channelScale(1.10f, 1.0f, 0.85f)),
        Preset("Cool", channelScale(0.90f, 1.0f, 1.15f)),
        Preset("Vivid", ColorMatrix().apply { setSaturation(1.6f) }),
        Preset("Fade", ColorMatrix().apply {
            setSaturation(0.7f)
            postConcat(contrastBrightness(0.8f, 20f))
        }),
        Preset("Invert", ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))),
    )

    private fun contrastBrightness(contrast: Float, brightness: Float): ColorMatrix {
        val offset = (1f - contrast) * 128f + brightness
        return ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    private fun sepia() = ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))

    private fun channelScale(r: Float, g: Float, b: Float) =
        ColorMatrix().apply { setScale(r, g, b, 1f) }
}
