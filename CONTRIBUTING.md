# 本地开发与提交约定

## 提交信息

采用 `type(scope): 描述`，括号和冒号使用英文半角；一个提交围绕一类可说明的变更。

```text
feat(app): 完善 Demo 状态管理
fix(map-baidu): 修复销毁后的迟到回调
refactor(app): 拆分地图资源所有权
test(app): 补充页面重建用例
docs(plan): 更新实际验证结果
chore(repo): 增加提交检查
```

支持 feat、fix、refactor、docs、test、chore、build、ci、perf、revert；scope 使用小写模块名。
破坏性调整用 `type(scope)!: 描述`，正文说明迁移方式。不要在标题或正文加入 Key、实际签名指纹或其他凭据。

## 本地门禁

```powershell
git config --local core.hooksPath .githooks
python tools/check_sensitive.py
python -m unittest discover -s tools/tests -p 'test_*.py' -v
```

- pre-commit 检查真实暂存区；commit-msg 检查格式和提交信息的敏感内容。
- 使用现有自定义 hooks 时先合并，不直接覆盖；不使用 --no-verify 绕过。
- 先保存本地基线，再将实现、测试与文档按范围提交；没有明确要求不推送远端。
- 新增 Demo 状态层仅依赖发布 SDK 的公开接口；ViewModel 不持有 Activity、原生 View 或 Activity 绑定会话。
- 先验证三个 Demo 变体、相关单测/Lint和 apiCheck，公共 API 基线不得机械重建。
- 真机、Release/R8、发布消费与授权分别记录，不用编译成功代替设备验收。

其余敏感信息与调试产物规则见 [提交卫生](docs/REPOSITORY_HYGIENE.md)。
