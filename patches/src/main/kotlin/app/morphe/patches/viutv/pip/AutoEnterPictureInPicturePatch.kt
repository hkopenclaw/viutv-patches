package app.morphe.patches.viutv.pip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.viutv.shared.Constants.COMPATIBILITY_VIUTV
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

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
 * 三個 hook（原本四個，Hook 2 喺 v2.2 刪除咗，見下面），全部喺 ReactJWPlayerView：
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
 * 2) setPlay(Z) —— 已刪除（v2.2）。
 *    原本：收到 pause（false）就閂 auto-enter，目的係 pause 咗、或者返去
 *    瀏覽頁之後再撳 Home，唔應該彈個 PiP 出嚟。
 *    刪除原因（bisect 實證，2026-11-11）：manifest-only 版播得返；
 *    bisect 2（無 Hook 1，留 Hook 2／3／4）都係停；bisect 3（無 Hook 1／2，
 *    留 Hook 3／4）播得返。Hook 1 同 Hook 3／4 都清白，兇手就係 Hook 2——
 *    佢喺每次 pause 嗰陣 call setPictureInPictureParams，唔知咩機制搞到
 *    返嚟嗰陣串流停埋。機制未解到，所以唔係改寫佢，係直接刪咗佢。
 *    已知代價：播片停咗／完咗之後 auto-enter 仲係開住，呢個時候撳 Home
 *    會入 PiP（播緊嗰陣就正常）。要清 auto-enter 嘅話，要搵個唔喺 pause
 *    嗰條路上面嘅觸發點，日後再做。
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
 * 寫法注意（2026-11-10 Build #54 撞過）：
 * addInstructionsWithLabels 要求每個 label 後面喺同一個插入 block 入面
 * 至少要有一行 instruction；label 喺 block 最尾、後面乜都無，就會
 * IndexOutOfBoundsException（length 0）成個 patch fail。所以下面啲
 * hook 嘅 :pip_done / :pip_orig 後面全部有一個 nop 墊底，唔好刪。
 * （2026-11-11 Build #56 撞過）const/4 只裝到 -8 至 7，GONE（8）
 * 一定要用 const/16，寫 const/4 v1, 0x8 會 parse 失敗，成個 patch
 * 跌埋。VISIBLE（0）用 const/4 無問題。
 *
 * 唔改嘅嘢：
 * - 唔掂 JWPlayer SDK 內部（m.b / isPipIconVisible / CenterControlsView），
 *   唔掂任何 DRM（Widevine）code，唔掂 JS bundle。
 * - Patch 2 留喺度（對一個 JS 從來唔用嘅 view set flag，係 inert 嘅，
 *   唔會有副作用）；Patch 1（manifest）繼續係必要條件。
 * - minSdk 係 24，所以啲 hook 全部有 SDK_INT 檢查，API 24–25 嘅機
 *   完全唔行呢啲 code，唔會 crash。
 *
 * 已知限制（Hook 2 刪除之後）：
 * - 播完片（CompleteEvent）之後 auto-enter 仲開住，撳 Home 會彈個
 *   已完結畫面嘅 PiP。Hook 2 刪除之前，setPlay(false) 嗰個 clear 未必
 *   cover 到完結嗰條路；刪除之後更加唔會清。要清 auto-enter 嘅話，要搵
 *   個唔喺 pause 嗰條路上面嘅觸發點，日後再做。
 *
 * 仲未核實、要實機測先知嘅嘢：
 * - JWPlayer 嘅事件 callback 係咪行喺 main thread（setPictureInPictureParams
 *   要 main thread；JWPlayer Android SDK 慣例係 main thread 派事件）。
 *
 * v2.1（2026-11-11，用戶實測 v2 嗰版之後）：
 * v2 入到 PiP，但有兩個問題：
 * 1) 非全螢幕入 PiP，PiP 窗入面連下面嗰行功能 bar（主頁／節目／一齊睇／
 *    節目表／我的）都包埋——因為 setPictureInPictureParams 淨係得
 *    aspect ratio，系統將成個 activity 內容縮入個窗。修正：加埋
 *    setSourceRectHint，用播放器 view 喺螢幕上面嘅實際範圍做 rect，
 *    PiP 窗淨係顯示片嗰忽（同 JWPlayer 自己 m.b.d() 嘅做法一樣）。
 *    問題係 onPlay 得 6 個 register，計 rect 要 7 個值同時在生，
 *    唔夠位。所以將成個 set params 搬去一個新加嘅 helper method
 *    morpheUpdatePipParams(Activity)，佢有自己嘅 register set，
 *    onPlay 淨係負責拎 activity 再 call 佢。
 * 2) PiP 返去正常／全螢幕，串流畫面停咗，要出去入番先得。
 *    onHostResume 本身已經有 setPlay(true)，但如果個 player 喺
 *    PLAYING 狀態，play() 係 no-op，畫面凍結（surface／layout 未
 *    refresh）係唔會靠佢解到。修正：onHostResume 之後加多句
 *    forceRefreshPlayerLayout()——ViuTV 自己嘅 helper（200ms 後
 *    invalidate + requestLayout），本來就係廣告播完／mid-roll／
 *    鍵盤彈出呢啲 layout 變咗之後用嚟 refresh 個 player view。
 *    每次 resume 都行一次，layout 本來啱嘅話係無視覺變化嘅。
 *    仲未分到凍結嗰陣聲音有無繼續（surface 問題定係真係 pause 咗）；
 *    如果 v2.1 之後仲凍，就要問埋呢樣再查。
 *    （2026-11-11 01:02 用戶覆：無聲，成個串流停埋——即係唔係淨係
 *    surface 凍，係個 player 真係停咗。見下面問題 2 嘅跟進。）
 * 3) 入 PiP 嗰刻畫面上面嗰啲功能制（從頭開始／切換頻道／全螢幕嗰啲）
 *    如果仲顯示緊，入咗 PiP 之後唔會自己消失（要捉住佢哋自動收起
 *    咗嗰一刻先入先乾淨）。原因：JWPlayer 嗰個 controls auto-hide
 *    計時器喺 activity pause 嗰陣會停，入咗 PiP 之後唔會再行，所以
 *    啲制凍住喺度。修正：入 PiP 嗰陣（hook 3 嗰條路）直接將成個
 *    ControlsContainerView 隱藏（setVisibility GONE）。
 *    用嘅係 JWPlayer 公開 API（JWPlayerView.getControlsContainer）
 *    加 View.setVisibility，唔使掂混淆咗嘅 controls controller。
 *    （v2.3：原本返出嚟嗰陣（hook 4）會再將啲制 setVisibility(VISIBLE)
 *    顯示返，但實測原版 Full Screen 啲制 3–4 秒會 auto-hide，patched
 *    版唔識收起，即係我個 patch 搞到，而最可疑就係呢句 VISIBLE——
 *    詳見 hook 4 嗰段註解。所以 v2.3 刪咗佢；出 PiP 嗰陣啲制會繼續
 *    隱藏住，要撳一下畫面先再顯示返。）
 *
 * 問題 2 跟進（2026-11-11 01:02–02:45，已解決）：
 * 用戶話返出嚟係無聲、成個串流停埋。對過 code：
 * - onDetachedFromWindow 只係 call onStopPlayback（淨係熄 keepScreenOn，
 *   唔會停 player），唔係兇手。
 * - 最初懷疑 releasePlayer()：pause + stop + 用空 config 重新 setup +
 *   player = null + 拆走 playerView。行完之後 setPlay(true) 會因為 player
 *   係 null 而直接 return，撳播放都救唔返——同用戶「要重新出去入番先得」
 *   吻合。但 pip-exit.log 入面完全無 stopPlayback／releasePlayer／
 *   onHostDestroy／onDetachedFromWindow，即係 player 根本無被釋放，
 *   呢個理論錯咗，已經收回。
 * - Bisect 實證（2026-11-11 02:12–02:44）：manifest-only 版（v1.1.4）
 *   播得返 → manifest 無辜；bisect 2（v1.1.5，無 Hook 1，留 Hook 2／3／4）
 *   都係停 → Hook 1 無辜；bisect 3（v1.1.6，無 Hook 1／2，留 Hook 3／4）
 *   播得返 → 兇手就係 Hook 2（setPlay(false) 嗰陣 call
 *   setPictureInPictureParams 清 auto-enter）。機制未解到——靜態對過
 *   DEX，原有 code 逐行同 stock 一樣，jump target 同 register 都啱，
 *   但 runtime 就係搞到返嚟嗰陣串流停埋。
 * - v2.2（2026-11-11）：刪除 Hook 2，還原 Hook 1（連 helper）、Hook 3、
 *   Hook 4，Patch 2 繼續停用。已知代價見上面「已知限制」。
 */
