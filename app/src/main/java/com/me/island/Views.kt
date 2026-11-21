package com.me.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** Animated equalizer bars shown on the right side of the compact island while music plays. */
internal class EqView(c: Context) : View(c) {
    var playing = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#30D158") }
    private val a = ValueAnimator.ofFloat(0f, 6.2832f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); a.start() }
    override fun onDetachedFromWindow() { a.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val t = (a.animatedValue as? Float) ?: 0f
        val n = 4
        val bw = width / (n * 2f - 1)
        for (i in 0 until n) {
            val f = if (playing) 0.3f + 0.7f * abs(sin(t + i * 1.4f)) else 0.25f
            val h = height * f
            val x = i * bw * 2
            cv.drawRoundRect(x, height - h, x + bw, height.toFloat(), bw / 2, bw / 2, p)
        }
    }
}

/** Thin rounded progress bar (media position, downloads, battery). */
internal class ThinBar(c: Context) : View(c) {
    var frac = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var fill = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val r = height / 2f
        p.color = 0x44FFFFFF
        cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = fill
        cv.drawRoundRect(0f, 0f, width * frac, height.toFloat(), r, r, p)
    }
}
