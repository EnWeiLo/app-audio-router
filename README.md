# App 音效分流測試（AppAudioRouter）

用 Shizuku 測試「讓單一 App 的聲音走指定輸出裝置」，類似三星的「隔離應用程式音效」。

## 原理

在 Shizuku 裡（shell 身分）建立一個系統音訊規則（AudioPolicy）：
只比對選定 App 的 UID，把它的聲音輸出到指定裝置，其他 App 照系統預設走。
這需要 `MODIFY_AUDIO_ROUTING` 權限，能不能成功取決於手機的 shell 有沒有這個權限。

## 編譯

推到 GitHub 的 `main` 分支後，GitHub Actions 會自動編譯，
APK 會出現在 Releases（`build-N`）和 Actions 的 Artifacts 裡。

## 使用

1. 打開 Shizuku，確認服務正在執行
2. 開本 App → 「連線 Shizuku」→ 允許授權
3. 「① 檢查權限」→ 看結論
4. 選 App、選輸出裝置 →「② 套用分流」
5. 被分流的 App 如果正在播放，暫停再播放一次讓設定生效
6. 「全部還原」可以取消所有分流

### 小提醒

- 藍牙連上時，系統預設所有聲音都走藍牙。
  所以最常見的用法是把**某一個 App 拉回「手機喇叭」**，其他的繼續走藍牙。
- 關掉本 App 分流仍然有效（服務留在 Shizuku 裡）；重開機或 Shizuku 停止後會失效。
- 自我測試：App 選「★ 本 App」，再按「播放測試音」，聽嗶聲從哪裡出來。
