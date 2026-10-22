# Sensitive-lexicon 来源说明

- 上游仓库：https://github.com/konsheng/Sensitive-lexicon
- 采用版本：`5a8da94c61c160e203a6b2fcfafbea642404d50c`
- 上游版本日期：2026-06-15
- 许可证：MIT
- 参考文件：`Vocabulary/其他词库.txt`、`Vocabulary/补充词库.txt`、`Vocabulary/暴恐词库.txt`、`Vocabulary/网易前端过滤敏感词库.txt`、`Vocabulary/零时-Tencent.txt` 和 `ThirdPartyCompatibleFormats/TrChat/SensitiveLexicon.json`

本项目没有将上游数万条词全部用于“辱骂响应”。上游还包含政治、色情、广告、药品、网址、枪爆等内容，直接全部导入会把正常业务咨询误判为辱骂。

项目只保留适合客服场景的明确辱骂、人身攻击和暴力威胁表达，并排除歧义较大的单字、常用词及无关分类。随包词表会成为网页 AI 配置中心的默认值，管理员仍可逐条增删后发布。
