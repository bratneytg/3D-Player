package com.vr3d.player

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Recibe los fotogramas del reproductor en una textura externa (OES) y dibuja la vista estéreo:
 * VR (dos ojos con corrección de lente), rojo/cian (anaglifo) o 2D (solo un ojo).
 */
class StereoRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        const val MODE_VR = 0
        const val MODE_ANAGLYPH = 1
        const val MODE_2D = 2

        private const val VS = """
attribute vec2 aPos;
varying vec2 vPos;
void main() {
  vPos = aPos;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
        private const val FS = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vPos;
uniform samplerExternalOES uTex;
uniform mat4 uTexMat;
uniform vec4 uRectA;
uniform vec4 uRectB;
uniform vec2 uFit;
uniform vec2 uShifts;
uniform vec2 uK;
uniform int uMode;

vec3 fetchSafe(vec4 r, vec2 p) {
  if (abs(p.x) > 1.0 || abs(p.y) > 1.0) {
    return vec3(0.0);
  }
  vec2 uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
  uv = clamp(uv, 0.0, 1.0);
  vec2 vu = r.xy + uv * r.zw;
  vec2 t = (uTexMat * vec4(vu.x, 1.0 - vu.y, 0.0, 1.0)).xy;
  return texture2D(uTex, t).rgb;
}

void main() {
  vec2 q = vPos;
  float r2 = dot(q, q);
  q = q * (1.0 + uK.x * r2 + uK.y * r2 * r2);
  vec2 p = q / uFit;
  vec2 pa = vec2(p.x + uShifts.x, p.y);
  vec2 pb = vec2(p.x + uShifts.y, p.y);
  vec3 a = fetchSafe(uRectA, pa);
  if (uMode == 1) {
    vec3 b = fetchSafe(uRectB, pb);
    gl_FragColor = vec4(a.r, b.g, b.b, 1.0);
  } else {
    gl_FragColor = vec4(a, 1.0);
  }
}
"""
        // Fuerza de la corrección de lente para cada nivel (0 = apagada)
        val LENS_LEVELS = floatArrayOf(0f, 0.6f, 1f, 1.6f)
    }

    @Volatile var viewMode: Int = MODE_VR
    @Volatile var layout: Layout = Layout.MONO
    @Volatile var swapEyes: Boolean = false
    @Volatile var separation: Float = 0f
    @Volatile var zoom: Float = 1f
    @Volatile var lensLevel: Int = 2
    @Volatile var videoWidth: Int = 0
    @Volatile var videoHeight: Int = 0

    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var uTexMat = 0
    private var uRectA = 0
    private var uRectB = 0
    private var uFit = 0
    private var uShifts = 0
    private var uK = 0
    private var uMode = 0
    private var uTex = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val frameAvailable = AtomicBoolean(false)
    private val texMatrix = FloatArray(16)
    private var viewW = 1
    private var viewH = 1

    private val quad: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    init {
        Matrix.setIdentityM(texMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
        uRectA = GLES20.glGetUniformLocation(program, "uRectA")
        uRectB = GLES20.glGetUniformLocation(program, "uRectB")
        uFit = GLES20.glGetUniformLocation(program, "uFit")
        uShifts = GLES20.glGetUniformLocation(program, "uShifts")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uTex = GLES20.glGetUniformLocation(program, "uTex")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surface?.release()
        surfaceTexture?.release()
        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        val s = Surface(st)
        surface = s
        frameAvailable.set(false)
        onSurfaceReady(s)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = if (width > 0) width else 1
        viewH = if (height > 0) height else 1
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        frameAvailable.set(true)
        requestRender()
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture
        if (st != null && frameAvailable.getAndSet(false)) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            } catch (e: Exception) {
                // se ignora: se reintenta con el siguiente fotograma
            }
        }
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (program == 0 || videoWidth <= 0 || videoHeight <= 0) return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMatrix, 0)

        val sep = separation
        val lensK = LENS_LEVELS[lensLevel.coerceIn(0, LENS_LEVELS.size - 1)]
        val leftIdx = if (swapEyes) 1 else 0
        val rightIdx = if (swapEyes) 0 else 1
        val lay = layout

        when (viewMode) {
            MODE_VR -> {
                val half = viewW / 2
                drawEye(0, 0, half, viewH, rect(lay, leftIdx), rect(lay, leftIdx), sep, sep, 0, lensK)
                drawEye(half, 0, viewW - half, viewH, rect(lay, rightIdx), rect(lay, rightIdx), -sep, -sep, 0, lensK)
            }
            MODE_ANAGLYPH ->
                drawEye(0, 0, viewW, viewH, rect(lay, leftIdx), rect(lay, rightIdx), sep, -sep, 1, 0f)
            else ->
                drawEye(0, 0, viewW, viewH, rect(lay, leftIdx), rect(lay, leftIdx), 0f, 0f, 0, 0f)
        }
    }

    private fun drawEye(
        x: Int, y: Int, w: Int, h: Int,
        a: FloatArray, b: FloatArray,
        shiftA: Float, shiftB: Float, mode: Int, lensK: Float
    ) {
        GLES20.glViewport(x, y, w, h)
        val ea = StereoDetect.eyeAspect(layout, videoWidth, videoHeight)
        val va = w.toFloat() / h.toFloat()
        var fx: Float
        var fy: Float
        if (ea > va) {
            fx = 1f
            fy = va / ea
        } else {
            fx = ea / va
            fy = 1f
        }
        fx *= zoom
        fy *= zoom
        GLES20.glUniform4f(uRectA, a[0], a[1], a[2], a[3])
        GLES20.glUniform4f(uRectB, b[0], b[1], b[2], b[3])
        GLES20.glUniform2f(uFit, fx, fy)
        GLES20.glUniform2f(uShifts, shiftA, shiftB)
        GLES20.glUniform2f(uK, 0.12f * lensK, 0.06f * lensK)
        GLES20.glUniform1i(uMode, mode)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Zona del video (x, y, ancho, alto; origen arriba-izquierda, 0..1) que corresponde a un ojo. */
    private fun rect(l: Layout, eye: Int): FloatArray = when (l) {
        Layout.LR_FULL, Layout.LR_HALF ->
            if (eye == 0) floatArrayOf(0f, 0f, 0.5f, 1f) else floatArrayOf(0.5f, 0f, 0.5f, 1f)
        Layout.TB ->
            if (eye == 0) floatArrayOf(0f, 0f, 1f, 0.5f) else floatArrayOf(0f, 0.5f, 1f, 0.5f)
        else -> floatArrayOf(0f, 0f, 1f, 1f)
    }

    fun release() {
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FS)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("No se pudo enlazar el shader: $log")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("No se pudo compilar el shader: $log")
        }
        return s
    }
}
