package app.morphe.patches.viutv.pip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.viutv.shared.Constants.COMPATIBILITY_VIUTV
import app.morphe.util.indexOfFirstInstructionOrThrow

/**
 * ReactExoplayerViewManager.createViewInstance(ThemedReactContext) 嘅 fingerprint。
 *
 * 由 ViuTV 2.17.0 嘅 classes.dex 抽出嚟嘅真實 method（唔係估）：
 *
 *   createViewInstance(ThemedReactContext)Lcom/brentvatne/exoplayer/ReactExoplayerView;
 *       sget-object v0, Lcom/brentvatne/react/ReactNativeVideoManager$Companion;
 *       invoke-virtual {v0}, getInstance()Lcom/brentvatne/react/ReactNativeVideoManager;
 *       move-result-object v0
 *       invoke-virtual {v0, v2}, ReactNativeVideoManager;->registerView(Ljava/lang/Object;)V
 *       new-instance v0, Lcom/brentvatne/exoplayer/ReactExoplayerView;
 *       iget-object v1, v2, ReactExoplayerViewManager;->config
 *       invoke-direct {v0, v3, v1}, ReactExoplayerView;-><init>(
 *           Lcom/facebook/react/uimanager/ThemedReactContext;
 *           Lcom/brentvatne/exoplayer/ReactExoplayerConfig;)V
 *       return-object v0
 *
 * 註：上面嘅 method descriptor 喺 DEX 入面係無空格嘅
 * （(Lcom/facebook/react/uimanager/ThemedReactContext;Lcom/brentvatne/exoplayer/ReactExoplayerConfig;)V）；
 * androguard 顯示嗰陣會喺參數之間加空格，唔好照抄嗰個格式。
 *
 * 點解要 hook 呢個 method（唔係直接 patch 個 setter）：
 * - ReactExoplayerView.setEnterPictureInPictureOnLeave(Z) 係一個 @ReactProp setter，
 *   只有 JS 喺 <Video> component 度 set 咗 enterPictureInPictureOnLeave 呢個 prop，
 *   React Native bridge 先會 call 佢。但 ViuTV 嘅 JS bundle（assets/index.android.bundle）
 *   從來無 set 過呢個 prop（0 命中），所以個 setter 永遠唔會行——patch 佢係無用嘅。
 * - createViewInstance 每次起一個新嘅播放器 view 就會行一次，係一定會行嘅地方。
 * - 呢個 field 係雙路開關：player init 嗰陣 lambda$initializePlayer$7 會讀佢再 call
 *   PictureInPictureUtil.applyAutoEnterEnabled（API 31+ 自動入 PiP）；API 31 以下，
 *   onUserLeaveHint 嗰條路（addLifecycleEventListener$lambda$1）一樣係讀呢個 field。
 *   所以 set 咗佢一次，兩條路都通。
 */
private object CreateViewInstanceFingerprint : Fingerprint(
    definingClass = "Lcom/brentvatne/exoplayer/ReactExoplayerViewManager;",
    returnType = "Lcom/brentvatne/exoplayer/ReactExoplayerView;",
    parameters = listOf("Lcom/facebook/react/uimanager/ThemedReactContext;"),
    filters = listOf(
        methodCall(
            "Lcom/brentvatne/react/ReactNativeVideoManager\$Companion;->getInstance()Lcom/brentvatne/react/ReactNativeVideoManager;"
        ),
        methodCall(
            "Lcom/brentvatne/react/ReactNativeVideoManager;->registerView(Ljava/lang/Object;)V"
        ),
        methodCall(
            "Lcom/brentvatne/exoplayer/ReactExoplayerView;-><init>(Lcom/facebook/react/uimanager/ThemedReactContext;Lcom/brentvatne/exoplayer/ReactExoplayerConfig;)V"
        ),
    )
)

/**
 * Patch 2/2 — Bytecode：強制開 enterPictureInPictureOnLeave。
 *
 * 做法：喺 createViewInstance 入面，ReactExoplayerView.<init> 嗰個
 * invoke-direct 之後（return-object v0 之前）插入兩行 smali：
 *
 *   const/4 v1, 0x1
 *   invoke-virtual {v0, v1}, ReactExoplayerView;->setEnterPictureInPictureOnLeave(Z)V
 *
 * Register 安全分析（已核實）：
 * - 呢個 method 一共用 4 個 register：v0–v3（v2 = this，v3 = ThemedReactContext）。
 * - 插入點嗰陣：v0 係啱啱 new 完、init 完嘅 ReactExoplayerView；v1 之前裝住 config，
 *   constructor call 之後已經唔再用，可以安全借嚟做常數 1。
 * - 唔使加新 register，唔會影響 return-object v0。
 *
 * 效果：每個新起嘅播放器 view，一出世個 enterPictureInPictureOnLeave field 就係 true。
 * 撳 Home / 離開 app 嗰陣，react-native-video 嘅 PiP 機制就會自動入 PiP。
 * （前提係 Patch 1 嘅 manifest 已經加咗 supportsPictureInPicture，
 *  否則 PictureInPictureUtil.checkIsSystemSupportPIP() 會因為讀唔到 manifest flag
 *  而 return false，成條路都係唔通。）
 *
 * 唔改嘅嘢：唔掂 onHostPause() 嘅 pause 邏輯——入咗 PiP 之後 isInPictureInPictureMode()
 * 係 true，onHostPause() 嗰個「唔係 PiP 先 pause」嘅條件本身就會放行，唔使改。
 * 亦唔掂任何 DRM（Widevine）code——DRM 同 PiP 無關。
 */
val forcePictureInPictureOnLeavePatch = bytecodePatch(
    name = "Force Picture-in-Picture on leave",
    description = "Forces the video player to enter Picture-in-Picture when leaving the app " +
        "by enabling enterPictureInPictureOnLeave on every created player view " +
        "(the app's JavaScript never sets this prop).",
) {
    compatibleWith(COMPATIBILITY_VIUTV)

    execute {
        CreateViewInstanceFingerprint.method.apply {
            // 搵 ReactExoplayerView.<init> 嗰個 invoke-direct 嘅位置，
            // 喺佢後面（initIndex + 1，即係 return-object 之前）插入。
            val initIndex = indexOfFirstInstructionOrThrow(
                methodCall(
                    "Lcom/brentvatne/exoplayer/ReactExoplayerView;-><init>(Lcom/facebook/react/uimanager/ThemedReactContext;Lcom/brentvatne/exoplayer/ReactExoplayerConfig;)V"
                )
            )

            addInstructions(
                initIndex + 1,
                """
                    const/4 v1, 0x1
                    invoke-virtual {v0, v1}, Lcom/brentvatne/exoplayer/ReactExoplayerView;->setEnterPictureInPictureOnLeave(Z)V
                """
            )
        }
    }
}
