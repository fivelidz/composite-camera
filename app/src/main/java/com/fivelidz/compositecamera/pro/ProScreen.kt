package com.fivelidz.compositecamera.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.fivelidz.compositecamera.common.ProTutorialDialog
import com.fivelidz.compositecamera.common.SaveResult
import com.fivelidz.compositecamera.common.Tutorial
import com.fivelidz.compositecamera.common.saveBitmapToGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CapturedFrame(
    val exposureBucket: Int,
    val focusBucket: Int,
    val jpeg: ByteArray,
    val thumb: Bitmap,
)

private data class ProSettings(
    val exposureSteps: Int = 3,
    val evRange: Float = 2f,
    val focusSteps: Int = 1,
    val nearFocusFraction: Float = 1.0f,   // fraction of lens min-focus to use as nearest plane
    val alignFrames: Boolean = true,
    val pyramidLevels: Int = 5,
)

private enum class Preset(
    val label: String,
    val sub: String,
    val apply: (ProSettings) -> ProSettings,
) {
    RealEstate(
        "Real-estate",
        "3 exposures · ±2 EV · focus off",
        { it.copy(exposureSteps = 3, evRange = 2f, focusSteps = 1, nearFocusFraction = 1.0f) }
    ),
    CloseUpDistance(
        "Close-up + distance",
        "5 focus planes · 1 exposure · subject ≈ 15 cm to ∞",
        { it.copy(exposureSteps = 1, evRange = 2f, focusSteps = 5, nearFocusFraction = 0.6f) }
    ),
    FullComposite(
        "Full composite",
        "3 exposures × 5 focus planes (15 frames)",
        { it.copy(exposureSteps = 3, evRange = 2f, focusSteps = 5, nearFocusFraction = 0.6f) }
    ),
    Custom("Custom", "as configured below", { it })
}

