package com.lutcam.app.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.view.MotionEvent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { ContextCompat.getMainExecutor(context) }

    // LUT 渲染引擎 (在整個 Composable 生命週期中保持同一個實例)
    val lutProcessor = remember { com.lutcam.app.camera.lut.LutSurfaceProcessor() }

    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { 
            coroutineScope.launch {
                val lut = withContext(Dispatchers.IO) {
                    com.lutcam.app.camera.lut.CubeLutParser.parse(context, it)
                }
                if (lut != null) {
                    // 將解析好的 LUT 資料傳給 GPU 渲染引擎
                    lutProcessor.setLut(lut)
                    android.widget.Toast.makeText(
                        context, 
                        "LUT 已套用 (${lut.size}³)", 
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(context, "LUT 匯入失敗或格式錯誤", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // UI 互動狀態
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var isFocusUIVisible by remember { mutableStateOf(false) }
    var exposureIndex by remember { mutableFloatStateOf(0f) }
    var exposureRange by remember { mutableStateOf(0f..0f) }

    // 觸控 3 秒後自動隱藏對焦與亮度介面
    LaunchedEffect(isFocusUIVisible, exposureIndex) {
        if (isFocusUIVisible) {
            delay(3000)
            isFocusUIVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // === 1. 相機 4:3 預覽區 ===
        // 將預覽限制在 3:4 比例 (橫行 3:4 = 直拍 4:3)，與實際拍出的照片一致
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .align(Alignment.TopCenter)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        previewView = this

                        setOnTouchListener { view, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                // 攔截觸控座標，送給 CameraX 進行對焦與測光
                                val factory = this.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                    .build()
                                
                                camera?.cameraControl?.startFocusAndMetering(action)
                                
                                // 更新 UI 紀錄點
                                focusPoint = Offset(event.x, event.y)
                                isFocusUIVisible = true
                                
                                view.performClick()
                                return@setOnTouchListener true
                            }
                            false
                        }
                    }
                },
                update = {
                    if (camera == null) {
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            // 設定預覽為 4:3 比例
                            val preview = Preview.Builder()
                                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                .build()
                                .also {
                                    it.setSurfaceProvider(previewView?.surfaceProvider)
                                }

                            // 設定拍照為 4:3 比例 + 最高品質
                            val imageCaptureBuilder = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setTargetAspectRatio(AspectRatio.RATIO_4_3)

                            // 關閉 Pixel 的過度後製：降噪、銳化、色調映射
                            val ext = Camera2Interop.Extender(imageCaptureBuilder)
                            ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                            ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            ext.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)

                            imageCapture = imageCaptureBuilder.build()

                            try {
                                // 建立 LUT 色彩效果，綁定到預覽和拍照
                                val lutEffect = com.lutcam.app.camera.lut.LutCameraEffect(lutProcessor)

                                val useCaseGroup = UseCaseGroup.Builder()
                                    .addUseCase(preview)
                                    .addUseCase(imageCapture!!)
                                    .addEffect(lutEffect)
                                    .build()

                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    useCaseGroup
                                )
                                
                                // 獲取硬體支援的極限曝光補償範圍
                                camera?.cameraInfo?.exposureState?.let { exposureState ->
                                    val range = exposureState.exposureCompensationRange
                                    exposureRange = range.lower.toFloat()..range.upper.toFloat()
                                    exposureIndex = exposureState.exposureCompensationIndex.toFloat()
                                }

                            } catch (exc: Exception) {
                                exc.printStackTrace()
                            }
                        }, cameraExecutor)
                    }
                }
            )

            // === 2. 對焦黃框 + 垂直曝光控制 ===
            AnimatedVisibility(
                visible = isFocusUIVisible,
                exit = fadeOut(animationSpec = tween(500)),
                modifier = Modifier.fillMaxSize()
            ) {
                focusPoint?.let { point ->
                    val density = LocalDensity.current.density
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 對焦環 (黃金外框)
                        Canvas(
                            modifier = Modifier
                                .size(72.dp)
                                .offset(
                                    x = (point.x / density).dp - 36.dp,
                                    y = (point.y / density).dp - 36.dp
                                )
                        ) {
                            drawCircle(
                                color = Color(0xFFFFCC00),
                                radius = size.minDimension / 2,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                        
                        // 垂直曝光補償控制 (仿 iPhone 太陽圖示 + 拖曳調整)
                        if (exposureRange.endInclusive > exposureRange.start) {
                            val controlHeight = 160.dp
                            val controlHeightPx = with(LocalDensity.current) { controlHeight.toPx() }
                            val totalRange = exposureRange.endInclusive - exposureRange.start

                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = (point.x / density).dp + 52.dp,
                                        y = (point.y / density).dp - 80.dp
                                    )
                                    .width(40.dp)
                                    .height(controlHeight)
                                    .pointerInput(exposureRange) {
                                        detectVerticalDragGestures { _, dragAmount ->
                                            // 向上拖曳增加曝光，向下減少
                                            val sensitivity = totalRange / controlHeightPx
                                            val newValue = (exposureIndex - dragAmount * sensitivity)
                                                .coerceIn(exposureRange.start, exposureRange.endInclusive)
                                            exposureIndex = newValue
                                            camera?.cameraControl?.setExposureCompensationIndex(newValue.toInt())
                                            isFocusUIVisible = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // 軌道線
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val centerX = size.width / 2
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.3f),
                                        start = Offset(centerX, 16.dp.toPx()),
                                        end = Offset(centerX, size.height - 16.dp.toPx()),
                                        strokeWidth = 2.dp.toPx()
                                    )
                                }
                                
                                // 太陽圖示 (表示亮度) 隨曝光值上下移動
                                val normalizedPosition = if (totalRange > 0f) {
                                    1f - (exposureIndex - exposureRange.start) / totalRange
                                } else { 0.5f }
                                val sunOffsetY = (normalizedPosition - 0.5f) * (controlHeightPx - with(LocalDensity.current) { 32.dp.toPx() })

                                Text(
                                    text = "☀",
                                    fontSize = 22.sp,
                                    color = Color(0xFFFFCC00),
                                    modifier = Modifier
                                        .offset(y = with(LocalDensity.current) { (sunOffsetY / density).dp })
                                )
                            }
                        }
                    }
                }
            }
        } // End of 4:3 preview area

        // === 3. 底部選單控制區 ===
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(bottom = 48.dp, top = 24.dp)
        ) {
            // 左側：LUT 檔案匯入按鈕
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 32.dp)
                    .size(56.dp)
                    .background(Color.DarkGray.copy(alpha = 0.5f), CircleShape)
                    .clickable {
                        launcher.launch(arrayOf("*/*"))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📂",
                    fontSize = 24.sp
                )
            }

            // 置中：快門大按鈕
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(4.dp)
                    .background(Color.White, CircleShape)
                    .clickable {
                        val captureOpt = imageCapture ?: return@clickable
                        
                        val name = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(System.currentTimeMillis())
                        
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "LutCam_$name.jpg")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LutCam")
                            }
                        }

                        val outputOptions = ImageCapture.OutputFileOptions
                            .Builder(
                                context.contentResolver,
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            )
                            .build()

                        captureOpt.takePicture(
                            outputOptions,
                            cameraExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    android.widget.Toast.makeText(context, "照片已儲存至 LutCam 相簿", android.widget.Toast.LENGTH_SHORT).show()
                                }

                                override fun onError(exc: ImageCaptureException) {
                                    android.widget.Toast.makeText(context, "儲存失敗: ${exc.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
            )
        }
    }
}
