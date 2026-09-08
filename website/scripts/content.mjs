import {readFile,mkdir,writeFile,rm} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {groups} from './content-map.mjs';

const root=fileURLToPath(new URL('../../',import.meta.url));
const output=path.join(root,'website/src/content/docs');
const sourceUrl='https://github.com/lucasnotfound59/FTC-Knowledge-Bank/blob/62307d8/';
const integration=await readFile(path.join(root,'docs/project-integration.md'),'utf8');
const cli=await readFile(path.join(root,'docs/cli-agent.md'),'utf8');
const technical=await readFile(path.join(root,'docs/website/integration-and-checks.md'),'utf8');
const sourceRoutes=new Map(groups.flatMap(g=>g.pages.filter(p=>p[2]).map(([slug,,source])=>[source,`/${slug}/`])));

function section(text,heading){
  const lines=text.split('\n');
  const start=lines.findIndex(line=>line===`## ${heading}`);
  if(start<0)throw new Error(`Missing source section: ${heading}`);
  let end=start+1;
  while(end<lines.length&&!lines[end].startsWith('## '))end++;
  return lines.slice(start+1,end).join('\n').trim();
}

function links(text,source){
  return text.replace(/\[([^\]]*)\]\(([^\s)]+)\)/g,(match,label,href)=>{
    if(/^(https?:|mailto:|#|\/)/.test(href))return match;
    const [file,fragment]=href.split('#');
    const target=path.posix.normalize(path.posix.join(path.posix.dirname(source),file));
    // Route imported documents to their published page. Omit source fragments
    // because normalized headings may have different generated anchors.
    const route=sourceRoutes.get(target);
    return `[${label}](${route??sourceUrl+target+(fragment?'#'+fragment:'')})`;
  });
}

const pieces={
  'introduction':section(technical,'一、项目介绍：将规范接入日常编码')+'\n\n## 从这里开始\n\n- [安装与第一次校验](/getting-started/)\n- [接入 FTC 项目](/getting-started/integrate/)\n- [Agent 编码工作流](/agent/workflow/)\n- [FTC 实践教程](/tutorials/sdk/)',
  'introduction/use-cases':'## 适合谁\n\n- **队员**：配置工具、学习示例，并查阅有来源的工程经验。\n- **编码 Agent 使用者**：在修改前取得规则，在修改后检查 diff。\n- **队伍维护者**：审阅候选知识、固定知识库版本并组织协作。\n\n## 已有能力\n\n规则加载与裁决、确定性检查、项目级接入、候选提取与审批、CLI 聊天与编辑、本地网页和 Android Studio 插件。\n\n## 能力边界\n\n静态检查不能证明机器人安全；Run 模式与官方文档联网检索尚未实现。Agent 是否自动发现 Skill 取决于其产品支持。详见[版本与验证范围](/reference/status/)。',
  'getting-started':section(technical,'二、快速开始：安装与第一次校验'),
  'concepts/rules':'## 一条规则包含什么\n\n规则记录 id、topic、title、instruction、rationale、status、authority、applicability 和 evidence，也可附带机器 checks。规则正文说明要求和理由，证据记录来源，适用范围表达队伍与赛季。\n\n## 来源与证据\n\nGit 证据记录仓库、固定提交、文件及符号或行；网页证据记录 HTTPS 地址、标题、发布者、核验日期和章节。记录来源不等于验证内容为真，也不意味着 validate 会联网核验网页。\n\n## 硬检查与软提醒\n\n只有定义了 checks 的规则才参与对应的机器执法。其余生效规则作为 soft 提醒交给 Agent 和人评估。规则优先级由 resolve 实现。\n\n详见[JSON 契约](/reference/kernel/)和[检查语义](/reference/checks/)。',
  'concepts/resolution':section(technical,'三、核心概念：版本、规则与审批')+'\n\n## 为什么先调用 resolve\n\n同一队伍、赛季与知识输入产生确定性结果。OFFICIAL > TEAM > SHARED 的优先级以及冲突检测由内核处理；Agent 不应直接读 YAML 自行裁决。冲突未解决时不能把结果当作正常生效规则集。\n\n20827 与 16093 的队伍规则状态不同，不能互换结果。',
  'concepts/approval':'## 从候选到生效\n\n提取工具提出的规则首先是候选。维护者检查内容、证据、适用范围与审批权限，再决定批准或驳回。候选保持 inactive，不进入 resolve 的 activeRules。\n\n## 审批与验证不同\n\n审批表达队伍或维护者对规则的认可；validate 检查知识结构与约束；check 检查当前 diff。三个步骤不能互相替代。\n\n操作见[候选提取与审批](/reference/candidates/)，字段见[JSON 契约](/reference/kernel/)。',
  'guides/local-web':section(cli,'本地网页会话（ftckb serve）')+'\n\n## 与文档站的关系\n\n本文档站是静态说明网站。访问 ftckb.lucasxl.com 不会连接你的本地项目；本地网页 Agent 需要在自己的电脑上单独启动。',
  'agent/workflow':section(integration,'每次编码与验收'),
  'agent/configuration':section(integration,'配置与重复执行'),
  'agent/verification':'## 完整验收\n\n'+section(integration,'每次编码与验收')+'\n\n## 错误恢复\n\n'+section(integration,'fresh clone、升级和失败恢复'),
  'agent/upgrades':section(integration,'fresh clone、升级和失败恢复'),
  'reference/contributing':'## 维护流程\n\n1. 在知识库对应的 official、shared 或 teams 目录提出候选规则。\n2. 填写来源、适用范围、明确的要求和理由。\n3. 按[候选审批流程](/reference/candidates/)审阅和审批。\n4. 修改知识后执行 `ftckb validate knowledge --json`，确认退出 0 且 `ok:true`。\n5. 使用目标队号和赛季 resolve，检查规则和冲突，再对实际 diff 执行 check。\n\n## 贡献边界\n\n不要上传密钥、个人 SDK 路径或未经允许公开的队伍信息。只有适用范围明确、能可靠通过文本判定的约束才适合硬检查；行为要求保留人工验证步骤。\n\n完整字段与创建示例参见[仓库规则维护手册](https://github.com/lucasnotfound59/FTC-Knowledge-Bank#创建候选规则)。',
  'reference/ci':section(integration,'可选 CI 门禁（不自动安装）'),
  'reference/status':'## 版本说明\n\n项目接入功能已在仓库提交 `62307d8` 中实现；发布 tag、CLI 版本和 kernel schemaVersion 是独立版本层。首次安装必须选择包含接入功能的可取得版本，不能把 README 的 V0.1.1 文案当作有效 tag。\n\n## 已知限制\n\n'+section(integration,'规则会不会太严格？')+'\n\n## 开发验收记录\n\n以下是现有文档记录的验收结果，不代表文档站构建时重新运行机器人或核心测试。\n\n'+section(integration,'开发验收'),
};

