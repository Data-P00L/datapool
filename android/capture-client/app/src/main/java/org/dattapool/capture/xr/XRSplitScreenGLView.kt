package org.dattapool.capture.xr

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class XRDisplayMode {
    MONO_DEBUG,     // Fullscreen single viewport for orientation verification
    SBS_RAW,        // SBS viewports, camera only
    SBS_FULL        // SBS viewports with eye-safe HUD
}

data class XRDiagnostics(
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val displayRotationDegrees: Int = 0,
    val sensorOrientation: Int = 90,
    val cameraRotationDegrees: Int = 0,
    val bufferWidth: Int = 0,
    val bufferHeight: Int = 0,
    val eyeWidth: Int = 0,
    val eyeHeight: Int = 0,
    val mode: XRDisplayMode = XRDisplayMode.SBS_FULL
)

class XRSplitScreenGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), Preview.SurfaceProvider {

    private val renderer: XRRenderer

    var displayMode: XRDisplayMode
        get() = renderer.displayMode
        set(value) {
            renderer.displayMode = value
            requestRender()
        }

    var eyeCalibration: XrEyeCalibration
        get() = renderer.eyeCalibration
        set(value) {
            renderer.eyeCalibration = value
            requestRender()
        }

    var eyeVisibilityMode: EyeVisibilityMode
        get() = renderer.eyeVisibilityMode
        set(value) {
            renderer.eyeVisibilityMode = value
            requestRender()
        }

    var eyeCenterOffsetX: Float
        get() = renderer.eyeCalibration.leftOffsetXPx
        set(value) {
            renderer.eyeCalibration = renderer.eyeCalibration.copy(leftOffsetXPx = value, rightOffsetXPx = value)
            requestRender()
        }

    var eyeCenterOffsetY: Float
        get() = renderer.eyeCalibration.leftOffsetYPx
        set(value) {
            renderer.eyeCalibration = renderer.eyeCalibration.copy(leftOffsetYPx = value, rightOffsetYPx = value)
            requestRender()
        }

    var eyeScale: Float
        get() = renderer.eyeCalibration.leftScale
        set(value) {
            renderer.eyeCalibration = renderer.eyeCalibration.copy(leftScale = value, rightScale = value)
            requestRender()
        }

    // Backward compatibility for boolean split-screen toggle
    var isSplitScreen: Boolean
        get() = renderer.displayMode != XRDisplayMode.MONO_DEBUG
        set(value) {
            renderer.displayMode = if (value) XRDisplayMode.SBS_FULL else XRDisplayMode.MONO_DEBUG
            requestRender()
        }

