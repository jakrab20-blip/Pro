package com.example.procamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

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

enum class CaptureMode { PHOTO, VIDEO }

// w:h เป็นอัตราส่วนแนวตั้ง (portrait) ของช่องมองภาพ
enum class AspectOption(val label: String, val w: Int, val h: Int, val camerax: Int?) {
    R4_3("4:3", 3, 4, AspectRatio.RATIO_4_3),
    R1_1("1:1", 1, 1, AspectRatio.RATIO_4_3),
    R16_9("16:9", 9, 16, AspectRatio.RATIO_16_9),
    FULL("เต็มจอ", 0, 0, null);

    val ratio: Float get() = if (h == 0) 0f else w.toFloat() / h
}

fun qualityLabel(q: Quality): String = when (q) {
    Quality.UHD -> "4K"
    Quality.FHD -> "1080p"
    Quality.HD -> "720p"
    Quality.SD -> "480p"
    else -> "?"
}

fun flashLabel(mode: Int): String = when (mode) {
    ImageCapture.FLASH_MODE_ON -> "เปิด"
    ImageCapture.FLASH_MODE_AUTO -> "อัตโนมัติ"
    else -> "ปิด"
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color(0xFFFFD60A) else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
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
    var mode by remember { mutableStateOf(CaptureMode.PHOTO) }
    val videoMode = mode == CaptureMode.VIDEO
    var stab by remember { mutableStateOf(Stab.OIS) }
    var quality by remember { mutableStateOf(Quality.FHD) }
    var fps by remember { mutableIntStateOf(30) }
    var supported by remember { mutableStateOf<List<Quality>>(emptyList()) }
    var aspect by remember { mutableStateOf(AspectOption.R4_3) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var gridOn by remember { mutableStateOf(true) }
    var levelOn by remember { mutableStateOf(false) }
    var selfTimer by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

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

    // ---- capture feedback ----
    var lastMediaUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var countdownValue by remember { mutableStateOf<Int?>(null) }
    var captureTrigger by remember { mutableIntStateOf(0) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var rollDeg by remember { mutableFloatStateOf(0f) }

    val isRecording = recording != null

    // ---- bind camera whenever the key settings change ----
    LaunchedEffect(lens, quality, fps, stab, aspect) {
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
                aspect.camerax?.let { previewBuilder.setTargetAspectRatio(it) }
                val preview = previewBuilder.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // Photo: prioritize quality over speed
                val icBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                aspect.camerax?.let { icBuilder.setTargetAspectRatio(it) }
                val ic = icBuilder.build()
                ic.flashMode = flashMode

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

                // ViewPort ทำให้รูป/วิดีโอที่บันทึกถูกครอปตรงกับที่เห็นบนจอ (เช่น 1:1)
                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                fun buildGroup(withVideo: Boolean): UseCaseGroup {
                    val b = UseCaseGroup.Builder().addUseCase(preview).addUseCase(ic)
                    if (withVideo) b.addUseCase(vc)
                    if (aspect != AspectOption.FULL) {
                        b.setViewPort(
                            ViewPort.Builder(Rational(aspect.w, aspect.h), rotation).build()
                        )
                    }
                    return b.build()
                }

                provider.unbindAll()
                var withVideo = true
                val cam = try {
                    provider.bindToLifecycle(lifecycleOwner, selector, buildGroup(true))
                } catch (e: Exception) {
                    // บางเครื่องผูก Preview + Photo + Video พร้อมกันไม่ได้ -> ถอยไปใช้โหมดรูปอย่างเดียว
                    withVideo = false
                    provider.unbindAll()
                    Toast.makeText(
                        context,
                        "เครื่องนี้ใช้วิดีโอด้วยตัวเลือกนี้ไม่ได้ (${e.message}) จึงเหลือโหมดรูปภาพ",
                        Toast.LENGTH_LONG
                    ).show()
                    provider.bindToLifecycle(lifecycleOwner, selector, buildGroup(false))
                }

                camera = cam
                imageCapture = ic
                videoCapture = if (withVideo) vc else null

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
                if (stab == Stab.EIS) stab = Stab.OIS
            }
        }, executor)
    }

    // keep flash mode in sync without a full rebind
    LaunchedEffect(flashMode, imageCapture) {
        imageCapture?.flashMode = flashMode
    }

    // recording timer (mm:ss)
    LaunchedEffect(isRecording) {
        recordSeconds = 0
        while (isRecording) {
            delay(1000)
            recordSeconds++
        }
    }

    // horizon level using the accelerometer
    DisposableEffect(levelOn) {
        if (!levelOn) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                rollDeg = Math.toDegrees(atan2(-x.toDouble(), y.toDouble())).toFloat().let {
                    if (it > 90f) it - 180f else if (it < -90f) it + 180f else it
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensor?.let { sm?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(listener) }
    }

    // load a thumbnail of the last photo/video
    LaunchedEffect(lastMediaUri) {
        val uri = lastMediaUri ?: return@LaunchedEffect
        thumbnail = try {
            withContext(Dispatchers.IO) {
                context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
            }
        } catch (e: Exception) {
            null
        }
    }
    // ---- actions ----
    fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    fun takePhotoActual() {
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
                output.savedUri?.let { lastMediaUri = it }
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(context, "ถ่ายรูปไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    fun requestPhoto() {
        if (selfTimer <= 0) {
            takePhotoActual()
        } else if (countdownValue == null) {
            captureTrigger++
        }
    }

    LaunchedEffect(captureTrigger) {
        if (captureTrigger == 0) return@LaunchedEffect
        for (s in selfTimer downTo 1) {
            countdownValue = s
            delay(1000)
        }
        countdownValue = null
        takePhotoActual()
    }

    fun toggleRecord() {
        val current = recording
        if (current != null) {
            current.stop()
            return
        }
        val vc = videoCapture
        if (vc == null) {
            Toast.makeText(context, "ตัวเลือกนี้ยังอัดวิดีโอไม่ได้", Toast.LENGTH_SHORT).show()
            return
        }
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
                if (!event.hasError()) lastMediaUri = event.outputResults.outputUri
            }
        }
    }

    val zoomSteps = listOf(0.5f, 1f, 2f, 5f).filter { it in minZoom..maxZoom }

    // ---- UI ----
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        val viewportModifier = if (aspect != AspectOption.FULL) {
            Modifier.aspectRatio(aspect.ratio).align(Alignment.Center)
        } else {
            Modifier.fillMaxSize()
        }

        Box(viewportModifier) {
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

            if (gridOn) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val lineColor = Color.White.copy(alpha = 0.55f)
                    for (i in 1..2) {
                        drawLine(lineColor, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1f)
                        drawLine(lineColor, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1f)
                    }
                }
            }

            if (levelOn) {
                val onLevel = kotlin.math.abs(rollDeg) < 1.5f
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .rotate(rollDeg),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                        drawLine(
                            color = if (onLevel) Color(0xFF34C759) else Color.White,
                            start = Offset(0f, size.height / 2f),
                            end = Offset(size.width, size.height / 2f),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            countdownValue?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "$it",
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge
                    )
                }
            }
        }

        // ---- top bar ----
        val torchOrFlashLabel = if (videoMode) {
            "ไฟ: " + (if (torch) "เปิด" else "ปิด")
        } else {
            "แฟลช: " + flashLabel(flashMode)
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill(torchOrFlashLabel) {
                if (videoMode) {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                } else {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                }
            }

            if (isRecording) {
                Text(
                    text = "● " + "%02d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                    color = Color(0xFFFF3B30),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Pill("ตั้งค่า", selected = showSettings) { showSettings = !showSettings }
        }

        // ---- settings panel ----
        if (showSettings) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 92.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("สัดส่วน: ${aspect.label}") {
                        val all = AspectOption.values()
                        aspect = all[(aspect.ordinal + 1) % all.size]
                    }
                    Pill("ตัวตั้งเวลา: " + if (selfTimer == 0) "ปิด" else "${selfTimer}s") {
                        selfTimer = when (selfTimer) {
                            0 -> 3
                            3 -> 10
                            else -> 0
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("ตาราง", selected = gridOn) { gridOn = !gridOn }
                    Pill("ระดับน้ำ", selected = levelOn) { levelOn = !levelOn }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(stab.label) {
                        val all = Stab.values()
                        stab = all[(stab.ordinal + 1) % all.size]
                    }
                    Pill("${fps}fps") { fps = if (fps == 30) 60 else 30 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("วิดีโอ: ${qualityLabel(quality)}") {
                        val options = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                            .filter { it in supported }
                        if (options.isNotEmpty()) {
                            val idx = options.indexOf(quality)
                            quality = options[(idx + 1) % options.size]
                        }
                    }
                }
            }
        }

        // ---- bottom controls ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // zoom buttons
            if (zoomSteps.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    zoomSteps.forEach { z ->
                        Pill(
                            text = (if (z < 1f) "0.5" else z.toInt().toString()) + "x",
                            selected = kotlin.math.abs(zoom - z) < 0.05f
                        ) {
                            zoom = z
                            camera?.cameraControl?.setZoomRatio(z)
                        }
                    }
                }
            }

            // exposure compensation
            if (evMax > evMin) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("EV", color = Color.White)
                    Slider(
                        value = evIndex.toFloat(),
                        onValueChange = {
                            evIndex = it.roundToInt()
                            camera?.cameraControl?.setExposureCompensationIndex(evIndex)
                        },
                        valueRange = evMin.toFloat()..evMax.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    Text("%+.1f".format(evIndex * evStep), color = Color.White)
                }
            }

            // mode selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("รูปภาพ", selected = mode == CaptureMode.PHOTO) {
                    if (!isRecording) mode = CaptureMode.PHOTO
                }
                Pill("วิดีโอ", selected = mode == CaptureMode.VIDEO) {
                    if (countdownValue == null) mode = CaptureMode.VIDEO
                }
            }

            // thumbnail | shutter | flip camera
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val thumb = thumbnail
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .clickable {
                                lastMediaUri?.let { uri ->
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "เปิดแกลเลอรีไม่ได้", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                    )
                } else {
                    Box(Modifier.size(56.dp))
                }

                // shutter
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (videoMode) Color(0xFFFF3B30) else Color.White
                        )
                        .clickable {
                            if (videoMode) toggleRecord() else requestPhoto()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                    }
                }

                // flip camera
                Pill("สลับ") {
                    if (!isRecording) {
                        lens = if (lens == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    }
                }
            }
        }
    }
}
