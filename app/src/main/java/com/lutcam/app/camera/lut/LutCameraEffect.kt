package com.lutcam.app.camera.lut

import androidx.camera.core.CameraEffect

/**
 * LutCameraEffect 宣告這個濾鏡會同時套用在 PREVIEW (預覽) 與 IMAGE_CAPTURE (拍照)。
 * 因此你在螢幕上看到的 LUT 色彩，跟最後存下來的 JPEG 檔案的色彩會是一模一樣的。
 * 所見即所得 (WYSIWYG)。
 */
class LutCameraEffect(
    processor: LutSurfaceProcessor
) : CameraEffect(
    PREVIEW or IMAGE_CAPTURE,  // 同時影響預覽和拍照
    { it.run() },              // 在呼叫者的執行緒上執行
    processor,                 // 我們的自製 GPU 渲染器
    { it.printStackTrace() }   // 錯誤處理
)
