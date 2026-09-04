# Phase 9 处理基础设施契约

## 状态机

```text
Queued -> Preparing -> Running -> Succeeded
                         |  |-> Failed -> Retry -> Queued
                         |-> Paused -> Resume -> Queued
任意非终态 -> Canceling -> Canceled
进程恢复：Preparing/Running -> Queued，Canceling -> Canceled
```

终态拒绝进度和取消；重复事件幂等。总量未知时 `fraction=null`，UI 只显示不确定进度。进度仅在状态变化、至少 1% 增量、2 秒间隔或完成时 checkpoint。

## 调度与产物

调度器默认单任务并发，按用户优先级和创建时间选择；低于 15% 电量或私有缓存可用空间少于 256 MiB 时不启动新任务。协程作用域由 Application 拥有，不使用 `GlobalScope`。

临时产物只位于 `cacheDir/processing-artifacts/<taskId>`。提交前独立探测，成功后使用 MediaStore `IS_PENDING` 原子提交到 `Movies/YingLi-Output`；取消/失败删除半成品，源文件永不覆盖。

## 呈现与隐私

Running 使用信息钢蓝，Queued/Preparing/Canceling 使用警告琥珀，Succeeded 使用成功绿，Failed 使用错误红；同时始终提供状态文字、图标、动作和进度语义。前台通知不显示文件名或 URI，诊断只包含任务 ID 和错误码。