    init {
        setEGLContextClientVersion(2)
        renderer = XRRenderer(context) { requestRender() }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun getDiagnostics(): XRDiagnostics = renderer.getDiagnostics()

    override fun onSurfaceRequested(request: SurfaceRequest) {
        renderer.provideSurfaceRequest(request)
    }

    override fun onPause() {
        super.onPause()
        renderer.releaseSurface()
    }

    private class XRRenderer(
        private val context: Context,
        private val onRequestRender: () -> Unit
    ) : Renderer {

        var displayMode: XRDisplayMode = XRDisplayMode.SBS_FULL
        var eyeCalibration: XrEyeCalibration = XrEyeCalibration()
        var eyeVisibilityMode: EyeVisibilityMode = EyeVisibilityMode.BOTH

        private var textureId: Int = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var pendingRequest: SurfaceRequest? = null

        private var program: Int = 0
        private var aPositionHandle: Int = 0
        private var aTexCoordHandle: Int = 0
        private var uSTMatrixHandle: Int = 0

        private var viewWidth: Int = 2400
        private var viewHeight: Int = 1080

        private var bufferWidth: Int = 1920
        private var bufferHeight: Int = 1080
        private var sensorOrientation: Int = 90

        private val stMatrix = FloatArray(16)

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uSTMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(32)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(floatArrayOf(
                    -1.0f, -1.0f,
                     1.0f, -1.0f,
                    -1.0f,  1.0f,
                     1.0f,  1.0f
                ))
                position(0)
            }

        private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(32)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 1.0f
                ))
                position(0)
            }

        init {
            sensorOrientation = querySensorOrientation()
            Log.i("XRRenderer", "Initialized XRRenderer with sensorOrientation=$sensorOrientation")
        }

        fun getDiagnostics(): XRDiagnostics {
            val dispDeg = getDisplayRotationDegrees()
            val rotDeg = calculateCameraRotationDegrees(dispDeg)
            val eyeW = if (displayMode == XRDisplayMode.MONO_DEBUG) viewWidth else viewWidth / 2
            val eyeH = viewHeight
            return XRDiagnostics(
                displayWidth = viewWidth,
                displayHeight = viewHeight,
                displayRotationDegrees = dispDeg,
                sensorOrientation = sensorOrientation,
                cameraRotationDegrees = rotDeg,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                eyeWidth = eyeW,
                eyeHeight = eyeH,
                mode = displayMode
            )
        }

        private fun querySensorOrientation(): Int {
            return try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: "0"
                val chars = cameraManager?.getCameraCharacteristics(cameraId)
                chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            } catch (e: Exception) {
                Log.w("XRRenderer", "Error querying camera sensor orientation, fallback to 90: ${e.message}")
                90
            }
        }

        private fun getDisplayRotationDegrees(): Int {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val rot = wm?.defaultDisplay?.rotation ?: Surface.ROTATION_90
            return when (rot) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 90
            }
        }

        /**
         * Canonical orientation calculation:
         * Back camera sensor orientation (90°) relative to display rotation (90° for Landscape).
         * To rotate the sensor buffer (which is oriented at 90°) to upright landscape (90° display):
         * (sensorOrientation - displayDegrees + 270 + 360) % 360
         * For back camera (90°) in Landscape (90°): (90 - 90 + 270 + 360) % 360 = 270°.
         */
        private fun calculateCameraRotationDegrees(displayDegrees: Int): Int {
            return (sensorOrientation - displayDegrees + 270 + 360) % 360
        }

        fun provideSurfaceRequest(request: SurfaceRequest) {
            bufferWidth = request.resolution.width
            bufferHeight = request.resolution.height
            Log.i("XRRenderer", "provideSurfaceRequest resolution=${bufferWidth}x${bufferHeight}")

            val s = surface
            val st = surfaceTexture
            if (s != null && st != null) {
                st.setDefaultBufferSize(bufferWidth, bufferHeight)
                request.provideSurface(s, ContextCompat.getMainExecutor(context)) {}
            } else {
                pendingRequest = request
            }
        }

        fun releaseSurface() {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

            // Setup OES external texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    onRequestRender()
                }
            }
            surfaceTexture = st
            surface = Surface(st)

            // Fulfill pending CameraX request if present
            pendingRequest?.let { req ->
                st.setDefaultBufferSize(bufferWidth, bufferHeight)
                req.provideSurface(surface!!, ContextCompat.getMainExecutor(context)) {}
                pendingRequest = null
            }

            // Compile shaders & create program
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            program = GLES20.glCreateProgram().also { prog ->
                GLES20.glAttachShader(prog, vertexShader)
                GLES20.glAttachShader(prog, fragmentShader)
                GLES20.glLinkProgram(prog)
            }

            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            Log.i("XRRenderer", "onSurfaceChanged viewWidth=$viewWidth, viewHeight=$viewHeight")
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return

            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            } catch (e: Exception) {
                return
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

            val displayDegrees = getDisplayRotationDegrees()
            val rotationDegrees = calculateCameraRotationDegrees(displayDegrees)

            when (displayMode) {
                XRDisplayMode.MONO_DEBUG -> {
                    // Single Fullscreen Viewport for verification of upright camera
                    drawEyeViewport(
                        x = 0,
                        y = 0,
                        vpWidth = viewWidth,
                        vpHeight = viewHeight,
                        rotationDegrees = rotationDegrees,
                        offsetXPx = eyeCalibration.effectiveLeftX,
                        offsetYPx = eyeCalibration.effectiveLeftY,
                        scale = eyeCalibration.effectiveLeftScale,
                        eyeRotationDeg = eyeCalibration.effectiveLeftRotation
                    )
                }
                XRDisplayMode.SBS_RAW, XRDisplayMode.SBS_FULL -> {
                    val halfW = viewWidth / 2

                    // Determine eye visibility based on EyeVisibilityMode
                    val nowMs = System.currentTimeMillis()
                    val showLeft: Boolean
                    val showRight: Boolean

                    when (eyeVisibilityMode) {
                        EyeVisibilityMode.BOTH -> {
                            showLeft = true
                            showRight = true
                        }
                        EyeVisibilityMode.LEFT_ONLY -> {
                            showLeft = true
                            showRight = false
                        }
                        EyeVisibilityMode.RIGHT_ONLY -> {
                            showLeft = false
                            showRight = true
                        }
                        EyeVisibilityMode.ALTERNATE_FLICKER -> {
                            // 1 Hz alternation (500ms Left, 500ms Right)
                            val isLeftPhase = (nowMs % 1000L) < 500L
                            showLeft = isLeftPhase
                            showRight = !isLeftPhase
                            // Continually request render for flicker animation
                            onRequestRender()
                        }
                    }

                    // Left Eye Viewport: [0, 0, W/2, H]
                    if (showLeft) {
                        drawEyeViewport(
                            x = 0,
                            y = 0,
                            vpWidth = halfW,
                            vpHeight = viewHeight,
                            rotationDegrees = rotationDegrees,
                            offsetXPx = eyeCalibration.effectiveLeftX,
                            offsetYPx = eyeCalibration.effectiveLeftY,
                            scale = eyeCalibration.effectiveLeftScale,
                            eyeRotationDeg = eyeCalibration.effectiveLeftRotation
                        )
                    }

                    // Right Eye Viewport: [W/2, 0, W/2, H]
                    if (showRight) {
                        drawEyeViewport(
                            x = halfW,
                            y = 0,
                            vpWidth = halfW,
                            vpHeight = viewHeight,
                            rotationDegrees = rotationDegrees,
                            offsetXPx = eyeCalibration.effectiveRightX,
                            offsetYPx = eyeCalibration.effectiveRightY,
                            scale = eyeCalibration.effectiveRightScale,
                            eyeRotationDeg = eyeCalibration.effectiveRightRotation
                        )
                    }
                }
            }

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        }

        /**
         * Renders an individual eye viewport.
         *
         * ===================================================================================
         * ARCHITECTURE DEMARCATION:
         * 1. CANONICAL CAMERA TRANSFORM:
         *    - Back camera sensor orientation (90°) rotated to display landscape orientation
         *    - Symmetrical CENTER_CROP to fit camera aspect ratio to eye viewport aspect ratio
         *
         * 2. PER-EYE CALIBRATION TRANSFORM:
         *    - Normalized UV optical center translation (offsetXPx / vpWidth, offsetYPx / vpHeight)
         *    - Per-eye optical scaling (scale)
         *    - Per-eye rotational alignment (eyeRotationDeg)
         * ===================================================================================
         */
        private fun drawEyeViewport(
            x: Int,
            y: Int,
            vpWidth: Int,
            vpHeight: Int,
            rotationDegrees: Int,
            offsetXPx: Float,
            offsetYPx: Float,
            scale: Float,
            eyeRotationDeg: Float
        ) {
            GLES20.glViewport(x, y, vpWidth, vpHeight)

            // --- SECTION 1: CANONICAL CAMERA TRANSFORM (UPRIGHT ASPECT & CROP) ---
            val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
            val uprightCamWidth = if (isRotated) bufferHeight else bufferWidth
            val uprightCamHeight = if (isRotated) bufferWidth else bufferHeight

            val camAspect = uprightCamWidth.toFloat() / uprightCamHeight.toFloat().coerceAtLeast(1f)
            val vpAspect = vpWidth.toFloat() / vpHeight.toFloat().coerceAtLeast(1f)

            val cropCanonicalX: Float
            val cropCanonicalY: Float

            // Symmetrical CENTER_CROP in canonical upright viewport space:
            if (camAspect > vpAspect) {
                cropCanonicalX = vpAspect / camAspect
                cropCanonicalY = 1.0f
            } else {
                cropCanonicalX = 1.0f
                cropCanonicalY = camAspect / vpAspect
            }

            // --- SECTION 2: PER-EYE CALIBRATION TRANSFORM (OFFSET, SCALE, ROTATION) ---
            // Convert pixel offsets into normalized UV coordinates (-1.0 to 1.0 UV shift)
            val normOffsetX = offsetXPx / vpWidth.toFloat()
            val normOffsetY = -offsetYPx / vpHeight.toFloat() // Invert Y for OpenGL UV space

            val transformMatrix = FloatArray(16)
            Matrix.setIdentityM(transformMatrix, 0)

            // 1. Shift optical center in UV space
            Matrix.translateM(transformMatrix, 0, 0.5f + normOffsetX, 0.5f + normOffsetY, 0.0f)

            // 2. Rotate canonical upright frame to sensor buffer orientation + eye calibration rotation
            val totalRotation = rotationDegrees.toFloat() + eyeRotationDeg
            if (totalRotation != 0f) {
                Matrix.rotateM(transformMatrix, 0, totalRotation, 0f, 0f, 1f)
            }

            // 3. Apply symmetric CENTER_CROP & optical scale in canonical frame
            Matrix.scaleM(transformMatrix, 0, cropCanonicalX * scale, cropCanonicalY * scale, 1.0f)

            // 4. Center coordinates around (0, 0) for rotation & scaling
            Matrix.translateM(transformMatrix, 0, -0.5f, -0.5f, 0.0f)

            // 5. Combine with SurfaceTexture hardware matrix
            val finalSTMatrix = FloatArray(16)
            Matrix.multiplyMM(finalSTMatrix, 0, stMatrix, 0, transformMatrix, 0)

            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, finalSTMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            return GLES20.glCreateShader(type).also { shader ->
                GLES20.glShaderSource(shader, shaderCode)
                GLES20.glCompileShader(shader)
            }
        }
    }
}

