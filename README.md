# LutCam 📸

**LutCam** 是一款專為追求真實畫質與專業色彩管理的攝影愛好者所打造的 Android 相機應用程式。我們將手機攝影的主控權交還給攝影師，移除過度強烈的 AI 運算，並允許直接載入專業電影級 3D LUT，在取景畫面上實現**所見即所得**的真實純淨影像。

首要完美支援：**Google Pixel 10 Pro XL**

## ✨ 核心特色 (v1.0)

- **🚫 拒絕過度運算**：從底層 CameraX API 強制關閉手機廠商預設的降噪 (Noise Reduction)、邊緣銳化 (Edge Sharpening) 以及強制多幀合成 (HDR Tonemap)，還原感光元件的真實光影。
- **🎨 專業 3D LUT 色彩引擎**：
  - 支援 `.cube` 檔案直接匯入 (支援 33x33x33 網格精度，如各種 Fujifilm 底片模擬)。
  - 搭載客製化 OpenGL ES 3.0 圖像著色器 (Shader)，將 LUT 色彩直接實時綁定至預覽畫面與最終成像，真正做到零延遲的所見即所得。
- **🕹️ 極簡操作體驗**：
  - 點擊螢幕任意處：啟動精準觸控對焦（經典黃金對焦環）。
  - 對焦鎖定後，即時呼出縱向曝光補償 (Exposure) 滑桿，調亮度直覺滑順。
  - 三秒無操作，介面元件自動淡出，保留最純淨的觀景窗。
  - 單鍵直出帶有 LUT 的高畫質 JPEG 原檔。

## 🛠 技術規格

- **語言**：Kotlin (Android 14 / API 34)
- **UI 架構**：Jetpack Compose 
- **相機核心與管線**：AndroidX CameraX 1.3+ / Camera2Interop
- **底層圖形渲染**：OpenGL ES 3.0 / EGL14 / GLSL Shader
- **自動化部署**：內建 GitHub Actions CI，每次 Push 至 `main` 分支將自動編譯 Debug 與 Release APK。

## 🚀 安裝與編譯

1. **從 GitHub Actions 取得（建議）**
   您可以進入此專案的 [Actions](https://github.com/abc0922001/LutCam/actions) 頁面，下載最新一次建置成功的 `app-debug.apk`，並直接安裝於您的 Android 手機上。

2. **本機編譯**
   ```bash
   git clone https://github.com/abc0922001/LutCam.git
   cd LutCam
   ./gradlew assembleDebug
   ```

## 📂 如何匯入專屬的 LUT

1. 將您的 `.cube` 檔案（例如：`GFX100II_FLog_FGamut_to_ETERNA.cube`）放入手機的儲存空間（下載資料夾等）。
2. 開啟 **LutCam**，點擊左下角的「📂 (資料夾)」圖示。
3. 在系統檔案選擇器中，選擇您的 `.cube` 檔。
4. 匯入完成後，畫面上會出現成功提示，並立即為您的相機預覽套用全新的專業色彩！

---
*LutCam is created to bring the joy of pure photography back to mobile devices.*
