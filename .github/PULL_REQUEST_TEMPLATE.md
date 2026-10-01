---
name: Pull request 模板
about: 提交代码变更
title: ''
labels: ''
assignees: ''
---

## 变更说明

<!-- 一两句话说明本次变更的目的与方案 -->

## 变更类型

- [ ] 缺陷修复（fix）
- [ ] 新功能（feat）
- [ ] 重构（refactor，不改行为）
- [ ] 文档
- [ ] 工程基建（CI / 构建 / 依赖）

## 自检清单

- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] `./gradlew :app:lintDebug` 无新增 error
- [ ] 涉及供应商行为变化的，已在真机/模拟器验证对应供应商
- [ ] 涉及存储结构变化的，已考虑旧版本升级兼容
- [ ] 公开文档（README / doc/开发指南.md）已同步
