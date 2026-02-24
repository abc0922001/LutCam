package com.lutcam.app.camera.lut

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL 工具集：負責 Shader 編譯、Program 建立、3D LUT 紋理上傳、全螢幕繪製。
 * 這是整套 GPU 硬體加速 LUT 色彩引擎的核心運算模組。
 */
object LutGlUtils {
    private const val TAG = "LutGlUtils"

    // ─── Shaders ─────────────────────────────────────────────

    // 頂點著色器：將畫面貼到全螢幕，並傳遞紋理座標
    // 注意：OES 紋理的 Y 軸可能跟 OpenGL 相反，所以用 uTexMatrix 修正
    const val VERTEX_SHADER = """
        #version 300 es
        in vec4 aPosition;
        in vec4 aTextureCoord;
        uniform mat4 uTexMatrix;
        out vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = (uTexMatrix * aTextureCoord).xy;
        }
    """

    // 片段著色器：從相機讀取 OES 紋理，用 RGB 值查詢 3D LUT，輸出轉換後的色彩
    const val FRAGMENT_SHADER_LUT = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;

        in vec2 vTextureCoord;
        out vec4 outColor;

        uniform samplerExternalOES sCameraTexture;
        uniform mediump sampler3D sLutTexture;
        uniform float uLutIntensity; // 0.0 = 原始色彩, 1.0 = 完整 LUT

        void main() {
            vec4 cam = texture(sCameraTexture, vTextureCoord);
            vec3 lutColor = texture(sLutTexture, cam.rgb).rgb;
            // 在原始色彩和 LUT 色彩之間做線性內插，讓使用者未來可以調整強度
            outColor = vec4(mix(cam.rgb, lutColor, uLutIntensity), cam.a);
        }
    """

    // Pass-through 片段著色器：不套 LUT 時直接輸出原始色彩
    const val FRAGMENT_SHADER_PASSTHROUGH = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;

        in vec2 vTextureCoord;
        out vec4 outColor;

        uniform samplerExternalOES sCameraTexture;

        void main() {
            outColor = texture(sCameraTexture, vTextureCoord);
        }
    """

    // ─── 全螢幕四邊形頂點 ────────────────────────────────────

    // 兩個三角形拼成一個覆蓋全螢幕的矩形 (NDC 座標)
    private val FULL_QUAD_COORDS = floatArrayOf(
        -1f, -1f,   // 左下
         1f, -1f,   // 右下
        -1f,  1f,   // 左上
         1f,  1f    // 右上
    )

    // 紋理座標 (對應上面的四個頂點)
    private val FULL_QUAD_TEX_COORDS = floatArrayOf(
        0f, 0f,     // 左下
        1f, 0f,     // 右下
        0f, 1f,     // 左上
        1f, 1f      // 右上
    )

    private val vertexBuffer: FloatBuffer = createFloatBuffer(FULL_QUAD_COORDS)
    private val texCoordBuffer: FloatBuffer = createFloatBuffer(FULL_QUAD_TEX_COORDS)

    // ─── Shader 工具方法 ──────────────────────────────────────

    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader 編譯失敗 (type=$type): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * 建立完整的 Shader Program (頂點 + 片段)
     */
    fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)
        if (vertexShader == 0 || fragmentShader == 0) return 0

        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program 連結失敗: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }

        // Shader 已經連結到 Program，可以安全釋放
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    // ─── OES 紋理 (接收相機影像) ──────────────────────────────

    /**
     * 建立一個 OES External 紋理 (專門接收 Android 相機/影片的硬體影像)
     */
    fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    // ─── 3D LUT 紋理上傳 ─────────────────────────────────────

    /**
     * 將解析好的 LUT 資料上傳到 GPU 成為 3D 紋理
     * @param lut 解析後的 Lut3D 物件 (包含 size 和 RGB float data)
     * @return OpenGL 紋理 ID
     */
    fun upload3DLutTexture(lut: Lut3D): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textures[0])

        // 線性內插讓 LUT 中間值平滑過渡 (這是由 GPU 硬體加速的三線性插值)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        // 將 FloatArray 轉換成 GPU 可用的 ByteBuffer (RGBA 格式, 每通道 16-bit half-float)
        // 使用 GL_RGB + GL_FLOAT 直接上傳更精確
        val buffer = createFloatBuffer(lut.data)

        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,                      // mipmap level
            GLES30.GL_RGB16F,       // 內部格式：半精度浮點 (足夠色彩精度)
            lut.size,               // width
            lut.size,               // height
            lut.size,               // depth
            0,                      // border
            GLES30.GL_RGB,          // 外部格式
            GLES30.GL_FLOAT,        // 資料型別
            buffer                  // 像素資料
        )

        Log.d(TAG, "3D LUT 紋理上傳成功: size=${lut.size}, texId=${textures[0]}")
        return textures[0]
    }

    // ─── 全螢幕繪製 ──────────────────────────────────────────

    /**
     * 使用指定的 Program 把全螢幕四邊形畫出來 (觸發 Fragment Shader 執行 LUT 轉換)
     * @param program Shader Program ID
     * @param oesTextureId 相機 OES 紋理 ID
     * @param lutTextureId 3D LUT 紋理 ID (0 = 不套 LUT)
     * @param texMatrix SurfaceTexture 的 transform matrix (修正座標方向)
     * @param viewportWidth 輸出寬度
     * @param viewportHeight 輸出高度
     */
    fun drawFrame(
        program: Int,
        oesTextureId: Int,
        lutTextureId: Int,
        texMatrix: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glUseProgram(program)

        // 綁定頂點座標
        val aPosition = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)

        // 綁定紋理座標
        val aTextureCoord = GLES30.glGetAttribLocation(program, "aTextureCoord")
        GLES30.glEnableVertexAttribArray(aTextureCoord)
        GLES30.glVertexAttribPointer(aTextureCoord, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        // 傳入紋理座標變換矩陣
        val uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        // 綁定相機 OES 紋理到紋理單元 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "sCameraTexture"), 0)

        // 如果有 LUT 紋理，綁定到紋理單元 1
        if (lutTextureId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "sLutTexture"), 1)

            val uLutIntensity = GLES30.glGetUniformLocation(program, "uLutIntensity")
            GLES30.glUniform1f(uLutIntensity, 1.0f) // 完整套用 LUT
        }

        // 畫兩個三角形 = 一個全螢幕矩形
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTextureCoord)
    }

    // ─── 工具 ────────────────────────────────────────────────

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .apply { position(0) }
    }
}
