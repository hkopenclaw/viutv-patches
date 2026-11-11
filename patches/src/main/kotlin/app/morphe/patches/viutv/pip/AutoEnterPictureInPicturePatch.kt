package app.morphe.patches.viutv.pip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.viutv.shared.Constants.COMPATIBILITY_VIUTV
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableField
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
 *
 * v2.4（2026-11-11，用戶實測 v2.2 之後）：
 * v2.2 入到 PiP、串流 keep 到、Full Screen 啲制 3–4 秒會收起（同原版
 * 一樣），但 PiP 窗仲有兩個問題，令個 PiP 唔係一個睇得嘅 PiP：
 * 1) PiP 窗入面有成行 nav bar（主頁／節目／一齊睇／節目表／我的），
 *    即使 PiP 較到最大都仲阻住 1/3 畫面（2026-11-11 03:34 用戶截圖
 *    實證）。原因：setSourceRectHint 淨係入 PiP 動畫嘅 hint，唔係裁剪
 *    畫面，PiP 窗永遠顯示成個 activity 再縮去 16:9。
 * 2) 功能制開住嗰陣轉 PiP，入咗 PiP 之後唔識 auto-hide（Hook 3 嗰句
 *    GONE 淨係掂個 ControlsContainerView，唔知點解 hold 唔住）。
 * 修正：新加一個 helper morpheSetPipUiHidden(Z) + 一個 field
 * morpheHiddenViews。入 PiP（onHostPause 開頭，無條件行）嗰陣，
 * 將 ControlsContainerView 同埋由播放器 view 行上去 root、逐層
 * 嘅 siblings（即係 nav bar、標題列等等所有唔係片嘅 UI）全部隱藏
 * （GONE），淨係 hide 原本 VISIBLE 嗰啲，全部記入 list；出 PiP
 * （onHostResume 開頭）嗰陣將 list 入面嗰啲逐個 set 返 VISIBLE。
 * 咁樣 PiP 窗淨係會顯示片本身。還原唔係強制 VISIBLE，唔會重蹈
 * v2.3 嗰句 VISIBLE 嘅覆轍。
 * 另外澄清：v2.3（刪 Hook 4 嗰句 VISIBLE）係建基於「Full Screen
 * 啲制唔識收起」呢個前提，但用戶 2026-11-11 03:28 再試 v1.1.7
 * 證實 Full Screen 啲制其實 3–4 秒會收起，同原版一樣——即係 v2.3
 * 嗰個前提係錯嘅，Full Screen 從來無壞過。v2.3 嗰版（tag 15）
 * 唔使裝，留喺 v2.2（tag 14）就得；v2.4 係喺 v2.2 嘅行為上面加
 * hide／restore，唔會還原嗰句強制 VISIBLE。
 *
 * v2.5（2026-11-11，用戶喺主機實測 v2.4 之後）：
 * 主機（POCO F2 Pro，LineageOS 23.2，SDK 36／Android 16）撳 Home
 * 直接入 PiP 會停播；副機（Mi MIX 2，SDK 35／Android 15）v2.4
 * 四樣都 work。原因係 race：Hook 3 喺 onHostPause 嗰陣靠
 * isInPictureInPictureMode() 決定跳唔跳過 pause，但撳 Home 嗰陣，
 * onHostPause 幾時行、PiP state 幾時落地，兩者嘅先後次序係睇
 * Android 版本／ROM。副機嗰陣 PiP state 趕得切喺 onHostPause 之前
 * 落地，guard 讀到 true 就 skip pause；主機嗰陣 onHostPause 行先
 * 過 PiP state 落地，guard 讀到 false 就照行 setPlay(false)。
 * （推論，未喺主機上面實證個 race，但機制同觀察到嘅嘢吻合。）
 * 修正：唔再淨係靠 isInPictureInPictureMode()。新加一個 public
 * static boolean field morpheUserLeaving（喺 ReactJWPlayerView），
 * 再 hook ReactActivity.onUserLeaveHint()（MainActivity 繼承佢，
 * MainActivity 自己無 override onUserLeaveHint，所以 framework 叫
 * 嗰陣會行 ReactActivity 呢個；核實過 MainActivity 得 7 個 method，
 * 無 onUserLeaveHint）。撳 Home／離開 activity 嗰陣，
 * onUserLeaveHint 一定喺 onPause／onHostPause 之前行，喺嗰度將
 * morpheUserLeaving set 做 true；Hook 3 除咗 check
 * isInPictureInPictureMode 之外，再 check 呢個 flag，兩個任何
 * 一個 true 就 skip pause。Hook 4（onHostResume 開頭）將個 flag
 * 清返 false。
 * 邊界情況：如果撳 Home 之後 PiP 最終無入到（例如系統 PiP 俾人閂
 * 咗），個 flag 會令 onHostPause 跳過 pause，條片會喺 background
 * 繼續播（有聲）。但 auto-enter 開住嗰陣，撳 Home 系統一定會入
 * PiP（除非 PiP 俾人閂咗），而用戶兩部機都核實過 PiP work，所以
 * 呢個情況唔會撞到；返嚟嗰陣 onHostResume 會清返個 flag。
 *
 * v2.6 diagnostic（2026-11-11，用戶喺主機實測 v2.5 之後）：
 * v2.5 喺主機無效。pip-home.log（19:03:41–19:03:46，主機，直播
 * 7 點新聞）證實：onHostPause 行咗，跟住即刻 setPlay(false)——
 * 即係 Hook 3 個 guard 無 skip 到 pause。換句話說，onHostPause
 * 嗰陣 morpheUserLeaving 係 false，而且 isInPictureInPictureMode()
 * 都係 false。呢個 log 排除咗「pause 經第二條路」呢個可能（兇手
 * 的確係 onHostPause → setPlay(false)），但睇唔到 onUserLeaveHint
 * 有無行（佢唔係 REACT_JWPLAYER_VIEW 嗰個 tag，唔會出現喺呢個
 * log 入面），所以分唔到係 onUserLeaveHint 根本無行，定係有行但
 * 個 flag 無 set 到／無讀到。
 * v2.6 淨係加 diagnostic log，唔改任何行為：新加一個 static helper
 * morpheLog(String)（Log.d，tag 係 REACT_JWPLAYER_VIEW，等用戶
 * 用同一個 logcat filter 就捉到）；Hook 5（onUserLeaveHint）開頭
 * 印一句；Hook 3（onHostPause）印 flag 值同 isInPictureInPictureMode()
 * 嘅值。等主機 log 返嚟先知下一步點 fix。
 *
 * v2.6 diagnostic 結果（2026-11-11 20:01，用戶交 pip-home__1.log，
 * 主機 tag 18）：
 * 「morphe onUserLeaveHint fired」成個 log 完全無出現——即係主機
 * （Android 16）撳 Home 入 auto-enter PiP 嗰陣，ReactActivity
 * .onUserLeaveHint 根本無行（唔係 timing 問題，係從來無行）。
 * Hook 3 嗰陣：flag=false、isInPiP=false，跟住 onHostPause →
 * setPlay(false) 照 pause。結論：v2.5 個 flag approach 喺
 * Android 16 行唔通，因為系統喺 auto-enter PiP flow 入面唔叫
 * onUserLeaveHint。另核實：onPictureInPictureModeChanged 淨係得
 * framework Activity 有，ReactActivity／MainActivity 無 override，
 * 唔可以直接 hook。
 *
 * v2.7 diagnostic（2026-11-11，用戶批准「核實」之後）：
 * 轉方向：唔再靠喺 onHostPause 嗰陣 prevent pause，改為入咗 PiP
 * 之後先 resume。候選 signal 係 MainActivity.onConfigurationChanged
 * ——入 PiP 會令個 activity 視窗大細變，應該會觸發 config change。
 * MainActivity 有 override onConfigurationChanged（7 個 method 之一，
 * 已核實：4 個 register，v2=this，v3=Configuration；body 係
 * super call＋new Intent("onConfigurationChanged")＋putExtra("newConfig")
 * ＋sendBroadcast）。Hook 6 喺佢開頭印 isInPictureInPictureMode()
 * 嘅值，等主機 log 返嚟就知：入 PiP 嗰陣 onConfigurationChanged
 * 有無行、行嗰陣 isInPiP 係咪 true。如果係，v2.8 就可以喺呢度
 * resume 個 player（setPlay(true)）；如果唔係，就要搵第二個 signal。
 * v2.7 淨係加 log，唔改任何行為（唔 resume）。
 *
 * v2.7 diagnostic 結果（2026-11-11 21:23，用戶交 pip-home__2.log，
 * 主機 tag 19）：
 * 入 PiP 嗰陣 onConfigurationChanged 真係有行，而且行嗰陣
 * isInPictureInPictureMode() 係 true。時間線：21:22:15.933
 * onHostPause（flag=false、isInPiP=false→setPlay(false) 照停）
 * →21:22:15.968 onPause→21:22:16.052 onConfigurationChanged
 * （isInPiP=true）。即係入 PiP 之後大約 0.12 秒，onConfigurationChanged
 * 就會行，而且嗰陣已經入咗 PiP——呢個係一個有效嘅「入咗 PiP」
 * signal，v2.8 可以喺呢度 resume。
 *
 * v2.8（2026-11-11，用戶批准之後）：
 * 喺 Hook 6（MainActivity.onConfigurationChanged）嗰度，isInPiP
 * 係 true 嗰陣 resume 返個 player。設計：
 * 1) 新加一個 public static field morphePlayerView（ReactJWPlayerView），
 *    喺 Hook 1（onPlay）嗰度 set 做 this——onPlay 一定喺入 PiP 之前
 *    行過（auto-enter 係喺 onPlay 嗰度開嘅），所以個 field 一定有值。
 * 2) 新加一個 public method morpheResumePlayer()（喺 ReactJWPlayerView），
 *    入面 call private setPlay(true)。點解要 wrapper：setPlay 係
 *    private（access 0x2，已核實），MainActivity（另一個 class）
 *    唔可以直接 call 佢，否則 runtime 會 IllegalAccessError；wrapper
 *    同 setPlay 係同一個 class，call 到。setPlay(true) 嗰陣 player
 *    已經播緊就係 no-op，所以唔使再 check 狀態。
 * 3) Hook 6 喺印完 isInPiP 之後再讀多次 isInPiP（因為印 log 嗰陣
 *    已經將個 boolean 轉咗做字串），true 嘅話就攞 morphePlayerView，
 *    唔係 null 就 call morpheResumePlayer()。false 或者 null 就
 *    乜都唔做，照行原本嘅 super＋broadcast。
 * 預期效果：撳 Home 之後條片會停大約 0.12 秒（onHostPause 嗰陣照
 * pause），跟住入咗 PiP 嗰陣 onConfigurationChanged 就 resume 返。
 * 嗰一下短暫停頓係預期之內，唔係 bug。
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

