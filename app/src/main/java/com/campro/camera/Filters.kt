package com.campro.camera

import android.graphics.Color
import android.graphics.ColorMatrix

enum class Category(val label: String) {
    ORIGINAL("Original"),
    CINEMA("CINEMATIC"),
    KODAK("KODAK")
}

/**
 * A look = a 4x5 color matrix + optional vignette + optional film grain.
 * The exact same look is used for the live preview and for the saved photo.
 */
class CamFilter(
    val name: String,
    val category: Category,
    val matrix: ColorMatrix,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val swatchA: Int,
    val swatchB: Int
) {
    val isNone: Boolean get() = category == Category.ORIGINAL
}

object Looks {

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Blend between "no filter" (k = 0) and the full filter (k = 1). */
    fun lerp(m: ColorMatrix, k: Float): ColorMatrix {
        val a = m.array
        return ColorMatrix(FloatArray(20) { IDENTITY[it] + (a[it] - IDENTITY[it]) * k })
    }

    /**
     * out = ((saturate(in) * gain) * contrast + 0.5 * (1 - contrast)) * (1 - fade) + fade + bright
     * fade lifts the blacks (matte film look).
     */
    private fun tone(
        sat: Float = 1f,
        contrast: Float = 1f,
        bright: Float = 0f,
        r: Float = 1f,
        g: Float = 1f,
        b: Float = 1f,
        fade: Float = 0f
    ): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(sat)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    r, 0f, 0f, 0f, 0f,
                    0f, g, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val scale = contrast * (1f - fade)
        val off = (0.5f * (1f - contrast) * (1f - fade) + fade + bright) * 255f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, off,
                    0f, scale, 0f, 0f, off,
                    0f, 0f, scale, 0f, off,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return m
    }

    private fun c(hex: String) = Color.parseColor(hex)

    val NONE = CamFilter(
        "Original", Category.ORIGINAL, ColorMatrix(),
        swatchA = c("#9AA0A6"), swatchB = c("#3C4043")
    )

    val CINEMATIC: List<CamFilter> = listOf(
        CamFilter(
            "Teal & Orange", Category.CINEMA,
            tone(sat = 1.12f, contrast = 1.15f, r = 1.10f, g = 1.0f, b = 0.90f, bright = -0.01f),
            vignette = 0.30f, grain = 0.10f,
            swatchA = c("#FF8A3D"), swatchB = c("#0E6E7E")
        ),
        CamFilter(
            "Blockbuster", Category.CINEMA,
            tone(sat = 0.92f, contrast = 1.28f, r = 1.05f, g = 1.02f, b = 0.95f),
            vignette = 0.38f, grain = 0.12f,
            swatchA = c("#D9A441"), swatchB = c("#1B2A3A")
        ),
        CamFilter(
            "Noir Cine", Category.CINEMA,
            tone(sat = 0f, contrast = 1.38f, bright = -0.02f),
            vignette = 0.48f, grain = 0.45f,
            swatchA = c("#E8E8E8"), swatchB = c("#101010")
        ),
        CamFilter(
            "Moody Blue", Category.CINEMA,
            tone(sat = 0.85f, contrast = 1.15f, r = 0.92f, g = 1.0f, b = 1.14f, bright = -0.03f),
            vignette = 0.35f, grain = 0.10f,
            swatchA = c("#5C7CFA"), swatchB = c("#0B1230")
        ),
        CamFilter(
            "Golden Hour", Category.CINEMA,
            tone(sat = 1.15f, contrast = 1.08f, r = 1.13f, g = 1.04f, b = 0.86f, bright = 0.02f),
            vignette = 0.22f,
            swatchA = c("#FFC93C"), swatchB = c("#FF6B35")
        ),
        CamFilter(
            "Matte Film", Category.CINEMA,
            tone(sat = 0.88f, contrast = 1.06f, fade = 0.10f, r = 1.02f, b = 0.98f),
            vignette = 0.15f, grain = 0.20f,
            swatchA = c("#B7A99A"), swatchB = c("#4A4E5A")
        ),
        CamFilter(
            "Cyber Neon", Category.CINEMA,
            tone(sat = 1.38f, contrast = 1.20f, r = 1.10f, g = 0.92f, b = 1.18f),
            vignette = 0.30f,
            swatchA = c("#FF2BD6"), swatchB = c("#00E5FF")
        ),
        CamFilter(
            "Desert Dust", Category.CINEMA,
            tone(sat = 0.95f, contrast = 1.10f, fade = 0.05f, r = 1.10f, g = 1.0f, b = 0.84f),
            vignette = 0.28f, grain = 0.15f,
            swatchA = c("#E6B87A"), swatchB = c("#8C5A2B")
        ),
        CamFilter(
            "Thriller Green", Category.CINEMA,
            tone(sat = 0.80f, contrast = 1.22f, r = 0.94f, g = 1.08f, b = 0.96f, bright = -0.03f),
            vignette = 0.42f, grain = 0.15f,
            swatchA = c("#7FB069"), swatchB = c("#0F1F14")
        )
    )

    val KODAK: List<CamFilter> = listOf(
        CamFilter(
            "Gold 200", Category.KODAK,
            tone(sat = 1.15f, contrast = 1.05f, r = 1.10f, g = 1.03f, b = 0.88f),
            vignette = 0.12f, grain = 0.30f,
            swatchA = c("#FFD04D"), swatchB = c("#E07A1F")
        ),
        CamFilter(
            "Portra 400", Category.KODAK,
            tone(sat = 0.92f, contrast = 0.97f, fade = 0.04f, r = 1.05f, g = 1.0f, b = 0.96f, bright = 0.015f),
            vignette = 0.08f, grain = 0.25f,
            swatchA = c("#F6D5C0"), swatchB = c("#C9A28B")
        ),
        CamFilter(
            "Ektar 100", Category.KODAK,
            tone(sat = 1.42f, contrast = 1.15f, r = 1.04f, g = 1.0f, b = 0.98f),
            vignette = 0.10f, grain = 0.12f,
            swatchA = c("#FF4D4D"), swatchB = c("#2E86DE")
        ),
        CamFilter(
            "ColorPlus 200", Category.KODAK,
            tone(sat = 1.05f, contrast = 1.04f, fade = 0.05f, r = 1.08f, g = 1.0f, b = 0.92f),
            vignette = 0.12f, grain = 0.35f,
            swatchA = c("#F2B880"), swatchB = c("#8A6F5A")
        ),
        CamFilter(
            "Ultramax 400", Category.KODAK,
            tone(sat = 1.25f, contrast = 1.10f, r = 1.06f, g = 1.02f, b = 0.94f),
            vignette = 0.14f, grain = 0.30f,
            swatchA = c("#FFB02E"), swatchB = c("#D7263D")
        ),
        CamFilter(
            "Vision3 500T", Category.KODAK,
            tone(sat = 1.0f, contrast = 1.10f, fade = 0.03f, r = 0.95f, g = 1.0f, b = 1.12f),
            vignette = 0.20f, grain = 0.30f,
            swatchA = c("#7FA8FF"), swatchB = c("#2B3A67")
        ),
        CamFilter(
            "Kodachrome", Category.KODAK,
            tone(sat = 1.30f, contrast = 1.20f, r = 1.10f, g = 0.98f, b = 0.92f),
            vignette = 0.16f, grain = 0.18f,
            swatchA = c("#E63946"), swatchB = c("#F4A261")
        ),
        CamFilter(
            "Tri-X 400 B&W", Category.KODAK,
            tone(sat = 0f, contrast = 1.30f),
            vignette = 0.20f, grain = 0.60f,
            swatchA = c("#F1F1F1"), swatchB = c("#222222")
        ),
        CamFilter(
            "Funsaver Fade", Category.KODAK,
            tone(sat = 1.05f, contrast = 0.98f, fade = 0.12f, r = 1.07f, g = 1.0f, b = 0.90f, bright = 0.01f),
            vignette = 0.25f, grain = 0.40f,
            swatchA = c("#F7C59F"), swatchB = c("#6D597A")
        )
    )

    fun forCategory(cat: Category): List<CamFilter> = when (cat) {
        Category.CINEMA -> CINEMATIC
        Category.KODAK -> KODAK
        Category.ORIGINAL -> listOf(NONE)
    }
}