/**
 * 喺 ReactJWPlayerView 加一個 helper method：
 *   public morpheUpdatePipParams(Activity activity)
 *
 * 做晒成個 setPictureInPictureParams：auto-enter on、16:9、再加埋
 * source rect hint（呢個 view 喺螢幕上面嘅實際範圍，等 PiP 窗淨係
 * 顯示片嗰忽，唔包下面嗰行功能 bar）。
 *
 * 點解要獨立一個 method（唔直接寫喺 onPlay）：onPlay 得 6 個 register，
 * 計 source rect 要 activity、builder、left、top、right、bottom、
 * Rect object 七個值同時在生，唔夠位。呢個 method 自己有 10 個
 * register（v0–v7 locals + p0 this + p1 activity），夠用。
 * （加 method 嘅寫法抄 ref 嘅 PlaylistTrackTapPatch.addInterfaceMethod；
 * 注意 ImmutableMethod 嘅 registerCount 係 locals + params 嘅總數。）
 *
 * Register 用法（已核實）：
 * - v0：SDK_INT → Builder → Params（一路轉手，唔會同時要兩個值）。
 * - v1：31 → Rational → int[2] array → width → right。
 * - v2：16 → left。v3：9 → top。v4：height → bottom。v5：Rect。
 * - v6、v7：aget 嗰陣嘅 index 常數（0／1）。
 * - p0 = this（ReactJWPlayerView），p1 = activity。
 */