/**
 * 喺 ReactJWPlayerView 加一個 private field：
 *   private ArrayList<View> morpheHiddenViews
 *
 * 用嚟記住入 PiP 嗰陣隱藏咗邊啲 view（連埋佢哋原本係 VISIBLE 呢個事實），
 * 等出 PiP 嗰陣還原返。淨係記 VISIBLE 嗰啲，所以還原嗰陣一律 set VISIBLE
 * 就啱（原本 GONE／INVISIBLE 嗰啲根本唔會入 list，唔會俾人錯誤顯示返）。
 * （加 field 嘅寫法抄 ref 嘅 ForceOriginalAudioPatch。）
 */
private fun addMorpheHiddenViewsField() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").apply {
        fields.add(
            ImmutableField(
                "Lcom/viutv/ReactJWPlayerView;",
                "morpheHiddenViews",
                "Ljava/util/ArrayList;",
                AccessFlags.PRIVATE.value,
                null,
                null,
                null
            ).toMutable()
        )
    }
}

/**
 * 喺 ReactJWPlayerView 加一個 public static field：
 *   public static boolean morpheUserLeaving
 *
 * v2.5（2026-11-11）：用嚟避開 onHostPause 嗰個 PiP race。
 * ReactActivity.onUserLeaveHint()（MainActivity 繼承佢）喺用戶撳 Home／
 * 離開 activity 嗰陣 set 佢做 true；ReactJWPlayerView.onHostPause 喺
 * 決定跳唔跳過 pause 嗰陣，除咗 check isInPictureInPictureMode 之外，
 * 再 check 呢個 flag；onHostResume 喺開頭清返佢做 false。
 * 一定要 public static：set 佢嗰個係 ReactActivity（另一個 class），
 * 唔係 ReactJWPlayerView 自己；private 的話第二個 class 掂唔到。
 * Static boolean 預設值係 false，唔使 static initializer。
 * （加 field 嘅寫法抄 ref 嘅 ForceOriginalAudioPatch；要同時 import
 * MutableField.Companion.toMutable 同 MutableMethod.Companion.toMutable，
 * 淨係 import Method 嗰個會令 fields.add() compile 唔到——v1.1.9
 * Build #18 撞過。）
 */
