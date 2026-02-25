package com.lutcam.app.camera.lut

import android.util.Log
import androidx.camera.core.CameraEffect

/**
 * LutCameraEffect：套用在 PREVIEW（即時預覽）。
 * 拍照的 LUT 套用改由 LutBitmapProcessor 在拍照後進行軟體處理。
 */
class LutCameraEffect(
    processor: LutSurfaceProcessor
) : CameraEffect(
    PREVIEW,                         // 只套用在預覽
    processor.glExecutor,
    processor,
    { Log.e("LutCameraEffect", "CameraEffect 錯誤", it) }
)
