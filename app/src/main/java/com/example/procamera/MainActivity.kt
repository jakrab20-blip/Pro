package com.example.procamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Root()
            }
        }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    fun camGranted() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(camGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = camGranted() }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    if (granted) {
        CameraScreen()
    } else {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("ต้องอนุญาตการใช้กล้องก่อนครับ", color = Color.White)
        }
    }
}

enum class Stab(val label: String) {
    OFF("กันสั่น: ปิด"),
    OIS("กันสั่น: OIS"),
    EIS("กันสั่น: EIS")
}

fun qualityLabel(q: Quality): String = when (q) {
    Quality.UHD -> "4K"
    Quality.FHD -> "1080p"
    Quality.HD -> "720p"
    Quality.SD -> "480p"
    else -> "?"
}

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val previewView = remember { PreviewView(context) }

    // ---- settings ----
    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var videoMode by remember { mutableStateOf(false) }
    var stab by remember { mutableStateOf(Stab.OIS) }
    var quality by remember { mutableStateOf(Quality.FHD) }
    var fps by remember { mutableIntStateOf(30) }
    var supported by remember { mutableStateOf<List<Quality>>(emptyList()) }

    // ---- camera objects ----
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    // ---- live controls ----
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var evIndex by remember { mutableIntStateOf(0) }
    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var evStep by remember { mutableFloatStateOf(0f) }
    var torch by remember { mutableStateOf(false) }

    val isRecording = recording != null

    // ---- bind camera whenever the key settings change ----
    LaunchedEffect(lens, quality, fps, stab) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val selector = CameraSelector.Builder().requireLensFacing(lens).build()

                val info = selector.filter(provider.availableCameraInfos).firstOrNull()
                supported = info?.let { QualitySelector.getSupportedQualities(it) } ?: emptyList()

                // Preview
                val previewBuilder = Preview.Builder()
                if (stab == Stab.EIS) previewBuilder.setPreviewStabilizationEnabled(true)
                val preview = previewBuilder.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // Photo: prioritize quality over speed
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                // Video
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality))
                    )
                    .build()
                val vb = VideoCapture.Builder(recorder)
                    .setTargetFrameRate(Range(fps, fps))

                when (stab) {
                    Stab.OFF -> {
                        Camera2Interop.Extender(vb)
                            .setCaptureRequestOption(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                            )
                    }
                    Stab.OIS -> {
                        // Hardware lens-shift stabilization
                        Camera2Interop.Extender(vb)
                            .setCaptureRequestOption(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                            )
                    }
                    Stab.EIS -> {
                        // Electronic (software crop + gyro) stabilization
                        vb.setVideoStabilizationEnabled(true)
                    }
                }
                val vc = vb.build()

                provider.unbindAll()
                val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, ic, vc)

                camera = cam
                imageCapture = ic
                videoCapture = vc

                cam.cameraInfo.zoomState.value?.let {
                    minZoom = it.minZoomRatio
                    maxZoom = it.maxZoomRatio
                }
                zoom = 1f.coerceIn(minZoom, maxOf(minZoom, maxZoom))
                cam.cameraControl.setZoomRatio(zoom)

                val exp = cam.cameraInfo.exposureState
                evMin = exp.exposureCompensationRange.lower
                evMax = exp.exposureCompensationRange.upper
                evStep = exp.exposureCompensationStep.toFloat()
                evIndex = 0
                torch = false
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "ตัวเลือกนี้ใช้ไม่ได้กับเครื่อง: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                if (fps != 30) fps = 30
            }
        }, executor)
    }

    // ---- actions ----
    fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    fun takePhoto() {
        val ic = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ProCamera")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()
        ic.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Toast.makeText(context, "บันทึกรูปแล้ว", Toast.LENGTH_SHORT).show()
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(context, "ถ่ายรูปไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    fun toggleRecord() {
        val current = recording
        if (current != null) {
            current.stop()
            return
        }
        val vc = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ProCamera")
        }
        val out = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(context, out)
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) pending = pending.withAudioEnabled()

        recording = pending.start(executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                val msg = if (event.hasError()) "บันทึกวิดีโอไม่สำเร็จ (${event.error})" else "บันทึกวิดีโอแล้ว"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- UI ----
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
            update = { pv ->
                // tap to focus
                pv.setOnTouchListener { v, e ->
                    if (e.action == MotionEvent.ACTION_UP) {
                        val point = pv.meteringPointFactory.createPoint(e.x, e.y)
                        camera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build()
                        )
                        v.performClick()
                    }
                    true
                }
            }
        )

        // top bar (video settings)
        if (videoMode) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Chip(stab.label, !isRecording) {
                    stab = Stab.values()[(stab.ordinal + 1) % Stab.values().size]
                }
                Chip(qualityLabel(quality), !isRecording) {
                    val list = supported.filter { it == Quality.SD || it == Quality.HD || it == Quality.FHD || it == Quality.UHD }
                        .sortedBy { listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD).indexOf(it) }
                    if (list.isNotEmpty()) {
                        quality = list[(list.indexOf(quality) + 1) % list.size]
                    }
                }
                Chip("${fps}fps", !isRecording) { fps = if (fps == 30) 60 else 30 }
            }
        }

        // bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (maxZoom > minZoom) {
                Text("ซูม ${"%.1f".format(zoom)}x", color = Color.White)
                Slider(
                    value = zoom,
                    onValueChange = {
                        zoom = it
                        camera?.cameraControl?.setZoomRatio(it)
                    },
                    valueRange = minZoom..maxZoom
                )
            }
            if (evMax > evMin) {
                Text("แสง ${"%.1f".format(evIndex * evStep)} EV", color = Color.White)
                Slider(
                    value = evIndex.toFloat(),
                    onValueChange = {
                        evIndex = it.toInt()
                        camera?.cameraControl?.setExposureCompensationIndex(evIndex)
                    },
                    valueRange = evMin.toFloat()..evMax.toFloat(),
                    steps = (evMax - evMin - 1).coerceAtLeast(0)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Chip(if (videoMode) "โหมด: วิดีโอ" else "โหมด: รูปภาพ", !isRecording) {
                    videoMode = !videoMode
                }

                val inner = if (isRecording) RoundedCornerShape(10.dp) else CircleShape
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(inner)
                        .background(if (videoMode) Color.Red else Color.White)
                        .clickable { if (videoMode) toggleRecord() else takePhoto() }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (torch) "ไฟ: เปิด" else "ไฟ: ปิด", true) {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    }
                    Chip("สลับกล้อง", !isRecording) {
                        lens = if (lens == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0x99000000),
            contentColor = Color.White,
            disabledContainerColor = Color(0x55000000),
            disabledContentColor = Color.Gray
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text)
    }
}