private fun addMorpheUserLeavingField() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").apply {
        fields.add(
            ImmutableField(
                "Lcom/viutv/ReactJWPlayerView;",
                "morpheUserLeaving",
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                null
            ).toMutable()
        )
    }
}

/**
 * 喺 ReactJWPlayerView 加一個 public static field：
 *   public static ReactJWPlayerView morphePlayerView
 *
 * v2.8（2026-11-11）：用嚟等 MainActivity.onConfigurationChanged
 * （Hook 6，另一個 class）喺入咗 PiP 嗰陣搵返而家嗰個 player view
 * 去 resume。Hook 1（onPlay）嗰度 set 佢做 this——onPlay 一定喺
 * 入 PiP 之前行過，所以個 field 一定有值；每次 onPlay 都會再 set
 * 過，idempotent。一定要 public static：讀佢嗰個係 MainActivity。
 * Static object field 預設值係 null，唔使 static initializer；
 * Hook 6 讀嗰陣會 check null。
 */
private fun addMorphePlayerViewField() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").apply {
        fields.add(
            ImmutableField(
                "Lcom/viutv/ReactJWPlayerView;",
                "morphePlayerView",
                "Lcom/viutv/ReactJWPlayerView;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                null
            ).toMutable()
        )
    }
}

/**
 * 喺 ReactJWPlayerView 加一個 public static helper method：
 *   public static morpheLog(String msg)
 *
 * v2.6 diagnostic（2026-11-11）：用 Log.d 將 msg 寫去 logcat，tag 係
 * "REACT_JWPLAYER_VIEW"，等用戶用同一個 logcat filter（-s
 * REACT_JWPLAYER_VIEW）就捉到。Hook 5 同 Hook 3 用佢嚟印 diagnostic。
 * 一定要 public static：call 佢嗰個包括 ReactActivity（另一個 class）。
 * Register：2 個（v0 local 裝 tag，p0 = msg 參數）。
 */
