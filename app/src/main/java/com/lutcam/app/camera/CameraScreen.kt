package com.lutcam.app.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.view.MotionEvent
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { ContextCompat.getMainExecutor(context) }

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
        // === 1. 相機底層預覽與觸控對接 ===
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
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

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView?.surfaceProvider)
                        }

                        val imageCaptureBuilder = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

                        val ext = Camera2Interop.Extender(imageCaptureBuilder)
                        ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                        ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                        ext.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)

                        imageCapture = imageCaptureBuilder.build()

                        try {
                            val lutProcessor = com.lutcam.app.camera.lut.LutSurfaceProcessor(cameraExecutor)
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

        // === 2. 極簡美學：對焦黃框與亮度滑桿覆蓋層 ===
        AnimatedVisibility(
            visible = isFocusUIVisible,
            exit = fadeOut(animationSpec = tween(500)),
            modifier = Modifier.fillMaxSize()
        ) {
            focusPoint?.let { point ->
                val density = LocalDensity.current.density
                Box(modifier = Modifier.fillMaxSize()) {
                    // 對焦環 (外框)
                    Canvas(
                        modifier = Modifier
                            .size(72.dp)
                            .offset(
                                x = (point.x / density).dp - 36.dp,
                                y = (point.y / density).dp - 36.dp
                            )
                    ) {
                        drawCircle(
                            color = Color(0xFFFFCC00), // 沉穩黃金對焦色
                            radius = size.minDimension / 2,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                    
                    // 曝光補償滑桿 (直立顯示在對焦框右側)
                    if (exposureRange.endInclusive > exposureRange.start) {
                        Box(
                            modifier = Modifier
                                .offset(
                                    x = (point.x / density).dp + 48.dp, // 放在右側 48dp 處
                                    y = (point.y / density).dp - 60.dp  // 同等高度
                                )
                                .width(32.dp)
                                .height(120.dp)
                        ) {
                            Slider(
                                value = exposureIndex,
                                onValueChange = { newVal ->
                                    exposureIndex = newVal
                                    camera?.cameraControl?.setExposureCompensationIndex(newVal.toInt())
                                    isFocusUIVisible = true // 重置自動隱藏計時
                                },
                                valueRange = exposureRange,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFFFFCC00),
                                    activeTrackColor = Color(0xFFFFCC00),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // 原生的 Slider 是水平的，將他逆時針旋轉 90 度變成垂直，上推增加、下推變暗
                                        rotationZ = -90f
                                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                                    }
                            )
                        }
                    }
                }
            }
        }

        // === 3. 底部選單控制區 ===
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 微弱的黑色漸層或半透明背景，突顯純淨感
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(bottom = 48.dp, top = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // 快門大按鈕
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(4.dp) // 預留空間創造雙層圓環感
                    .background(Color.White, CircleShape)
                    .clickable {
                        val captureOpt = imageCapture ?: return@clickable
                        
                        // 建立存檔的檔名與屬性
                        val name = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(System.currentTimeMillis())
                        
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "LutCam_$name.jpg")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LutCam")
                            }
                        }

                        // 設定輸出至 MediaStore (相簿)
                        val outputOptions = ImageCapture.OutputFileOptions
                            .Builder(
                                context.contentResolver,
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                contentValues
                            )
                            .build()

                        // 觸發拍照
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
