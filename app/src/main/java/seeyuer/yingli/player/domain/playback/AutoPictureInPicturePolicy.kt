package seeyuer.yingli.player.domain.playback

/**
 * 「按 Home 键自动进入画中画」（系统自动进入）是否成立——**纯规则，唯一判定点**。
 *
 * 三个输入就是运行期会影响它的全部状态：
 * - [preferenceEnabled]：用户偏好「自动画中画」（DataStore → `PlayerUiState.preferences`）；
 * - [secureContent]：窗口当前是否承载安全内容（保险库播放 / 应用锁）。私密内容不把画面露出到应用外，
 *   这条在窗口层与 `FLAG_SECURE` 是同源事实，必须用同一个值判断，否则会出现
 *   "窗口已经是安全的、画中画参数还没跟上"的中间态；
 * - [hasMedia]：播放页当前是否真的有媒体。没有媒体的空播放页被切到后台时不该出现一个空浮窗。
 *
 * 为什么必须是纯函数、且只能有一份：这条规则有两个使用时机——**参数镜像**（提前把
 * `setAutoEnterEnabled(true)` 下发给系统，见 `ActivityPictureInPictureGateway`）与**测试/评审**。
 * 如果在下发点再抄一遍条件，两份规则迟早会漂移，而漂移的表现是"偏好开着却不进画中画"
 * 或"保险库内容跑进了浮窗"，两种都很难在真机上复现定位。
 *
 * 它不包含设备能力判定（`PackageManager.FEATURE_PICTURE_IN_PICTURE`）：那是窗口层的事，
 * 由 gateway 的下发结果体现（能力缺失时下发不会生效）。
 */
fun shouldAutoEnterPictureInPicture(
    preferenceEnabled: Boolean,
    secureContent: Boolean,
    hasMedia: Boolean,
): Boolean = preferenceEnabled && !secureContent && hasMedia
