# 提交卫生与敏感信息门禁

工具/测试核对：2026-11-06；最终工作区与暂存区复查：2026-11-09。本仓库提供检查和拦截，不会自动修改业务 Key，也不会自动提交。

## 1. 保留与排除规则

- API Key 仍从未跟踪的 `local.properties` 注入 BuildConfig/Manifest；不要为“脱敏”而破坏实际 SDK 鉴权。
- `local.properties`、签名文件、私钥、仓库密码和环境凭据不能进入 Git。
- 文档仅记录占位符、错误码与复现步骤，不记录实际签名指纹或百度 MCODE。
- `artifacts/` 的随手截图和布局 JSON 可能包含身份、位置等信息，已整体忽略。
- 公共验证输出放 `build/reports/`；保留人工维护的 `docs/official-api-mappings.csv`，不按扩展名批量删除 JSON/CSV/资源。
- 合法字符串、启动图标、厂商依赖、ABI 基线与测试输入不能因为“看起来无用”而删除。
- 本次删除的旧调试产物已在会话临时目录备份并校验；备份不属于项目交付物，不得提交。

## 2. 本地检查

在仓库根目录执行，要求 Python 3.10+，不依赖第三方 Python 包：

```powershell
python tools/check_sensitive.py
python tools/check_sensitive.py --staged
python -m unittest discover -s tools/tests -p 'test_*.py' -v
```

- 默认检查已跟踪文件和未被忽略的工作区文件；未跟踪且被忽略的本地 Key 不打印、不提交。
- `--staged` 读取**整个 Git 暂存区快照**，不是磁盘工作副本。已改正工作副本但忘记重新暂存时，仍会阻止旧值提交。
- 检查本地 Key 副本、具名明文凭据、Manifest Key、常见访问 Token、私钥头和文档/日志中的签名指纹。
- 检查签名文件、APK/AAB、构建目录和调试产物被强制暂存的情况；过大的文件会拒绝未经检查通过。
- 报告只包含路径、行号和规则名，绝不包含匹配值。失败退出码为 1，检查无法完成为 2。
- 测试样例和变量引用有回归测试约束；不会通过忽略整个业务文件来消除误报。

## 3. 启用每次提交前检查

本次已在当前仓库本地启用 `.githooks`。Git 不会随克隆自动复制本地 config，新克隆需执行：

```powershell
git config --local core.hooksPath .githooks
```

如果已经有其他 `core.hooksPath` 或自定义 pre-commit，先把本检查合入现有 hook，**不要直接覆盖**。
`.gitattributes` 保证 hook 保持 LF 换行；hook 找不到可用 Python 时会阻止提交，不静默跳过。

推荐每次提交前的顺序：

1. 看 `git status --short`，不要把其他人的未完成改动一起暂存。
2. 运行工作区检查；将真实值迁回本地属性/环境注入，公开材料改为占位符。
3. 仅暂存本次明确修改的文件，检查 `git diff --cached --stat`。
4. 运行 `--staged`，再人工核对本次差异和 SDK/测试门禁。
5. 提交时 hook 再检查一次；发现敏感信息时修正并重新暂存，不使用 `--no-verify` 绕过。

## 4. 边界

- 这是本地防护，不是“绝对无敏感信息”的承诺；无法覆盖未知密钥格式、任意压缩包、全部二进制或远程历史。
- 本次没有改写 Git 历史、没有推送或发布，也没有自动暂存用户原有改动。
- 本地 hook 可被绕过；受保护分支、CI 强制检查和正式发布身份仍是 [生产交付计划](../MapSdk-Development-Plan.md) 的待办。
- 项目构建、设备授权与厂商控制台问题分别验收，脱敏检查通过不代表导航鉴权或商用发布通过。
