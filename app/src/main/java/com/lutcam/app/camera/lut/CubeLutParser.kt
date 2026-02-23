package com.lutcam.app.camera.lut

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class Lut3D(
    val size: Int,
    val data: FloatArray // RGB 數值依序排列 (r, g, b, r, g, b...)
)

object CubeLutParser {
    private const val TAG = "CubeLutParser"

    fun parse(context: Context, uri: Uri): Lut3D? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        return parseStream(inputStream)
    }

    fun parseStream(inputStream: InputStream): Lut3D? {
        var size = 0
        val dataList = mutableListOf<Float>()

        try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    // 忽略註解與空行
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine

                    if (trimmed.startsWith("LUT_3D_SIZE")) {
                        size = trimmed.substringAfter("LUT_3D_SIZE").trim().toInt()
                    } else if (trimmed.startsWith("TITLE") || trimmed.startsWith("DOMAIN_MIN") || trimmed.startsWith("DOMAIN_MAX")) {
                        // 目前先忽略這些中介資料 (Metadata)
                    } else {
                        // 資料行應包含三個由空白分隔的浮點數：R, G, B
                        val rgb = trimmed.split("\\s+".toRegex())
                        if (rgb.size >= 3) {
                            try {
                                dataList.add(rgb[0].toFloat())
                                dataList.add(rgb[1].toFloat())
                                dataList.add(rgb[2].toFloat())
                            } catch (e: NumberFormatException) {
                                // 非浮點數行，跳過
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 LUT 檔案時發生錯誤", e)
            return null
        }

        // 驗證解析出的總資料量是否等於 size * size * size * 3 (RGB 三通道)
        if (size > 0 && dataList.size == size * size * size * 3) {
            Log.d(TAG, "成功解析 LUT: 尺寸 $size, 資料點 ${dataList.size / 3}")
            return Lut3D(size, dataList.toFloatArray())
        } else {
            Log.e(TAG, "無效的 LUT 資料量。預期 ${size * size * size * 3} 個浮點數，實際讀取到 ${dataList.size} 個")
            return null
        }
    }
}
