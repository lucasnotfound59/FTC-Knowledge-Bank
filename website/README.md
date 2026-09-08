# FTC Knowledge Bank 文档站

Astro Starlight 静态文档站。七个一级栏目由 `scripts/content-map.mjs` 定义，正文由 `scripts/content.mjs` 从仓库现有技术文档与教程生成。

## 本地工作

在本目录使用 Node.js 24：

```bash
npm ci
npm run build
npm run preview
```

生成的 `src/content/docs` 与 `dist` 不入 Git；应修改原始文档、内容清单或生成脚本。网站只导入明确列出的文档，不发布整个 docs 目录。

## 自动构建与部署

网站与知识库共用 GitHub 仓库 `lucasnotfound59/FTC-Knowledge-Bank`。Cloudflare Pages 项目为 `ftckb-docs`，生产分支为 `main`。

Cloudflare 配置：

| 设置 | 值 |
| --- | --- |
| Root directory | 仓库根目录（留空） |
| Build command | `npm --prefix website ci && npm --prefix website run build` |
| Build output directory | `website/dist` |
| NODE_VERSION | `24` |

必须保留整个仓库，因为构建会读取 docs 和 knowledge/guides。根目录的 `.node-version` 固定 Node 主版本；website/package-lock.json 锁定依赖。

推送 main 后，Cloudflare 自动拉取源码、安装依赖、生成文档并部署。构建失败时保留上一次成功部署。PR 预览以 Cloudflare 项目设置为准。

文档域名 `ftckb.lucasxl.com` 已绑定到 `ftckb-docs`，CNAME 指向 `ftckb-docs.pages.dev`。原 Direct Upload 项目 `ftckb` 保留作为迁移回退。

原始 Markdown 的相对链接会映射到网站页面，其他仓库链接使用 scripts/content.mjs 中的固定来源版本；更新来源版本时需要审阅。

## 已执行验证

2026-09-08：35 个内容页面加 404 页面在本地和 Cloudflare 构建成功，生成搜索索引；输出 HTML 的本地资源与页面链接无缺失；ftckb check 无硬违规。Cloudflare 已从 GitHub main 拉取源码完成首次部署。未将这些检查表述为机器人测试或核心 Kotlin 测试。


## README 内容迁移

第二版已发布：README 从 698 行精简至 41 行，详细内容迁入 docs/handbook 并生成 35 个内容页面。构建与链接、规则检查通过。用户手动上传构建包完成部署；2026-09-08 通过 Chrome 验证 https://ftckb.lucasxl.com/reference/rule-schema/ 正常加载，包含 schema v3 checks 字段和新增文档导航。