private fun addMorpheUpdatePipParamsMethod() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").methods.add(
        ImmutableMethod(
            "Lcom/viutv/ReactJWPlayerView;",
            "morpheUpdatePipParams",
            listOf(ImmutableMethodParameter("Landroid/app/Activity;", null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(10),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v1, 0x1f
                    if-lt v0, v1, :done
                    if-eqz p1, :done
                    new-instance v0, Landroid/app/PictureInPictureParams${'$'}Builder;
                    invoke-direct {v0}, Landroid/app/PictureInPictureParams${'$'}Builder;-><init>()V
                    const/4 v1, 0x1
                    invoke-virtual {v0, v1}, Landroid/app/PictureInPictureParams${'$'}Builder;->setAutoEnterEnabled(Z)Landroid/app/PictureInPictureParams${'$'}Builder;
                    move-result-object v0
                    new-instance v1, Landroid/util/Rational;
                    const/16 v2, 0x10
                    const/16 v3, 0x9
                    invoke-direct {v1, v2, v3}, Landroid/util/Rational;-><init>(II)V
                    invoke-virtual {v0, v1}, Landroid/app/PictureInPictureParams${'$'}Builder;->setAspectRatio(Landroid/util/Rational;)Landroid/app/PictureInPictureParams${'$'}Builder;
                    move-result-object v0
                    const/4 v1, 0x2
                    new-array v1, v1, [I
                    invoke-virtual {p0, v1}, Landroid/view/View;->getLocationOnScreen([I)V
                    const/4 v6, 0x0
                    aget v2, v1, v6
                    const/4 v6, 0x1
                    aget v3, v1, v6
                    invoke-virtual {p0}, Landroid/view/View;->getWidth()I
                    move-result v1
                    add-int v1, v2, v1
                    invoke-virtual {p0}, Landroid/view/View;->getHeight()I
                    move-result v4
                    add-int v4, v3, v4
                    new-instance v5, Landroid/graphics/Rect;
                    invoke-direct {v5, v2, v3, v1, v4}, Landroid/graphics/Rect;-><init>(IIII)V
                    invoke-virtual {v0, v5}, Landroid/app/PictureInPictureParams${'$'}Builder;->setSourceRectHint(Landroid/graphics/Rect;)Landroid/app/PictureInPictureParams${'$'}Builder;
                    move-result-object v0
                    invoke-virtual {v0}, Landroid/app/PictureInPictureParams${'$'}Builder;->build()Landroid/app/PictureInPictureParams;
                    move-result-object v0
                    invoke-virtual {p1, v0}, Landroid/app/Activity;->setPictureInPictureParams(Landroid/app/PictureInPictureParams;)V
                    :done
                    return-void
                """
            )
        }
    )
}

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

private object ReactJWPlayerViewOnHostResumeFingerprint : Fingerprint(
    definingClass = "Lcom/viutv/ReactJWPlayerView;",
    name = "onHostResume",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            "Lcom/viutv/ReactJWPlayerView;->setPlay(Z)V"
        ),
        // 注意：唔好加 LifecycleRegistry.setCurrentState 做 filter。
        // 2026-11-11 Build #56 嗰陣，加咗呢個 filter（入面有 Lifecycle$State）
        // 之後成個 fingerprint match 唔到。名 + class + signature
        // 已經唯一識別到呢個 method，setPlay 一個 filter 就夠。
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
        // 先加 helper method（Hook 1 會 call 佢）。
        addMorpheUpdatePipParamsMethod()

        // Hook 1: onPlay -> 開 auto-enter（經 helper：16:9 + source rect）。
        // Register 分析（已核實）：呢個 method 一共用 6 個 register，
        // v4 = this，v0 借嚟裝 activity；call 完 helper 就唔再用 v0。
        ReactJWPlayerViewOnPlayFingerprint.method.apply {
            val playingIndex = indexOfFirstInstructionOrThrow(
                methodCall(
                    "Lcom/viutv/VideoEventEmitter;->playing()V"
                )
            )

            addInstructionsWithLabels(
                playingIndex + 1,
                """
                    iget-object v0, v4, Lcom/viutv/ReactJWPlayerView;->reactContext:Lcom/facebook/react/bridge/ReactContext;
                    invoke-virtual {v0}, Lcom/facebook/react/bridge/ReactContext;->getCurrentActivity()Landroid/app/Activity;
                    move-result-object v0
                    if-eqz v0, :pip_done
                    invoke-virtual {v4, v0}, Lcom/viutv/ReactJWPlayerView;->morpheUpdatePipParams(Landroid/app/Activity;)V
                    :pip_done
                    nop
                """
            )
        }

        // Hook 2（setPlay(false) -> 閂 auto-enter）已刪除，唔係停用。
        // Bisect 實證（2026-11-11）：manifest-only 版播得返；
        // bisect 2（無 Hook 1，留 Hook 2／3／4）都係停；bisect 3
        // （無 Hook 1／2，留 Hook 3／4）播得返。Hook 1 同 Hook 3／4
        // 都清白，兇手就係 Hook 2——佢喺每次 pause 嗰陣 call
        // setPictureInPictureParams，唔知咩機制搞到返嚟嗰陣串流停埋。
        // 機制未解到，所以唔係改寫佢，係直接刪咗佢。
        // 已知代價：播片停咗／完咗之後 auto-enter 仲係開住，
        // 呢個時候撳 Home 會入 PiP（播緊嗰陣就正常）。要清 auto-enter
        // 嘅話，要搵個唔喺 pause 嗰條路上面嘅觸發點，日後再做。

        // Hook 3: onHostPause -> 入咗 PiP 就唔 pause，兼收起啲功能制。
        // 啲制喺 PiP 入面唔會自己消失（auto-hide 計時器喺 pause 嗰陣停
        // 咗），所以入 PiP 嗰陣直接將成個 ControlsContainerView 隱藏。
        // Register 分析（已核實）：呢個 method 一共用 3 個 register，
        // v2 = this；v0 喺 isInPictureInPictureMode 之後已經死晒，可以
        // 借嚟裝 playerView／container，v1 借嚟裝 GONE 常數 8。
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
                    iget-object v0, v2, Lcom/viutv/ReactJWPlayerView;->playerView:Lcom/jwplayer/pub/view/JWPlayerView;
                    if-eqz v0, :pip_hide_done
                    invoke-virtual {v0}, Lcom/jwplayer/pub/view/JWPlayerView;->getControlsContainer()Lcom/jwplayer/ui/views/ControlsContainerView;
                    move-result-object v0
                    if-eqz v0, :pip_hide_done
                    const/16 v1, 0x8
                    invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V
                    :pip_hide_done
                    return-void
                    :pip_orig
                    nop
                """
            )
        }

        // Hook 4: onHostResume -> refresh 個 player view，等 PiP 返出嚟
        // 畫面唔好凍住。
        // forceRefreshPlayerLayout() 係 ViuTV 自己嘅 helper：200ms 後
        // invalidate + requestLayout。每次 resume 都行一次，layout 本來
        // 啱嘅話係無視覺變化嘅。
        // （v2.3 刪咗原本喺度將 ControlsContainerView setVisibility(VISIBLE)
        // 嗰段：實測原版 Full Screen 啲制 3–4 秒會 auto-hide，但 patched
        // 版唔識收起，即係我個 patch 搞到。最可疑就係呢句 VISIBLE——
        // 佢喺每次 resume 嗰陣強制將啲制顯示返，如果佢喺 auto-hide 計時器
        // 收起咗啲制之後先行，啲制就會再彈出嚟而計時器唔會重啟，變相
        // 永遠唔收起。刪咗佢之後，出 PiP 嗰陣啲制會繼續隱藏住，要撳一下
        // 畫面先再顯示返（JWPlayer 撳畫面顯示啲制嗰個機制本身仲喺度）。
        // Register 分析（已核實）：呢個 method 一共用 3 個 register，
        // v2 = this；插入點喺 setPlay(true) 之後。
        ReactJWPlayerViewOnHostResumeFingerprint.method.apply {
            val setPlayIndex = indexOfFirstInstructionOrThrow(
                methodCall(
                    "Lcom/viutv/ReactJWPlayerView;->setPlay(Z)V"
                )
            )

            addInstructionsWithLabels(
                setPlayIndex + 1,
                """
                    invoke-virtual {v2}, Lcom/viutv/ReactJWPlayerView;->forceRefreshPlayerLayout()V
                """
            )
        }
    }
}
