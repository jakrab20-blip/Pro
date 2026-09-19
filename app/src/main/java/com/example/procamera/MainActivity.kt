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
import android.view.ScaleGestureDetector
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
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
    OFF("ปิด"),
    OIS("OIS"),
    EIS("EIS")
}

// เรียงแบบ iPhone: ไทม์แลปส์ - สโลว์โมชัน - วิดีโอ - ภาพถ่าย - สแควร์
enum class CaptureMode(val label: String) {
    TIMELAPSE("ไทม์แลปส์"),
    SLOMO("สโลว์โมชัน"),
    VIDEO("วิดีโอ"),
    PHOTO("ภาพถ่าย"),
    SQUARE("สแควร์")
}

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

/** ปุ่มแบบแคปซูลโปร่งแสงสไตล์ iOS Camera (ใช้สัญลักษณ์ตัวอักษร/อิโมจิ ไม่ต้องพึ่งไลบรารีไอคอนเพิ่ม) */
@Composable
fun GlassIcon(
    symbol: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = tint, fontSize = 17.sp)
    }
}

@Composable
fun GlassLabel(text: String, tint: Color = Color(0xFFFFD60A), onClick: () -> Unit) {
    Text(
        text = text,
        color = tint,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
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
    val videoMode = mode == CaptureMode.VIDEO || mode == CaptureMode.SLOMO
    var stab by remember { mutableStateOf(Stab.OIS) }
    var quality by remember { mutableStateOf(Quality.FHD) }
    var supported by remember { mutableStateOf<List<Quality>>(emptyList()) }
    var aspect by remember { mutableStateOf(AspectOption.R4_3) }
    // โหมดสแควร์บังคับสัดส่วน 1:1 เสมอ ไม่ว่าผู้ใช้จะตั้งค่าอัตราส่วนไว้อย่างไร
    val effectiveAspect = if (mode == CaptureMode.SQUARE) AspectOption.R1_1 else aspect
    // โหมดสโลว์โมชันพยายามอัดที่ 120fps ก่อน ถ้าเครื่องไม่รองรับจะลดลงอัตโนมัติ
    val effectiveFps = if (mode == CaptureMode.SLOMO) 120 else 30
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var gridOn by remember { mutableStateOf(true) }
    var selfTimer by remember { mutableIntStateOf(0) }

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
    var torch by remember { mutableStateOf(false) }

    // ---- iPhone-style tap-to-focus + drag-to-expose ----
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusVisible by remember { mutableStateOf(false) }
    var dragBaselineEv by remember { mutableIntStateOf(0) }
    var dragBaselineY by remember { mutableFloatStateOf(0f) }
    var focusToken by remember { mutableIntStateOf(0) }

    // ---- capture feedback ----
    var lastMediaUri by remember { mutableStateOf<Uri?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var countdownValue by remember { mutableStateOf<Int?>(null) }
    var captureTrigger by remember { mutableIntStateOf(0) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var rollDeg by remember { mutableFloatStateOf(0f) }
    var timelapseActive by remember { mutableStateOf(false) }
    var timelapseShots by remember { mutableIntStateOf(0) }

    val isRecording = recording != null

    // ---- bind camera whenever the key settings change ----
    LaunchedEffect(lens, quality, effectiveFps, stab, effectiveAspect) {
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
                effectiveAspect.camerax?.let { previewBuilder.setTargetAspectRatio(it) }
                val preview = previewBuilder.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // Photo: prioritize quality over speed
                val icBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                effectiveAspect.camerax?.let { icBuilder.setTargetAspectRatio(it) }
                val ic = icBuilder.build()
                ic.flashMode = flashMode

                // Video
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality))
                    )
                    .build()
                val vb = VideoCapture.Builder(recorder)
                    .setTargetFrameRate(Range(effectiveFps, effectiveFps))

                when (stab) {
                    Stab.OFF -> {
                        Camera2Interop.Extender(vb)
                            .setCaptureRequestOption(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                            )
                    }
                    Stab.OIS -> {
                        Camera2Interop.Extender(vb)
                            .setCaptureRequestOption(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                            )
                    }
                    Stab.EIS -> {
                        vb.setVideoStabilizationEnabled(true)
                    }
                }
                val vc = vb.build()

                val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                fun buildGroup(withVideo: Boolean): UseCaseGroup {
                    val b = UseCaseGroup.Builder().addUseCase(preview).addUseCase(ic)
                    if (withVideo) b.addUseCase(vc)
                    if (effectiveAspect != AspectOption.FULL) {
                        b.setViewPort(
                            ViewPort.Builder(Rational(effectiveAspect.w, effectiveAspect.h), rotation).build()
                        )
                    }
                    return b.build()
                }

                provider.unbindAll()
                var withVideo = true
                val cam = try {
                    provider.bindToLifecycle(lifecycleOwner, selector, buildGroup(true))
                } catch (e: Exception) {
                    withVideo = false
                    provider.unbindAll()
                    if (effectiveFps > 60) {
                        Toast.makeText(
                            context,
                            "เครื่องนี้อัดสโลว์โมชัน 120fps ไม่ได้ ลองลดเฟรมเรตหรือใช้โหมดวิดีโอปกติ",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "เครื่องนี้ใช้วิดีโอด้วยตัวเลือกนี้ไม่ได้ (${e.message}) จึงเหลือโหมดรูปภาพ",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
                evIndex = 0
                torch = false
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "ตัวเลือกนี้ใช้ไม่ได้กับเครื่อง: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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

    // ซ่อนกรอบโฟกัส/เอ็กซ์โพสเชอร์อัตโนมัติหลัง 4 วินาที เหมือน iPhone
    LaunchedEffect(focusToken) {
        if (focusToken == 0) return@LaunchedEffect
        focusVisible = true
        delay(4000)
        focusVisible = false
    }

    // horizon level using the accelerometer (เปิดค้างไว้เสมอ กดกริดเปิดปิดแยก)
    DisposableEffect(Unit) {
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
                if (mode != CaptureMode.TIMELAPSE) {
                    Toast.makeText(context, "บันทึกรูปแล้ว", Toast.LENGTH_SHORT).show()
                }
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

    // ไทม์แลปส์: ถ่ายรูปทุก 2 วินาทีขณะเปิดใช้งาน (หมายเหตุ: บันทึกเป็นชุดภาพ ยังไม่ได้ประกอบเป็นวิดีโอให้อัตโนมัติ)
    LaunchedEffect(timelapseActive) {
        if (!timelapseActive) {
            timelapseShots = 0
            return@LaunchedEffect
        }
        while (timelapseActive) {
            takePhotoActual()
            timelapseShots++
            delay(2000)
        }
    }

    fun onShutterClick() {
        when (mode) {
            CaptureMode.PHOTO, CaptureMode.SQUARE -> requestPhoto()
            CaptureMode.VIDEO, CaptureMode.SLOMO -> toggleRecord()
            CaptureMode.TIMELAPSE -> {
                timelapseActive = !timelapseActive
                if (!timelapseActive) {
                    Toast.makeText(context, "ถ่ายไทม์แลปส์ไปแล้ว $timelapseShots ภาพ", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val shutterActive = isRecording || timelapseActive
    val zoomSteps = listOf(0.5f, 1f, 2f, 5f).filter { it in minZoom..maxZoom }
    val animatedZoom by animateFloatAsState(targetValue = zoom, label = "zoom")

    // ---- UI ----
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        val viewportModifier = if (effectiveAspect != AspectOption.FULL) {
            Modifier.aspectRatio(effectiveAspect.ratio).align(Alignment.Center)
        } else {
            Modifier.fillMaxSize()
        }

        Box(viewportModifier) {
            val scaleDetector = remember {
                ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val cam = camera ?: return true
                        val base = cam.cameraInfo.zoomState.value?.zoomRatio ?: zoom
                        val newZoom = (base * detector.scaleFactor).coerceIn(minZoom, maxOf(minZoom, maxZoom))
                        cam.cameraControl.setZoomRatio(newZoom)
                        zoom = newZoom
                        return true
                    }
                })
            }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
                update = { pv ->
                    var downX = 0f
                    var downY = 0f
                    var downTime = 0L
                    var dragging = false

                    pv.setOnTouchListener { v, e ->
                        scaleDetector.onTouchEvent(e)

                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                downX = e.x; downY = e.y
                                downTime = System.currentTimeMillis()
                                dragging = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (e.pointerCount == 1 && focusPoint != null && focusVisible) {
                                    val movedFromDown = abs(e.y - downY) + abs(e.x - downX)
                                    if (movedFromDown > 24f) {
                                        if (!dragging) {
                                            dragging = true
                                            dragBaselineEv = evIndex
                                            dragBaselineY = e.y
                                        }
                                        val dy = dragBaselineY - e.y // ลากขึ้น = สว่างขึ้น เหมือน iPhone
                                        val steps = (dy / 28f).roundToInt()
                                        val newEv = (dragBaselineEv + steps).coerceIn(evMin, evMax)
                                        if (newEv != evIndex) {
                                            evIndex = newEv
                                            camera?.cameraControl?.setExposureCompensationIndex(evIndex)
                                        }
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (!dragging && e.pointerCount <= 1 && elapsed < 300) {
                                    val point = pv.meteringPointFactory.createPoint(e.x, e.y)
                                    camera?.cameraControl?.startFocusAndMetering(
                                        FocusMeteringAction.Builder(point).build()
                                    )
                                    focusPoint = Offset(e.x, e.y)
                                    dragBaselineEv = evIndex
                                    focusToken++
                                }
                                v.performClick()
                                dragging = false
                            }
                        }
                        true
                    }
                }
            )

            if (gridOn) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val lineColor = Color.White.copy(alpha = 0.45f)
                    for (i in 1..2) {
                        drawLine(lineColor, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1f)
                        drawLine(lineColor, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1f)
                    }
                }
            }

            // เส้นระดับน้ำแบบ iPhone (โผล่เฉพาะตอนใกล้แนวนอน)
            if (abs(rollDeg) < 8f) {
                val onLevel = abs(rollDeg) < 1.2f
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
                            color = if (onLevel) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.7f),
                            start = Offset(0f, size.height / 2f),
                            end = Offset(size.width, size.height / 2f),
                            strokeWidth = 3f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // กรอบโฟกัสสีเหลือง + ไอคอนพระอาทิตย์ ลากขึ้นลงเพื่อปรับแสง แบบ iPhone
                        val fp = focusPoint
            AnimatedVisibility(
                visible = fp != null && focusVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (fp != null) {
                    Box(
                        Modifier.offset { IntOffset((fp.x - 40.dp.toPx()).roundToInt(), (fp.y - 40.dp.toPx()).roundToInt()) }
                    ) {
                        Box(
                            Modifier
                                .size(80.dp)
                                .border(1.dp, Color(0xFFFFD60A), RoundedCornerShape(2.dp))
                        )
                        Text(
                            "☀",
                            color = Color(0xFFFFD60A),
                            fontSize = 15.sp,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 20.dp)
                        )
                    }
                }
            }

            countdownValue?.let {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "$it", color = Color.White, fontSize = 64.sp)
                }
            }
        }

        // ---- top bar สไตล์ iOS: แคปซูลลอย โปร่งแสง ----
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIcon(
                symbol = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> "⚡"
                    ImageCapture.FLASH_MODE_AUTO -> "⚡A"
                    else -> "⚡"
                },
                tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.White else Color(0xFFFFD60A)
            ) {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }

            if (isRecording) {
                Text(
                    text = "● " + "%02d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                    color = Color(0xFFFF3B30),
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            } else if (timelapseActive) {
                Text(
                    text = "● ไทม์แลปส์ $timelapseShots",
                    color = Color(0xFFFFD60A),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIcon(
                        symbol = "⏱",
                        tint = if (selfTimer > 0) Color(0xFFFFD60A) else Color.White
                    ) {
                        selfTimer = when (selfTimer) { 0 -> 3; 3 -> 10; else -> 0 }
                    }
                    GlassIcon(
                        symbol = "▦",
                        tint = if (gridOn) Color(0xFFFFD60A) else Color.White
                    ) { gridOn = !gridOn }
                }
            }

            GlassLabel(text = effectiveAspect.label, tint = Color.White) {
                if (mode != CaptureMode.SQUARE) {
                    val all = AspectOption.values()
                    aspect = all[(aspect.ordinal + 1) % all.size]
                }
            }
        }

        // ---- bottom controls ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // zoom capsule แบบ iPhone (แตะเลือกเร็ว หรือบีบนิ้วสองนิ้วบนจอเพื่อซูมต่อเนื่อง)
            if (zoomSteps.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(4.dp)
                ) {
                    zoomSteps.forEach { z ->
                        val selected = abs(animatedZoom - z) < 0.35f
                        Text(
                            text = (if (z < 1f) "0.5" else if (z == z.toInt().toFloat()) z.toInt().toString() else z.toString()) + "×",
                            color = if (selected) Color(0xFFFFD60A) else Color.White,
                            fontSize = if (selected) 13.sp else 11.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) Color.Black.copy(alpha = 0.5f) else Color.Transparent)
                                .clickable {
                                    zoom = z
                                    camera?.cameraControl?.setZoomRatio(z)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // mode carousel แบบเลื่อนแนวนอนของ iPhone
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Spacer(Modifier.width(60.dp))
                CaptureMode.values().forEach { m ->
                    val selected = m == mode
                    Text(
                        text = m.label,
                        color = if (selected) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.65f),
                        fontSize = if (selected) 15.sp else 13.sp,
                        modifier = Modifier
                            .clickable(enabled = !isRecording && !timelapseActive) { mode = m }
                            .padding(vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(60.dp))
            }

            // thumbnail | shutter | flip camera
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val thumb = thumbnail
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
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
                    Spacer(Modifier.size(52.dp))
                }

                // shutter แบบ iPhone: วงแหวนขาวบาง + วงในเปลี่ยนรูปตอนอัด
                Box(
                    Modifier
                        .size(78.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(if (shutterActive) Color(0xFFFF3B30) else Color.White)
                        .clickable { onShutterClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (shutterActive) {
                        Box(
                            Modifier
                                .size(if (mode == CaptureMode.TIMELAPSE) 30.dp else 26.dp)
                                .clip(RoundedCornerShape(if (mode == CaptureMode.TIMELAPSE) 15.dp else 6.dp))
                                .background(Color.White)
                        )
                    }
                }

                GlassIcon(symbol = "🔄") {
                    if (!isRecording && !timelapseActive) {
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
