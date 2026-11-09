package app.morphe.patches.viutv.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_VIUTV = Compatibility(
        name = "ViuTV",
        packageName = "com.hktve.viutv",
        // ViuTV 喺 APKMirror 係以 APKM bundle 形式提供（base + splits），
        // Morphe CLI / magisk-module builder 會自行處理 APKM。
        apkFileType = ApkFileType.APKM,
        // 純粹係 Morphe Manager 介面顯示用嘅 icon 顏色（深藍，近似 ViuTV app icon），
        // 唔影響 patch 功能，可以隨意改。
        appIconColor = 0x1A2B4D,
        signatures = setOf(
            // 由 ViuTV 2.17.0 官方 APK 嘅簽名證書抽出嚟嘅 SHA-256 fingerprint。
            // Subject: Roger Lau, HK Television Entertainment Company Limited。
            // 作用：只准 patch 真正由 ViuTV 官方簽名嘅 APK。
            "98c31e9d4bf53720168b3a789dd26c531fa393055499c8ea494e6b7c5a0be381"
        ),
        targets = listOf(
            AppTarget(
                version = "2.17.0",
                versionCode = 217000397,
                minSdk = 24
            )
        )
    )
}
