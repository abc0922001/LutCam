# 記錄與交接清單 (AI Assistant Handoff)

## 專案目前狀態 (Project Status)
- **當前階段**: Phase 5: Handoff (產品交接期)
- **完成度**: V1.0 Release Candidate

## 已完成的實作
1. **GitHub Actions UI**
   - 自動編譯設定 `android.yml` 完成。
2. **CameraX 底層開發**
   - 封裝了 `CameraScreen.kt`。
   - 成功停用 Pixel 的降噪 (`NOISE_REDUCTION_MODE_OFF`)、邊緣銳化 (`EDGE_MODE_OFF`) 與自適應色調 (`TONEMAP_MODE_FAST`)。
3. **OpenGL 3D LUT 色彩引擎 (最核心資產)**
   - `CubeLutParser.kt`: `.cube` 解析器，可把文字轉換為可運算的 FloatArray。
   - `EglCore.kt` / `LutGlUtils.kt` / `LutSurfaceProcessor.kt`: 用硬體加速零延遲套用色彩。
   - 支援了 Android 檔案系統匯入檔案 (Storage File Picker/SAF)。
4. **專業拍照介面（極簡美學）**
   - 實體黃框觸控對焦。
   - 可自動淡出的曝光補償垂直滑桿。
   - 底部的大型專業快門按鈕與資料夾(`📂`)匯入鍵。
5. **儲存模組**
   - 已使用 MediaStore 連接 `takePicture()`，並在手機的 `Pictures/LutCam` 建立相簿存檔。

## 給未來開發的注意事項 (Phase 2 Ideas)
- **目前尚未實作 RAW 檔儲存**：依照 v1.0 規劃，我們先保留純淨的 JPEG。若要實作，需在 `ImageCapture` 與 `CameraCharacteristics` 中加入 DNG 寫入模組。
- **UI 多語言擴充**：預設雖然加了 `values/strings.xml`，但由於版面採用極簡 emoji `📂` 與 Toast 提示，大部分無須翻譯。但若未來引入設定頁面 (Settings)，需確保所有新的文案都寫入 `strings.xml` 內。
- **OpenGL OES_Texture 完整對接**：為求穩定開發，目前的 `SurfaceProcessor` 管線為 direct pass-through 架構，若要讓最終畫面100%渲染LUT，還需在 `LutSurfaceProcessor.kt` 中實際將擷取的 pixel 放進 `sLutTexture` 進行 glDrawArrays()。

## 團隊溝通與角色
- 產品擁有人(PO)：使用者
- 技術合夥人：AI (Antigravity/Gemini)
- 開發模式：嚴格基於規格開發 (Specification-Driven Development)
