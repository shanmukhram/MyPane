package tv.pane.launcher

import android.view.Choreographer
import kotlin.math.abs

/**
 * Spring physics driven straight from Choreographer.
 *
 * ValueAnimator/ViewPropertyAnimator are scaled by the system "animator duration scale",
 * which is 0 on the target TV, so they would jump. Frame callbacks are not affected.
 */
object Motion : Choreographer.FrameCallback {
    /** In-app "reduce motion": springs jump straight to their target. */
    var reduce = false

    private val active = ArrayList<Spring>(16)
    private var running = false
    private var lastNanos = 0L

    fun add(s: Spring) {
        if (!active.contains(s)) active.add(s)
        if (!running) {
            running = true
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun remove(s: Spring) {
        active.remove(s)
    }

    override fun doFrame(frameTimeNanos: Long) {
        // Clamp dt so a dropped frame never makes a spring explode.
        var dt = if (lastNanos == 0L) 1f / 60f else (frameTimeNanos - lastNanos) / 1e9f
        if (dt > 0.034f) dt = 0.034f
        lastNanos = frameTimeNanos
        var i = active.size - 1
        while (i >= 0) {
            val s = active[i]
            if (s.step(dt)) active.removeAt(i)
            i--
        }
        if (active.isEmpty()) running = false
        else Choreographer.getInstance().postFrameCallback(this)
    }
}

/**
 * Damped harmonic oscillator. [onUpdate] gets the value every frame, [onEnd] once at rest.
 * Defaults are a soft, slightly under-damped feel.
 */
class Spring(
    var value: Float = 0f,
    private val stiffness: Float = 260f,
    private val damping: Float = 0.82f,
    private val onUpdate: (Float) -> Unit,
) {
    var target = value
        private set
    private var velocity = 0f
    var onEnd: (() -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var isRunning = false
        private set

    fun to(t: Float) {
        target = t
        if (Motion.reduce) {
            snap(t)
            return
        }
        if (!isRunning) {
            if (abs(target - value) < 0.0005f) {
                value = t; onUpdate(t); return
            }
            isRunning = true
            onStart?.invoke()
            Motion.add(this)
        }
    }

    fun snap(t: Float) {
        target = t
        value = t
        velocity = 0f
        onUpdate(t)
        if (isRunning) {
            isRunning = false
            Motion.remove(this)
            onEnd?.invoke()
        }
    }

    /** @return true when settled. */
    fun step(dt: Float): Boolean {
        // Two sub-steps of semi-implicit Euler: stable for these stiffness values at 60 Hz.
        val h = dt * 0.5f
        val c = 2f * damping * kotlin.math.sqrt(stiffness)
        var n = 0
        while (n < 2) {
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            n++
        }
        if (abs(value - target) < restDelta && abs(velocity) < restDelta * 20f) {
            value = target
            velocity = 0f
            isRunning = false
            onUpdate(value)
            onEnd?.invoke()
            return true
        }
        onUpdate(value)
        return false
    }

    /** Distance from target that counts as "arrived"; set larger for pixel-valued springs. */
    var restDelta = 0.001f
}
