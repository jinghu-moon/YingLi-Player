# Phase 11-14 TDD 报告

## 交付范围

- Phase 11：版本化固定预设、设备/源能力探测、保守空间规划、显式降级确认、Media3 Transformer、持久任务接入和独立结构/解码验证。
- Phase 12：Exact 分层流式哈希、Room v5 证据持久化、人工保留/回收站计划、执行前完整重验；Similar 因无合格基线保持关闭。
- Phase 13：PBKDF2 应用锁、强生物识别辅助与 PIN 回退、Keystore 密钥、版本化分块 AES-GCM、Room v6 匿名索引、原子导入/导出/删除、Media3 随机读安全播放及窗口防泄露。
- Phase 14：统一证据门禁；GIF、片段相似、第二播放内核、FFmpeg 和网络源均记录为 No-Go，生产依赖与 UI 零变化。

## 安全与数据决策

HDR tone mapping 和 Similar 去重没有足够证据，因此拒绝执行而不是伪造支持。Vault 内容和 metadata 独立加密，保存在 `noBackupFilesDir`；数据库不保存原始路径或标题。安全播放强制无痕，离页、进后台或锁定会关闭 reader 和播放会话。密钥失效不可恢复，删除不承诺闪存物理擦除。

## 自动化证据

最终门禁应执行：

```text
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug assembleDebugAndroidTest
./gradlew :benchmark:assembleBenchmark :app:generateReleaseChecksums
git diff --check
```

Room Schema `5.json` 与 `6.json` 已纳入仓库；仪器测试覆盖 `4→5`、`5→6` 与 `1→6`。正式 Baseline Profile/Macrobenchmark 按既有 Phase 8-10 决策继续跳过，不把 dry-run 当作性能基线。

2026-08-09 本地门禁结果：

- `testDebugUnitTest`：136 项通过，0 失败、0 错误、0 跳过。
- `lintDebug`：通过，0 错误。
- `assembleDebug`、`assembleDebugAndroidTest`、`:benchmark:assembleBenchmark`：通过。
- `:app:generateReleaseChecksums`：R8、资源收缩、四 ABI unsigned Release 和 SHA-256 生成通过。
- `git diff --check`：通过。

目标设备 Xiaomi M2012K11AC（Android 13/API 33）在线，但 `connectedDebugAndroidTest` 在安装阶段被 MIUI 以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，应用与 Benchmark 均执行 0 tests。该结果不计为仪器测试通过，也不是测试断言失败；本轮没有采集 Baseline Profile 或 Macrobenchmark 数据。

## 真机待验矩阵

需要在目标手机上验证 codec 能力快照、SDR/VFR/多音轨转码、空间不足与取消、GB 级 Exact 扫描、Keystore 失效、Vault 导入/篡改/随机 seek/导出/删除、最近任务、通知、截图、PiP、进后台和进程重建。未执行的人工样本不得写成已通过。
