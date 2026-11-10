# 提交卫生与敏感信息门禁

工具/测试初次核对：2026-11-06；状态/字段改造门禁：2026-11-09；脚本用途复查与无用调试产物清理：2026-11-10。检查器不会自动修改业务 Key 或自动提交。

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

`commit-msg` 另行检查 `type(scope): 描述` 格式和提交正文中的凭据；例子为
`feat(app): 完善状态管理`、`fix(app): 隔离迟到回调`，使用英文半角括号和冒号。
详见 [提交约定](../CONTRIBUTING.md)。

推荐每次提交前的顺序：

1. 看 `git status --short`，不要把其他人的未完成改动一起暂存。
2. 运行工作区检查；将真实值迁回本地属性/环境注入，公开材料改为占位符。
3. 仅暂存本次明确修改的文件，检查 `git diff --cached --stat`。
4. 运行 `--staged`，再人工核对本次差异和 SDK/测试门禁。
5. 提交时 hook 再检查一次；发现敏感信息时修正并重新暂存，不使用 `--no-verify` 绕过。

## 4. 边界

- 这是本地防护，不是“绝对无敏感信息”的承诺；无法覆盖未知密钥格式、任意压缩包、全部二进制或远程历史。
- 初次梳理未自动暂存用户原有改动；2026-11-09 按用户要求经敏感检查保存本地基线。后续实现按范围本地提交，不推送/发布；历史改写须另有明确授权与备份，本轮清理不改写历史。
- 本地 hook 可被绕过；受保护分支、CI 强制检查和正式发布身份仍是 [生产交付计划](../MapSdk-Development-Plan.md) 的待办。
- 项目构建、设备授权与厂商控制台问题分别验收，脱敏检查通过不代表导航鉴权或商用发布通过。

## 5. Python 脚本用途与清理边界（2026-11-10）

仓库当前只有 10 个可提交的 Python 源文件：5 个工具/内部模块、5 个测试。按真实入口、导入和文档核对，均仍有用途，不按文件数量或 .py 扩展名删除：

| 文件/分组 | 实际用途与引用 | 处理 |
| --- | --- | --- |
| [check_sensitive.py](../tools/check_sensitive.py) | pre-commit 与 commit-msg 的敏感内容检查，亦供手动工作区/整个 index 扫描 | 保留 |
| [check_commit_message.py](../tools/check_commit_message.py) | commit-msg 检查 scoped 信息与敏感正文，导入敏感检查模块 | 保留 |
| [check_demo_structure.py](../tools/check_demo_structure.py) | 贡献约定要求的 Demo 类/方法/属性/枚举中文 KDoc 与规模检查 | 保留 |
| [kotlin_source.py](../tools/kotlin_source.py) | 被结构检查器和测试导入，定位字段/枚举并辅助纯注释 token 比对 | 保留 |
| [official_api_inventory.py](../tools/official_api_inventory.py) | 官方 API 覆盖计划中的 AAR/JAR 签名盘点入口，消费 Gradle 制品清单和人工 CSV | 保留 |
| tools/tests 下 5 个 test_*.py | unittest discovery 共 53 项，含真实临时 Git 仓库双 hook、敏感检查与注释/词法边界 | 保留 |

- 本次删除 7 个已忽略、无源码精确文件名引用的旧 UI 调试产物：6 个节点快照 JSON、1 个截图 PNG，合计 114,923 字节。
- 删除 tools 与 tools/tests 下 9 个可再生成 .pyc 字节码缓存，合计 71,357 字节；总计 16 文件、186,280 字节。删除前复制到会话临时目录，并逐文件校验大小与 SHA256，不公布布局、身份或位置值。
- 后续本轮验证使用 PYTHONDONTWRITEBYTECODE=1，避免检查完又留下同一批缓存；常规 Python 运行仍可能自动生成缓存，已有 .gitignore 规则继续排除。
- 保留 `docs/README.md`：它是有接入示例且被审计提及的本地历史长笔记，不作当前能力/发布依据，不以“未跟踪”判为无用。
- 不清空 Gradle build/.gradle、IDE/本机设置、local.properties、签名文件、ABI、SDK 公共代码、消费者示例或历史备份；本轮没有业务源码/接口/依赖调整，也没有新增项目 Python 脚本。
- 忽略态产物删除不会表现为 Git 的源码删除；本地提交仅保存此次用途核对与清理记录，不推送远端。
