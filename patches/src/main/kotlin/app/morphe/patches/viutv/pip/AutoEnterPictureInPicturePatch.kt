package app.morphe.patches.viutv.pip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.viutv.shared.Constants.COMPATIBILITY_VIUTV
import app.morphe.util.indexOfFirstInstructionOrThrow

/**
 * Patch 3 — Bytecode：JWPlayer 自動入 PiP（v2，取代 Patch 2 嘅做法）。
 *
 * 背景（已核實，見 viutv-pip-analysis.md）：
 * ViuTV 播片用嘅係 JWPlayer（com.viutv.ReactJWPlayerView / JWPlayerView），
 * 唔係 react-native-video。JS bundle 入面 react-native-video 係 0 命中，
 * 所以 Patch 2（ForcePictureInPictureOnLeave）patch 嗰個 ReactExoplayerView
 * 從來唔會行——manifest 嗰半（Patch 1）係啱嘅，系統權限已經顯示 PiP 允許，
 * 但撳 Home 嗰陣根本無任何 code 會 call enterPictureInPictureMode。
 *
 * JWPlayer 自己有個 PiP 控制器（longtailvideo.jwplayer.m.b），唯一入口係
 * 播放器 UI 上面個 PiP icon（onClickedPipIcon -> d()），但 ViuTV 嗰個 UI
 * 唔顯示呢個掣，兼無自動入 PiP 嘅路。開返個掣要喺混淆咗嘅 JWPlayer SDK
 * code 入面改 LiveData 條鏈，fingerprint 脆弱；自動入 PiP 嗰邊就淨係喺
 * ViuTV 自己嘅 ReactJWPlayerView（無混淆）做三個細 hook，穩陣得多。
 *
 * 做法：Android 12+（API 31）嘅 setAutoEnterEnabled。播緊片嗰陣將
 * auto-enter 開埋，系統喺用戶撳 Home 嗰陣自動將個 activity 縮入 PiP，
 * 唔使喺 onUserLeaveHint / onPause 嗰啲 timing 敏感嘅位 call
 * enterPictureInPictureMode。
 *
 * 三個 hook，全部喺 ReactJWPlayerView：
 *
 * 1) onPlay(PlayEvent) —— 播片開始，開 auto-enter：
 *    喺 VideoEventEmitter.playing() 之後插入，SDK >= 31 先做：
 *      activity.setPictureInPictureParams(
 *        PictureInPictureParams.Builder()
 *          .setAutoEnterEnabled(true)
 *          .setAspectRatio(16:9)
 *          .build())
 *    Register 分析（已核實）：
 *    - 呢個 method 一共用 6 個 register：v0–v3 係 locals（playing() 之後全部
 *      已經死晒，可以借），v4 = this，v5 = PlayEvent 參數（playing() 之後
 *      已經死晒，可以借嚟裝常數 9）。
 *    - 插入點嗰陣：v4 係 this，v5 係 event，兩個都唔再用。
 *
 * 2) setPlay(Z) —— 收到 pause（false），閂 auto-enter：
 *    喺 method 開頭插入，SDK >= 31 先做：
 *      activity.setPictureInPictureParams(
 *        Builder().setAutoEnterEnabled(false).build())
 *    目的：pause 咗、或者返去瀏覽頁之後再撳 Home，唔應該彈個 PiP 出嚟
 *    （會變咗一個細窗顯示暫停畫面 / 瀏覽頁）。
 *    Register 分析（已核實）：
 *    - 呢個 method 一共用 4 個 register：v0–v1 係 locals，v2 = this，
 *      v3 = boolean 參數。
 *    - 插入點嗰陣：v3 係 false（淨係呢個分支先會清 auto-enter），
 *      之後原 code 讀 v3 都係 0，寫返同一個值係安全嘅。
 *    - setPlay(false) 嘅 caller 包括 onHostPause（離開 app）同 JS 嗰邊
 *      嘅 pause；onHostPause 嗰條路喺入咗 PiP 嗰陣會由 hook 3 攔截，
 *      唔會行到呢度，所以唔會喺 PiP 入面誤閂 auto-enter。
 *
 * 3) onHostPause() —— 入咗 PiP 嗰陣唔好 pause：
 *    呢個係 v2 第二個要解決嘅問題（未核實嗰陣已經估到，寫 patch 嗰陣核實咗）：
 *    onHostPause() 無條件 call setPlay(false)，即係直接 player.pause()。
 *    就算 auto-enter 成功入咗 PiP，onHostPause 跟住就會停播。
 *    喺 method 開頭插入，SDK >= 26 先做：
 *      if (activity.isInPictureInPictureMode()) return;
 *    唔係 PiP 就照行原本嘅 setPlay(false)。
 *    Register 分析（已核實）：
 *    - 呢個 method 一共用 3 個 register：v0–v1 係 locals，v2 = this。
 *    - 插入點嗰陣：v0、v1 未用過，可以安全借用。
 *
 * 唔改嘅嘢：
 * - 唔掂 JWPlayer SDK 內部（m.b / isPipIconVisible / CenterControlsView），
 *   唔掂任何 DRM（Widevine）code，唔掂 JS bundle。
 * - Patch 2 留喺度（對一個 JS 從來唔用嘅 view set flag，係 inert 嘅，
 *   唔會有副作用）；Patch 1（manifest）繼續係必要條件。
 * - minSdk 係 24，所以三個 hook 全部有 SDK_INT 檢查，API 24–25 嘅機
 *   完全唔行呢啲 code，唔會 crash。
 *
 * 仲未核實、要實機測先知嘅嘢：
 * - JWPlayer 嘅事件 callback 係咪行喺 main thread（setPictureInPictureParams
 *   要 main thread；JWPlayer Android SDK 慣例係 main thread 派事件）。
 * - 播完片（CompleteEvent）之後 auto-enter 仲開住，撳 Home 會唔會彈個
 *   已完結畫面嘅 PiP（setPlay(false) 嗰個 clear 未必 cover 到完結嗰條路）。
 */
