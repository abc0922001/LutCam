package com.lutcam.app.camera.lut

import android.graphics.Bitmap

/**
 * LutBitmapProcessor：用 CPU 將 3D LUT 套用到 Bitmap 上。
 * 用於拍照後處理（因為 CameraX SurfaceProcessor 不支援 IMAGE_CAPTURE）。
 *
 * 原理：對每個像素的 RGB 值在 3D LUT 中做三線性插值 (trilinear interpolation)，
 * 得到轉換後的顏色。一張 4000x3000 的照片大約需要 200-500ms。
 */
object LutBitmapProcessor {

    /**
     * 將 LUT 套用到 Bitmap，回傳新的 Bitmap。
     */
    fun applyLut(source: Bitmap, lut: Lut3D): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val size = lut.size
        val data = lut.data
        val maxIndex = size - 1
        val scale = maxIndex.toFloat()

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xFF

            // 正規化 RGB 到 0.0 ~ maxIndex
            val rf = ((pixel shr 16) and 0xFF) / 255f * scale
            val gf = ((pixel shr 8) and 0xFF) / 255f * scale
            val bf = (pixel and 0xFF) / 255f * scale

            // 下界和上界索引
            val r0 = rf.toInt().coerceIn(0, maxIndex - 1)
            val g0 = gf.toInt().coerceIn(0, maxIndex - 1)
            val b0 = bf.toInt().coerceIn(0, maxIndex - 1)
            val r1 = r0 + 1
            val g1 = g0 + 1
            val b1 = b0 + 1

            // 插值比例
            val rd = rf - r0
            val gd = gf - g0
            val bd = bf - b0

            // 三線性插值 (trilinear interpolation)
            // 從 LUT 讀取 8 個角點
            val c000 = lutLookup(data, size, r0, g0, b0)
            val c100 = lutLookup(data, size, r1, g0, b0)
            val c010 = lutLookup(data, size, r0, g1, b0)
            val c110 = lutLookup(data, size, r1, g1, b0)
            val c001 = lutLookup(data, size, r0, g0, b1)
            val c101 = lutLookup(data, size, r1, g0, b1)
            val c011 = lutLookup(data, size, r0, g1, b1)
            val c111 = lutLookup(data, size, r1, g1, b1)

            // 沿 R 軸插值
            val c00r = lerp3(c000, c100, rd)
            val c01r = lerp3(c001, c101, rd)
            val c10r = lerp3(c010, c110, rd)
            val c11r = lerp3(c011, c111, rd)

            // 沿 G 軸插值
            val c0r = lerp3(c00r, c10r, gd)
            val c1r = lerp3(c01r, c11r, gd)

            // 沿 B 軸插值
            val result = lerp3(c0r, c1r, bd)

            // 轉回像素
            val nr = (result[0] * 255f).toInt().coerceIn(0, 255)
            val ng = (result[1] * 255f).toInt().coerceIn(0, 255)
            val nb = (result[2] * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * 從 LUT FloatArray 中讀取 (r, g, b) 位置的顏色。
     * data 排列為 [b][g][r][channel]，每個顏色 3 個 float (R, G, B)。
     */
    private fun lutLookup(data: FloatArray, size: Int, r: Int, g: Int, b: Int): FloatArray {
        val idx = (b * size * size + g * size + r) * 3
        return floatArrayOf(data[idx], data[idx + 1], data[idx + 2])
    }

    /** 三個通道的線性插值 */
    private fun lerp3(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        return floatArrayOf(
            a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t
        )
    }
}