private fun addMorpheLogMethod() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").methods.add(
        ImmutableMethod(
            "Lcom/viutv/ReactJWPlayerView;",
            "morpheLog",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "REACT_JWPLAYER_VIEW"
                    invoke-static {v0, p0}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
                    return-void
                """
            )
        }
    )
}

/**
 * 喺 ReactJWPlayerView 加一個 helper method：
 *   public morpheResumePlayer()
 *
 * v2.8（2026-11-11）：等 MainActivity.onConfigurationChanged（Hook 6，
 * 另一個 class）喺入咗 PiP 嗰陣 resume 返個 player。入面淨係 call
 * private setPlay(true)——setPlay 係 private（access 0x2，已核實），
 * MainActivity 唔可以直接 call 佢，否則 runtime 會 IllegalAccessError；
 * 呢個 wrapper 同 setPlay 係同一個 class，call 到。一定要 public：
 * call 佢嗰個係 MainActivity。setPlay(true) 嗰陣 player 已經播緊
 * 就係 no-op，所以唔使再 check 狀態。
 * Register：1 個（v0 local 裝 true，p0 = this）。
 */
private fun addMorpheResumePlayerMethod() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").methods.add(
        ImmutableMethod(
            "Lcom/viutv/ReactJWPlayerView;",
            "morpheResumePlayer",
            listOf(),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    const/4 v0, 0x1
                    invoke-virtual {p0, v0}, Lcom/viutv/ReactJWPlayerView;->setPlay(Z)V
                    return-void
                """
            )
        }
    )
}

/**
 * 喺 ReactJWPlayerView 加一個 helper method：
 *   public morpheSetPipUiHidden(boolean hidden)
 *
 * hidden = true（入 PiP）：
 *   1) 將 JWPlayer 嘅 ControlsContainerView 隱藏（GONE），等功能制唔會
 *      黐住喺 PiP 窗入面。淨係 hide 原本 VISIBLE 嗰個，記入 list。
 *   2) 由呢個 view（ReactJWPlayerView）一路行上去 root，逐層將同層
 *      嘅其他 child（siblings）隱藏——呢啲就係 nav bar、標題列等等
 *      唔係片嘅 UI。淨係 hide 原本 VISIBLE 嗰啲，全部記入 list。
 *      咁樣 PiP 窗淨係會顯示播放器本身（片），唔會再有 1/3 畫面俾
 *      條 nav bar 阻住（2026-11-11 03:34 用戶截圖實證）。
 *   3) 每行一次都會先 clear 個 list，唔會累積舊嘅／重複嘅 entry。
 *
 * hidden = false（出 PiP）：
 *   將 list 入面所有 view 逐個 set 返 VISIBLE，再 clear 個 list。
 *   （淨係還原返我哋 hide 過嗰啲；原本已經 GONE／INVISIBLE 嗰啲
 *   從來無入過 list，唔會俾人錯誤顯示返。）
 *
 * 點解要行上去逐層 hide siblings，而唔係淨係搵條 nav bar：
 * 播放器畫面下面除咗 nav bar，唔同畫面仲可能有其他 UI（標題、頻道
 * 資訊等等），逐層 hide 就唔使估邊個係 nav bar、佢喺邊一層，全部
 * 唔係片嘅嘢都會匿埋。Walk 會喺 parent 唔係 ViewGroup（去到頂）
 * 嗰陣停。
 *
 * Register 用法（呢個 method 自己有 10 個 register）：
 * - v0：而家行緊嗰層嘅 view（cur），開頭係 this，一路變做 parent；
 *   hide container 嗰陣短暫借嚟裝 playerView／container。
 * - v1：parent（ViewGroup）。v2：child count／list size／instanceof 結果。
 * - v3：loop index。v4：child view。v5：morpheHiddenViews list。
 * - v6：visibility 值／GONE（8）／VISIBLE（0）。v7：ArrayList.add 嘅
 *   boolean return（要 consume，唔係齋放喺度）。
 * - p0 = this，p1 = hidden（Z）。
 *
 * 注意（if 指令嘅語義，唔好寫反）：
 * - if-eqz = jump if == 0；if-nez = jump if != 0。
 *   所以「淨係 hide VISIBLE（visibility == 0）」要用 if-eqz，
 *   「list 係 null 先 create」要用 if-nez 跳過 create。
 * - instance-of 係直接寫入目標 register，唔使 move-result。
 */