private object ReactJWPlayerViewOnPlayFingerprint : Fingerprint(
    definingClass = "Lcom/viutv/ReactJWPlayerView;",
    name = "onPlay",
    returnType = "V",
    parameters = listOf("Lcom/jwplayer/pub/api/events/PlayEvent;"),
    filters = listOf(
        methodCall(
            "Lcom/jwplayer/pub/api/JWPlayer;->getCurrentAudioTrack()I"
        ),
        methodCall(
            "Lcom/viutv/VideoEventEmitter;->playing()V"
        ),
    )
)

private object ReactJWPlayerViewOnHostPauseFingerprint : Fingerprint(
    definingClass = "Lcom/viutv/ReactJWPlayerView;",
    name = "onHostPause",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            "Lcom/viutv/ReactJWPlayerView;->setPlay(Z)V"
        ),
    )
)

private object ReactJWPlayerViewSetPlayFingerprint : Fingerprint(
    definingClass = "Lcom/viutv/ReactJWPlayerView;",
    name = "setPlay",
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(
            "Lcom/jwplayer/pub/api/JWPlayer;->play()V"
        ),
        methodCall(
            "Lcom/jwplayer/pub/api/JWPlayer;->pause()V"
        ),
    )
)

val autoEnterPictureInPicturePatch = bytecodePatch(
    name = "Auto-enter Picture-in-Picture (JWPlayer)",
    description = "Makes the JWPlayer enter Picture-in-Picture automatically when " +
        "leaving the app while a video is playing (Android 12+ auto-enter), " +
        "and keeps playback running while in Picture-in-Picture.",
) {
    compatibleWith(COMPATIBILITY_VIUTV)

    execute {
        // Hook 1: onPlay -> 開 auto-enter（16:9）。
        ReactJWPlayerViewOnPlayFingerprint.method.apply {
            val playingIndex = indexOfFirstInstructionOrThrow(
                methodCall(
                    "Lcom/viutv/VideoEventEmitter;->playing()V"
                )
            )

            addInstructionsWithLabels(
                playingIndex + 1,
                """
                    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v1, 0x1f
                    if-lt v0, v1, :pip_done
                    iget-object v0, v4, Lcom/viutv/ReactJWPlayerView;->reactContext:Lcom/facebook/react/bridge/ReactContext;
                    invoke-virtual {v0}, Lcom/facebook/react/bridge/ReactContext;->getCurrentActivity()Landroid/app/Activity;
                    move-result-object v0
                    if-eqz v0, :pip_done
                    new-instance v1, Landroid/app/PictureInPictureParams${'$'}Builder;
                    invoke-direct {v1}, Landroid/app/PictureInPictureParams${'$'}Builder;-><init>()V
                    const/4 v2, 0x1
                    invoke-virtual {v1, v2}, Landroid/app/PictureInPictureParams${'$'}Builder;->setAutoEnterEnabled(Z)Landroid/app/PictureInPictureParams${'$'}Builder;
                    move-result-object v1
                    new-instance v2, Landroid/util/Rational;
                    const/16 v3, 0x10
                    const/16 v5, 0x9
                    invoke-direct {v2, v3, v5}, Landroid/util/Rational;-><init>(II)V
                    invoke-virtual {v1, v2}, Landroid/app/PictureInPictureParams${'$'}Builder;->setAspectRatio(Landroid/util/Rational;)Landroid/app/PictureInPictureParams${'$'}Builder;
                    move-result-object v1
                    invoke-virtual {v1}, Landroid/app/PictureInPictureParams${'$'}Builder;->build()Landroid/app/PictureInPictureParams;
                    move-result-object v1
                    invoke-virtual {v0, v1}, Landroid/app/Activity;->setPictureInPictureParams(Landroid/app/PictureInPictureParams;)V
                    :pip_done
                """
            )
        }

        // Hook 2: setPlay(false) -> 閂 auto-enter。
        ReactJWPlayerViewSetPlayFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    if-nez v3, :pip_clear
                    goto :pip_orig
                    :pip_clear
                    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v1, 0x1f
                    if-lt v0, v1, :pip_orig
                    iget-object v0, v2, Lcom/viutv/ReactJWPlayerView;->reactContext:Lcom/facebook/react/bridge/ReactContext;
                    invoke-virtual {v0}, Lcom/facebook/react/bridge/ReactContext;->getCurrentActivity()Landroid/app/Activity;
                    move-result-object v0
                    if-eqz v0, :pip_orig
                    new-instance v1, Landroid/app/PictureInPictureParams${'$'}Builder;
                    invoke-direct {v1}, Landroid/app/PictureInPictureParams${'$'}Builder;-><init>()V
                    const/4 v3, 0x0
                    invoke-virtual {v1, v3}, Landroid/app/PictureInPictureParams${'$'}Builder;->setAutoEnterEnabled(Z)Landroid/app/PictureInPictureParams${'$'}Builder;
                    move-result-object v1
                    invoke-virtual {v1}, Landroid/app/PictureInPictureParams${'$'}Builder;->build()Landroid/app/PictureInPictureParams;
                    move-result-object v1
                    invoke-virtual {v0, v1}, Landroid/app/Activity;->setPictureInPictureParams(Landroid/app/PictureInPictureParams;)V
                    :pip_orig
                """
            )
        }

        // Hook 3: onHostPause -> 入咗 PiP 就唔 pause。
        ReactJWPlayerViewOnHostPauseFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v1, 0x1a
                    if-lt v0, v1, :pip_orig
                    iget-object v0, v2, Lcom/viutv/ReactJWPlayerView;->reactContext:Lcom/facebook/react/bridge/ReactContext;
                    invoke-virtual {v0}, Lcom/facebook/react/bridge/ReactContext;->getCurrentActivity()Landroid/app/Activity;
                    move-result-object v0
                    if-eqz v0, :pip_orig
                    invoke-virtual {v0}, Landroid/app/Activity;->isInPictureInPictureMode()Z
                    move-result v1
                    if-eqz v1, :pip_orig
                    return-void
                    :pip_orig
                """
            )
        }
    }
}
