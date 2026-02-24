package com.lutcam.app.camera.lut

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest

/**
 * LutSurfaceProcessor：LutCam 的核心渲染引擎。
 *
 * 工作原理：
 * 1. 建立一個 OES 紋理 + SurfaceTexture 當作「攔截畫布」，交給 CameraX
 * 2. CameraX 把每一幀相機影像渲染到這個攔截畫布
 * 3. 當新的一幀到達時 (onFrameAvailable)，我們在 GPU 上跑 Fragment Shader：
 *    - 讀取 OES 紋理（相機原始畫面）
 *    - 用 RGB 值去查詢 3D LUT 紋理（色彩轉換表）
 *    - 把轉換後的色彩畫到所有 CameraX 的輸出 Surface (預覽 + 拍照)
 * 4. 整個過程完全在 GPU 硬體上運行，零 CPU 開銷，零延遲。
 */
class LutSurfaceProcessor : SurfaceProcessor {
    companion object {
        private const val TAG = "LutSurfaceProcessor"
    }

    // 獨立的 GL 執行緒，避免卡住主 UI
    private val glThread = HandlerThread("LutRenderThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    // OpenGL 環境
    private var eglCore: EglCore? = null
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    // Shader Programs
    private var lutProgram = 0          // 有 LUT 時使用
    private var passthroughProgram = 0  // 無 LUT 時直接透傳

    // 3D LUT 紋理
    private var lutTextureId = 0
    @Volatile private var pendingLut: Lut3D? = null  // 來自 UI 線程的 LUT 資料

    // 輸出管理
    private data class OutputInfo(
        val eglSurface: EGLSurface,
        val surface: Surface,
        val surfaceOutput: SurfaceOutput,
        val size: Size
    )
    private val outputs = mutableListOf<OutputInfo>()

    // 輸入尺寸
    private var inputWidth = 0
    private var inputHeight = 0

    /**
     * 從 UI 線程呼叫：設定要套用的 LUT 資料
     * LUT 會在下一幀渲染時上傳到 GPU
     */
    fun setLut(lut: Lut3D?) {
        pendingLut = lut
        if (lut == null) {
            // 清除 LUT：在 GL 線程上釋放紋理
            glHandler.post {
                if (lutTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                    lutTextureId = 0
                    Log.d(TAG, "LUT 紋理已清除")
                }
            }
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            // === 初始化 OpenGL 環境 ===
            if (eglCore == null) {
                eglCore = EglCore()
            }
            val core = eglCore ?: return@post

            // 需要一個臨時的 PBuffer surface 來執行初始化 GL 指令
            core.bindDefaultContext()

            // 建立 OES 紋理 (用來接收相機影像)
            oesTextureId = LutGlUtils.createOesTexture()

            // 編譯 Shader Programs
            lutProgram = LutGlUtils.createProgram(LutGlUtils.VERTEX_SHADER, LutGlUtils.FRAGMENT_SHADER_LUT)
            passthroughProgram = LutGlUtils.createProgram(LutGlUtils.VERTEX_SHADER, LutGlUtils.FRAGMENT_SHADER_PASSTHROUGH)

            if (lutProgram == 0 || passthroughProgram == 0) {
                Log.e(TAG, "Shader Program 建立失敗!")
                request.willNotProvideSurface()
                return@post
            }

            // 取得輸入影像尺寸
            inputWidth = request.resolution.width
            inputHeight = request.resolution.height

            // 建立 SurfaceTexture (GPU 上的虛擬畫布)
            surfaceTexture = SurfaceTexture(oesTextureId).apply {
                setDefaultBufferSize(inputWidth, inputHeight)

                // 當相機畫出新的一幀時觸發渲染
                setOnFrameAvailableListener({ st ->
                    glHandler.post { renderFrame(st) }
                }, glHandler)
            }

            // 封裝成 Android Surface，交給 CameraX
            inputSurface = Surface(surfaceTexture)

            // 提供 Surface 給 CameraX (這是關鍵：CameraX 會把影像畫到這裡)
            request.provideSurface(inputSurface!!, { it.run() }) { result ->
                // Surface 被回收時清理資源
                glHandler.post {
                    inputSurface?.release()
                    surfaceTexture?.release()
                    inputSurface = null
                    surfaceTexture = null
                    Log.d(TAG, "Input surface 已釋放")
                }
            }

            Log.d(TAG, "Input surface 已建立: ${inputWidth}x${inputHeight}")
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            val core = eglCore ?: return@post

            // 取得 CameraX 提供的輸出 Surface，並包裝成 EGLSurface
            val outputSurface = surfaceOutput.getSurface({ it.run() }) {
                // 當 CameraX 要回收此輸出時
                glHandler.post {
                    val info = outputs.find { o -> o.surfaceOutput == surfaceOutput }
                    if (info != null) {
                        core.destroySurface(info.eglSurface)
                        outputs.remove(info)
                        Log.d(TAG, "Output surface 已釋放，剩餘 ${outputs.size} 個")
                    }
                    surfaceOutput.close()
                }
            }

            val eglSurface = core.createWindowSurface(outputSurface)
            val size = surfaceOutput.size
            outputs.add(OutputInfo(eglSurface, outputSurface, surfaceOutput, size))
            Log.d(TAG, "Output surface 已註冊: ${size.width}x${size.height}，共 ${outputs.size} 個")
        }
    }

    /**
     * 核心渲染方法：每當相機產生新的一幀時被呼叫
     */
    private fun renderFrame(st: SurfaceTexture) {
        val core = eglCore ?: return
        if (outputs.isEmpty()) return

        // 拉取最新的相機影像到 OES 紋理
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage 失敗: ${e.message}")
            return
        }

        // 檢查是否有新的 LUT 等待上傳
        val newLut = pendingLut
        if (newLut != null) {
            pendingLut = null
            // 先刪除舊的 LUT 紋理
            if (lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            }
            // 上傳新的 3D LUT 紋理到 GPU
            lutTextureId = LutGlUtils.upload3DLutTexture(newLut)
            Log.d(TAG, "新的 LUT 已上傳至 GPU: size=${newLut.size}")
        }

        // 選擇使用哪個 Shader Program
        val program = if (lutTextureId != 0) lutProgram else passthroughProgram

        // 對每個輸出 Surface 繪製一次 (通常是：預覽 + 拍照)
        val outputsCopy = outputs.toList() // 複製避免 concurrent modification
        for (output in outputsCopy) {
            try {
                core.makeCurrent(output.eglSurface)

                LutGlUtils.drawFrame(
                    program = program,
                    oesTextureId = oesTextureId,
                    lutTextureId = lutTextureId,
                    texMatrix = texMatrix,
                    viewportWidth = output.size.width,
                    viewportHeight = output.size.height
                )

                core.swapBuffers(output.eglSurface)
            } catch (e: Exception) {
                Log.w(TAG, "渲染到 output 失敗: ${e.message}")
            }
        }
    }

    /**
     * 釋放所有 GPU 資源
     */
    fun release() {
        glHandler.post {
            if (lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = 0
            }
            if (oesTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (lutProgram != 0) {
                GLES30.glDeleteProgram(lutProgram)
                lutProgram = 0
            }
            if (passthroughProgram != 0) {
                GLES30.glDeleteProgram(passthroughProgram)
                passthroughProgram = 0
            }

            inputSurface?.release()
            surfaceTexture?.release()
            inputSurface = null
            surfaceTexture = null

            val core = eglCore
            for (o in outputs) {
                core?.destroySurface(o.eglSurface)
            }
            outputs.clear()

            core?.release()
            eglCore = null

            Log.d(TAG, "所有 GPU 資源已釋放")
        }
        glThread.quitSafely()
    }
}
