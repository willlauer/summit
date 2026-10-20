package com.willlauer.summit.map

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the polar-night backdrop, then the tracks as glowing points (GLES 3):
 * an additive halo pass, then crisp cores with a hot centre (the desktop's galaxy.js shaders). All state comes from
 * [GalaxyView] (UI thread) through [Frame] snapshots.
 */
class GalaxyRenderer : GLSurfaceView.Renderer {
    class Frame(
        val view: FloatArray, val proj: FloatArray, val zoom: Float, val ref: Float, val morph: Float, val dot: Float,
        val positions: FloatArray?, val colors: FloatArray?, val alpha: FloatArray?, val n: Int,
        val time: Float = 0f, val pan: Float = 0f, val pixel: Float = 8f, val w: Int = 1, val h: Int = 1,
    )

    @Volatile private var pending: Frame? = null
    /** Seconds since the map appeared: drives the wind (set every tick, independent of camera frames). */
    @Volatile var time = 0f
    /** The look: pixel stars on the grid (and wind), or round glowing stars on a still sky; the sky's two colours. */
    @Volatile var pixel = true
    @Volatile var wind = true
    @Volatile var skyTop = floatArrayOf(0.027f, 0.063f, 0.106f)
    @Volatile var skyBottom = floatArrayOf(0.012f, 0.027f, 0.051f)
    private var frame: Frame? = null
    private var prog = 0; private var progHalo = 0; private var progSky = 0
    private val vbo = IntArray(3)
    private var n = 0
    private var bufPos: FloatBuffer? = null

    fun submit(f: Frame) {  // merge with an unrendered frame so buffer updates aren't lost
        val p = pending
        pending = if (p == null) f else Frame(f.view, f.proj, f.zoom, f.ref, f.morph, f.dot, f.positions ?: p.positions, f.colors ?: p.colors, f.alpha ?: p.alpha, f.n,
            f.time, f.pan, f.pixel, f.w, f.h)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(BG[0], BG[1], BG[2], 1f)
        prog = link(VERT, CORE); progHalo = link(VERT, HALO); progSky = link(SKY_VS, SKY_FS)
        glGenBuffers(3, vbo, 0)
        n = 0
        // A recreated surface lost its buffers: resend everything from the last frame.
        frame?.let { f -> pending = Frame(f.view, f.proj, f.zoom, f.ref, f.morph, f.dot, lastPos, lastCol, lastAlpha, f.n, f.time, f.pan, f.pixel, f.w, f.h) }
    }

    private var lastPos: FloatArray? = null; private var lastCol: FloatArray? = null; private var lastAlpha: FloatArray? = null

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { glViewport(0, 0, width, height) }

    private fun upload(slot: Int, data: FloatArray) {
        val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        b.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, vbo[slot])
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, b, GL_DYNAMIC_DRAW)
        if (slot == 0) bufPos = b
    }

    override fun onDrawFrame(gl: GL10?) {
        pending?.let { p ->
            pending = null
            p.positions?.let { upload(0, it); lastPos = it }
            p.colors?.let { upload(1, it); lastCol = it }
            p.alpha?.let { upload(2, it); lastAlpha = it }
            n = p.n
            frame = p
        }
        glClear(GL_COLOR_BUFFER_BIT)
        val f = frame ?: return
        sky(f)
        if (n == 0 || lastPos == null || lastCol == null || lastAlpha == null) return
        glEnable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)
        glBlendFunc(GL_ONE, GL_ONE)  // halos add up into glow
        draw(progHalo, f, halo = true)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        draw(prog, f, halo = false)
    }

    private fun sky(f: Frame) {  // one full-screen triangle; no vertex data
        glDisable(GL_BLEND)
        for (k in 0..2) glDisableVertexAttribArray(k)
        glUseProgram(progSky)
        glUniform2f(glGetUniformLocation(progSky, "uRes"), f.w.toFloat(), f.h.toFloat())
        glUniform1f(glGetUniformLocation(progSky, "uTime"), time % 3600f)
        glUniform1f(glGetUniformLocation(progSky, "uPan"), f.pan)
        glUniform1f(glGetUniformLocation(progSky, "uPx"), f.pixel)
        glUniform1f(glGetUniformLocation(progSky, "uWind"), if (wind) 1f else 0f)
        glUniform3fv(glGetUniformLocation(progSky, "uTop"), 1, skyTop, 0)
        glUniform3fv(glGetUniformLocation(progSky, "uBottom"), 1, skyBottom, 0)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    private fun draw(p: Int, f: Frame, halo: Boolean) {
        glUseProgram(p)
        glUniformMatrix4fv(glGetUniformLocation(p, "uView"), 1, false, f.view, 0)
        glUniformMatrix4fv(glGetUniformLocation(p, "uProj"), 1, false, f.proj, 0)
        glUniform1f(glGetUniformLocation(p, "uSize"), f.dot * (if (halo) 3.2f else 1f))
        glUniform1f(glGetUniformLocation(p, "uPx"), f.pixel)
        glUniform1f(glGetUniformLocation(p, "uPixel"), if (pixel) 1f else 0f)
        glUniform2f(glGetUniformLocation(p, "uRes"), f.w.toFloat(), f.h.toFloat())
        glUniform1f(glGetUniformLocation(p, "uZoom"), f.zoom)
        glUniform1f(glGetUniformLocation(p, "uRef"), f.ref)
        glUniform1f(glGetUniformLocation(p, "uMorph"), f.morph)
        glUniform1f(glGetUniformLocation(p, "uGlow"), if (pixel) 0.09f else 0.2f)
        attr(0, 0, 3); attr(1, 1, 3); attr(2, 2, 1)
        glDrawArrays(GL_POINTS, 0, n)
    }

    private fun attr(loc: Int, slot: Int, size: Int) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo[slot])
        glEnableVertexAttribArray(loc)
        glVertexAttribPointer(loc, size, GL_FLOAT, false, 0, 0)
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = glCreateShader(type).also { s ->
            glShaderSource(s, src); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(s) }
        }
        return glCreateProgram().also { pr ->
            glAttachShader(pr, shader(GL_VERTEX_SHADER, vs)); glAttachShader(pr, shader(GL_FRAGMENT_SHADER, fs))
            glBindAttribLocation(pr, 0, "aPos"); glBindAttribLocation(pr, 1, "aColor"); glBindAttribLocation(pr, 2, "aAlpha")
            glLinkProgram(pr)
        }
    }

    companion object {
        val BG = floatArrayOf(0x08 / 255f, 0x08 / 255f, 0x0D / 255f)
        const val VERT = """#version 300 es
            layout(location = 0) in vec3 aPos; layout(location = 1) in vec3 aColor; layout(location = 2) in float aAlpha;
            uniform mat4 uView, uProj; uniform float uSize, uZoom, uRef, uMorph, uPx, uPixel; uniform vec2 uRes;
            out vec3 vColor; out float vAlpha; out float vCells;
            void main() {
              vec4 mv = uView * vec4(aPos, 1.0);
              float persp = mix(1.0, clamp(uRef / -mv.z, 0.35, 2.6), uMorph);
              vec4 clip = uProj * mv;
              vColor = aColor; vAlpha = aAlpha;
              if (uPixel < 0.5) { gl_PointSize = uSize * uZoom * persp; gl_Position = clip; vCells = 0.0; return; }  // Classic: free-floating
              // pixel art: whole pixel units in size, centred on the pixel grid
              vCells = max(1.0, floor(uSize * uZoom * persp / uPx + 0.5));
              gl_PointSize = vCells * uPx;
              vec2 px = (clip.xy / clip.w * 0.5 + 0.5) * uRes;
              px = (floor(px / uPx) + (mod(vCells, 2.0) == 1.0 ? 0.5 : 0.0)) * uPx;
              gl_Position = vec4((px / uRes * 2.0 - 1.0) * clip.w, clip.z, clip.w);
              vColor = aColor; vAlpha = aAlpha;
            }"""
        const val CORE = """#version 300 es
            precision mediump float;
            in vec3 vColor; in float vAlpha; in float vCells; out vec4 o;
            void main() {
              if (vCells == 0.0) {  // Classic: a round star with a hot centre
                float r = length(gl_PointCoord - 0.5);
                float a = smoothstep(0.5, 0.36, r);
                if (a <= 0.0) discard;
                o = vec4(mix(vColor, vec3(1.0), smoothstep(0.26, 0.0, r) * 0.35), a * vAlpha);
                return;
              }
              // Pixel: a square star; bigger ones get a white centre pixel
              vec2 q = floor(gl_PointCoord * vCells);
              float centre = (vCells >= 3.0 && all(equal(q, vec2(floor(vCells / 2.0))))) ? 1.0 : 0.0;
              vec3 c = mix(mix(vColor, vec3(1.0), 0.15) * 0.82, vec3(0.92, 0.97, 1.0), centre * 0.7);
              o = vec4(c, vAlpha * 0.9);
            }"""
        // Polar night: a smooth, very dark gradient (matches ui/Pixel.kt's Backdrop). Wind: thin gusts streak across and snow blows sideways in two layers,
        // all on the pixel grid; the wind's strength rises and falls slowly.
        const val SKY_VS = """#version 300 es
            void main() { vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)); gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }"""
        const val SKY_FS = """#version 300 es
            precision highp float;
            uniform vec2 uRes; uniform float uTime, uPan, uPx, uWind; uniform vec3 uTop, uBottom;
            out vec4 o;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            void main() {
              vec2 cell = floor(vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uPx);
              // smooth, very dark gradient; sub-level noise hides 8-bit banding
              float y = gl_FragCoord.y / uRes.y;
              vec3 c = mix(uBottom, uTop, y * y);
              c += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
              if (uWind < 0.5) { o = vec4(c, 1.0); return; }
              float t = uTime, cols = uRes.x / uPx;
              float gust = 0.55 + 0.45 * sin(t * 0.21) * sin(t * 0.13 + 1.3);
              // gust streaks: lanes every 11 units; a bright head with a fading tail, drifting a little up and down
              float lane = floor(cell.y / 11.0), hl = hash(vec2(lane, 1.7));
              float wob = floor(sin(cell.x * 0.035 + t * 0.6 + lane) * 1.5);
              if (mod(cell.y + wob, 11.0) == 0.0 && hl > 0.5) {
                float speed = 22.0 + hl * 40.0, len = 18.0 + hash(vec2(lane, 4.2)) * 40.0, span = cols + len * 2.0 + 60.0;
                float head = mod(t * speed * (0.6 + gust * 0.6) + hash(vec2(lane, 9.1)) * span, span) - len;
                float d = head - cell.x;
                if (d >= 0.0 && d < len) c = mix(c, vec3(0.70, 0.88, 1.0), (1.0 - d / len) * 0.13 * gust);
              }
              // blown snow: a near, fast layer and a far, slow one
              vec2 p1 = floor(vec2(cell.x - t * (26.0 + 18.0 * gust), cell.y - t * 6.0));
              float f1 = hash(p1 + 0.37);
              if (f1 > 0.9986) c = mix(c, vec3(0.92, 0.97, 1.0), 0.5);
              vec2 p2 = floor(vec2(cell.x - t * (11.0 + 8.0 * gust) + floor(sin(cell.y * 0.07 + t) * 2.0), cell.y - t * 3.0));
              float f2 = hash(p2 + 7.1);
              if (f2 > 0.9982) c = mix(c, vec3(0.75, 0.87, 1.0), 0.28);
              o = vec4(c, 1.0);
            }"""
        const val HALO = """#version 300 es
            precision mediump float;
            in vec3 vColor; in float vAlpha; in float vCells; uniform float uGlow; out vec4 o;
            void main() {
              if (vCells == 0.0) {  // Classic: a soft round glow
                float r = length(gl_PointCoord - 0.5) * 2.0;
                float a = exp(-r * r * 5.0) * uGlow * vAlpha * vAlpha;
                if (a < 0.004) discard;
                o = vec4(vColor * a, a);
                return;
              }
              // Pixel: glow in two hard steps, on the pixel grid
              vec2 q = (floor(gl_PointCoord * vCells) + 0.5) / vCells - 0.5;
              float r = max(abs(q.x), abs(q.y)) * 2.0;
              float a = (r < 0.45 ? 1.0 : r < 0.8 ? 0.4 : 0.0) * uGlow * vAlpha * vAlpha;
              if (a < 0.004) discard;
              o = vec4(vColor * a, a);
            }"""
    }
}
