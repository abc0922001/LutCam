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
 * 1. 建立 OES 紋理 + SurfaceTexture 當作「攔截畫布」，交給 CameraX
 * 2. CameraX 把每一幀相機影像渲染到這個攔截畫布
 * 3. 每幀到達時 (onFrameAvailable)，在 GPU 上跑 Fragment Shader：
 *    - 讀取 OES 紋理（原始影像）
 *    - 用 RGB 值查詢 3D LUT 紋理（色彩轉換表）
 *    - 畫到所有 CameraX 的輸出 Surface（預覽 + 拍照）
 * 4. 完全在 GPU 硬體上運行，零 CPU 開銷。
 */
class LutSurfaceProcessor : SurfaceProcessor {
    companion object {
        private const val TAG = "LutSurfaceProcessor"
    }

    private val glThread = HandlerThread("LutRenderThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    // OpenGL 環境
    private var eglCore: EglCore? = null
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    // Shader Programs
    private var lutProgram = 0
    private var passthroughProgram = 0
    private var isGlInitialized = false

    // 3D LUT
    private var lutTextureId = 0
    @Volatile private var pendingLut: Lut3D? = null

    // 輸出管理
    private data class OutputInfo(
        val eglSurface: EGLSurface,
        val surface: Surface,
        val surfaceOutput: SurfaceOutput,
        val size: Size
    )
    private val outputs = mutableListOf<OutputInfo>()

    /**
     * 從任何線程呼叫：設定要套用的 LUT 資料
     */
    fun setLut(lut: Lut3D?) {
        if (lut != null) {
            pendingLut = lut
        } else {
            pendingLut = null
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
            try {
                initGl()

                val inputWidth = request.resolution.width
                val inputHeight = request.resolution.height
                Log.d(TAG, "onInputSurface: ${inputWidth}x${inputHeight}")

                // 建立 SurfaceTexture (GPU 上的虛擬畫布接收相機影像)
                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(inputWidth, inputHeight)
                    setOnFrameAvailableListener({ renderFrame() }, glHandler)
                }

                inputSurface = Surface(surfaceTexture)

                // 將 Surface 交給 CameraX（CameraX 會把相機影像畫到這裡）
                request.provideSurface(inputSurface!!, { it.run() }) { result ->
                    Log.d(TAG, "Input surface result: ${result.resultCode}")
                    glHandler.post {
                        inputSurface?.release()
                        surfaceTexture?.release()
                        inputSurface = null
                        surfaceTexture = null
                    }
                }
                Log.d(TAG, "Input surface 已提供給 CameraX")

            } catch (e: Exception) {
                Log.e(TAG, "onInputSurface 初始化失敗", e)
                request.willNotProvideSurface()
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            val core = eglCore ?: run {
                Log.e(TAG, "onOutputSurface: EGL 尚未初始化")
                surfaceOutput.close()
                return@post
            }

            try {
                val outSurface = surfaceOutput.getSurface({ it.run() }) {
                    glHandler.post {
                        val info = outputs.find { o -> o.surfaceOutput == surfaceOutput }
                        if (info != null) {
                            core.destroySurface(info.eglSurface)
                            outputs.remove(info)
                        }
                        surfaceOutput.close()
                        Log.d(TAG, "Output surface 已釋放，剩餘 ${outputs.size} 個")
                    }
                }

                val eglSurface = core.createWindowSurface(outSurface)
                val size = surfaceOutput.size
                outputs.add(OutputInfo(eglSurface, outSurface, surfaceOutput, size))
                Log.d(TAG, "Output surface 已註冊: ${size.width}x${size.height}，共 ${outputs.size} 個")

            } catch (e: Exception) {
                Log.e(TAG, "onOutputSurface 失敗", e)
                surfaceOutput.close()
            }
        }
    }

    /**
     * 初始化 OpenGL 環境（只執行一次）
     */
    private fun initGl() {
        if (isGlInitialized) return

        // 建立 EGL 環境
        val core = EglCore()
        eglCore = core

        // 使用 PBuffer 讓 GL context 生效（關鍵！無此步驟 GL 指令全部無效）
        core.makePBufferCurrent()

        // 建立 OES 紋理（接收相機影像）
        oesTextureId = LutGlUtils.createOesTexture()
        Log.d(TAG, "OES 紋理已建立: id=$oesTextureId")

        // 編譯 Shader Programs
        lutProgram = LutGlUtils.createProgram(LutGlUtils.VERTEX_SHADER, LutGlUtils.FRAGMENT_SHADER_LUT)
        passthroughProgram = LutGlUtils.createProgram(LutGlUtils.VERTEX_SHADER, LutGlUtils.FRAGMENT_SHADER_PASSTHROUGH)

        if (lutProgram == 0 || passthroughProgram == 0) {
            Log.e(TAG, "Shader 編譯失敗! lutProgram=$lutProgram passthroughProgram=$passthroughProgram")
            // 檢查 GL 錯誤
            var err = GLES30.glGetError()
            while (err != GLES30.GL_NO_ERROR) {
                Log.e(TAG, "GL Error: 0x${Integer.toHexString(err)}")
                err = GLES30.glGetError()
            }
        } else {
            Log.d(TAG, "Shader 編譯成功: lutProgram=$lutProgram passthroughProgram=$passthroughProgram")
        }

        isGlInitialized = true
        Log.d(TAG, "OpenGL 初始化完成")
    }

    /**
     * 核心渲染：每當相機產生新的一幀時被呼叫
     */
    private fun renderFrame() {
        val core = eglCore ?: return
        val st = surfaceTexture ?: return

        // 複製一份，避免 concurrent modification
        val currentOutputs = synchronized(outputs) { outputs.toList() }
        if (currentOutputs.isEmpty()) return

        try {
            // 先切到 PBuffer 來更新紋理（需要 valid GL context）
            core.makePBufferCurrent()

            // 拉取最新的相機影像到 OES 紋理
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // 檢查是否有新的 LUT 等待上傳
            val newLut = pendingLut
            if (newLut != null) {
                pendingLut = null
                if (lutTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                }
                lutTextureId = LutGlUtils.upload3DLutTexture(newLut)
                Log.d(TAG, "LUT 已上傳 GPU: size=${newLut.size}, texId=$lutTextureId")
            }

            // 選擇 Shader
            val program = if (lutTextureId != 0) lutProgram else passthroughProgram

            // 繪製到每個輸出 Surface
            for (output in currentOutputs) {
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "renderFrame 失敗", e)
        }
    }

    fun release() {
        glHandler.post {
            if (lutTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            }
            if (oesTextureId != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            }
            if (lutProgram != 0) GLES30.glDeleteProgram(lutProgram)
            if (passthroughProgram != 0) GLES30.glDeleteProgram(passthroughProgram)

            inputSurface?.release()
            surfaceTexture?.release()

            val core = eglCore
            for (o in outputs) {
                core?.destroySurface(o.eglSurface)
            }
            outputs.clear()

            core?.release()
            eglCore = null
            isGlInitialized = false

            Log.d(TAG, "所有 GPU 資源已釋放")
        }
        glThread.quitSafely()
    }
}
