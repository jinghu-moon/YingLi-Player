package seeyuer.yingli.player.feature.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把容器/实测帧率格式化成用户看到的那段文字（例如 `"29.97 fps"`），**纯函数、无 Android 依赖**。
 *
 * ## 为什么不能直接 `toInt()`
 *
 * `Media3` 报出的 `Format.frameRate` 是 `Float`，精确 30fps 的容器在真机上实测是 `29.999x`——
 * `toInt()` 截断会把它显示成 **"29 fps"**，用户看到的信息直接是错的。凡是"用户可见的数字"，
 * 都必须走这里，而不是各处各写一次 `toInt()`。
 *
 * ## 口径：整数吸附（±0.01）+ 两位小数
 *
 * - **整数吸附**：`|值 - 最近整数| < 0.01` 时只显示整数。这一条同时兼顾两件事：
 *   1. `29.999 → "30 fps"`，整数帧率不会被截断成 29；
 *   2. `59.94 → "59.94 fps"` 不会被吸成 `60`。
 *   容差必须比"Media3 对整数帧率的浮点噪声"大、又比"分数帧率到整数的距离"小：实测噪声量级是
 *   0.001（精确 30fps 的容器报 29.999x），而最小的分数帧率距离是 23.976/24 = 0.024，
 *   0.01 落在两者正中间（详见 [INTEGER_SNAP_TOLERANCE]）。
 * - **否则两位小数**：`23.976 → "23.98 fps"`、`29.97 → "29.97 fps"`、`59.94 → "59.94 fps"`。
 *   两位小数下 23.976/24、29.97/30、59.94/60 这三组**都能原样显示**，
 *   而 16:9 电视系的分数帧率本身就是两位小数（59.94 = 60000/1001），信息不多不少。
 *   一位小数不够：Float 的精度承载不了"先四舍五入到一位再判断"这一步，
 *   真机实测中 `59.94f` 会在这一步被压成 `59.9`，那样反而把分数帧率改错了。
 *
 * 由此得到一条自洽的口径：**显示为整数的值确实在整数容差内，显示为小数的值确实不是整数帧率**——
 * 不会出现"明明不是整数帧率却显示成整数"的误导。
 *
 * ## 缺失/异常值
 *
 * 返回 null 表示"这一项没有可显示的信息"，由调用方决定隐藏该字段（顶栏副标题里整段帧率都不出现、
 * 信息对话框显示"未知"）。**不返回 "0 fps"**：0/负数/NaN 不是帧率，编一个数字出来比不显示更糟，
 * 这也与 `effectiveFrameRate` 把 `<= 0` 一律视为"不可用"的口径一致。
 */
fun frameRateLabel(frameRate: Float?): String? {
    val fps = frameRate?.takeIf { it.isFinite() && it > 0f } ?: return null
    // 判断用**原始值到最近整数**的距离（不是"四舍五入到两位之后再比"），这样"值本身离整数很近"
    // 与"值被小数位吸到整数上"是两件事：29.999 离 30 只有 0.001，走整数分支；
    // 而 23.976 离 24 有 0.024，不许被写成 24。
    val nearestInteger = fps.roundToInt()
    if (abs(fps - nearestInteger) < INTEGER_SNAP_TOLERANCE && fps >= INTEGER_MIN_FPS) return "$nearestInteger fps"
    // 非整数帧率保留两位小数：这正是 23.976 / 29.97 / 59.94 这类分数帧率的原生精度
    //（NTSC 系的分数帧率就是两位小数），显示它们不需要也不应该丢掉这一位。
    val rounded = (fps * DECIMAL_SCALE).roundToInt() / DECIMAL_SCALE
    // 小到连 0.01 都不到的正数（异常容器）：同样不给文字，避免显示 "0 fps"。
    if (rounded < MIN_VISIBLE_FPS) return null
    return String.format(Locale.ROOT, "%.2f", rounded) + " fps"
}

/**
 * 整数吸附容差。取 0.01，理由是它必须同时满足两个实测约束：
 * - **大于** Media3 对整数帧率的浮点噪声（真机实测精确 30fps 的容器报 `29.999x`，偏差约 0.001）；
 * - **小于** 最小的分数帧率到整数的距离（`23.976` 与 `24` 相差 0.024，`29.97` 与 `30` 相差 0.03）。
 *
 * 0.01 落在 0.001 与 0.024 之间，因此 `29.999 → 30`、`23.976 → 23.98`（不吸成 24）可以同时成立。
 * 这个容差比"四舍五入到整数"（等价于 0.5 的容差）严格得多，正是为了不把 29.97 吸成 30。
 */
private const val INTEGER_SNAP_TOLERANCE = 0.01f

/**
 * 整数分支允许的最小值。小于它的值（例如真机上的 0.001）显示成 "0 fps" 是错的，
 * 宁可不显示——"重复率 ≈ 0"的容器不是 0fps 素材，而是读数不可信。
 */
private const val INTEGER_MIN_FPS = 0.5f

/** 非整数分支显示两位小数，见 [frameRateLabel] 的注释。 */
private const val DECIMAL_SCALE = 100f

/** 两位小数下仍然为 0 的值一律不显示（同 [INTEGER_MIN_FPS]）。 */
private const val MIN_VISIBLE_FPS = 0.01f
