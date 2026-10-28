package com.campro.camera

import android.graphics.Color
import android.graphics.ColorMatrix

enum class Category(val label: String) {
    ORIGINAL("Original"),
    CINEMA("CINEMATIC"),
    KODAK("KODAK"),
    IPHONE("IPHONE"),
    FUJI("FUJI")
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

    val CINEMATIC_EXTRA: List<CamFilter> = listOf(
        CamFilter(
            "Bleach Bypass", Category.CINEMA,
            tone(sat = 0.50f, contrast = 1.38f, bright = -0.02f),
            vignette = 0.30f, grain = 0.25f,
            swatchA = c("#9AA5AE"), swatchB = c("#1C2024")
        ),
        CamFilter(
            "Day for Night", Category.CINEMA,
            tone(sat = 0.70f, contrast = 1.12f, r = 0.82f, g = 0.95f, b = 1.18f, bright = -0.09f),
            vignette = 0.45f, grain = 0.12f,
            swatchA = c("#3A5BA0"), swatchB = c("#05081A")
        ),
        CamFilter(
            "Vintage 70s", Category.CINEMA,
            tone(sat = 1.10f, contrast = 1.02f, fade = 0.09f, r = 1.10f, g = 1.0f, b = 0.82f),
            vignette = 0.30f, grain = 0.35f,
            swatchA = c("#D98E3A"), swatchB = c("#5E3B22")
        ),
        CamFilter(
            "Cold Steel", Category.CINEMA,
            tone(sat = 0.70f, contrast = 1.22f, r = 0.88f, g = 0.98f, b = 1.14f),
            vignette = 0.33f, grain = 0.12f,
            swatchA = c("#8FB3D9"), swatchB = c("#1A2633")
        ),
        CamFilter(
            "Warm Sunset", Category.CINEMA,
            tone(sat = 1.25f, contrast = 1.10f, r = 1.18f, g = 1.0f, b = 0.80f, bright = 0.01f),
            vignette = 0.25f,
            swatchA = c("#FF9A3C"), swatchB = c("#B3262E")
        ),
        CamFilter(
            "Arri Neutral", Category.CINEMA,
            tone(sat = 0.96f, contrast = 1.12f, fade = 0.02f),
            vignette = 0.20f, grain = 0.10f,
            swatchA = c("#C9C6BE"), swatchB = c("#4B4F55")
        ),
        CamFilter(
            "Sci-Fi Cyan", Category.CINEMA,
            tone(sat = 0.90f, contrast = 1.20f, r = 0.84f, g = 1.06f, b = 1.14f),
            vignette = 0.36f, grain = 0.10f,
            swatchA = c("#2BD9E6"), swatchB = c("#0A1F2E")
        )
    )

    val KODAK_EXTRA: List<CamFilter> = listOf(
        CamFilter(
            "Ektachrome", Category.KODAK,
            tone(sat = 1.22f, contrast = 1.12f, r = 0.98f, g = 1.0f, b = 1.08f),
            vignette = 0.10f, grain = 0.15f,
            swatchA = c("#2E86DE"), swatchB = c("#F1C40F")
        ),
        CamFilter(
            "T-Max 100 B&W", Category.KODAK,
            tone(sat = 0f, contrast = 1.22f),
            vignette = 0.12f, grain = 0.22f,
            swatchA = c("#EDEDED"), swatchB = c("#2B2B2B")
        ),
        CamFilter(
            "Gold 400", Category.KODAK,
            tone(sat = 1.18f, contrast = 1.06f, r = 1.09f, g = 1.04f, b = 0.90f),
            vignette = 0.12f, grain = 0.32f,
            swatchA = c("#FFC857"), swatchB = c("#C8553D")
        ),
        CamFilter(
            "Kodak 5219 Night", Category.KODAK,
            tone(sat = 1.05f, contrast = 1.12f, fade = 0.02f, r = 0.94f, g = 1.0f, b = 1.14f, bright = -0.01f),
            vignette = 0.22f, grain = 0.35f,
            swatchA = c("#6C8EF5"), swatchB = c("#1D1B3A")
        ),
        CamFilter(
            "Disposable Flash", Category.KODAK,
            tone(sat = 1.12f, contrast = 1.18f, bright = 0.03f, r = 1.05f, g = 1.0f, b = 0.95f),
            vignette = 0.30f, grain = 0.45f,
            swatchA = c("#FFE066"), swatchB = c("#7A4E2D")
        )
    )