@Composable
fun ProScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showTutorial by remember { mutableStateOf(Tutorial.shouldShowPro(ctx)) }
    if (showTutorial) {
        ProTutorialDialog(onDismiss = {
            Tutorial.markProSeen(ctx)
            showTutorial = false
        })
    }

    val caps = remember { CameraCaps.probe(ctx) }
    var settings by remember { mutableStateOf(ProSettings()) }
    var preset by remember { mutableStateOf(Preset.RealEstate) }

    var capturing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("Ready") }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var previewSurface by remember { mutableStateOf<android.view.Surface?>(null) }

    // Streaming captured frames + final result.
    val frames = remember { mutableStateListOf<CapturedFrame>() }
    var finalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewingFrameIndex by remember { mutableStateOf(-1) }  // -1 = result, 0..n-1 = source frame

    if (caps == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No rear camera found", color = Color.White)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Preview area — shows live SurfaceView OR (post-capture) the selected frame / result.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.TopCenter)
        ) {
            // Always-on SurfaceView for the camera preview (lives behind any captured-frame image).
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { previewSurface = h.surface }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {
                                previewSurface = h.surface
                            }
                            override fun surfaceDestroyed(h: SurfaceHolder) { previewSurface = null }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Overlay an Image over the SurfaceView if user is reviewing a captured frame.
            val showing = when {
                viewingFrameIndex == -1 && finalBitmap != null -> finalBitmap
                viewingFrameIndex in frames.indices -> frames[viewingFrameIndex].thumb
                else -> null
            }
            if (showing != null) {
                Image(
                    bitmap = showing.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(Color.Black).zIndex(1f)
                )
                // Caption (which frame is this?)
                val caption = if (viewingFrameIndex == -1) "FUSED RESULT" else {
                    val f = frames[viewingFrameIndex]
                    "Frame ${viewingFrameIndex + 1}/${frames.size} — EV bucket ${f.exposureBucket}" +
                    (if (settings.focusSteps > 1) ", Focus ${f.focusBucket}" else "")
                }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .zIndex(2f)
                ) {
                    Text(caption, color = Color.White, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        // 2. Top bar
        Row(
            Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopStart).zIndex(3f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000))) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text("Pro Composite", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { showTutorial = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000))
            ) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "Help",
                    tint = Color(0xFF5FB0FF))
            }
            Spacer(Modifier.width(4.dp))
            CapsBadge(caps)
        }

        // 3. Bottom area — frame strip + controls.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xEE000000))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Thumbnail strip — always visible if any frames captured.
            if (frames.isNotEmpty() || finalBitmap != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (finalBitmap != null) {
                        item {
                            Thumb(
                                bitmap = finalBitmap!!,
                                label = "FUSED",
                                selected = viewingFrameIndex == -1,
                                accent = Color(0xFF5FB0FF),
                                onClick = { viewingFrameIndex = -1 }
                            )
                        }
                    }
                    itemsIndexed(frames) { idx, frame ->
                        Thumb(
                            bitmap = frame.thumb,
                            label = "EV${frame.exposureBucket}" +
                                (if (settings.focusSteps > 1) " F${frame.focusBucket}" else ""),
                            selected = viewingFrameIndex == idx,
                            accent = Color(0xFFFFFFFF),
                            onClick = { viewingFrameIndex = idx }
                        )
                    }
                }
            }

            // Preset selector.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Preset.values().forEach { p ->
                    val sel = p == preset
                    Surface(
                        color = if (sel) Color(0xFF5FB0FF) else Color(0xFF202020),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            preset = p
                            settings = p.apply(settings)
                        }
                    ) {
                        Column(
                            Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(p.label,
                                color = if (sel) Color.Black else Color.White,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text(
                preset.sub,
                color = Color(0xFF909090),
                fontSize = 11.sp,
            )

            // Stat line.
            Text(
                "${settings.exposureSteps} exposures" +
                (if (settings.focusSteps > 1) " × ${settings.focusSteps} focus planes" else "") +
                "  =  ${settings.exposureSteps * settings.focusSteps} frame" +
                (if (settings.exposureSteps * settings.focusSteps > 1) "s" else ""),
                color = Color.White, fontSize = 13.sp
            )

            // Controls.
            StepperRow("Exposure brackets", settings.exposureSteps, 1, 5) {
                settings = settings.copy(exposureSteps = it); preset = Preset.Custom
            }
            StepperRow("EV range  (±${settings.evRange.toInt()} stops)", settings.evRange.toInt(), 1, 3) {
                settings = settings.copy(evRange = it.toFloat()); preset = Preset.Custom
            }
            StepperRow("Focus planes", settings.focusSteps, 1, 7) {
                settings = settings.copy(focusSteps = it); preset = Preset.Custom
            }
            if (settings.focusSteps > 1) {
                // Show a "near focus" slider as a percentage of the lens's absolute minimum.
                val pct = (settings.nearFocusFraction * 100).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Closest focus", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text("$pct%", color = Color(0xFF5FB0FF), fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = settings.nearFocusFraction,
                    onValueChange = {
                        settings = settings.copy(nearFocusFraction = it); preset = Preset.Custom
                    },
                    valueRange = 0.2f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF5FB0FF),
                        activeTrackColor = Color(0xFF5FB0FF),
                        inactiveTrackColor = Color(0xFF404040)
                    )
                )
                Text(
                    "100% = lens absolute minimum (≈ 7 cm). 60% = ≈ 15 cm. 20% = ≈ 50 cm. " +
                    "Lower if your subject is further away — saves wasted frames.",
                    color = Color(0xFF707070), fontSize = 10.sp
                )
            }

            // Progress / status.
            if (capturing) {
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF5FB0FF),
                    trackColor = Color(0xFF202020)
                )
            }
            Text(status, color = Color(0xFFB0B0B0), fontSize = 12.sp)
            savedPath?.let { Text("Saved: $it", color = Color(0xFF8FFF8F), fontSize = 11.sp) }

            // Capture button.
            Button(
                onClick = {
                    if (capturing) return@Button
                    savedPath = null
                    frames.clear()
                    finalBitmap = null
                    viewingFrameIndex = -1
                    scope.launch {
                        runCapture(
                            ctx, caps, settings, previewSurface,
                            onProgress = { p, s -> progress = p; status = s },
                            onCapturing = { capturing = it },
                            onFrameCaptured = { f -> frames.add(f); viewingFrameIndex = frames.lastIndex },
                            onResult = { bmp -> finalBitmap = bmp; viewingFrameIndex = -1 },
                            onSaved = { savedPath = it }
                        )
                    }
                },
                enabled = !capturing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5FB0FF))
            ) {
                Text(
                    if (capturing) "Capturing…" else "Capture composite",
                    color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun Thumb(
    bitmap: Bitmap,
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)   // wrap the WHOLE thumb so taps anywhere work
    ) {
        Box(
            Modifier
                .size(width = 62.dp, height = 80.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) accent else Color(0xFF404040),
                    shape = RoundedCornerShape(6.dp)
                )
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            label,
            color = if (selected) accent else Color(0xFF909090),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CapsBadge(caps: CameraCaps) {
    val ok = caps.supportsManualSensor && caps.supportsManualPostProc
    val txt = if (ok) "PRO" else if (caps.supportsManualSensor) "MANUAL" else "AUTO"
    val tint = if (ok) Color(0xFF5FB0FF) else Color(0xFFFFB35F)
    Box(
        Modifier
            .background(tint.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) { Text(txt, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace) }
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { if (value > min) onChange(value - 1) },
            modifier = Modifier.size(34.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF202020))
        ) { Text("−", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Text("$value", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 18.dp))
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = { if (value < max) onChange(value + 1) },
            modifier = Modifier.size(34.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF202020))
        ) { Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

private suspend fun runCapture(
    ctx: Context,
    caps: CameraCaps,
    settings: ProSettings,
    previewSurface: android.view.Surface?,
    onProgress: (Float, String) -> Unit,
    onCapturing: (Boolean) -> Unit,
    onFrameCaptured: suspend (CapturedFrame) -> Unit,
    onResult: suspend (Bitmap) -> Unit,
    onSaved: (String) -> Unit,
) {
    onCapturing(true)
    try {
        val collected = mutableListOf<CapturedFrame>()
        val total = settings.exposureSteps * settings.focusSteps

        val bracket = BracketCapture(ctx, caps)
        try {
            withContext(Dispatchers.Default) {
                bracket.run(
                    previewSurface = previewSurface,
                    onMetered = { ns, iso ->
                        onProgress(0.02f, "Metered: ${ns / 1_000_000} ms @ ISO $iso")
                    },
                    planBuilder = { ns, iso ->
                        BracketPlanner.plan(
                            meteredExposureNs = ns,
                            meteredIso = iso,
                            exposureSteps = settings.exposureSteps,
                            evRangeStops = settings.evRange,
                            focusSteps = settings.focusSteps,
                            minFocusDiopters = caps.minFocusDistanceDiopters,
                            clampExposureRangeNs = caps.exposureRangeNs,
                            nearFocusFraction = settings.nearFocusFraction,
                        )
                    },
                    onFrame = { idx, n, step, jpeg ->
                        // Decode a tiny thumbnail for the strip.
                        val thumb = decodeThumbnail(jpeg, 320)
                        val frame = CapturedFrame(
                            exposureBucket = step.exposureBucket,
                            focusBucket = step.focusBucket,
                            jpeg = jpeg,
                            thumb = thumb,
                        )
                        collected += frame
                        withContext(Dispatchers.Main) { onFrameCaptured(frame) }
                        onProgress(
                            0.05f + 0.35f * ((idx + 1).toFloat() / n),
                            "Captured ${idx + 1}/$n"
                        )
                    },
                )
            }
        } finally { bracket.release() }

        if (collected.size != total) {
            onProgress(0f, "Capture incomplete — got ${collected.size}/$total")
            return
        }

        // Fuse. Group by focus plane.
        val byFocus: Map<Int, List<CapturedFrame>> = collected.groupBy { it.focusBucket }

        val fusedBitmap: Bitmap = withContext(Dispatchers.Default) {
            val reporter = object : ExposureFusion.Reporter {
                override fun progress(p: Float, label: String) {
                    onProgress(0.4f + 0.5f * p, label)
                }
            }
            if (byFocus.size == 1) {
                // Pure exposure fusion.
                val exp = byFocus.values.first()
                if (exp.size == 1) {
                    BitmapFactory.decodeByteArray(exp[0].jpeg, 0, exp[0].jpeg.size)
                } else {
                    ExposureFusion.fuse(
                        frames = exp.map { ExposureFusion.Frame(it.jpeg, it.exposureBucket) },
                        maxLongEdge = 1800,    // reduced from 2400 to stay under heap
                        pyramidLevels = settings.pyramidLevels,
                        alignFrames = settings.alignFrames,
                        reporter = reporter,
                    )
                }
            } else {
                // Multiple focus planes.
                //
                // If we also have multiple exposures: per-focus-plane exposure-fuse to a
                // single bitmap first, then focus-stack across those fused per-plane bitmaps
                // (encoded as JPEGs so the new FocusStack pipeline can consume them).
                //
                // If we only have one exposure per plane: feed the raw bracket JPEGs directly
                // into FocusStack — no extra encode/decode round-trip.
                val sortedFocus = byFocus.entries.sortedBy { it.key }

                val stackFrames: List<FocusStack.Frame> = sortedFocus.map { (focusBucket, frames) ->
                    if (frames.size == 1) {
                        FocusStack.Frame(
                            jpeg = frames[0].jpeg,
                            focusBucket = focusBucket,
                            focusDiopters = 0f,  // not needed for stacking math
                        )
                    } else {
                        // Exposure-fuse this focus plane first.
                        val fused = ExposureFusion.fuse(
                            frames = frames.map { ExposureFusion.Frame(it.jpeg, it.exposureBucket) },
                            maxLongEdge = 1800,
                            pyramidLevels = settings.pyramidLevels,
                            alignFrames = settings.alignFrames,
                            reporter = reporter,
                        )
                        val baos = java.io.ByteArrayOutputStream()
                        fused.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                        fused.recycle()
                        FocusStack.Frame(baos.toByteArray(), focusBucket, 0f)
                    }
                }

                FocusStack.stack(
                    frames = stackFrames,
                    maxLongEdge = 1800,
                    pyramidLevels = settings.pyramidLevels,
                    compensateBreathing = true,
                    reporter = object : FocusStack.Reporter {
                        override fun progress(p: Float, label: String) {
                            onProgress(0.80f + 0.15f * p, label)
                        }
                    }
                )
            }
        }

        withContext(Dispatchers.Main) { onResult(fusedBitmap) }

        // Save.
        onProgress(0.95f, "Saving")
        val result = withContext(Dispatchers.IO) {
            saveBitmapToGallery(ctx, fusedBitmap, "composite_camera",
                "pro_${System.currentTimeMillis()}.jpg", quality = 95)
        }
        when (result) {
            is SaveResult.Ok -> {
                onProgress(1f, "Done — tap thumbnails to compare source frames")
                onSaved(result.displayPath)
            }
            is SaveResult.Err -> onProgress(0f, "Save failed: ${result.message}")
        }
    } catch (t: Throwable) {
        onProgress(0f, "Error: ${t.message ?: t.javaClass.simpleName}")
    } finally {
        onCapturing(false)
    }
}

private fun decodeThumbnail(jpeg: ByteArray, longEdge: Int): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    var sample = 1
    val long = maxOf(opts.outWidth, opts.outHeight)
    while (long / sample > longEdge) sample *= 2
    val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts2)
        ?: Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
}
