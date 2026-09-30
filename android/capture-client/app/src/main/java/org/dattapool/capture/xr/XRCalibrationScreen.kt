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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.dattapool.capture.camera.CameraRecorder
import java.util.Locale

@Composable
fun XRCalibrationScreen(
    cameraRecorder: CameraRecorder,
    calibration: XrEyeCalibration,
    selectedParam: CalibrationParam,
    displaySource: CalibrationDisplaySource,
    stepMode: AdjustmentStepMode,
    eyeVisibility: EyeVisibilityMode,
    isOverlayVisible: Boolean,
    onAdjustParam: (delta: Float) -> Unit,
    onSelectParam: (CalibrationParam) -> Unit,
    onToggleSource: () -> Unit,
    onToggleStepMode: () -> Unit,
    onSetEyeVisibility: (EyeVisibilityMode) -> Unit,
    onToggleFlicker: () -> Unit,
    onResetParam: () -> Unit,
    onResetAll: () -> Unit,
    onSaveCalibration: () -> Unit,
    onExitCalibration: () -> Unit
) {
    // 1 Hz Flicker phase timing for Compose overlay synchronization
    val infiniteTransition = rememberInfiniteTransition(label = "cal_flicker")
    val flickerPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flicker_phase"
    )

    val isLeftVisible = when (eyeVisibility) {
        EyeVisibilityMode.BOTH -> true
        EyeVisibilityMode.LEFT_ONLY -> true
        EyeVisibilityMode.RIGHT_ONLY -> false
        EyeVisibilityMode.ALTERNATE_FLICKER -> flickerPhase < 0.5f
    }

    val isRightVisible = when (eyeVisibility) {
        EyeVisibilityMode.BOTH -> true
        EyeVisibilityMode.LEFT_ONLY -> false
        EyeVisibilityMode.RIGHT_ONLY -> true
        EyeVisibilityMode.ALTERNATE_FLICKER -> flickerPhase >= 0.5f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Live Passthrough OpenGL Background (when in LIVE_PASSTHROUGH)
        if (displaySource == CalibrationDisplaySource.LIVE_PASSTHROUGH) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    XRSplitScreenGLView(ctx).apply {
                        this.displayMode = XRDisplayMode.SBS_FULL
                        this.eyeCalibration = calibration
                        this.eyeVisibilityMode = eyeVisibility
                        cameraRecorder.attachPreview(this)
                    }
                },
                update = { glView ->
                    glView.displayMode = XRDisplayMode.SBS_FULL
                    glView.eyeCalibration = calibration
                    glView.eyeVisibilityMode = eyeVisibility
                }
            )
        }

        // 2. Dual Eye Viewports (Left & Right)
        Row(modifier = Modifier.fillMaxSize()) {
            // LEFT EYE VIEWPORT
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (isLeftVisible) {
                    if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) {
                        SyntheticCalibrationPattern(
                            eyeLabel = "LEFT EYE",
                            offsetX = calibration.effectiveLeftX,
                            offsetY = calibration.effectiveLeftY,
                            scale = calibration.effectiveLeftScale,
                            rotationDeg = calibration.effectiveLeftRotation,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // In Live Passthrough mode, overlay center crosshairs and optics boundary
                        LivePassthroughOverlay(
                            eyeLabel = "LEFT EYE (PASSTHROUGH)",
                            offsetX = calibration.effectiveLeftX,
                            offsetY = calibration.effectiveLeftY,
                            scale = calibration.effectiveLeftScale,
                            rotationDeg = calibration.effectiveLeftRotation,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Lens Safe-Zone Calibration HUD Overlay (Duplicated per eye)
                    if (isOverlayVisible) {
                        EyeSafeCalibrationCard(
                            eyeName = "LEFT EYE",
                            calibration = calibration,
                            selectedParam = selectedParam,
                            displaySource = displaySource,
                            stepMode = stepMode,
                            eyeVisibility = eyeVisibility,
                            onSelectParam = onSelectParam,
                            onAdjustParam = onAdjustParam,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    // Eye Blackout during Left/Right diagnostic isolating
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }

            // Center Viewport Divider (Lens Separation Line)
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color(0x3338BDF8))
            )

            // RIGHT EYE VIEWPORT
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (isRightVisible) {
                    if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) {
                        SyntheticCalibrationPattern(
                            eyeLabel = "RIGHT EYE",
                            offsetX = calibration.effectiveRightX,
                            offsetY = calibration.effectiveRightY,
                            scale = calibration.effectiveRightScale,
                            rotationDeg = calibration.effectiveRightRotation,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LivePassthroughOverlay(
                            eyeLabel = "RIGHT EYE (PASSTHROUGH)",
                            offsetX = calibration.effectiveRightX,
                            offsetY = calibration.effectiveRightY,
                            scale = calibration.effectiveRightScale,
                            rotationDeg = calibration.effectiveRightRotation,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (isOverlayVisible) {
                        EyeSafeCalibrationCard(
                            eyeName = "RIGHT EYE",
                            calibration = calibration,
                            selectedParam = selectedParam,
                            displaySource = displaySource,
                            stepMode = stepMode,
                            eyeVisibility = eyeVisibility,
                            onSelectParam = onSelectParam,
                            onAdjustParam = onAdjustParam,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }

        // 3. Bottom Calibration Action Bar (Screen & Controller Fallback Controls)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xEE090E17))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Source & Step Controls
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onToggleSource() },
                    color = if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) Color(0xFF0284C7) else Color(0xFF1E293B),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (displaySource == CalibrationDisplaySource.CALIBRATION_PATTERN) "▲ PATTERN" else "▲ CAMERA",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onToggleStepMode() },
                    color = if (stepMode == AdjustmentStepMode.FINE) Color(0xFF10B981) else Color(0xFFF59E0B),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (stepMode == AdjustmentStepMode.FINE) "L1/R1: FINE (1px)" else "L1/R1: COARSE (10px)",
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            val nextEye = when (eyeVisibility) {
                                EyeVisibilityMode.BOTH -> EyeVisibilityMode.LEFT_ONLY
                                EyeVisibilityMode.LEFT_ONLY -> EyeVisibilityMode.RIGHT_ONLY
                                EyeVisibilityMode.RIGHT_ONLY -> EyeVisibilityMode.ALTERNATE_FLICKER
                                EyeVisibilityMode.ALTERNATE_FLICKER -> EyeVisibilityMode.BOTH
                            }
                            onSetEyeVisibility(nextEye)
                        },
                    color = Color(0xFF334155),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "EYE: ${eyeVisibility.name}",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Quick Controller Legend
            Text(
                text = "DPAD: Adjust/Nav | TOUCHPAD: Confirm | L3+R3: Reset All | OPTIONS: Save | CIR: Exit",
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )

            // Save & Exit Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onResetParam,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("RESET", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                }

                Button(
                    onClick = onSaveCalibration,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SAVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                }

                Button(
                    onClick = onExitCalibration,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("EXIT", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White)
                }
            }
        }
    }
}