await mkdir(output,{recursive:true});
// This directory contains generated public docs only; authoring sources live elsewhere.
await rm(output,{recursive:true,force:true});
await mkdir(output,{recursive:true});
for(const group of groups){
  for(const [slug,title,source] of group.pages){
    let body=source?await readFile(path.join(root,source),'utf8'):pieces[slug];
    if(!body)throw new Error(`No content for ${slug}`);
    body=body.replace(/^# [^\n]+\n/,'');
    body=links(body,source??'docs/project-integration.md');
    const file=path.join(output,slug+'.md');
    await mkdir(path.dirname(file),{recursive:true});
    await writeFile(file,`---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(`FTC Knowledge Bank · ${group.label} · ${title}`)}\n---\n\n${body}\n`);
  }
}
await writeFile(path.join(output,'index.mdx'),`---
title: 查 FTC 教程，让 AI 按队伍要求写代码。
description: FTC Knowledge Bank 面向队伍与编码 Agent 的工程知识与确定性规则工具。
template: splash
hero:
  title: 查 FTC 教程，\n    让 AI 按队伍要求写代码。
  tagline: 查工具怎么用、向 AI 问代码、检查修改是否符合队伍要求。从这里了解每项功能怎么帮到你。
  actions:
    - text: 先看看能做什么
      link: /introduction/
      icon: right-arrow
    - text: 开始使用
      link: /getting-started/
      variant: secondary
---

import {Card, CardGrid, LinkCard} from '@astrojs/starlight/components';

## 让 AI 改代码时，多做两步检查

<CardGrid>
  <Card title="01 · 先看队伍要求" icon="document">告诉工具队号和赛季，取得这次适用的规则。AI 应先读这些要求，再动手。</Card>
  <Card title="02 · 让 AI 修改代码" icon="pencil">继续使用你熟悉的 AI 编程工具，告诉它要改什么，并要求它遵循项目中的规则。</Card>
  <Card title="03 · 看修改是否合规" icon="approve-check">工具检查本次修改，列出发现的问题和需要人确认的事项。你还要完成编译与真机测试。</Card>
</CardGrid>

## 从问题找到文档

<CardGrid>
${groups.map(g=>`  <LinkCard title="${g.label}" href="/${g.pages[0][0]}/" description="${g.pages.map(p=>p[1]).slice(0,3).join(' · ')}" />`).join('\n')}
</CardGrid>

## 阅读教程不用安装，改代码要在自己电脑上操作

这个网站是说明书，可以直接查阅。要让 AI 使用队伍规则，需要先接入本地项目。选规则和检查代码不需要额外模型 key；自带的 AI 聊天功能需要配置模型服务。

规则与教程保留来源和适用边界。静态检查通过不能替代机器人上的实际验证。
`);
console.log(`Generated ${groups.reduce((n,g)=>n+g.pages.length,0)+1} public pages across ${groups.length} sections.`);
