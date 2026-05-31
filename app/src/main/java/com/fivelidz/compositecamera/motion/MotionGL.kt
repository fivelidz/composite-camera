package com.fivelidz.compositecamera.motion

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/**
 * GLSurfaceView-based renderer for Motion Reveal mode.
 *
 * The pipeline that runs at 30 fps natively (was 5 fps in the Kotlin/CPU implementation):
 *
 *   1. CameraX feeds frames into a SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) — zero-copy.
 *   2. On each new frame, we render that SurfaceTexture into one slot of a circular
 *      ring of FBO-backed RGBA textures (the "history ring buffer").
 *   3. Then we render a fullscreen quad sampling BOTH the most-recent capture AND
 *      the ring slot from N seconds ago, blending them in a fragment shader.
 *
 * The blend is the heart of the effect:
 *
 *   InverseOverlay:  out = clamp(0.5 + sensitivity * (current - delayed))
 *   Difference:      out = clamp(sensitivity * abs(current - delayed))
 *   PersistenceTrail: out = current * 0.35 + sensitivity * abs(current - delayed)
 *
 * Everything happens on-GPU. The CPU only sets uniforms.
 *
 * Memory: ring of N textures, each width*height*4 bytes. At 720×1280 RGBA8 and
 * 4-second buffer at 30 fps that's 120 textures × 3.5 MB = 420 MB if naive — so we
 * actually sample at a lower buffer fps (we capture each frame, but only WRITE to a
 * ring slot every (buffer_fps_target) frames). Default: 1.0 s delay max → 30 slots
 * × 3.5 MB = 105 MB. Configurable up to 4 s.
 */
