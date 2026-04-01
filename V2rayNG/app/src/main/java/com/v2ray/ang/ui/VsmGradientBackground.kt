package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * Applies a slowly drifting radial gradient blob as the background of any View.
 * Call attach(view, ...) once; the animator loops indefinitely.
 */
object VsmGradientBackground {

    data class BlobConfig(
        val colorArgb: Int,
        val radiusFraction: Float,
        val startXFraction: Float,
        val endXFraction: Float,
        val startYFraction: Float,
        val endYFraction: Float,
        val cycleDurationMs: Long
    )

    fun attach(view: View, baseColor: Int, blobs: List<BlobConfig>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val animators = blobs.map { blob ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = blob.cycleDurationMs
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }

        animators.forEach { anim ->
            anim.addUpdateListener { view.invalidate() }
        }

        view.setWillNotDraw(false)
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        view.background = object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                if (w <= 0 || h <= 0) return

                canvas.drawColor(baseColor)

                blobs.forEachIndexed { i, blob ->
                    val progress = animators[i].animatedValue as Float
                    val cx = (blob.startXFraction + (blob.endXFraction - blob.startXFraction) * progress) * w
                    val cy = (blob.startYFraction + (blob.endYFraction - blob.startYFraction) * progress) * h
                    val radius = blob.radiusFraction * w
                    paint.shader = RadialGradient(cx, cy, radius,
                        blob.colorArgb, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawCircle(cx, cy, radius, paint)
                }
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(cf: ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
}
