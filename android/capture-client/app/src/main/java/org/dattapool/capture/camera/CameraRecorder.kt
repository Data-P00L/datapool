package org.dattapool.capture.camera

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class CameraRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var activeRecording: Recording? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null

    private fun getCurrentRotation(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return wm?.defaultDisplay?.rotation ?: Surface.ROTATION_90
    }

    suspend fun initialize(): Boolean = suspendCancellableCoroutine { cont ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD)) // 720p or 1080p for testing
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                val targetRot = getCurrentRotation()
                preview = Preview.Builder()
                    .setTargetRotation(targetRot)
                    .build()
                currentSurfaceProvider?.let { provider ->
                    preview?.setSurfaceProvider(provider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
                cont.resume(true)
            } catch (e: Exception) {
                cont.resume(false)
            }
        }, mainExecutor)
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?) {
        currentSurfaceProvider = surfaceProvider
        preview?.setSurfaceProvider(surfaceProvider)
    }

    fun startRecording(outputFile: File, onFinalized: (Boolean) -> Unit) {
        val capture = videoCapture ?: run {
            onFinalized(false)
            return
        }

        val targetRot = getCurrentRotation()
        videoCapture?.targetRotation = targetRot
        preview?.targetRotation = targetRot

        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            onFinalized(true)
                        } else {
                            onFinalized(false)
                        }
                    }
                }
            }
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }
}
