package com.lutcam.app.camera.lut

import android.util.Log
import androidx.camera.core.CameraEffect

/**
 * LutCameraEffect 宣告這個濾鏡套用在 PREVIEW（即時預覽）。
 * 使用 processor.glExecutor 確保所有 Surface 回呼在 GL 線程上執行。
 */
class LutCameraEffect(
    processor: LutSurfaceProcessor
) : CameraEffect(
    PREVIEW,                         // 只套用在預覽（IMAGE_CAPTURE 在 CameraX 1.3 不完整支援 SurfaceProcessor）
    processor.glExecutor,            // 在 GL 線程上執行回呼
    processor,                       // GPU 渲染器
    { Log.e("LutCameraEffect", "CameraEffect 錯誤", it) }
)
