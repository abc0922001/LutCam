package com.lutcam.app.camera.lut

import androidx.camera.core.CameraEffect

/**
 * LutCameraEffect 宣告這個濾鏡會同時套用在 PREVIEW (預覽) 與 IMAGE_CAPTURE (拍照)。
 * 因此你看到的 LUT 顏色，跟最後存下來的 JPEG 檔案的顏色會是一模一樣的。
 */
class LutCameraEffect(processor: LutSurfaceProcessor) : CameraEffect(
    IMAGE_CAPTURE or PREVIEW,
    { runnable -> runnable.run() },
    processor,
    { throwable -> 
        throwable.printStackTrace() 
    }
)
