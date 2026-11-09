package app.morphe.patches.viutv.pip

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.viutv.shared.Constants.COMPATIBILITY_VIUTV

/**
 * Patch 1/2 — Manifest: 幫 ViuTV 嘅 MainActivity 開返 PiP。
 *
 * 背景（已核實，見 viutv-pip-analysis.md）：
 * ViuTV 係 React Native single-activity app，唯一嘅 app activity 係
 * com.viutv.MainActivity。佢嘅 manifest 原本：
 *   - 無 android:supportsPictureInPicture（預設 false）；
 *   - application 層同 activity 層嘅 android:resizeableActivity 都係 false；
 *   - android:configChanges = 0x6B0（keyboard、keyboardHidden、orientation、
 *     screenSize、uiMode），欠 screenLayout（0x100）同 smallestScreenSize（0x800）。
 *
 * Android 系統只會俾 manifest 宣告咗 supportsPictureInPicture 嘅 activity 入 PiP，
 * 所以呢個 patch 係必要條件——就算下面個 bytecode patch 過咗 library 嗰關，
 * 冇呢個 manifest 宣告，系統都唔會俾佢入 PiP。
 */
val enablePictureInPicturePatch = patch(
    name = "Enable Picture-in-Picture",
    description = "Enables Picture-in-Picture support for the ViuTV player activity " +
        "(adds supportsPictureInPicture and resizeableActivity, " +
        "and adds screenLayout and smallestScreenSize to configChanges).",
) {
    compatibleWith(COMPATIBILITY_VIUTV)

    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            // Application 層：開 resizeableActivity（原本係 false）。
            manifest.elements("application").first()
                .setAttribute("android:resizeableActivity", "true")

            // MainActivity：唯一嘅 app activity。
            val mainActivity = manifest.elementByAttribute(
                "android:name",
                "com.viutv.MainActivity"
            )

            // 1) 宣告支援 PiP —— Android 系統准許入 PiP 嘅必要條件。
            mainActivity.setAttribute("android:supportsPictureInPicture", "true")

            // 2) 准許 resize —— PiP 視窗要 resize 個 activity。
            mainActivity.setAttribute("android:resizeableActivity", "true")

            // 3) configChanges 補返 screenLayout + smallestScreenSize，
            //    保留原有嘅 keyboard、keyboardHidden、orientation、screenSize、uiMode。
            //    （原本 0x6B0；加埋之後係 0xFB0。）
            //    註：orientation 本身已經有，唔使再加。
            mainActivity.setAttribute(
                "android:configChanges",
                "keyboard|keyboardHidden|orientation|screenSize|smallestScreenSize|screenLayout|uiMode"
            )
        }
    }
}
