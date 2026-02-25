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
import java.util.concurrent.Executor

/**
 * LutSurfaceProcessor：LutCam 的核心渲染引擎。
 * 
 * 徹底解決 Lifecycle 黑屏問題的架構：
 * 每次相機給我們新的 SurfaceRequest 時，都創造一組「全新的」 OES 紋理與 SurfaceTexture。
 * 當舊的 Request 被相機關閉時，只會清理舊的那組資源，完全不影響新的畫面。
 */
class LutSurfaceProcessor : SurfaceProcessor {
    companion object {
        private const val TAG = "LutSurfaceProcessor"
    }

    private val glThread = HandlerThread("LutRenderThread").apply { start() }
    private val glHandler = Handler(glThread.looper)
    val glExecutor: Executor = Executor { glHandler.post(it) }

    private var eglCore: EglCore? = null
    private var isGlInitialized = false

    private var lutProgram = 0
    private var passthroughProgram = 0

    // 目前作用中的輸入來源（最新的一幀）
    private var activeOesTextureId = 0
    private var activeSurfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)

    // LUT 資源
    private var lutTextureId = 0
    @Volatile private var pendingLut: Lut3D? = null

    // 輸出目標（預覽畫面等）
    private data class OutputInfo(
        val outputSurface: SurfaceOutput,
        val surface: Surface,
        val eglSurface: EGLSurface,
        val size: Size
    )
    private val outputs = mutableListOf<OutputInfo>()
    private val pendingOutputs = mutableListOf<SurfaceOutput>()

    // 保存目前作用中的 LUT（供拍照後處理使用）
    @Volatile private var currentLut: Lut3D? = null

    fun setLut(lut: Lut3D?) {
        currentLut = lut
        if (lut != null) {
            pendingLut = lut
        } else {
            pendingLut = null
            glHandler.post {
                if (lutTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                    lutTextureId = 0
                    Log.d(TAG, "LUT 已清除")
                }
            }
        }
    }

    /** 取得目前套用中的 LUT（供拍照後軟體處理用） */
    fun getCurrentLut(): Lut3D? = currentLut

    private fun initGl() {
        if (isGlInitialized) return
        try {
            val core = EglCore()
            eglCore = core
            core.makePBufferCurrent()

            lutProgram = LutGlUtils.createProgram(LutGlUtils.VERTEX_SHADER, LutGlUtils.FRAGMENT_SHADER_LUT)
            passthroughProgram = LutGlUtils.createProgram(LutGlUtils.VERTEX_SHADER, LutGlUtils.FRAGMENT_SHADER_PASSTHROUGH)

            isGlInitialized = true
            Log.d(TAG, "GL 初始化成功: lutProg=$lutProgram, passProg=$passthroughProgram")
        } catch (e: Exception) {
            Log.e(TAG, "GL 初始化失敗", e)
            throw e
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        glExecutor.execute {
            try {
                initGl()
                
                // 【關鍵修復】每次相機提供新的輸入，就建立一組「完全獨立」的 OES Texture
                val tex = LutGlUtils.createOesTexture()
                val st = SurfaceTexture(tex)
                st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(st)
                
                // 更新作用中的變數，讓 renderFrame 可以讀取最新影像
                activeOesTextureId = tex
                activeSurfaceTexture = st
                
                st.setOnFrameAvailableListener({ renderFrame() }, glHandler)
                
                // 將 surface 提供給 CameraX
                request.provideSurface(surface, glExecutor) { result ->
                    Log.d(TAG, "舊的 Input session 結束: ${result.resultCode}")
                    surface.release()
                    st.release()
                    GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
                    
                    // 如果被結束的是當前作用中的紋理，就清空
                    if (activeOesTextureId == tex) {
                        activeSurfaceTexture = null
                        activeOesTextureId = 0
                    }
                }
                Log.d(TAG, "新的 Input session 已註冊: TexID=$tex")
                
                // 處理所有在 GL 初始化前就排隊等候的 Output
                while (pendingOutputs.isNotEmpty()) {
                    registerOutputSurface(pendingOutputs.removeAt(0))
                }
            } catch (e: Exception) {
                Log.e(TAG, "onInputSurface 嚴重錯誤", e)
                request.willNotProvideSurface()
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glExecutor.execute {
            if (!isGlInitialized) {
                pendingOutputs.add(surfaceOutput)
                return@execute
            }
            registerOutputSurface(surfaceOutput)
        }
    }

    private fun registerOutputSurface(surfaceOutput: SurfaceOutput) {
        val core = eglCore ?: run {
            surfaceOutput.close()
            return
        }
        try {
            val outSurface = surfaceOutput.getSurface(glExecutor) {
                Log.d(TAG, "Output surface 關閉事件")
                val info = outputs.find { it.outputSurface == surfaceOutput }
                if (info != null) {
                    core.destroySurface(info.eglSurface)
                    outputs.remove(info)
                }
                surfaceOutput.close()
            }
            
            val eglSurface = core.createWindowSurface(outSurface)
            outputs.add(OutputInfo(surfaceOutput, outSurface, eglSurface, surfaceOutput.size))
            Log.d(TAG, "Output 已註冊，目前共有 ${outputs.size} 個輸出目標")
        } catch (e: Exception) {
            Log.e(TAG, "Output 註冊失敗", e)
            surfaceOutput.close()
        }
    }

    private fun renderFrame() {
        val core = eglCore ?: return
        val st = activeSurfaceTexture ?: return
        if (outputs.isEmpty()) return

        try {
            core.makePBufferCurrent()
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            val newLut = pendingLut
            if (newLut != null) {
                pendingLut = null
                if (lutTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                }
                lutTextureId = LutGlUtils.upload3DLutTexture(newLut)
                Log.d(TAG, "LUT 上傳完成 GPU TexID: $lutTextureId")
            }

            val program = if (lutTextureId != 0) lutProgram else passthroughProgram

            for (output in outputs) {
                core.makeCurrent(output.eglSurface)
                LutGlUtils.drawFrame(
                    program = program,
                    oesTextureId = activeOesTextureId,
                    lutTextureId = lutTextureId,
                    texMatrix = texMatrix,
                    viewportWidth = output.size.width,
                    viewportHeight = output.size.height
                )
                core.swapBuffers(output.eglSurface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "renderFrame 畫幀失敗", e)
        }
    }

    fun release() {
        glExecutor.execute {
            if (lutTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            if (activeOesTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(activeOesTextureId), 0)
            if (lutProgram != 0) GLES30.glDeleteProgram(lutProgram)
            if (passthroughProgram != 0) GLES30.glDeleteProgram(passthroughProgram)

            activeSurfaceTexture?.release()

            val core = eglCore
            for (o in outputs) core?.destroySurface(o.eglSurface)
            outputs.clear()
            for (p in pendingOutputs) p.close()
            pendingOutputs.clear()

            core?.release()
            eglCore = null
            isGlInitialized = false
            
            glThread.quitSafely()
        }
    }
}
