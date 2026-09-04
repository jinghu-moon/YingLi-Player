# ADR：保险库分块认证容器

- 状态：已采用
- 格式版本：1
- 日期：2026-08-09

## 决策

内容和 metadata 分别使用 AES-256-GCM 分块加密。密钥由 Android Keystore 生成，别名按版本 `yingli_vault_v<version>` 管理，并要求设备处于已解锁状态。密钥材料不写入数据库、偏好、日志或备份。

容器采用大端序，结构如下：

```text
magic "YLVLT001"
formatVersion:int32
keyVersion:int32
chunkSize:int32
originalSize:int64
noncePrefix:8 bytes
repeat until originalSize consumed:
  plainLength:int32
  cipherLength:int32
  ciphertextAndTag:plainLength + 16 bytes
```

每个块的 12 字节 nonce 为随机 8 字节前缀加单调 `int32` 块号。AAD 为格式版本、密钥版本、块大小、原始大小、块号和明文长度。随机前缀由 `SecureRandom` 为每个容器独立生成；同一容器内块号不得重复。块大小默认 1 MiB，允许范围 16 KiB 到 4 MiB。

## 原因

整文件 AEAD 无法在大视频上提供有界内存和随机 seek。分块 AEAD 允许 Media3 只解密所需窗口，同时每块在输出明文前完成认证。自定义格式保持最小，不加入压缩、可选字段或未使用的恢复 envelope。

## 提交与失败语义

导入先写私有 `.part`，内容与 metadata 均反向解密到丢弃流验证后，再重命名并写 Room v6。数据库失败会删除已提交密文。随机 reader 发现认证失败后清零缓存并永久拒绝后续读取。导出直接写用户创建的文档，失败或取消时请求 ContentResolver 删除目标。

## 后果

密钥轮换接口已存在，但现有条目不会自动重加密；读取按条目 `keyVersion` 选择旧密钥。删除密钥或 Keystore 失效会使对应数据不可恢复。当前没有云备份、恢复短语或跨设备迁移，这是明确的产品限制。
