package com.willlauer.summit.map

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.*

/**
 * The map: a GL view plus the camera, animations, gestures and picking (all on the UI thread). Mirrors web/galaxy.js:
 * positions morph between spaces and between the flat map and 3D; drag pans (orbits in 3D), pinch zooms, two-finger
 * twist rotates in 3D; tap / double-tap / long-press report the nearest track. [Listener.onFrame] runs after every
 * camera or position change so the Compose overlay (labels, rings, paths) can redraw.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GalaxyView(context: Context) : GLSurfaceView(context) {
    interface Listener {
        fun onTap(i: Int?, x: Float, y: Float) {}
        fun onLongPress(i: Int) {}
        fun onFrame() {}
    }
    var listener: Listener? = null

    private val renderer = GalaxyRenderer()
    private val density = resources.displayMetrics.density

    // ---- points ----
    var n = 0; private set
    private var p2 = FloatArray(0); private var p3 = FloatArray(0); private var pFrom = FloatArray(0)
    private var cur = FloatArray(0)                           // displayed positions (what the shader draws)
    private var colors = FloatArray(0); private var alpha = FloatArray(0)
    private var trans = 1f; private var transT0 = -1L
    private var morph = 0f; private var morphFrom = 0f; private var morphTo = 0f; private var morphT0 = -1L
    var has3D = false; private set
    private var posDirty = true; private var colDirty = true; private var alphaDirty = true

    // ---- camera: target, distance, azimuth, tilt (0 = looking straight down); cur eases toward goal ----
    private class Cam(var tx: Float = 0f, var ty: Float = 0f, var tz: Float = 0f, var d: Float = 30f, var th: Float = 0f, var ph: Float = 0f) {
        fun set(o: Cam) { tx = o.tx; ty = o.ty; tz = o.tz; d = o.d; th = o.th; ph = o.ph }
    }
    private val cam = Cam(); private val goal = Cam()
    private var fitD = 30f
    private var w = 0; private var h = 0
    private var velX = 0f; private var velY = 0f
    private val view = FloatArray(16); private val proj = FloatArray(16); private val vp = FloatArray(16); private val inv = FloatArray(16)
    var screen = FloatArray(0); private set                   // projected px of every point (x, y)
    private var depth = FloatArray(0)
    var frameNo = 0L; private set

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPositions(x: FloatArray, y: FloatArray, xyz: Array<FloatArray>?, animate: Boolean = true) {
        val first = n != x.size
        val from = if (first) null else cur.copyOf()
        n = x.size
        p2 = FloatArray(n * 3); p3 = FloatArray(n * 3)
        for (i in 0 until n) {
            p2[i * 3] = x[i]; p2[i * 3 + 1] = y[i]
            if (xyz != null) { p3[i * 3] = xyz[0][i]; p3[i * 3 + 1] = xyz[1][i]; p3[i * 3 + 2] = xyz[2][i] } else { p3[i * 3] = x[i]; p3[i * 3 + 1] = y[i] }
        }
        has3D = xyz != null
        if (first) {
            cur = FloatArray(n * 3); pFrom = p2.copyOf(); screen = FloatArray(n * 2); depth = FloatArray(n)
            colors = FloatArray(n * 3) { 0.7f }; alpha = FloatArray(n) { 1f }
            colDirty = true; alphaDirty = true
        } else pFrom = from!!
        if (first || !animate) { trans = 1f; transT0 = -1 } else { trans = 0f; transT0 = System.nanoTime() }
        computePositions()
        kick()
    }

    fun setColors(argb: IntArray) {
        if (argb.size != n) return
        for (i in 0 until n) { val c = argb[i]; colors[i * 3] = ((c shr 16) and 255) / 255f; colors[i * 3 + 1] = ((c shr 8) and 255) / 255f; colors[i * 3 + 2] = (c and 255) / 255f }
        colDirty = true; kick()
    }

    /** null = every point at full strength. */
    fun setAlpha(a: FloatArray?) {
        if (a != null && a.size != n) return
        for (i in 0 until n) alpha[i] = a?.get(i) ?: 1f
        alphaDirty = true; kick()
    }
    fun alphaOf(i: Int) = alpha[i]

    fun set3D(on: Boolean, animate: Boolean = true) {
        if (on && !has3D) return
        val to = if (on) 1f else 0f
        if (to == morphTo && (morphT0 >= 0 || morph == to)) return
        morphFrom = morph; morphTo = to; morphT0 = if (animate) System.nanoTime() else System.nanoTime() - 10_000_000_000
        goal.ph = if (on) 0.95f else 0f; goal.th = if (on) 0.5f else 0f; goal.tz = 0f
        if (on) goal.d = max(goal.d, fitD * 0.9f)
        velX = 0f; velY = 0f
        kick()
    }
    val is3D get() = morphTo > 0.5f

    // ---- camera ----
    private fun worldPerPx() = (2f * cam.d * TAN) / max(h, 1)
    val zoomLevel get() = fitD / cam.d   // 1 = the whole map

    private fun targetPos(i: Int, out: FloatArray) {  // where a point is heading (ignoring a running transition)
        val k = i * 3
        for (a in 0..2) out[a] = p2[k + a] + (p3[k + a] - p2[k + a]) * morphTo
    }

    fun fit(animate: Boolean = true, idxs: IntArray? = null) {
        if (n == 0 || w == 0) return
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        val v = FloatArray(3)
        val each = { i: Int -> targetPos(i, v); x0 = min(x0, v[0]); x1 = max(x1, v[0]); y0 = min(y0, v[1]); y1 = max(y1, v[1]); z0 = min(z0, v[2]); z1 = max(z1, v[2]) }
        if (idxs == null) for (i in 0 until n) each(i) else idxs.forEach(each)
        val aspect = w.toFloat() / h
        val dist = { pad: Float -> max(max(y1 - y0, 0.5f), max(x1 - x0, 0.5f) / aspect) * pad / (2 * TAN) + if (morphTo > 0) (z1 - z0) / 2 else 0f }
        if (idxs == null) fitD = dist(1.25f)
        goal.tx = (x0 + x1) / 2; goal.ty = (y0 + y1) / 2; goal.tz = (z0 + z1) / 2
        goal.d = if (idxs == null) fitD else max(dist(1.5f), fitD * 0.06f)
        if (!animate) cam.set(goal)
        velX = 0f; velY = 0f
        fitted = true
        kick()
    }
    private var fitted = false
    private var touched = false

    fun centerOn(i: Int, zoom: Float = 3.3f) {
        if (i !in 0 until n) return
        goal.tx = cur[i * 3]; goal.ty = cur[i * 3 + 1]; goal.tz = cur[i * 3 + 2]
        goal.d = min(goal.d, fitD / zoom)
        velX = 0f; velY = 0f
        kick()
    }

    private fun rightUp(): FloatArray { val th = cam.th; return floatArrayOf(cos(th), sin(th), -sin(th), cos(th)) }
    private fun panBy(dx: Float, dy: Float) {
        val wpp = worldPerPx(); val r = rightUp()
        val mx = (-dx * r[0] + dy * r[2]) * wpp; val my = (-dx * r[1] + dy * r[3]) * wpp
        goal.tx += mx; goal.ty += my; cam.tx += mx; cam.ty += my
        kick()
    }
    /** f < 1 zooms in, anchored at the finger on the flat map (at the centre in 3D). */
    fun zoomAt(f: Float, px: Float, py: Float, immediate: Boolean = false) {
        val d1 = (goal.d * f).coerceIn(fitD * 0.02f, fitD * 3f); val k = d1 / goal.d
        if (!is3D) { val a = toData(px, py); goal.tx = a[0] + (goal.tx - a[0]) * k; goal.ty = a[1] + (goal.ty - a[1]) * k }
        goal.d = d1
        if (immediate) { cam.d = goal.d; cam.tx = goal.tx; cam.ty = goal.ty }
        kick()
    }

    private fun applyCamera() {
        val ex = cam.tx + cam.d * sin(cam.ph) * sin(cam.th); val ey = cam.ty - cam.d * sin(cam.ph) * cos(cam.th); val ez = cam.tz + cam.d * cos(cam.ph)
        Matrix.setLookAtM(view, 0, ex, ey, ez, cam.tx, cam.ty, cam.tz, -sin(cam.th), cos(cam.th), 0f)
        Matrix.perspectiveM(proj, 0, FOV, w.toFloat() / max(h, 1), 0.01f, 2000f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(inv, 0, vp, 0)
    }

    private val tmp4 = FloatArray(4); private val out4 = FloatArray(4)
    /** Screen px of a world point, or null when it's behind the camera. */
    fun project(x: Float, y: Float, z: Float): FloatArray? {
        tmp4[0] = x; tmp4[1] = y; tmp4[2] = z; tmp4[3] = 1f
        Matrix.multiplyMV(out4, 0, vp, 0, tmp4, 0)
        if (out4[3] <= 0f) return null
        return floatArrayOf((out4[0] / out4[3] + 1) * w / 2, (1 - out4[1] / out4[3]) * h / 2)
    }
    private fun projectAll() {
        for (i in 0 until n) {
            tmp4[0] = cur[i * 3]; tmp4[1] = cur[i * 3 + 1]; tmp4[2] = cur[i * 3 + 2]; tmp4[3] = 1f
            Matrix.multiplyMV(out4, 0, vp, 0, tmp4, 0)
            val wv = out4[3]
            if (wv <= 0f) { screen[i * 2] = -1e6f; screen[i * 2 + 1] = -1e6f; depth[i] = 2f; continue }
            screen[i * 2] = (out4[0] / wv + 1) * w / 2; screen[i * 2 + 1] = (1 - out4[1] / wv) * h / 2; depth[i] = out4[2] / wv
        }
    }
    /** The world point under a screen position, on the plane at the target's depth. */
    fun toData(px: Float, py: Float): FloatArray {
        val nx = px / w * 2 - 1; val ny = 1 - py / h * 2
        val a = FloatArray(4); val b = FloatArray(4)
        Matrix.multiplyMV(a, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0); Matrix.multiplyMV(b, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        for (k in 0..2) { a[k] /= a[3]; b[k] /= b[3] }
        val t = if (abs(b[2] - a[2]) < 1e-6) 0f else (cam.tz - a[2]) / (b[2] - a[2])
        return floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, cam.tz)
    }

    fun pick(px: Float, py: Float, radiusPx: Float = 26 * density): Int? {
        var best = -1; var bd = radiusPx * radiusPx; var bz = Float.MAX_VALUE
        for (i in 0 until n) {
            if (alpha[i] < 0.05f) continue
            val dx = screen[i * 2] - px; val dy = screen[i * 2 + 1] - py; val e = dx * dx + dy * dy
            if (e < bd || (e == bd && depth[i] < bz)) { bd = e; best = i; bz = depth[i] }
        }
        return best.takeIf { it >= 0 }
    }
    fun screenOf(i: Int) = floatArrayOf(screen[i * 2], screen[i * 2 + 1])

    // ---- animation loop (only while something moves) ----
    private var scheduled = false
    private var lastNs = 0L
    private val tick = Choreographer.FrameCallback { now -> scheduled = false; frame(now) }
    fun kick() { if (!scheduled) { scheduled = true; Choreographer.getInstance().postFrameCallback(tick) } }

    private fun computePositions() {
        for (k in 0 until n * 3) {
            val t = p2[k] + (p3[k] - p2[k]) * morph
            cur[k] = pFrom[k] + (t - pFrom[k]) * trans
        }
        posDirty = true
    }

    private fun frame(now: Long) {
        if (w == 0) return
        val dt = if (lastNs == 0L) 16f else min(64f, (now - lastNs) / 1e6f); lastNs = now
        var moving = false
        if (transT0 >= 0) {
            val t = min(1f, (now - transT0) / 900e6f); trans = ease(t); computePositions()
            if (t >= 1f) transT0 = -1 else moving = true
        }
        if (morphT0 >= 0) {
            val t = min(1f, (now - morphT0) / 1100e6f); morph = morphFrom + (morphTo - morphFrom) * ease(t); computePositions()
            if (t >= 1f) morphT0 = -1 else moving = true
        }
        if (velX != 0f || velY != 0f) {  // momentum after a fling
            goal.tx += velX * dt; goal.ty += velY * dt; cam.tx = goal.tx; cam.ty = goal.ty
            val f = exp(-dt / 260f); velX *= f; velY *= f
            if (hypot(velX, velY) / worldPerPx() < 0.02f) { velX = 0f; velY = 0f } else moving = true
        }
        val k = 1 - exp(-dt / 70f)
        fun approach(c: Float, g: Float, eps: Float): Float { val d = g - c; return if (abs(d) > eps) { moving = true; c + d * k } else g }
        cam.tx = approach(cam.tx, goal.tx, 1e-4f); cam.ty = approach(cam.ty, goal.ty, 1e-4f); cam.tz = approach(cam.tz, goal.tz, 1e-4f)
        cam.d = approach(cam.d, goal.d, cam.d * 1e-4f); cam.th = approach(cam.th, goal.th, 1e-4f); cam.ph = approach(cam.ph, goal.ph, 1e-4f)
        applyCamera()
        projectAll()
        renderer.submit(GalaxyRenderer.Frame(
            view.copyOf(), proj.copyOf(), (fitD / cam.d).pow(0.33f).coerceIn(0.85f, 2.4f), cam.d, morph, 2.6f * density,
            if (posDirty) cur.copyOf() else null, if (colDirty) colors.copyOf() else null, if (alphaDirty) alpha.copyOf() else null, n,
            (System.nanoTime() - born) / 1e9f, cam.tx / (fitD * 2f), 3f * density, w, h,
        ))
        posDirty = false; colDirty = false; alphaDirty = false
        requestRender()
        frameNo++
        listener?.onFrame()
        if (moving) kick()
    }

    // The stars twinkle: a slow tick while the map is on screen (the map itself only redraws when something moves).
    private val born = System.nanoTime()
    // The wind: only the sky redraws (no re-projecting tracks), ~30 times a second while the map is on screen.
    private val ambient = object : Runnable {
        override fun run() { renderer.time = (System.nanoTime() - born) / 1e9f; if (w > 0) requestRender(); postDelayed(this, 33) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(ambient) }
    override fun onDetachedFromWindow() { removeCallbacks(ambient); super.onDetachedFromWindow() }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        w = nw; h = nh
        if (!fitted || !touched) fit(false) else kick()  // keep the whole map in view until the user moves it
    }

    // ---- gestures ----
    private var twist: Float? = null
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        var fx = 0f; var fy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { fx = d.focusX; fy = d.focusY; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            panBy(d.focusX - fx, d.focusY - fy); fx = d.focusX; fy = d.focusY
            zoomAt(1f / d.scaleFactor, d.focusX, d.focusY, immediate = true)
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { velX = 0f; velY = 0f; return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1 || scaler.isInProgress) return true
            if (is3D) { goal.th += dx * 0.006f; goal.ph = (goal.ph + dy * 0.006f).coerceIn(0f, 1.45f); cam.th = goal.th; cam.ph = goal.ph; kick() }
            else panBy(-dx, -dy)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (is3D || e2.pointerCount > 1) return false
            val wpp = worldPerPx(); val r = rightUp()
            velX = (-vx * r[0] + vy * r[2]) * wpp / 1000f; velY = (-vx * r[1] + vy * r[3]) * wpp / 1000f
            kick(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { listener?.onTap(pick(e.x, e.y), e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomAt(0.5f, e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) {
            pick(e.x, e.y)?.let { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); listener?.onLongPress(it) }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        touched = true
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (is3D && e.pointerCount == 2) {  // two-finger twist turns the 3D view
            val a = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
            twist?.let { goal.th -= (a - it); cam.th = goal.th; kick() }
            twist = a
        } else twist = null
        return true
    }

    companion object {
        private const val FOV = 35f
        private val TAN = tan(FOV / 2 * PI / 180).toFloat()
        private fun ease(t: Float) = 1 - (1 - t).pow(3)
    }
}
