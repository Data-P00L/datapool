package org.dattapool.capture.xr

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.dattapool.capture.camera.CameraRecorder
import org.dattapool.capture.protocol.ActiveSession
import org.dattapool.capture.sensor.SensorCollector
import java.util.Locale

@Composable
fun XRRecordingScreen(
    cameraRecorder: CameraRecorder,
    sensorCollector: SensorCollector,
    activeSession: ActiveSession?,
    recordingSeconds: Int,
    calibration: XrEyeCalibration = XrEyeCalibration(),
    onEnterCalibration: (() -> Unit)? = null,
    onStopCapture: () -> Unit
) {
    var displayMode by remember { mutableStateOf(XRDisplayMode.SBS_FULL) }
    var showDiagnostics by remember { mutableStateOf(true) }

    var glViewRef by remember { mutableStateOf<XRSplitScreenGLView?>(null) }
    var diagnostics by remember { mutableStateOf(XRDiagnostics()) }

    var latestAccel by remember { mutableStateOf(floatArrayOf(0f, 0f, 9.81f)) }
    var latestGyro by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }

    // Periodically update sensor readings & diagnostics (30fps)
    LaunchedEffect(Unit) {
        while (true) {
            latestAccel = sensorCollector.getLatestAccel()
            latestGyro = sensorCollector.getLatestGyro()
            glViewRef?.let {
                diagnostics = it.getDiagnostics()
            }
            delay(33)
        }
    }

    val mins = recordingSeconds / 60
    val secs = recordingSeconds % 60
    val timeStr = "%02d:%02d".format(Locale.US, mins, secs)

    // Pulsing recording dot animation
    val infiniteTransition = rememberInfiniteTransition(label = "rec_pulse")
    val recAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec_alpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // CameraX OpenGL ES 2.0 View (Single canonical texture rendered across 1 or 2 viewports)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                XRSplitScreenGLView(ctx).apply {
                    this.displayMode = displayMode
                    this.eyeCalibration = calibration
                    cameraRecorder.attachPreview(this)
                    glViewRef = this
                }
            },
            update = { glView ->
                glView.displayMode = displayMode
                glView.eyeCalibration = calibration
                glViewRef = glView
            }
        )

        // Viewport Overlays based on Mode
        when (displayMode) {
            XRDisplayMode.MONO_DEBUG -> {
                // Stage A: Single Fullscreen Debug Viewport
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showDiagnostics) {
                        // Diagnostic directional markers & center crosshair
                        XRDiagnosticMarkers(
                            modifier = Modifier.fillMaxSize(),
                            isMono = true
                        )
                        // Telemetry & diagnostics card
                        XRDiagnosticsCard(
                            diagnostics = diagnostics,
                            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                        )
                    }

                    // Eye HUD for Mono
                    XREyeHUD(
                        eyeLabel = "MONO DEBUG",
                        timeStr = timeStr,
                        recAlpha = recAlpha,
                        activeSession = activeSession,
                        latestAccel = latestAccel,
                        latestGyro = latestGyro,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            XRDisplayMode.SBS_RAW -> {
                // Stage B: Dual Viewports, Camera Only with diagnostic crosshairs
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (showDiagnostics) {
                            XRDiagnosticMarkers(modifier = Modifier.fillMaxSize(), isMono = false)
                            Text(
                                "LEFT EYE (RAW)",
                                color = Color(0x8838BDF8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                            )
                        }
                    }
                    // Subtle Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0x2238BDF8))
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (showDiagnostics) {
                            XRDiagnosticMarkers(modifier = Modifier.fillMaxSize(), isMono = false)
                            Text(
                                "RIGHT EYE (RAW)",
                                color = Color(0x8838BDF8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                            )
                        }
                    }
                }
            }

            XRDisplayMode.SBS_FULL -> {
                // Stage D/E: Dual Viewports with Lens-Safe HUD
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Eye Viewport
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        XREyeHUD(
                            eyeLabel = "L",
                            timeStr = timeStr,
                            recAlpha = recAlpha,
                            activeSession = activeSession,
                            latestAccel = latestAccel,
                            latestGyro = latestGyro,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (showDiagnostics) {
                            XRDiagnosticMarkers(modifier = Modifier.fillMaxSize(), isMono = false)
                        }
                    }

                    // Center Divider (Subtle)
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0x2238BDF8))
                    )

                    // Right Eye Viewport
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        XREyeHUD(
                            eyeLabel = "R",
                            timeStr = timeStr,
                            recAlpha = recAlpha,
                            activeSession = activeSession,
                            latestAccel = latestAccel,
                            latestGyro = latestGyro,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (showDiagnostics) {
                            XRDiagnosticMarkers(modifier = Modifier.fillMaxSize(), isMono = false)
                        }
                    }
                }
            }
        }

        // Global Diagnostics Card in SBS mode when diagnostics enabled
        if (showDiagnostics && displayMode != XRDisplayMode.MONO_DEBUG) {
            XRDiagnosticsCard(
                diagnostics = diagnostics,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            )
        }

        // Bottom Controls Bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode Toggle Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode Selector
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            displayMode = when (displayMode) {
                                XRDisplayMode.MONO_DEBUG -> XRDisplayMode.SBS_RAW
                                XRDisplayMode.SBS_RAW -> XRDisplayMode.SBS_FULL
                                XRDisplayMode.SBS_FULL -> XRDisplayMode.MONO_DEBUG
                            }
                        },
                    color = Color(0xCC0F172A),
                    shape = RoundedCornerShape(20.dp),
                    border = ButtonDefaults.outlinedButtonBorder
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            when (displayMode) {
                                XRDisplayMode.MONO_DEBUG -> Icons.Default.Visibility
                                XRDisplayMode.SBS_RAW -> Icons.Default.ViewAgenda
                                XRDisplayMode.SBS_FULL -> Icons.Default.ViewCarousel
                            },
                            contentDescription = "Mode",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (displayMode) {
                                XRDisplayMode.MONO_DEBUG -> "MODE: MONO DEBUG"
                                XRDisplayMode.SBS_RAW -> "MODE: SBS RAW"
                                XRDisplayMode.SBS_FULL -> "MODE: SBS FULL XR"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Diagnostics Toggle
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { showDiagnostics = !showDiagnostics },
                    color = if (showDiagnostics) Color(0xCC0284C7) else Color(0xCC1E293B),
                    shape = RoundedCornerShape(20.dp),
                    border = ButtonDefaults.outlinedButtonBorder
                ) {
                    Text(
                        text = if (showDiagnostics) "DIAG: ON" else "DIAG: OFF",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                // Calibration Mode Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onEnterCalibration?.invoke() },
                    color = Color(0xCC1E293B),
                    shape = RoundedCornerShape(20.dp),
                    border = ButtonDefaults.outlinedButtonBorder
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Calibrate", tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "CALIB",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Stop & Seal Action Button
            Button(
                onClick = onStopCapture,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xEEDC2626)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop Capture", modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("STOP & SEAL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/**
 * Optics-Safe Eye HUD:
 * Keeps all UI elements within the comfortable optical field of view of the Gear VR lens.
 */
@Composable
fun XREyeHUD(
    eyeLabel: String,
    timeStr: String,
    recAlpha: Float,
    activeSession: ActiveSession?,
    latestAccel: FloatArray,
    latestGyro: FloatArray,
    modifier: Modifier = Modifier
) {
    // Optics Safe Zone: 24dp padding from viewport edges to avoid lens perimeter distortion
    Box(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // Top HUD Header (Safe Zone)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: REC Indicator + Timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xAA0F172A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .alpha(recAlpha)
                        .background(Color(0xFFEF4444), CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "REC",
                    color = Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timeStr,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // Eye Identifier Badge
            Text(
                text = eyeLabel,
                color = Color(0xAA38BDF8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Right: Provenance Badge
            val level = activeSession?.targetLevel ?: "P3"
            val isWitnessed = activeSession?.isWitnessed == true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xAA0F172A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isWitnessed) "$level [WITNESSED]" else "$level [STANDALONE]",
                    color = if (level == "P4") Color(0xFF38BDF8) else Color(0xFFF59E0B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Center Eye-Relative Reticle & Artificial Horizon Target
        XRReticleCrosshair(
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.Center),
            pitchAngle = latestAccel[1] * 2.5f,
            rollAngle = -latestAccel[0] * 2.5f
        )

        // Bottom Telemetry Overlay (Safe Zone)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 44.dp)
                .background(Color(0xAA0F172A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "TASK: ${activeSession?.skillTag ?: "warehouse_object_pick"}",
                color = Color(0xFF38BDF8),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "ACC: [%.1f, %.1f, %.1f]".format(Locale.US, latestAccel[0], latestAccel[1], latestAccel[2]),
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "GYR: [%.1f, %.1f, %.1f]".format(Locale.US, latestGyro[0], latestGyro[1], latestGyro[2]),
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Diagnostic Directional Markers:
 * Draws TOP, BOTTOM, LEFT, RIGHT and a central crosshair to immediately reveal any orientation mistakes.
 */
@Composable
fun XRDiagnosticMarkers(
    modifier: Modifier = Modifier,
    isMono: Boolean = false
) {
    Box(modifier = modifier) {
        Text(
            "TOP",
            color = Color(0x6638BDF8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (isMono) 40.dp else 4.dp)
        )
        Text(
            "BOTTOM",
            color = Color(0x6638BDF8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)
        )
        Text(
            "LEFT",
            color = Color(0x6638BDF8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)
        )
        Text(
            "RIGHT",
            color = Color(0x6638BDF8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
        )

        // Center Crosshair
        Canvas(modifier = Modifier.size(30.dp).align(Alignment.Center)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val crossColor = Color(0x8838BDF8)
            drawLine(crossColor, Offset(cx - 12.dp.toPx(), cy), Offset(cx + 12.dp.toPx(), cy), strokeWidth = 1.dp.toPx())
            drawLine(crossColor, Offset(cx, cy - 12.dp.toPx()), Offset(cx, cy + 12.dp.toPx()), strokeWidth = 1.dp.toPx())
        }
    }
}

/**
 * Live Diagnostics Card:
 * Displays authoritative device orientation, camera resolution, sensor angles, and viewport dimensions.
 */
@Composable
fun XRDiagnosticsCard(
    diagnostics: XRDiagnostics,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xDD0F172A),
        shape = RoundedCornerShape(8.dp),
        border = ButtonDefaults.outlinedButtonBorder
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = "XR ORIENTATION DIAGNOSTICS",
                color = Color(0xFF38BDF8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text("DISPLAY: ${diagnostics.displayWidth}×${diagnostics.displayHeight}", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("ROT: ROTATION_${diagnostics.displayRotationDegrees}°", color = Color(0xFF38BDF8), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("CAM SENSOR: ${diagnostics.sensorOrientation}°", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Column {
                    Text("CAM TARGET: ROTATION_90°", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("FRAME ROT: ${diagnostics.cameraRotationDegrees}°", color = Color(0xFF10B981), fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("EYE: ${diagnostics.eyeWidth}×${diagnostics.eyeHeight}", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Column {
                    Text("CAM BUFFER: ${diagnostics.bufferWidth}×${diagnostics.bufferHeight}", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("MODE: ${diagnostics.mode.name}", color = Color(0xFFF59E0B), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}



/**
 * Eye-Relative Reticle Crosshair with Artificial Horizon
 */
@Composable
fun XRReticleCrosshair(
    modifier: Modifier = Modifier,
    pitchAngle: Float = 0f,
    rollAngle: Float = 0f
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val reticleColor = Color(0x8838BDF8)
        val centerDotColor = Color(0xFF38BDF8)

        // Center Point
        drawCircle(
            color = centerDotColor,
            radius = 2.5.dp.toPx(),
            center = Offset(cx, cy)
        )

        // Inner Circle
        drawCircle(
            color = reticleColor,
            radius = 16.dp.toPx(),
            center = Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )

        // Outer Reticle Brackets / Crosshairs
        val lineLen = 8.dp.toPx()
        val gap = 20.dp.toPx()

        // Top tick
        drawLine(
            color = reticleColor,
            start = Offset(cx, cy - gap),
            end = Offset(cx, cy - gap - lineLen),
            strokeWidth = 1.5.dp.toPx()
        )
        // Bottom tick
        drawLine(
            color = reticleColor,
            start = Offset(cx, cy + gap),
            end = Offset(cx, cy + gap + lineLen),
            strokeWidth = 1.5.dp.toPx()
        )
        // Left tick
        drawLine(
            color = reticleColor,
            start = Offset(cx - gap, cy),
            end = Offset(cx - gap - lineLen, cy),
            strokeWidth = 1.5.dp.toPx()
        )
        // Right tick
        drawLine(
            color = reticleColor,
            start = Offset(cx + gap, cy),
            end = Offset(cx + gap + lineLen, cy),
            strokeWidth = 1.5.dp.toPx()
        )

        // Horizon Guide Lines with dynamic IMU roll & pitch tilt
        val horizonWidth = 30.dp.toPx()
        val pitchOffset = (pitchAngle.coerceIn(-25f, 25f) / 25f) * 14.dp.toPx()

        rotate(
            degrees = rollAngle.coerceIn(-45f, 45f),
            pivot = Offset(cx, cy)
        ) {
            drawLine(
                color = Color(0x6638BDF8),
                start = Offset(cx - horizonWidth - 10.dp.toPx(), cy + pitchOffset),
                end = Offset(cx - 10.dp.toPx(), cy + pitchOffset),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = Color(0x6638BDF8),
                start = Offset(cx + 10.dp.toPx(), cy + pitchOffset),
                end = Offset(cx + horizonWidth + 10.dp.toPx(), cy + pitchOffset),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

