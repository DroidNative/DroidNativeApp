package app.droidnative.apps

/**
 * Rendering resolution as the user writes it: pixels along the display's
 * short side ("720") or a percentage of the native resolution ("50%").
 */
object Resolution {
    const val MIN_PIXELS = 240

    /** Factor of the native resolution (0.1..1), or null when [spec] is invalid. */
    fun factor(spec: String, shortSide: Int): Float? {
        val s = spec.trim()
        if (s.isEmpty()) return 1f
        val f = if (s.endsWith("%")) {
            s.dropLast(1).trim().toFloatOrNull()?.div(100f) ?: return null
        } else {
            val px = s.toIntOrNull() ?: return null
            if (px < MIN_PIXELS) return null
            px.toFloat() / shortSide
        }
        if (f <= 0f || f > 1f || f * shortSide < MIN_PIXELS) return null
        return f
    }

    fun isValid(spec: String, shortSide: Int) = factor(spec, shortSide) != null
}
