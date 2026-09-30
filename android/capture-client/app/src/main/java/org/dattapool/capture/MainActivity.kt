package org.dattapool.capture

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dattapool.capture.camera.CameraRecorder
import org.dattapool.capture.compensation.*
import org.dattapool.capture.crypto.KeyPair
import org.dattapool.capture.export.BundleExporter
import org.dattapool.capture.input.CaptureTriggerSource
import org.dattapool.capture.input.DualSenseInputHandler
import org.dattapool.capture.input.ExternalCaptureInputHandler
import org.dattapool.capture.input.InputDiagnostics
import org.dattapool.capture.nostr.WitnessClient
import org.dattapool.capture.protocol.ActiveSession
import org.dattapool.capture.protocol.CaptureEngine
import org.dattapool.capture.protocol.CaptureResult
import org.dattapool.capture.xr.AdjustmentStepMode
import org.dattapool.capture.xr.CalibrationDisplaySource
import org.dattapool.capture.xr.CalibrationParam
import org.dattapool.capture.xr.EyeVisibilityMode
import org.dattapool.capture.xr.XRCalibrationScreen
import org.dattapool.capture.xr.XRRecordingScreen
import org.dattapool.capture.xr.XrCalibrationStore
import org.dattapool.capture.xr.XrEyeCalibration
import org.dattapool.capture.publication.*
import org.dattapool.capture.ui.publication.YouTubePublicationDialog
import org.dattapool.capture.youtube.*
import java.io.File

import android.view.PointerIcon
import android.view.WindowManager

