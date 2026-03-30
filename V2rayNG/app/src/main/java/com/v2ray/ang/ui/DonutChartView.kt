package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * VseMoiOnline: Canvas-drawn donut ring chart.
 *
 * Geometry mirrors the approved SVG mockup:
 *   stroke-width = 10dp, radius = 38dp, total = 96×96dp
 *   arc starts at -90° (12 o'clock), sweeps clockwise
 *
 * Layers (bottom → top):
 *   1. Background ring     — full circle, ringBgColor
 *   2. Ghost arc (optional)— from fraction→100%, ghostColor at ghostAlpha
 *                            used for free-user speed chart to show paid-tier potential
 *   3. Main arc            — 0→fraction, ringColor, round cap
 *   4. Center value text   — large, bold, ringColor
 *   5. Center sub text     — small, subTextColor
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dp = context.resources.displayMetrics.density

    // ── Public properties ────────────────────────────────────────────────

    /** Filled proportion, 0.0–1.0. */
    var fraction: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    /** Color of the main arc and the center value text. */
    var ringColor: Int = 0xFF56AB7B.toInt()
        set(value) { field = value; invalidate() }

    /** Background ring color (ring_bg in mockup). */
    var ringBgColor: Int = 0xFFE8EAF6.toInt()
        set(value) { field = value; invalidate() }

    /** Large text in the center (e.g. "24" or "2.8"). */
    var centerValue: String = ""
        set(value) { field = value; invalidate() }

    /** Small text below centerValue (e.g. "из 25 ГБ"). */
    var centerSub: String = ""
        set(value) { field = value; invalidate() }

    /** Color for centerSub text (hint/secondary color). */
    var subTextColor: Int = 0xFF9E9E9E.toInt()
        set(value) { field = value; invalidate() }

    /** Draw the ghost arc (used only for the free-user speed ring). */
    var showGhostArc: Boolean = false
        set(value) { field = value; invalidate() }

    /**
     * Ghost arc color — always vsm_mint (#7DC4A0).
     * Alpha is controlled separately via ghostAlpha.
     */
    var ghostColor: Int = 0xFF7DC4A0.toInt()
        set(value) { field = value; invalidate() }

    /**
     * Alpha for the ghost arc.
     * Light theme: 56  (≈ 22%)
     * Dark theme:  77  (≈ 30%)
     */
    var ghostAlpha: Int = 56
        set(value) { field = value; invalidate() }

    // ── Paints ───────────────────────────────────────────────────────────

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val oval = RectF()

    // ── Drawing ──────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val strokeW = 10f * dp
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - strokeW / 2f

        oval.set(cx - r, cy - r, cx + r, cy + r)

        // 1. Background ring
        bgPaint.color = ringBgColor
        bgPaint.strokeWidth = strokeW
        canvas.drawArc(oval, -90f, 360f, false, bgPaint)

        // 2. Ghost arc — spans from the current free fraction to 100%,
        //    showing the additional headroom of the paid tier
        if (showGhostArc && fraction < 1f) {
            ghostPaint.color = ghostColor
            ghostPaint.alpha = ghostAlpha   // overrides alpha from color
            ghostPaint.strokeWidth = strokeW
            val ghostStart = -90f + fraction * 360f
            val ghostSweep = (1f - fraction) * 360f
            canvas.drawArc(oval, ghostStart, ghostSweep, false, ghostPaint)
        }

        // 3. Main arc
        if (fraction > 0f) {
            arcPaint.color = ringColor
            arcPaint.strokeWidth = strokeW
            // Full circle looks better without a round cap bump at the seam
            arcPaint.strokeCap = if (fraction >= 1f) Paint.Cap.BUTT else Paint.Cap.ROUND
            canvas.drawArc(oval, -90f, fraction * 360f, false, arcPaint)
        }

        // 4. Center texts — vertically centred as a two-line block
        val valSize = 17f * dp
        val subSize = 10f * dp
        val gap = 2f * dp
        val totalH = valSize + gap + subSize
        val valBaseline = cy - totalH / 2f + valSize
        val subBaseline = valBaseline + gap + subSize

        if (centerValue.isNotEmpty()) {
            valuePaint.color = ringColor
            valuePaint.textSize = valSize
            canvas.drawText(centerValue, cx, valBaseline, valuePaint)
        }

        if (centerSub.isNotEmpty()) {
            subPaint.color = subTextColor
            subPaint.textSize = subSize
            canvas.drawText(centerSub, cx, subBaseline, subPaint)
        }
    }

    // ── Measurement ──────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Default intrinsic size: 96×96dp (matches mockup SVG canvas)
        val desired = (96f * dp).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        // Always square
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }
}