    val IPHONE: List<CamFilter> = listOf(
        CamFilter(
            "Vivid", Category.IPHONE,
            tone(sat = 1.28f, contrast = 1.10f, bright = 0.01f),
            swatchA = c("#FF3B7F"), swatchB = c("#FFC400")
        ),
        CamFilter(
            "Vivid Warm", Category.IPHONE,
            tone(sat = 1.24f, contrast = 1.10f, r = 1.07f, b = 0.93f),
            swatchA = c("#FF8A00"), swatchB = c("#FF3B30")
        ),
        CamFilter(
            "Vivid Cool", Category.IPHONE,
            tone(sat = 1.24f, contrast = 1.10f, r = 0.94f, b = 1.09f),
            swatchA = c("#0A84FF"), swatchB = c("#5E5CE6")
        ),
        CamFilter(
            "Dramatic", Category.IPHONE,
            tone(sat = 0.85f, contrast = 1.32f, bright = -0.04f),
            vignette = 0.10f,
            swatchA = c("#8E8E93"), swatchB = c("#1C1C1E")
        ),
        CamFilter(
            "Dramatic Warm", Category.IPHONE,
            tone(sat = 0.88f, contrast = 1.30f, r = 1.07f, b = 0.94f, bright = -0.03f),
            vignette = 0.10f,
            swatchA = c("#C98A5A"), swatchB = c("#2A1B12")
        ),
        CamFilter(
            "Dramatic Cool", Category.IPHONE,
            tone(sat = 0.85f, contrast = 1.30f, r = 0.93f, b = 1.09f, bright = -0.04f),
            vignette = 0.10f,
            swatchA = c("#6E8CB5"), swatchB = c("#10161F")
        ),
        CamFilter(
            "Mono", Category.IPHONE,
            tone(sat = 0f, contrast = 1.02f),
            swatchA = c("#D1D1D6"), swatchB = c("#48484A")
        ),
        CamFilter(
            "Silvertone", Category.IPHONE,
            tone(sat = 0f, contrast = 1.06f, fade = 0.05f, b = 1.03f, bright = 0.02f),
            swatchA = c("#F2F2F7"), swatchB = c("#8E8E93")
        ),
        CamFilter(
            "Noir", Category.IPHONE,
            tone(sat = 0f, contrast = 1.38f, bright = -0.03f),
            vignette = 0.12f,
            swatchA = c("#C7C7CC"), swatchB = c("#000000")
        ),
        CamFilter(
            "Instant", Category.IPHONE,
            tone(sat = 1.10f, contrast = 1.02f, fade = 0.05f, r = 1.06f, g = 1.0f, b = 0.92f),
            vignette = 0.14f,
            swatchA = c("#F4C58D"), swatchB = c("#B47B5A")
        ),
        CamFilter(
            "Process", Category.IPHONE,
            tone(sat = 0.90f, contrast = 1.10f, r = 0.95f, g = 1.0f, b = 1.08f),
            swatchA = c("#7FA6C9"), swatchB = c("#3A4F66")
        ),
        CamFilter(
            "Transfer", Category.IPHONE,
            tone(sat = 1.10f, contrast = 1.05f, r = 1.08f, g = 1.02f, b = 0.90f),
            vignette = 0.08f,
            swatchA = c("#F2B35E"), swatchB = c("#8A5A2B")
        ),
        CamFilter(
            "Chrome", Category.IPHONE,
            tone(sat = 1.25f, contrast = 1.16f),
            swatchA = c("#34C759"), swatchB = c("#007AFF")
        ),
        CamFilter(
            "Fade", Category.IPHONE,
            tone(sat = 0.85f, contrast = 0.95f, fade = 0.10f),
            swatchA = c("#E5D5C8"), swatchB = c("#9A8F86")
        ),
        CamFilter(
            "Tonal", Category.IPHONE,
            tone(sat = 0f, contrast = 1.0f, fade = 0.03f),
            swatchA = c("#E5E5EA"), swatchB = c("#636366")
        )
    )

    val FUJI: List<CamFilter> = listOf(
        CamFilter(
            "Velvia 50", Category.FUJI,
            tone(sat = 1.55f, contrast = 1.20f, g = 1.03f),
            vignette = 0.08f, grain = 0.12f,
            swatchA = c("#E53935"), swatchB = c("#1E8E3E")
        ),
        CamFilter(
            "Provia 100F", Category.FUJI,
            tone(sat = 1.10f, contrast = 1.08f, b = 1.02f),
            grain = 0.12f,
            swatchA = c("#4A90E2"), swatchB = c("#F5A623")
        ),
        CamFilter(
            "Astia 100F", Category.FUJI,
            tone(sat = 0.95f, contrast = 0.98f, fade = 0.03f, r = 1.03f),
            grain = 0.12f,
            swatchA = c("#F7D6C4"), swatchB = c("#9FB8AD")
        ),
        CamFilter(
            "Classic Chrome", Category.FUJI,
            tone(sat = 0.75f, contrast = 1.16f, fade = 0.04f, b = 1.04f),
            vignette = 0.15f, grain = 0.20f,
            swatchA = c("#8C8F7A"), swatchB = c("#3B4A57")
        ),
        CamFilter(
            "Pro Neg Std", Category.FUJI,
            tone(sat = 0.85f, contrast = 0.98f, fade = 0.03f),
            grain = 0.15f,
            swatchA = c("#D8C7B6"), swatchB = c("#7A7568")
        ),
        CamFilter(
            "Eterna Cinema", Category.FUJI,
            tone(sat = 0.80f, contrast = 1.05f, fade = 0.06f, g = 1.02f, b = 1.04f),
            vignette = 0.12f, grain = 0.18f,
            swatchA = c("#7F9C9A"), swatchB = c("#2F3B3A")
        ),
        CamFilter(
            "Superia 400", Category.FUJI,
            tone(sat = 1.15f, contrast = 1.06f, r = 0.98f, g = 1.05f, b = 1.03f),
            vignette = 0.12f, grain = 0.32f,
            swatchA = c("#5DBB63"), swatchB = c("#2A6F97")
        ),
        CamFilter(
            "Acros 100", Category.FUJI,
            tone(sat = 0f, contrast = 1.26f),
            vignette = 0.14f, grain = 0.40f,
            swatchA = c("#F0F0F0"), swatchB = c("#1A1A1A")
        )
    )

    fun forCategory(cat: Category): List<CamFilter> = when (cat) {
        Category.CINEMA -> CINEMATIC + CINEMATIC_EXTRA
        Category.KODAK -> KODAK + KODAK_EXTRA
        Category.IPHONE -> IPHONE
        Category.FUJI -> FUJI
        Category.ORIGINAL -> listOf(NONE)
    }
}