enum class AppState {
    IDLE,
    RECORDING,
    SEALING,
    SEALED,
    ERROR,
    XR_CALIBRATION
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG_CAPTURE = "DATTA_CAPTURE"
        private const val TAG_XR_CAL = "DATTA_XR_CAL"
    }

    private lateinit var cameraRecorder: CameraRecorder
    private lateinit var captureEngine: CaptureEngine
    private lateinit var inputHandler: ExternalCaptureInputHandler
    private lateinit var calibrationStore: XrCalibrationStore
    private lateinit var dualSenseHandler: DualSenseInputHandler
    private lateinit var compensationRepository: CompensationRepository
    private lateinit var publicationRepository: PublicationRepository
    private lateinit var youTubeAuthManager: YouTubeAuthManager
    private lateinit var youTubeUploader: YouTubeUploader
    private val witnessClient = WitnessClient()

    private var workerKey by mutableStateOf<KeyPair?>(null)
    private var deviceKey by mutableStateOf<KeyPair?>(null)
    private var appState by mutableStateOf(AppState.IDLE)
    private var previousStateBeforeCalibration by mutableStateOf(AppState.IDLE)
    private var activeSession by mutableStateOf<ActiveSession?>(null)
    private var captureResult by mutableStateOf<CaptureResult?>(null)
    private var compensationReceipt by mutableStateOf<CompensationReceipt?>(null)
    private var mediaPublication by mutableStateOf<MediaPublication?>(null)
    private var showReceiptDetails by mutableStateOf(false)
    private var showYouTubeDialog by mutableStateOf(false)
    private var uploadProgress by mutableStateOf(UploadProgress())
    private var errorMessage by mutableStateOf("")

    private var recordingSeconds by mutableStateOf(0)
    private var skillTag by mutableStateOf("warehouse_object_pick")
    private var bitcoinBlockHeight by mutableStateOf<Long?>(null)

    // Calibration States
    private var eyeCalibration by mutableStateOf(XrEyeCalibration())
    private var selectedParam by mutableStateOf(CalibrationParam.RIGHT_X)
    private var displaySource by mutableStateOf(CalibrationDisplaySource.CALIBRATION_PATTERN)
    private var stepMode by mutableStateOf(AdjustmentStepMode.FINE)
    private var eyeVisibility by mutableStateOf(EyeVisibilityMode.BOTH)
    private var isOverlayVisible by mutableStateOf(true)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initCamera()
        } else {
            Toast.makeText(this, "Camera permission required for DattaPool capture", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure screen remains awake and in landscape mode for Gear VR headset
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Enable default mouse/trackpad pointer cursor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.decorView.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_DEFAULT)
        }
        window.decorView.isFocusable = true
        window.decorView.isFocusableInTouchMode = true
        window.decorView.requestFocus()

        captureEngine = CaptureEngine(this)
        cameraRecorder = CameraRecorder(this, this)
        calibrationStore = XrCalibrationStore(this)
        compensationRepository = LocalCompensationRepository(this)
        publicationRepository = LocalPublicationRepository(this)
        youTubeAuthManager = DefaultYouTubeAuthManager(this)
        youTubeUploader = DefaultYouTubeUploader()
        eyeCalibration = calibrationStore.loadCalibration()

        dualSenseHandler = DualSenseInputHandler(
            onAdjustSelectedParam = { dir ->
                val delta = selectedParam.getDelta(stepMode, dir > 0)
                eyeCalibration = eyeCalibration.adjust(selectedParam, delta)
                eyeCalibration.logSummary(TAG_XR_CAL)
            },
            onNextParam = { selectedParam = selectedParam.next() },
            onPreviousParam = { selectedParam = selectedParam.previous() },
            onResetCurrentParam = {
                eyeCalibration = eyeCalibration.resetParam(selectedParam)
                eyeCalibration.logSummary(TAG_XR_CAL)
            },
            onResetAllCalibration = {
                eyeCalibration = calibrationStore.resetToDefaults()
                eyeCalibration.logSummary(TAG_XR_CAL)
                Toast.makeText(this@MainActivity, "Calibration reset to defaults (L3+R3)", Toast.LENGTH_SHORT).show()
            },
            onToggleDisplaySource = {
                displaySource = if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) {
                    CalibrationDisplaySource.LIVE_PASSTHROUGH
                } else {
                    CalibrationDisplaySource.CALIBRATION_PATTERN
                }
            },
            onToggleStepMode = { stepMode = it },
            onSetEyeVisibility = { eyeVisibility = it },
            onToggleFlickerMode = {
                eyeVisibility = if (eyeVisibility == EyeVisibilityMode.ALTERNATE_FLICKER) {
                    EyeVisibilityMode.BOTH
                } else {
                    EyeVisibilityMode.ALTERNATE_FLICKER
                }
            },
            onSaveCalibration = {
                calibrationStore.saveCalibration(eyeCalibration)
                Toast.makeText(this@MainActivity, "Calibration profile saved (gearvr_s20fe_v1)", Toast.LENGTH_SHORT).show()
            },
            onExitCalibration = {
                appState = previousStateBeforeCalibration
            },
            onTouchpadClick = {
                if (appState == AppState.XR_CALIBRATION) {
                    if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) {
                        selectedParam = selectedParam.next()
                    } else {
                        isOverlayVisible = !isOverlayVisible
                    }
                } else {
                    toggleCapture(CaptureTriggerSource.EXTERNAL_CONTROLLER)
                }
            }
        )

        inputHandler = ExternalCaptureInputHandler(
            onToggleCaptureRequested = { source -> toggleCapture(source) }
        )

        // Log all connected input and USB devices on startup
        InputDiagnostics.logInputDevices()
        InputDiagnostics.logUsbDevices(this)

        loadOrGenerateKeys()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Fetch freshness info
        lifecycleScope.launch {
            val fresh = witnessClient.queryFreshness()
            if (fresh.type == "bitcoin_block") {
                bitcoinBlockHeight = fresh.block_height
            }
        }

        // Register broadcast receiver for developer OAuth token injection
        val tokenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val token = intent?.getStringExtra("token")
                if (!token.isNullOrBlank()) {
                    lifecycleScope.launch {
                        youTubeAuthManager.setCustomAccessToken(token)
                        Toast.makeText(this@MainActivity, "Set YouTube OAuth Token via ADB", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val intentFilter = android.content.IntentFilter("org.dattapool.capture.SET_TOKEN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tokenReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(tokenReceiver, intentFilter)
        }

        setContent {
            DattaPoolTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    when (appState) {
                        AppState.IDLE -> HomeScreen()
                        AppState.RECORDING -> RecordingScreen()
                        AppState.SEALING -> SealingScreen()
                        AppState.SEALED -> SealedScreen()
                        AppState.ERROR -> ErrorScreen()
                        AppState.XR_CALIBRATION -> CalibrationScreen()
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        InputDiagnostics.logKeyEvent(event)
        if (appState == AppState.XR_CALIBRATION) {
            if (dualSenseHandler.handleKeyEvent(event)) {
                return true
            }
        } else {
            if (inputHandler.handleKeyEvent(event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        InputDiagnostics.logMotionEvent("GenericMotionEvent", event)
        if (appState == AppState.XR_CALIBRATION) {
            if (dualSenseHandler.handleGenericMotionEvent(event)) {
                return true
            }
        } else {
            if (inputHandler.handleGenericMotionEvent(event)) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            InputDiagnostics.logMotionEvent("TouchEvent", event)
        }
        if (appState == AppState.XR_CALIBRATION) {
            if (dualSenseHandler.handleTouchEvent(event)) {
                return true
            }
        } else {
            if (inputHandler.handleTouchEvent(event)) {
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    fun enterCalibrationMode() {
        previousStateBeforeCalibration = appState
        appState = AppState.XR_CALIBRATION
        Log.i(TAG_XR_CAL, "Entered XR_CALIBRATION mode from $previousStateBeforeCalibration")
    }

    fun toggleCapture(source: CaptureTriggerSource = CaptureTriggerSource.GEAR_VR) {
        if (appState == AppState.XR_CALIBRATION) {
            Log.d(TAG_CAPTURE, "Capture trigger ignored in XR_CALIBRATION mode")
            return
        }
        Log.i(TAG_CAPTURE, "toggleCapture requested | source=${source.sourceName} | currentState=$appState")
        when (appState) {
            AppState.IDLE -> {
                triggerCapture(source)
            }
            AppState.RECORDING -> {
                Log.i(TAG_CAPTURE, "Toggling capture: stopping active session")
                stopAndSealCapture()
            }
            AppState.SEALED -> {
                Log.i(TAG_CAPTURE, "Toggling capture from SEALED: starting new capture")
                appState = AppState.IDLE
                activeSession = null
                captureResult = null
                triggerCapture(source)
            }
            AppState.SEALING, AppState.ERROR, AppState.XR_CALIBRATION -> {
                Log.w(TAG_CAPTURE, "Ignoring capture toggle while in state $appState")
            }
        }
    }

    private fun loadOrGenerateKeys() {
        val prefs = getSharedPreferences("dattapool_keys", Context.MODE_PRIVATE)
        var wPriv = prefs.getString("worker_priv", null)
        if (wPriv == null) {
            val kp = KeyPair.generate()
            prefs.edit().putString("worker_priv", kp.privateKeyHex).apply()
            workerKey = kp
        } else {
            workerKey = KeyPair.fromHex(wPriv)
        }

        var dPriv = prefs.getString("device_priv", null)
        if (dPriv == null) {
            val kp = KeyPair.generate()
            prefs.edit().putString("device_priv", kp.privateKeyHex).apply()
            deviceKey = kp
        } else {
            deviceKey = KeyPair.fromHex(dPriv)
        }
    }

    private fun initCamera() {
        lifecycleScope.launch {
            cameraRecorder.initialize()
        }
    }

    fun triggerCapture(source: CaptureTriggerSource = CaptureTriggerSource.SCREEN) {
        Log.i(TAG_CAPTURE, "capture requested | source=${source.sourceName} | currentState=$appState")
        if (appState != AppState.IDLE) {
            Log.w(TAG_CAPTURE, "Ignoring capture request; app is not in IDLE state ($appState)")
            return
        }

        val wKey = workerKey ?: run {
            Log.w(TAG_CAPTURE, "Worker key not ready")
            return
        }
        val dKey = deviceKey ?: run {
            Log.w(TAG_CAPTURE, "Device key not ready")
            return
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        lifecycleScope.launch {
            try {
                appState = AppState.RECORDING
                Log.i(TAG_CAPTURE, "state IDLE -> RECORDING | source=${source.sourceName}")
                confirmCaptureStarted()

                recordingSeconds = 0
                val session = captureEngine.startSession(
                    workerKey = wKey,
                    deviceKey = dKey,
                    skillTag = skillTag,
                    cameraRecorder = cameraRecorder
                )
                activeSession = session
                Log.i(TAG_CAPTURE, "session=${session.sessionId}")

                // Start timer
                while (appState == AppState.RECORDING) {
                    delay(1000)
                    recordingSeconds++
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to start capture"
                appState = AppState.ERROR
            }
        }
    }

    private fun confirmCaptureStarted() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (e: Exception) {
            Log.w(TAG_CAPTURE, "Failed to trigger haptic confirmation: ${e.message}")
        }
    }

    private fun stopAndSealCapture() {
        val session = activeSession ?: return
        appState = AppState.SEALING

        lifecycleScope.launch {
            try {
                val result = captureEngine.stopAndSeal(session, cameraRecorder)
                captureResult = result
                compensationReceipt = compensationRepository.getCompensation(result.manifest.session.id)
                mediaPublication = publicationRepository.getPublication(result.sessionDir)
                uploadProgress = UploadProgress()
                appState = AppState.SEALED
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to seal capture"
                appState = AppState.ERROR
            }
        }
    }

    private fun simulateReward() {
        val res = captureResult ?: return
        lifecycleScope.launch {
            try {
                val receipt = compensationRepository.simulateReward(res)
                compensationReceipt = receipt
                Toast.makeText(this@MainActivity, "25.000 ₿DATA reward assigned & receipt created!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG_CAPTURE, "Failed to simulate reward", e)
                Toast.makeText(this@MainActivity, "Failed to simulate reward: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportCompensationReceipt() {
        val res = captureResult ?: return
        val receiptFile = BundleExporter.getCompensationReceiptFile(res.sessionDir)
        if (receiptFile == null) {
            Toast.makeText(this, "No compensation receipt found to export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val uri: Uri = try {
                FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    receiptFile
                )
            } catch (e: Exception) {
                Uri.fromFile(receiptFile)
            }

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "application/json"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Export Compensation Receipt JSON"))
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun exportSession() {
        val res = captureResult ?: return
        lifecycleScope.launch {
            val zipFile = BundleExporter.createZipBundle(res.sessionDir)
            val uri: Uri = try {
                FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    zipFile
                )
            } catch (e: Exception) {
                Uri.fromFile(zipFile)
            }

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "application/zip"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Export DattaPool Session Bundle"))
        }
    }

    private fun startYouTubeUpload(
        visibility: YouTubeVisibility,
        includeMetadata: Boolean,
        customToken: String? = null
    ) {
        val res = captureResult ?: return
        val wKey = workerKey ?: run {
            Toast.makeText(this, "Worker key missing", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                uploadProgress = UploadProgress(status = YouTubeUploadStatus.AUTHORIZING)

                if (!customToken.isNullOrBlank()) {
                    youTubeAuthManager.setCustomAccessToken(customToken)
                }

                // 1. Authenticate with YouTube OAuth
                val authResult = youTubeAuthManager.authenticate(this@MainActivity)
                val token = when (authResult) {
                    is AuthResult.Success -> authResult.accessToken
                    is AuthResult.Cancelled -> {
                        uploadProgress = UploadProgress(status = YouTubeUploadStatus.NOT_PUBLISHED)
                        return@launch
                    }
                    is AuthResult.Failure -> {
                        uploadProgress = UploadProgress(
                            status = YouTubeUploadStatus.FAILED,
                            errorMessage = authResult.errorMessage
                        )
                        return@launch
                    }
                }

                // 2. Build Title, Description, and Tags
                val title = YouTubeMetadataBuilder.buildTitle(
                    skillTag = res.manifest.task.skillTag,
                    provenanceLevel = res.provenanceLevel,
                    sessionId = res.manifest.session.id
                )
                val tags = YouTubeMetadataBuilder.buildTags(
                    skillTag = res.manifest.task.skillTag,
                    provenanceLevel = res.provenanceLevel
                )
                val description = if (includeMetadata) {
                    YouTubeMetadataBuilder.buildDescription(
                        manifest = res.manifest,
                        manifestHash = res.manifestHash,
                        tapClaimAssetId = compensationReceipt?.asset?.assetId
                    )
                } else {
                    "DattaPool Robotics Capture Session ${res.manifest.session.id}"
                }

                uploadProgress = UploadProgress(
                    status = YouTubeUploadStatus.UPLOADING,
                    progressPercent = 0,
                    totalBytes = res.videoFile.length()
                )

                // 3. Upload Exact Sealed MP4
                val uploadResult = youTubeUploader.uploadVideo(
                    videoFile = res.videoFile,
                    title = title,
                    description = description,
                    tags = tags,
                    requestedVisibility = visibility,
                    accessToken = token,
                    onProgress = { percent, bytes, total ->
                        uploadProgress = uploadProgress.copy(
                            status = YouTubeUploadStatus.UPLOADING,
                            progressPercent = percent,
                            bytesUploaded = bytes,
                            totalBytes = total
                        )
                    }
                )

                when (uploadResult) {
                    is YouTubeUploadResult.Success -> {
                        uploadProgress = uploadProgress.copy(status = YouTubeUploadStatus.NOSTR_ANNOUNCING)

                        // 4. Create and Sign Nostr Dataset Availability Event
                        val availEvent = AvailabilityRecord.createAvailabilityEvent(
                            workerKey = wKey,
                            sessionId = res.manifest.session.id,
                            manifestHash = res.manifestHash,
                            videoId = uploadResult.videoId,
                            visibility = uploadResult.actualVisibility,
                            captureClaimAssetId = compensationReceipt?.asset?.assetId
                        )

                        // 5. Broadcast Nostr Announcement to Witness
                        witnessClient.publishEvent(availEvent)

                        // 6. Build and Persist Media Publication Record
                        val publication = MediaPublication(
                            sessionId = res.manifest.session.id,
                            manifestHash = res.manifestHash,
                            captureClaimAssetId = compensationReceipt?.asset?.assetId,
                            platform = "youtube",
                            videoId = uploadResult.videoId,
                            requestedVisibility = uploadResult.requestedVisibility,
                            actualVisibility = uploadResult.actualVisibility,
                            watchUrl = uploadResult.watchUrl,
                            sourceVideoSha256 = res.manifest.capture.videoSha256,
                            publishedByNostrPubkey = wKey.publicKeyHex,
                            publishedAt = uploadResult.uploadedAt,
                            nostrAvailabilityEventId = availEvent.id
                        )

                        publicationRepository.savePublication(res.sessionDir, publication, availEvent)
                        mediaPublication = publication
                        uploadProgress = UploadProgress(status = YouTubeUploadStatus.AVAILABLE)

                        Toast.makeText(
                            this@MainActivity,
                            "Published to YouTube (${uploadResult.videoId}) & Nostr announced!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is YouTubeUploadResult.Failure -> {
                        uploadProgress = UploadProgress(
                            status = YouTubeUploadStatus.FAILED,
                            errorMessage = uploadResult.errorMessage
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "YouTube upload failed: ${uploadResult.errorMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG_CAPTURE, "YouTube publication error", e)
                uploadProgress = UploadProgress(
                    status = YouTubeUploadStatus.FAILED,
                    errorMessage = e.message ?: "Publication failed"
                )
            }
        }
    }

    private fun openYouTubeVideo(watchUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open video URL: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- UI Composables ---

    @Composable
    fun HomeScreen() {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Branding + Identity Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "DATTA ▸ POOL",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Robotics Data Attribution Client v0.4.0",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Identity Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Worker Nostr ID", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                        }
                        Text(
                            text = workerKey?.publicKeyHex?.take(16) + "...",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Device Hardware ID", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                        }
                        Text(
                            text = deviceKey?.publicKeyHex?.take(16) + "...",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Freshness Anchor", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            if (bitcoinBlockHeight != null) {
                                Text("Bitcoin Block #$bitcoinBlockHeight ✓", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text("Local / Standalone", color = Color(0xFF64748B), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Right Column: Skill Tag & Start Trigger & Calibration Mode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                OutlinedTextField(
                    value = skillTag,
                    onValueChange = { skillTag = it },
                    label = { Text("Task / Skill Tag", color = Color(0xFF94A3B8)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { triggerCapture(CaptureTriggerSource.SCREEN) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("START CAPTURE (TAP TRACKPAD)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { enterCalibrationMode() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "XR Calibration", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("XR CALIBRATION MODE (PS5 / GEAR VR)", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }

    @Composable
    fun CalibrationScreen() {
        XRCalibrationScreen(
            cameraRecorder = cameraRecorder,
            calibration = eyeCalibration,
            selectedParam = selectedParam,
            displaySource = displaySource,
            stepMode = stepMode,
            eyeVisibility = eyeVisibility,
            isOverlayVisible = isOverlayVisible,
            onAdjustParam = { dir ->
                val delta = selectedParam.getDelta(stepMode, dir > 0)
                eyeCalibration = eyeCalibration.adjust(selectedParam, delta)
                eyeCalibration.logSummary(TAG_XR_CAL)
            },
            onSelectParam = { selectedParam = it },
            onToggleSource = {
                displaySource = if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) {
                    CalibrationDisplaySource.LIVE_PASSTHROUGH
                } else {
                    CalibrationDisplaySource.CALIBRATION_PATTERN
                }
            },
            onToggleStepMode = {
                stepMode = if (stepMode == AdjustmentStepMode.FINE) AdjustmentStepMode.COARSE else AdjustmentStepMode.FINE
            },
            onSetEyeVisibility = { eyeVisibility = it },
            onToggleFlicker = {
                eyeVisibility = if (eyeVisibility == EyeVisibilityMode.ALTERNATE_FLICKER) {
                    EyeVisibilityMode.BOTH
                } else {
                    EyeVisibilityMode.ALTERNATE_FLICKER
                }
            },
            onResetParam = {
                eyeCalibration = eyeCalibration.resetParam(selectedParam)
                eyeCalibration.logSummary(TAG_XR_CAL)
            },
            onResetAll = {
                eyeCalibration = calibrationStore.resetToDefaults()
            },
            onSaveCalibration = {
                calibrationStore.saveCalibration(eyeCalibration)
                Toast.makeText(this@MainActivity, "Calibration profile saved (gearvr_s20fe_v1)", Toast.LENGTH_SHORT).show()
            },
            onExitCalibration = {
                appState = previousStateBeforeCalibration
            }
        )
    }

    @Composable
    fun RecordingScreen() {
        XRRecordingScreen(
            cameraRecorder = cameraRecorder,
            sensorCollector = captureEngine.getSensorCollector(),
            activeSession = activeSession,
            recordingSeconds = recordingSeconds,
            calibration = eyeCalibration,
            onEnterCalibration = { enterCalibrationMode() },
            onStopCapture = { stopAndSealCapture() }
        )
    }

    @Composable
    fun SealingScreen() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text("Sealing Capture Session...", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Computing 64KB Merkle Roots & Hash Chain", color = Color(0xFF94A3B8), fontSize = 13.sp)
            }
        }
    }

    @Composable
    fun SealedScreen() {
        val res = captureResult ?: return

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                Icon(Icons.Default.CheckCircle, contentDescription = "Sealed", tint = Color(0xFF10B981), modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CAPTURE SEALED",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Expected Provenance: ${res.provenanceLevel}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (res.provenanceLevel == "P4") Color(0xFF38BDF8) else Color(0xFFF59E0B)
                )
            }

            // Capture Metadata Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Session ID", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Text(res.manifest.session.id, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Manifest Hash", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Text(res.manifestHash, color = Color(0xFF38BDF8), fontFamily = FontFamily.Monospace, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Capture Chain Root", color = Color(0xFF94A3B8), fontSize = 11.sp)
                    Text(res.manifest.capture.captureChainRoot, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Chunks: ${res.manifest.capture.chunkCount}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Text("Video: 1080p MP4 ✓", color = Color(0xFF10B981), fontSize = 12.sp)
                    }
                }
            }

            // Remuneration / Compensation Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = compensationReceipt != null) {
                        showReceiptDetails = true
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (compensationReceipt != null) Color(0xFF0F2942) else Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DATA CONTRIBUTION REMUNERATION",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.sp
                        )
                        if (compensationReceipt != null) {
                            Surface(
                                color = Color(0xFF064E3B),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "✓ Verified",
                                    color = Color(0xFF34D399),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else {
                            Surface(
                                color = Color(0xFF451A03),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Verification Pending",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val receipt = compensationReceipt
                    if (receipt != null) {
                        val receiptHash = receipt.computeSha256()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Reward", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(
                                    text = "${receipt.amountDisplay} ${receipt.asset.displaySymbol}",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF59E0B)
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text("Asset", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(
                                    text = receipt.asset.ticker,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Transfer: ${receipt.transfer.status.replaceFirstChar { it.uppercase() }} (${receipt.transfer.method})", color = Color(0xFF38BDF8), fontSize = 12.sp)
                            Text(
                                "Receipt: ${receiptHash.take(8)}...${receiptHash.takeLast(6)}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showReceiptDetails = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("VIEW DETAILS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { exportCompensationReceipt() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("EXPORT JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Text(
                            text = "Reward",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Verification pending",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E8F0)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Remuneration receipt will be generated upon verification.",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { simulateReward() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.CurrencyBitcoin, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SIMULATE DATAPOOL REWARD (25.000 ₿DATA)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // YouTube Publication Card (if already published)
            val pub = mediaPublication
            if (pub != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showYouTubeDialog = true },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1917)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "YOUTUBE PUBLICATION",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEF4444),
                                    letterSpacing = 1.sp
                                )
                            }
                            Surface(
                                color = Color(0xFF064E3B),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "✓ Published",
                                    color = Color(0xFF34D399),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Video ID", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(
                                    text = pub.videoId,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text("Actual Visibility", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                Text(
                                    text = pub.actualVisibility.replaceFirstChar { it.uppercase() },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (pub.actualVisibility.equals("private", ignoreCase = true) && !pub.requestedVisibility.equals("private", ignoreCase = true)) {
                                        Color(0xFFF59E0B)
                                    } else {
                                        Color(0xFF38BDF8)
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { openYouTubeVideo(pub.watchUrl) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("OPEN VIDEO", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { showYouTubeDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("DETAILS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Bottom Actions
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Upload to YouTube Button
                Button(
                    onClick = { showYouTubeDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = "YouTube", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (mediaPublication != null) "YOUTUBE PUBLICATION STATUS" else "UPLOAD TO YOUTUBE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { exportSession() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderZip, contentDescription = "Export", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("EXPORT COMPLETE CAPTURE BUNDLE", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        appState = AppState.IDLE
                        activeSession = null
                        captureResult = null
                        compensationReceipt = null
                        mediaPublication = null
                        showReceiptDetails = false
                        showYouTubeDialog = false
                        uploadProgress = UploadProgress()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("NEW CAPTURE", fontSize = 13.sp)
                }
            }
        }

        // YouTube Publication Dialog
        if (showYouTubeDialog) {
            YouTubePublicationDialog(
                publication = mediaPublication,
                uploadProgress = uploadProgress,
                onDismiss = { showYouTubeDialog = false },
                onStartUpload = { visibility, includeMetadata, customToken ->
                    startYouTubeUpload(visibility, includeMetadata, customToken)
                },
                onOpenVideo = { watchUrl ->
                    openYouTubeVideo(watchUrl)
                }
            )
        }

        // Receipt Detail Modal Dialog
        if (showReceiptDetails && compensationReceipt != null) {
            ReceiptDetailDialog(
                receipt = compensationReceipt!!,
                captureResult = res,
                onDismiss = { showReceiptDetails = false }
            )
        }
    }

    @Composable
    fun ReceiptDetailDialog(
        receipt: CompensationReceipt,
        captureResult: CaptureResult,
        onDismiss: () -> Unit
    ) {
        val verification = remember(receipt, captureResult) {
            CompensationVerifier.verifyCompensationReceipt(
                receipt = receipt,
                expectedCaptureId = captureResult.manifest.session.id,
                expectedVideoSha256 = captureResult.manifest.capture.videoSha256,
                expectedManifestSha256 = captureResult.manifestHash
            )
        }
        val receiptSha256 = remember(receipt) { receipt.computeSha256() }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.90f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Title Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Remuneration Receipt",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = receipt.type,
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Verification Banner
                    if (verification.isValid) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF064E3B),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Cryptographically Verified & Associated",
                                    color = Color(0xFF34D399),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF7F1D1D),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Verification Mismatch",
                                        color = Color(0xFFF87171),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = verification.errorSummary(),
                                    color = Color(0xFFFECACA),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable Fields
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReceiptFieldItem("Capture ID", receipt.captureId) { copyToClipboard("Capture ID", receipt.captureId) }
                        ReceiptFieldItem("Video SHA-256", receipt.videoSha256) { copyToClipboard("Video SHA-256", receipt.videoSha256) }
                        ReceiptFieldItem("Manifest SHA-256", receipt.manifestSha256) { copyToClipboard("Manifest SHA-256", receipt.manifestSha256) }
                        ReceiptFieldItem("Worker PubKey", receipt.workerPubkey) { copyToClipboard("Worker PubKey", receipt.workerPubkey) }

                        Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 4.dp))

                        ReceiptFieldItem("Asset ID", receipt.asset.assetId) { copyToClipboard("Asset ID", receipt.asset.assetId) }
                        ReceiptFieldItem("Asset Ticker (Machine)", receipt.asset.ticker)
                        ReceiptFieldItem("Asset Display Symbol", receipt.asset.displaySymbol)
                        ReceiptFieldItem("Amount (Atomic Units)", "${receipt.amountAtomic}")
                        ReceiptFieldItem("Amount (Display)", "${receipt.amountDisplay} ${receipt.asset.displaySymbol}")
                        ReceiptFieldItem("Reason", receipt.reason)

                        Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 4.dp))

                        ReceiptFieldItem("Transfer Method", receipt.transfer.method)
                        ReceiptFieldItem("Transfer Status", receipt.transfer.status)
                        val txOrHash = receipt.transfer.txid ?: receipt.transfer.paymentHash ?: "N/A"
                        ReceiptFieldItem("Transaction ID / Hash", txOrHash) { copyToClipboard("TxID / Hash", txOrHash) }
                        ReceiptFieldItem("Timestamp (UTC)", receipt.transfer.timestamp)

                        Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 4.dp))

                        ReceiptFieldItem("Receipt SHA-256", receiptSha256) { copyToClipboard("Receipt SHA-256", receiptSha256) }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dialog Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard("Canonical Receipt JSON", receipt.toCanonicalJson())
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("COPY JSON", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                exportCompensationReceipt()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("EXPORT JSON", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReceiptFieldItem(
        label: String,
        value: String,
        onCopy: (() -> Unit)? = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color(0xFF94A3B8), fontSize = 11.sp)
                if (onCopy != null) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onCopy() }
                    )
                }
            }
            Text(
                text = value,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }

    @Composable
    fun ErrorScreen() {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Error, contentDescription = "Error", tint = Color(0xFFEF4444), modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Capture Error", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(errorMessage, color = Color(0xFF94A3B8), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { appState = AppState.IDLE }) {
                    Text("Back to Home")
                }
            }
        }
    }
}

@Composable
fun DattaPoolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF0284C7),
            background = Color(0xFF0F172A),
            surface = Color(0xFF1E293B)
        ),
        content = content
    )
}
