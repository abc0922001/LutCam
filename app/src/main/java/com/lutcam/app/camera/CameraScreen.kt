package com.lutcam.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = ContextCompat.getMainExecutor(context)

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = androidx.camera.view.PreviewView(ctx).apply {
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                }

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    // 設定預覽
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    // 設定 ImageCapture (拍照) - 我們在這裡介入 Camera2 底層 API
                    val imageCaptureBuilder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

                    // 核心：關閉廠商的最佳化 (Pixel 的強制 HDR、降噪、邊緣銳化)
                    val ext = Camera2Interop.Extender(imageCaptureBuilder)
                    ext.setCaptureRequestOption(
                        CaptureRequest.NOISE_REDUCTION_MODE, 
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    )
                    ext.setCaptureRequestOption(
                        CaptureRequest.EDGE_MODE, 
                        CaptureRequest.EDGE_MODE_OFF
                    )
                    ext.setCaptureRequestOption(
                        CaptureRequest.TONEMAP_MODE, 
                        CaptureRequest.TONEMAP_MODE_FAST
                    )
                    // 如果有光學防手震，可以預期保留，但我們關閉了軟體干預

                    val imageCapture = imageCaptureBuilder.build()

                    try {
                        // 移除先前的綁定，並綁定 Lifecycle
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        // 處理相機綁定失敗
                    }
                }, cameraExecutor)

                previewView
            }
        )
    }
}