/**
 * Synthetic Calibration Target Pattern:
 * Rendered with exact identical geometry in both viewports before eye transforms.
 */
@Composable
fun SyntheticCalibrationPattern(
    eyeLabel: String,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    rotationDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // Apply per-eye optical calibration transform around the viewport center
        translate(left = offsetX, top = offsetY) {
            scale(scaleX = scale, scaleY = scale, pivot = Offset(cx, cy)) {
                rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
                    // Background grid color definitions
                    val gridColor10 = Color(0x1A38BDF8)
                    val gridColor20 = Color(0x3338BDF8)
                    val gridColor30 = Color(0x4D38BDF8)
                    val axisColor = Color(0x9938BDF8)
                    val centerColor = Color(0xFF38BDF8)
                    val rectColor = Color(0x6638BDF8)

                    // 1. 10% Coordinate Grid Lines
                    for (i in 1..9) {
                        val gx = w * (i * 0.1f)
                        val gy = h * (i * 0.1f)
                        val color = if (i % 2 == 0) gridColor20 else gridColor10
                        val stroke = if (i % 2 == 0) 1.dp.toPx() else 0.5.dp.toPx()

                        // Vertical Grid
                        drawLine(color, Offset(gx, 0f), Offset(gx, h), strokeWidth = stroke)
                        // Horizontal Grid
                        drawLine(color, Offset(0f, gy), Offset(w, gy), strokeWidth = stroke)
                    }

                    // 2. Concentric Alignment Rectangles (10%, 20%, 30%, 40%, 50%, 60%, 70%, 80%)
                    for (pct in listOf(0.15f, 0.30f, 0.45f, 0.60f, 0.75f)) {
                        val rw = w * pct
                        val rh = h * pct
                        drawRect(
                            color = rectColor,
                            topLeft = Offset(cx - rw / 2f, cy - rh / 2f),
                            size = Size(rw, rh),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // 3. Primary Center Horizontal & Vertical Lines
                    drawLine(axisColor, Offset(0f, cy), Offset(w, cy), strokeWidth = 1.5.dp.toPx())
                    drawLine(axisColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5.dp.toPx())

                    // 4. Diagonal Alignment Rays (Corner-to-Corner guide rays)
                    drawLine(Color(0x2238BDF8), Offset(0f, 0f), Offset(w, h), strokeWidth = 1.dp.toPx())
                    drawLine(Color(0x2238BDF8), Offset(w, 0f), Offset(0f, h), strokeWidth = 1.dp.toPx())

                    // 5. Outer Edge Marker Ticks
                    val tickLen = 16.dp.toPx()
                    for (i in 1..9) {
                        val gx = w * (i * 0.1f)
                        val gy = h * (i * 0.1f)
                        // Top & Bottom edge ticks
                        drawLine(Color(0xFF38BDF8), Offset(gx, 0f), Offset(gx, tickLen), strokeWidth = 2.dp.toPx())
                        drawLine(Color(0xFF38BDF8), Offset(gx, h), Offset(gx, h - tickLen), strokeWidth = 2.dp.toPx())
                        // Left & Right edge ticks
                        drawLine(Color(0xFF38BDF8), Offset(0f, gy), Offset(tickLen, gy), strokeWidth = 2.dp.toPx())
                        drawLine(Color(0xFF38BDF8), Offset(w, gy), Offset(w - tickLen, gy), strokeWidth = 2.dp.toPx())
                    }

                    // 6. High-Contrast Center Crosshair Target
                    val chLen = 32.dp.toPx()
                    val chGap = 6.dp.toPx()
                    // Crosshair Ticks
                    drawLine(centerColor, Offset(cx - chGap - chLen, cy), Offset(cx - chGap, cy), strokeWidth = 2.dp.toPx())
                    drawLine(centerColor, Offset(cx + chGap, cy), Offset(cx + chGap + chLen, cy), strokeWidth = 2.dp.toPx())
                    drawLine(centerColor, Offset(cx, cy - chGap - chLen), Offset(cx, cy - chGap), strokeWidth = 2.dp.toPx())
                    drawLine(centerColor, Offset(cx, cy + chGap), Offset(cx, cy + chGap + chLen), strokeWidth = 2.dp.toPx())

                    // Center Optical Circles
                    drawCircle(centerColor, radius = 24.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
                    drawCircle(centerColor, radius = 48.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
                    drawCircle(Color(0xFFFFCC00), radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                }
            }
        }
    }
}

/**
 * Live Passthrough Overlay with Center Crosshairs and Alignment Guides
 */
@Composable
fun LivePassthroughOverlay(
    eyeLabel: String,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    rotationDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        translate(left = offsetX, top = offsetY) {
            scale(scaleX = scale, scaleY = scale, pivot = Offset(cx, cy)) {
                rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
                    val reticleColor = Color(0xCC38BDF8)
                    val centerDotColor = Color(0xFFF59E0B)

                    // Center Crosshair
                    val len = 20.dp.toPx()
                    drawLine(reticleColor, Offset(cx - len, cy), Offset(cx + len, cy), strokeWidth = 1.5.dp.toPx())
                    drawLine(reticleColor, Offset(cx, cy - len), Offset(cx, cy + len), strokeWidth = 1.5.dp.toPx())

                    // Center Dot
                    drawCircle(centerDotColor, radius = 3.dp.toPx(), center = Offset(cx, cy))

                    // Optics Safe Perimeter Frame
                    drawRect(
                        color = Color(0x4438BDF8),
                        topLeft = Offset(w * 0.1f, h * 0.1f),
                        size = Size(w * 0.8f, h * 0.8f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Optics Safe-Zone Calibration Card:
 * Positioned in the sweet spot of the Gear VR lenses, duplicated in both eyes.
 */
@Composable
fun EyeSafeCalibrationCard(
    eyeName: String,
    calibration: XrEyeCalibration,
    selectedParam: CalibrationParam,
    displaySource: CalibrationDisplaySource,
    stepMode: AdjustmentStepMode,
    eyeVisibility: EyeVisibilityMode,
    onSelectParam: (CalibrationParam) -> Unit,
    onAdjustParam: (delta: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(330.dp),
        color = Color(0xD90B132B),
        shape = RoundedCornerShape(12.dp),
        border = ButtonDefaults.outlinedButtonBorder
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // Header: Mode & Diagnostics status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "XR CALIBRATION",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = eyeName,
                    color = Color(0xFFF59E0B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sub-header: Source, Step Mode & Eye Visibility
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SRC: ${displaySource.name}",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "VIEW: ${eyeVisibility.name}",
                    color = Color(0xFF38BDF8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "STEP: ${if (stepMode == AdjustmentStepMode.FINE) "FINE" else "COARSE"}",
                    color = if (stepMode == AdjustmentStepMode.FINE) Color(0xFF10B981) else Color(0xFFF59E0B),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            HorizontalDivider(color = Color(0x3338BDF8), modifier = Modifier.padding(vertical = 4.dp))

            // Active Parameter Highlight Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0284C7), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EDIT: ${selectedParam.displayName}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = calibration.formatValue(selectedParam),
                    color = Color(0xFFFFEE00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // All Parameters List (Compact 2-Column Grid)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Right Eye Parameters (Primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text("RIGHT EYE", color = Color(0xFF38BDF8), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    ParamRow(CalibrationParam.RIGHT_X, calibration, selectedParam, onSelectParam)
                    ParamRow(CalibrationParam.RIGHT_Y, calibration, selectedParam, onSelectParam)
                    ParamRow(CalibrationParam.RIGHT_SCALE, calibration, selectedParam, onSelectParam)
                    ParamRow(CalibrationParam.RIGHT_ROTATION, calibration, selectedParam, onSelectParam)
                }

                // Left Eye Parameters
                Column(modifier = Modifier.weight(1f)) {
                    Text("LEFT EYE", color = Color(0xFF38BDF8), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    ParamRow(CalibrationParam.LEFT_X, calibration, selectedParam, onSelectParam)
                    ParamRow(CalibrationParam.LEFT_Y, calibration, selectedParam, onSelectParam)
                    ParamRow(CalibrationParam.LEFT_SCALE, calibration, selectedParam, onSelectParam)
                    ParamRow(CalibrationParam.LEFT_ROTATION, calibration, selectedParam, onSelectParam)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Global Parameters Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    ParamRow(CalibrationParam.GLOBAL_SEPARATION, calibration, selectedParam, onSelectParam)
                }
                Column(modifier = Modifier.weight(1f)) {
                    ParamRow(CalibrationParam.GLOBAL_VERTICAL, calibration, selectedParam, onSelectParam)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Interactive On-Screen Adjustment Buttons (for direct touching)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onAdjustParam(-1f) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.weight(1f).height(30.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("◀ -", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onAdjustParam(1f) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.weight(1f).height(30.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+ ▶", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ParamRow(
    param: CalibrationParam,
    calibration: XrEyeCalibration,
    selectedParam: CalibrationParam,
    onSelectParam: (CalibrationParam) -> Unit
) {
    val isSelected = param == selectedParam
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0x4038BDF8) else Color.Transparent)
            .clickable { onSelectParam(param) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (if (isSelected) "▶ " else "  ") + param.shortName,
            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFFCBD5E1),
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = calibration.formatValue(param),
            color = if (isSelected) Color(0xFFFFEE00) else Color.White,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace
        )
    }
}
