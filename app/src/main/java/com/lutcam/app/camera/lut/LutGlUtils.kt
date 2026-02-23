package com.lutcam.app.camera.lut

import android.opengl.GLES30
import android.util.Log

object LutGlUtils {
    // 頂點著色器: 負責將畫面貼到全螢幕，並傳遞紋理座標 (Texture Coordinates)
    const val VERTEX_SHADER = """
        #version 300 es
        in vec4 aPosition;
        in vec4 aTextureCoord;
        out vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord.xy;
        }
    """

    // 片段著色器: 負責讀取相機外部紋理，並利用其 R,G,B 值當作 3D LUT 的座標，轉換成新色彩
    const val FRAGMENT_SHADER = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        
        in vec2 vTextureCoord;
        out vec4 outColor;
        
        // sCameraTexture 負責接收 CameraX 傳來的即時影像 (OES 類型)
        uniform samplerExternalOES sCameraTexture;
        
        // sLutTexture 負責存放我們解析出來的 3D LUT 資料 (立體尋找表)
        uniform highp sampler3D sLutTexture;

        void main() {
            vec4 cameraColor = texture(sCameraTexture, vTextureCoord);
            
            // 使用相機的 rgb 值作為 3D 紋理的取樣座標
            // 此處會利用 GPU 硬體加速，瞬間跑完所有的色彩空間轉換
            vec3 lutColor = texture(sLutTexture, cameraColor.rgb).rgb;
            
            // 輸出套用 LUT 後的結果，並保留原始透明度
            outColor = vec4(lutColor, cameraColor.a);
        }
    """

    // 編譯著色器 (Shader) 程式
    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("LutGlUtils", "無法編譯 Shader 類型 $type: ")
            Log.e("LutGlUtils", GLES30.glGetShaderInfoLog(shader))
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