private fun addMorpheSetPipUiHiddenMethod() {
    mutableClassDefBy("Lcom/viutv/ReactJWPlayerView;").methods.add(
        ImmutableMethod(
            "Lcom/viutv/ReactJWPlayerView;",
            "morpheSetPipUiHidden",
            listOf(ImmutableMethodParameter("Z", null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(10),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    if-eqz p1, :restore
                    iget-object v5, p0, Lcom/viutv/ReactJWPlayerView;->morpheHiddenViews:Ljava/util/ArrayList;
                    if-nez v5, :list_ready
                    new-instance v5, Ljava/util/ArrayList;
                    invoke-direct {v5}, Ljava/util/ArrayList;-><init>()V
                    iput-object v5, p0, Lcom/viutv/ReactJWPlayerView;->morpheHiddenViews:Ljava/util/ArrayList;
                    :list_ready
                    invoke-virtual {v5}, Ljava/util/ArrayList;->clear()V
                    iget-object v0, p0, Lcom/viutv/ReactJWPlayerView;->playerView:Lcom/jwplayer/pub/view/JWPlayerView;
                    if-eqz v0, :walk_start
                    invoke-virtual {v0}, Lcom/jwplayer/pub/view/JWPlayerView;->getControlsContainer()Lcom/jwplayer/ui/views/ControlsContainerView;
                    move-result-object v0
                    if-eqz v0, :walk_start
                    invoke-virtual {v0}, Landroid/view/View;->getVisibility()I
                    move-result v6
                    if-eqz v6, :hide_container
                    goto :walk_start
                    :hide_container
                    invoke-virtual {v5, v0}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                    move-result v7
                    const/16 v6, 0x8
                    invoke-virtual {v0, v6}, Landroid/view/View;->setVisibility(I)V
                    :walk_start
                    move-object v0, p0
                    :walk_loop
                    invoke-virtual {v0}, Landroid/view/View;->getParent()Landroid/view/ViewParent;
                    move-result-object v1
                    if-eqz v1, :hide_done
                    instance-of v2, v1, Landroid/view/ViewGroup;
                    if-eqz v2, :hide_done
                    check-cast v1, Landroid/view/ViewGroup;
                    invoke-virtual {v1}, Landroid/view/ViewGroup;->getChildCount()I
                    move-result v2
                    const/4 v3, 0x0
                    :child_loop
                    if-ge v3, v2, :child_done
                    invoke-virtual {v1, v3}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;
                    move-result-object v4
                    if-eq v4, v0, :child_next
                    invoke-virtual {v4}, Landroid/view/View;->getVisibility()I
                    move-result v6
                    if-eqz v6, :hide_child
                    goto :child_next
                    :hide_child
                    invoke-virtual {v5, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                    move-result v7
                    const/16 v6, 0x8
                    invoke-virtual {v4, v6}, Landroid/view/View;->setVisibility(I)V
                    :child_next
                    add-int/lit8 v3, v3, 0x1
                    goto :child_loop
                    :child_done
                    move-object v0, v1
                    goto :walk_loop
                    :hide_done
                    return-void
                    :restore
                    iget-object v5, p0, Lcom/viutv/ReactJWPlayerView;->morpheHiddenViews:Ljava/util/ArrayList;
                    if-eqz v5, :restore_done
                    invoke-virtual {v5}, Ljava/util/ArrayList;->size()I
                    move-result v2
                    const/4 v3, 0x0
                    :restore_loop
                    if-ge v3, v2, :restore_clear
                    invoke-virtual {v5, v3}, Ljava/util/ArrayList;->get(I)Ljava/lang/Object;
                    move-result-object v4
                    check-cast v4, Landroid/view/View;
                    const/4 v6, 0x0
                    invoke-virtual {v4, v6}, Landroid/view/View;->setVisibility(I)V
                    add-int/lit8 v3, v3, 0x1
                    goto :restore_loop
                    :restore_clear
                    invoke-virtual {v5}, Ljava/util/ArrayList;->clear()V
                    :restore_done
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

// v2.5：ReactActivity.onUserLeaveHint。MainActivity 繼承 ReactActivity，
// MainActivity 自己無 override onUserLeaveHint（核實過，得 7 個 method），
// 所以 framework 撳 Home 嗰陣會行呢個。佢嘅 body 係 super call 加
// ReactActivityDelegate.onUserLeaveHint()，個 filter 就係後者。
// Register：2 個（v0 local，v1 = this），position 0 嗰陣 v0 未用過。
private object ReactActivityOnUserLeaveHintFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/react/ReactActivity;",
    name = "onUserLeaveHint",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            "Lcom/facebook/react/ReactActivityDelegate;->onUserLeaveHint()V"
        ),
    )
)

// v2.7：MainActivity.onConfigurationChanged。MainActivity 有 override 呢個
// （7 個 method 之一，已核實：4 個 register，8 行 instruction，body 係
// super call＋new Intent("onConfigurationChanged")＋putExtra("newConfig")
// ＋sendBroadcast）。入 PiP 會令個 activity 視窗大細變，應該會觸發
// config change。個 filter 就係 sendBroadcast 嗰句。
// Register：4 個（v0–v1 locals，v2 = this，v3 = Configuration 參數），
// position 0 嗰陣 v0、v1 未用過。
private object MainActivityOnConfigurationChangedFingerprint : Fingerprint(
    definingClass = "Lcom/viutv/MainActivity;",
    name = "onConfigurationChanged",
    returnType = "V",
    parameters = listOf("Landroid/content/res/Configuration;"),
    filters = listOf(
        methodCall(
            "Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V"
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
        // 先加 helper method（Hook 1 會 call 佢）同埋 v2.4 嘅 field＋helper、
        // v2.5 嘅 static flag field（Hook 5 會 set 佢，Hook 3 會讀佢），
        // v2.6 嘅 log helper（Hook 5 同 Hook 3 會 call 佢印 diagnostic），
        // v2.8 嘅 static player field（Hook 1 會 set 佢，Hook 6 會讀佢）
        // 同 resume wrapper（Hook 6 會 call 佢）。
        addMorpheUpdatePipParamsMethod()
        addMorpheHiddenViewsField()
        addMorpheSetPipUiHiddenMethod()
        addMorpheUserLeavingField()
        addMorpheLogMethod()
        addMorphePlayerViewField()
        addMorpheResumePlayerMethod()

        // Hook 1: onPlay -> 開 auto-enter（經 helper：16:9 + source rect），
        // 再將呢個 view 記入 static morphePlayerView，等 Hook 6（另一個
        // class）入咗 PiP 嗰陣搵返佢去 resume。
        // Register 分析（已核實）：呢個 method 一共用 6 個 register，
        // v4 = this，v0 借嚟裝 activity；call 完 helper 就唔再用 v0。
        // sput-object 嗰句用 v4（this），唔掂 v0，唔會同上面嗰段衝突。
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
                    sput-object v4, Lcom/viutv/ReactJWPlayerView;->morphePlayerView:Lcom/viutv/ReactJWPlayerView;
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

        // Hook 3: onHostPause -> 離開嗰陣隱藏所有唔係片嘅 UI，入咗 PiP 就唔 pause。
        // morpheSetPipUiHidden(true) 喺 method 開頭無條件行：無論入 PiP
        // 定係普通 background，離開播放畫面嗰陣都將 nav bar／功能制等
        // 非播放器 UI 隱藏埋（background 嗰陣根本無人睇到，唔會有影響）；
        // 咁做係為咗唔使依賴 isInPictureInPictureMode 喺 onHostPause
        // 嗰刻讀到嘅 timing——就算 PiP mode 嗰刻仲未 set 好，啲 UI
        // 都已經匿埋咗。還原係 Hook 4 嗰邊做。
        // PiP guard（v2.5 改過）：入咗 PiP 或者用戶撳咗 Home（morpheUserLeaving
        // flag，Hook 5 喺 onUserLeaveHint 嗰度 set）就 return，唔行原本嘅
        // setPlay(false)。兩個條件任何一個 true 都 skip pause。
        // 點解要加個 flag：v2.4 淨係靠 isInPictureInPictureMode()，但撳 Home
        // 嗰陣 onHostPause 幾時行、PiP state 幾時落地，兩者嘅先後次序係睇
        // Android 版本／ROM——主機（Android 16）onHostPause 行先過 PiP
        // state 落地，guard 讀到 false 就照 pause。onUserLeaveHint 一定喺
        // onPause／onHostPause 之前行，所以個 flag 一定趕得切。
        // （v2.4 之前呢度係 inline hide 個 ControlsContainerView，
        // 而家搬咗入 morpheSetPipUiHidden，連 nav bar 一齊處理。）
        // v2.6 diagnostic：pip-home.log 證實 v2.5 喺主機無 skip 到 pause，
        // 但睇唔到 onUserLeaveHint 有無行。所以呢度加 log：讀 flag 嗰陣
        // 印埋個 flag 值（兩行：label 一行，true/false 一行），讀
        // isInPictureInPictureMode() 嗰陣同樣印埋個值。咁樣主機 log
        // 返嚟就知 guard 嗰陣見到啲咩，再加埋 Hook 5 嗰句就分到係
        // onUserLeaveHint 無行，定係有行但 flag 無 set 到／無讀到。
        // Register 分析（已核實）：呢個 method 一共用 3 個 register，
        // v2 = this；開頭 v0 未用過，借嚟裝 boolean（true = 1）。
        // 之後 v0 會俾 SDK 檢查覆寫，唔會有衝突。
        // Log 嗰陣嘅 register：讀 flag 嗰段，v0 係 flag boolean，v1
        // 嗰陣已經唔再要（SDK 比較用完），借 v1 裝 label 字串；印完
        // boolean 字串之後要重新 sget 個 flag 返嚟（v0 嗰陣已經變咗
        // 字串）。讀 PiP 嗰段，v1 係 PiP boolean，v0 嗰陣已經唔再要
        // （Activity ref 用完），借 v0 裝 label 字串；v1 一路保留住
        // PiP boolean 俾之後嘅 if-eqz 用。
        // 注意 if 指令嘅語義：if-nez = jump if != 0；if-eqz = jump if == 0。
        // 「flag 係 true（v0 != 0）就跳去 skip pause」要用 if-nez。
        ReactJWPlayerViewOnHostPauseFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    const/4 v0, 0x1
                    invoke-virtual {v2, v0}, Lcom/viutv/ReactJWPlayerView;->morpheSetPipUiHidden(Z)V
                    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
                    const/16 v1, 0x1a
                    if-lt v0, v1, :pip_orig
                    sget-boolean v0, Lcom/viutv/ReactJWPlayerView;->morpheUserLeaving:Z
                    const-string v1, "morphe onHostPause flag="
                    invoke-static {v1}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    invoke-static {v0}, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;
                    move-result-object v0
                    invoke-static {v0}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    sget-boolean v0, Lcom/viutv/ReactJWPlayerView;->morpheUserLeaving:Z
                    if-nez v0, :pip_skip_pause
                    iget-object v0, v2, Lcom/viutv/ReactJWPlayerView;->reactContext:Lcom/facebook/react/bridge/ReactContext;
                    invoke-virtual {v0}, Lcom/facebook/react/bridge/ReactContext;->getCurrentActivity()Landroid/app/Activity;
                    move-result-object v0
                    if-eqz v0, :pip_orig
                    invoke-virtual {v0}, Landroid/app/Activity;->isInPictureInPictureMode()Z
                    move-result v1
                    const-string v0, "morphe onHostPause isInPiP="
                    invoke-static {v0}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    invoke-static {v1}, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;
                    move-result-object v0
                    invoke-static {v0}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    if-eqz v1, :pip_orig
                    :pip_skip_pause
                    return-void
                    :pip_orig
                    nop
                """
            )
        }

        // Hook 4: onHostResume -> 清返 morpheUserLeaving flag，還原返隱藏咗嘅 UI，
        // 再 refresh 個 player view，等 PiP 返出嚟畫面唔好凍住。
        // 清 flag（v2.5）：將 morpheUserLeaving set 返 false，等下次撳 Home
        // 嗰陣 onUserLeaveHint 再 set 過。呢個同 morpheSetPipUiHidden(false)
        // 共用同一個 const/4 v0, 0x0——v0 = 0 先 sput-boolean 清 flag，
        // 再用同一個 v0（仲係 0）做 morpheSetPipUiHidden 嘅 false 參數。
        // 還原（morpheSetPipUiHidden(false)）：將入 PiP 嗰陣隱藏咗嘅
        // nav bar／功能制逐個 set 返 VISIBLE。呢個唔係 v2.3 嗰句強制
        // setVisibility(VISIBLE)——嗰句係無條件將成個 container 顯示返，
        // 唔理佢原本係咩狀態；呢個係還原 list 入面記住咗嗰啲 view（淨係
        // 原本 VISIBLE 先會入 list），所以唔會重蹈覆轍。
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
        // 2026-11-11 03:28 用戶再試 v1.1.7 澄清：Full Screen 啲制其實
        // 3–4 秒會收起，同原版一樣——即係 v2.3 嗰個前提係錯嘅，Full Screen
        // 從來無壞過。不過 v2.4 呢個還原做法本身就唔係強制 VISIBLE，
        // 所以唔會受嗰個問題影響；v2.3 嗰句 VISIBLE 唔會還原返。）
        // Register 分析（已核實）：呢個 method 一共用 3 個 register，
        // v2 = this。還原嗰個插入點喺 method 開頭（position 0），嗰陣
        // v0 未用過，借嚟裝 boolean（false = 0）。forceRefresh 嗰個
        // 插入點喺 setPlay(true) 之後。
        // 注意：兩個插入要由後向前做（先 setPlay+1，後 position 0），
        // 唔係 position 0 嗰個插入會令 setPlay 嗰個 index 移位。
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

            addInstructionsWithLabels(
                0,
                """
                    const/4 v0, 0x0
                    sput-boolean v0, Lcom/viutv/ReactJWPlayerView;->morpheUserLeaving:Z
                    invoke-virtual {v2, v0}, Lcom/viutv/ReactJWPlayerView;->morpheSetPipUiHidden(Z)V
                """
            )
        }

        // Hook 5 (v2.5): ReactActivity.onUserLeaveHint -> set morpheUserLeaving flag。
        // 撳 Home／離開 activity 嗰陣，framework 一定會喺 onPause／onHostPause
        // 之前 call onUserLeaveHint。MainActivity 繼承 ReactActivity，MainActivity
        // 自己無 override onUserLeaveHint，所以行嘅係 ReactActivity 呢個。
        // 喺 method 開頭將 ReactJWPlayerView 嗰個 static flag set 做 true，
        // 等 Hook 3（onHostPause）嗰陣就算 PiP state 仲未落地，都識得 skip pause。
        // 之後原本嘅 super call＋delegate call 照行，唔會影響 React Native 嗰邊。
        // v2.6 diagnostic：pip-home.log 睇唔到 onUserLeaveHint 有無行，所以
        // 喺 set flag 之前加多句 morpheLog，印「onUserLeaveHint fired」。
        // 如果主機 log 有呢句但 Hook 3 讀到 flag 係 false，即係 flag 寫入／
        // 讀出有問題；如果連呢句都無，即係 Android 16 撳 Home 根本無行
        // onUserLeaveHint（或者行咗第二個 onUserLeaveHint）。
        // v2.6 diagnostic 結果（2026-11-11）：主機 log 完全無呢句，即係
        // Android 16 撳 Home 入 auto-enter PiP 嗰陣根本唔叫 onUserLeaveHint。
        // 呢個 hook 留喺度（副機 SDK 35 嗰邊可能仲有用），但主機唔可以靠佢。
        // Register 分析（已核實）：呢個 method 一共用 2 個 register，
        // v1 = this，v0 係 local；position 0 嗰陣 v0 未用過，借嚟裝字串／
        // true（1）。v1（this）一路唔掂，原本嘅 super＋delegate call 照用。
        ReactActivityOnUserLeaveHintFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "morphe onUserLeaveHint fired"
                    invoke-static {v0}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    const/4 v0, 0x1
                    sput-boolean v0, Lcom/viutv/ReactJWPlayerView;->morpheUserLeaving:Z
                """
            )
        }

        // Hook 6 (v2.7 diagnostic, v2.8 變做 fix): MainActivity
        // .onConfigurationChanged -> 入咗 PiP 嗰陣 resume 返個 player。
        // v2.7 diagnostic（2026-11-11）：主機 log 證實入 PiP 嗰陣
        // onConfigurationChanged 真係有行，而且行嗰陣 isInPiP 係 true
        // （pip-home__2.log：onHostPause 21:22:15.933→onPause 21:22:15.968
        // →onConfigurationChanged 21:22:16.052，isInPiP=true）。
        // v2.8：喺印完 log 之後再讀多次 isInPiP（因為印 log 嗰陣已經
        // 將個 boolean 轉咗做字串），true 嘅話就攞 static morphePlayerView，
        // 唔係 null 就 call morpheResumePlayer()（經 public wrapper，
        // 入面 call private setPlay(true)）。false 或者 null 就乜都唔做，
        // 照行原本嘅 super＋broadcast。
        // 預期：撳 Home 之後條片會停大約 0.12 秒（onHostPause 嗰陣照
        // pause），跟住入咗 PiP 嗰陣 onConfigurationChanged 就 resume 返。
        // Register 分析（已核實）：呢個 method 一共用 4 個 register，
        // v2 = this，v3 = Configuration 參數；position 0 嗰陣 v0、v1
        // 未用過。v0 裝 isInPiP boolean，之後轉做字串；v1 裝 label 字串。
        // Resume 嗰段再讀多次 isInPiP 入 v0，之後 v0 轉做 player view
        // （sget-object）。之後原本嘅 code 會再用 v0／v1 做 Intent，
        // 嗰陣我哋啲值已經死晒，唔會有衝突。isInPictureInPictureMode()
        // 係 Activity 嘅 method，所以 invoke 嗰陣用 Landroid/app/Activity
        // 呢個 class。
        MainActivityOnConfigurationChangedFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-virtual {v2}, Landroid/app/Activity;->isInPictureInPictureMode()Z
                    move-result v0
                    const-string v1, "morphe onConfigurationChanged isInPiP="
                    invoke-static {v1}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    invoke-static {v0}, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;
                    move-result-object v0
                    invoke-static {v0}, Lcom/viutv/ReactJWPlayerView;->morpheLog(Ljava/lang/String;)V
                    invoke-virtual {v2}, Landroid/app/Activity;->isInPictureInPictureMode()Z
                    move-result v0
                    if-eqz v0, :pip_resume_done
                    sget-object v0, Lcom/viutv/ReactJWPlayerView;->morphePlayerView:Lcom/viutv/ReactJWPlayerView;
                    if-eqz v0, :pip_resume_done
                    invoke-virtual {v0}, Lcom/viutv/ReactJWPlayerView;->morpheResumePlayer()V
                    :pip_resume_done
                    nop
                """
            )
        }
    }
}
