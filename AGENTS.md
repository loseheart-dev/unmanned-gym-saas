# 无人健身房 SaaS 项目规范

## Commit 和 PR

- 标题统一使用 `type: 描述`，也可用 `type(scope): 描述`。使用半角冒号 `:`，后接一个空格；描述可用中文或英文，简洁说明实际改动。
- 类型用小写：`feat` 新功能、`fix` 修复、`docs` 文档、`style` 格式、`refactor` 重构、`perf` 性能、`test` 测试、`build` 构建、`ci` 持续集成、`chore` 维护、`revert` 回退。有破坏性变更时可在冒号前加 `!`。
- 示例：`feat: 新增预约接口`、`fix(auth): 修复令牌校验`、`ci: 检查 PR 和提交标题`。Git 自动生成的合并提交不受此格式限制。
- PR 正文说明改动、验证结果及尚未验证的范围；不用为统一格式重写已经推送的历史提交。

## 后端协作

- 沿用根目录 Maven Wrapper 和 `backend` 模块，Java 基础包为 `com.hiking.gymsaas`。业务代码沿用现有 `controller`、`service/impl`、`mapper` 和 `model/{dto,vo,entity}` 分层；不把业务改动和目录重构混在同一条 PR 里。
- 后端改动用 Java 21 运行 `./mvnw verify`；如果数据库集成测试被跳过，在 PR 中说明。
- `pom.xml` 和 Maven Wrapper 是共享构建文件；`.env`、真实密钥及 IDE 个人配置不提交。
- 本文件是团队共用规范，随仓库维护。个人 Agent 规则写在本地 `AGENTS.override.md`，不提交。