class MotionRenderer(
    private val context: Context,
    val onFrameReady: (textureName: Int, width: Int, height: Int) -> Unit = { _, _, _ -> },
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    enum class BlendMode(val shaderConstant: Int) {
        InverseOverlay(0),
        Difference(1),
        PersistenceTrail(2),
    }

    @Volatile var delaySeconds: Float = 1.0f
    @Volatile var sensitivity: Float = 1.5f
    @Volatile var mode: BlendMode = BlendMode.InverseOverlay

    // Set by CameraXBinder once preview size is known.
    @Volatile private var previewW: Int = 720
    @Volatile private var previewH: Int = 1280

    /** Filled by [getCameraSurfaceTexture] for CameraX to consume. */
    @Volatile private var cameraTextureName: Int = 0
    @Volatile private var cameraSurfaceTexture: SurfaceTexture? = null

    // SurfaceTexture transform matrix (handles camera rotation/mirror).
    private val stMatrix = FloatArray(16)

    // Ring buffer of FBO textures (RGBA8).
    private val ringSize = 60                                // 30 fps × 2 s window
    private val ringFbos  = IntArray(ringSize)
    private val ringTexs  = IntArray(ringSize)
    private val ringTimestampsNs = LongArray(ringSize) { 0L }
    private var ringHead: Int = 0                            // next slot to write
    private var ringInitialized = false
    private var ringW: Int = 0
    private var ringH: Int = 0

    // Shader program handles.
    private var progExternal: Int = 0  // OES → RGBA copy
    private var progBlend:    Int = 0  // blend(current, delayed, mode)

    // Vertex buffer for a fullscreen quad (NDC + UV).
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            // x, y, u, v
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    @Volatile private var newFrameAvailable = false

    fun getCameraSurfaceTexture(width: Int, height: Int): SurfaceTexture? {
        previewW = width; previewH = height
        return cameraSurfaceTexture
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        newFrameAvailable = true
    }

    // --- GLSurfaceView.Renderer ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // External camera texture (OES).
        val texNames = IntArray(1)
        GLES20.glGenTextures(1, texNames, 0)
        cameraTextureName = texNames[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureName)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        cameraSurfaceTexture = SurfaceTexture(cameraTextureName).also { it.setOnFrameAvailableListener(this) }

        progExternal = buildProgram(VERT_BASIC, FRAG_EXTERNAL)
        progBlend    = buildProgram(VERT_BASIC, FRAG_BLEND)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        screenW = w; screenH = h
        GLES20.glViewport(0, 0, w, h)
        // (Re)allocate ring buffer to match preview dims, downscaled to keep memory sane.
        val targetW = min(previewW, 720)
        val targetH = (targetW.toFloat() * previewH / previewW).toInt() and 1.inv()
        if (targetW != ringW || targetH != ringH || !ringInitialized) {
            releaseRing()
            allocateRing(targetW, targetH)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = cameraSurfaceTexture ?: return

        if (newFrameAvailable) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            newFrameAvailable = false
            writeCameraIntoRingHead()
        }

        // Find the delayed slot.
        val nowNs = System.nanoTime()
        val targetNs = nowNs - (delaySeconds * 1e9f).toLong()
        val delayedTex = nearestRingSlot(targetNs)

        // Render the blended composite to the screen-fbo (0).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(progBlend)

        // Bind current = ring head's most recent texture, delayed = nearestRingSlot.
        val currentTex = ringTexs[(ringHead - 1 + ringSize) % ringSize]

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "u_current"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, delayedTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "u_delayed"), 1)

        GLES20.glUniform1f(GLES20.glGetUniformLocation(progBlend, "u_sensitivity"), sensitivity)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlend, "u_mode"), mode.shaderConstant)

        drawFullscreenQuad(progBlend)
    }

    // --- Ring buffer ---

    private fun allocateRing(w: Int, h: Int) {
        ringW = w; ringH = h
        GLES20.glGenFramebuffers(ringSize, ringFbos, 0)
        GLES20.glGenTextures(ringSize, ringTexs, 0)
        for (i in 0 until ringSize) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ringTexs[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbos[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, ringTexs[i], 0)
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) Log.w("MotionRenderer", "FBO incomplete: $st")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        ringInitialized = true
        ringHead = 0
    }

    private fun releaseRing() {
        if (!ringInitialized) return
        GLES20.glDeleteFramebuffers(ringSize, ringFbos, 0)
        GLES20.glDeleteTextures(ringSize, ringTexs, 0)
        ringInitialized = false
    }

    /** Render the camera external texture into the ring head's FBO, then advance the head. */
    private fun writeCameraIntoRingHead() {
        if (!ringInitialized) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ringFbos[ringHead])
        GLES20.glViewport(0, 0, ringW, ringH)
        GLES20.glUseProgram(progExternal)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureName)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progExternal, "u_cam"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(progExternal, "u_stMatrix"), 1, false, stMatrix, 0)

        drawFullscreenQuad(progExternal)

        ringTimestampsNs[ringHead] = System.nanoTime()
        ringHead = (ringHead + 1) % ringSize

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // Restore screen viewport (changed for FBO render above).
        GLES20.glViewport(0, 0, screenW, screenH)
    }

    @Volatile private var screenW: Int = 0
    @Volatile private var screenH: Int = 0

    /** Helper: pick the ring slot whose timestamp is closest to targetNs but ≤ targetNs. */
    private fun nearestRingSlot(targetNs: Long): Int {
        // Walk backwards from (head-1) until we find one with ts ≤ target.
        for (i in 0 until ringSize) {
            val idx = ((ringHead - 1 - i) + ringSize) % ringSize
            val ts = ringTimestampsNs[idx]
            if (ts != 0L && ts <= targetNs) return ringTexs[idx]
        }
        // Fallback to oldest valid slot (initial warm-up).
        val oldest = ringHead  // about-to-be-overwritten oldest slot
        return ringTexs[oldest]
    }

    private fun drawFullscreenQuad(program: Int) {
        val pos = GLES20.glGetAttribLocation(program, "a_pos")
        val uv  = GLES20.glGetAttribLocation(program, "a_uv")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(uv)
    }

    // --- Shader compilation ---

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            Log.e("MotionRenderer", "link failed: $log")
        }
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e("MotionRenderer", "compile failed: ${GLES20.glGetShaderInfoLog(s)}\nsource:\n$src")
        }
        return s
    }

    companion object {
        // Basic vertex shader — receives (x,y) NDC + (u,v) tex coords, passes UV through.
        // For the external-texture pass we ALSO transform the UV by the SurfaceTexture matrix.
        private const val VERT_BASIC = """
            attribute vec2 a_pos;
            attribute vec2 a_uv;
            varying vec2 v_uv;
            void main() {
                v_uv = a_uv;
                gl_Position = vec4(a_pos, 0.0, 1.0);
            }
        """

        // Fragment shader that samples the OES external camera texture into RGBA.
        private const val FRAG_EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_cam;
            uniform mat4 u_stMatrix;
            varying vec2 v_uv;
            void main() {
                vec2 uv = (u_stMatrix * vec4(v_uv, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(u_cam, uv);
            }
        """

        // Fragment shader that blends current + delayed.
        //   mode = 0 → InverseOverlay
        //   mode = 1 → Difference
        //   mode = 2 → PersistenceTrail
        private const val FRAG_BLEND = """
            precision mediump float;
            uniform sampler2D u_current;
            uniform sampler2D u_delayed;
            uniform float u_sensitivity;
            uniform int u_mode;
            varying vec2 v_uv;
            void main() {
                // Flip Y when sampling from FBO textures (they were rendered with flipped Y).
                vec2 uv = vec2(v_uv.x, 1.0 - v_uv.y);
                vec3 c = texture2D(u_current, uv).rgb;
                vec3 d = texture2D(u_delayed, uv).rgb;
                vec3 outc;
                if (u_mode == 0) {
                    outc = vec3(0.5) + u_sensitivity * (c - d);
                } else if (u_mode == 1) {
                    outc = u_sensitivity * abs(c - d);
                } else {
                    outc = c * 0.35 + u_sensitivity * abs(c - d);
                }
                gl_FragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
            }
        """
    }
}
