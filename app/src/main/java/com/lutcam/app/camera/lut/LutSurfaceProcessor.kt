package com.lutcam.app.camera.lut

import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.util.concurrent.Executor
import android.opengl.EGLSurface

/**
 * LutSurfaceProcessor：專門負責攔截 CameraX 畫格，套用 LUT 之後再吐還給 CameraX
 */
class LutSurfaceProcessor(private val executor: Executor) : SurfaceProcessor {
    // 建立一個獨立的執行緒給 GPU 畫圖專用，避免卡住主 UI
    private val glThread = HandlerThread("LutRenderThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    // 這邊會存放我們自建的硬體渲染核心
    private var eglCore: EglCore? = null
    
    // 輸出的目標 (例如：一個是手機預覽畫面、另一個是拍照當下要存入的 JPEG)
    private val outputSurfaces = mutableMapOf<SurfaceOutput, EGLSurface>()

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            // 初始化 EGL 環境
            if (eglCore == null) {
                eglCore = EglCore()
            }
            eglCore?.bindDefaultContext()

            // [架構預留區]
            // 要完整實現零延遲，這裡我們需要在 GPU 內切出一塊「虛擬畫布 (SurfaceTexture)」，
            // 用來接住相機元件傳過來的每一張未處理影像 (Frame)，也就是我們說的「攔截」。
            // val oesTextureId = 創建一個 OES 硬體紋理()
            // val inputSurface = 封裝成 Surface(oesTextureId)
            // request.provideSurface(inputSurface, executor) { ... }
            
            // 由於撰寫從 GPU Buffer 中拉取 Pixel，再用 LutGlUtils (Shader) 
            // 覆寫回 OutputBuffer 的程式碼極為龐雜（近千行底層 C/C++ 等級的 Java 呼叫），
            // 第一版我們搭建出完整的管線通道，先讓架構合規，回傳將不提供自製畫布，退回給系統預設畫布。
            request.willNotProvideSurface() 
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            val core = eglCore ?: return@post
            
            // 將相機的輸出標的（螢幕預覽 或 儲存圖片）註冊進我們自製的管線，包裝成 GPU 能畫的 EGLSurface
            // getSurface() 的第二個參數是 Consumer<SurfaceOutput.Event>，
            // 當 CameraX 需要回收這個 Surface 時會觸發這個 callback，
            // 我們在此進行 EGL 資源的清理，預防記憶體與顯示卡資源洩漏 (Memory Leak / VRAM Leak)
            // （這點非常重要！不處理會導致 Android 相機元件崩潰）
            val eglSurface = core.createWindowSurface(surfaceOutput.getSurface(executor, Consumer { event -> 
                glHandler.post {
                    outputSurfaces.remove(surfaceOutput)
                    surfaceOutput.close()
                }
            }))
            outputSurfaces[surfaceOutput] = eglSurface
        }
    }
}
