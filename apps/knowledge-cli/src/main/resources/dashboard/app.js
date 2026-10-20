'use strict';
(() => {
  // The launcher credential exists only in this page's memory. It is never persisted.
  const fragment = new URLSearchParams(location.hash.slice(1));
  const token = fragment.get('token') || '';
  if (location.hash) history.replaceState(null, '', location.pathname + location.search);
  const $ = id => document.getElementById(id);
  const empty = node => { node.replaceChildren(); return node; };
  const el = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  };
  const append = (node, ...children) => { children.flat().filter(Boolean).forEach(child => node.append(child)); return node; };
  const list = value => Array.isArray(value) ? value : [];
  const lower = value => String(value ?? '').toLowerCase();
  const human = value => labels[lower(value)] || String(value ?? '—');
  const labels = {
    approved:'已批准', candidate:'候选', rejected:'已拒绝', deprecated:'已弃用',
    official:'官方来源', shared:'共享', team:'队伍来源', global:'全局', local:'本地',
    hard:'硬检查', conditional_soft:'条件式 soft', unconditional_soft:'无条件 soft',
    started:'未完成', completed:'已完成', failed:'运行失败', passed:'通过', blocked:'拦截',
    not_run:'未执行', not_applicable:'不适用', soft:'待复核', dev_downgrade:'dev 降级提醒',
    parameters:'参数校验',knowledge_load:'加载知识库',knowledge_validate:'知识库校验',
    resolve:'规则裁决',diff_read:'读取补丁',standardizer:'diff 规范检测',path_guard:'路径保护',
    branch_guard:'分支保护',citation_guard:'引用保护',baseline_guard:'基线与版本检查',
    write:'写入',restore:'恢复',model:'模型调用',validate:'校验',check:'补丁检测',
    ask:'询问',edit:'编辑',undo:'撤销',discard:'丢弃',rule_create:'新增候选',
    rule_edit:'修改规则',rule_approve:'批准规则',rule_reject:'拒绝规则',
    cli:'CLI',chat:'聊天',serve:'网页 Agent',dashboard:'管理台',
    usage:'参数错误',load_error:'加载失败',invalid_knowledge:'知识无效',conflict:'规则冲突',
    stale_baseline:'代码基线已变更',stale_knowledge:'规则版本已变更',model_failure:'模型失败',storage_error:'存储错误',
    normal:'normal',test:'test',dev:'dev',overall_software_lead:'总软件负责人',team_software_lead:'队伍软件负责人'
  };
  const tone = value => {
    const v = lower(value);
    if (['passed','approved','completed'].includes(v)) return 'success';
    if (['blocked','failed','rejected','hard','invalid_knowledge','conflict','load_error'].includes(v)) return 'danger';
    if (['candidate','started','soft','dev_downgrade','conditional_soft','unconditional_soft'].includes(v)) return 'warning';
    return ['official','global','dev','test'].includes(v) ? 'info' : '';
  };
  const badge = (text, kind) => el('span', 'badge ' + (kind || ''), text);
  const statusBadge = status => badge(human(status), tone(status));
  const shorten = value => value ? String(value).slice(0, 12) : '—';
  const formatTime = value => {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', {hour12:false});
  };
  const state = {
    view:'overview',summary:null,rules:[],rulesVersion:null,selectedRule:null,detail:null,
    recent:[],audit:[],auditScope:'knowledge',auditRevision:null,auditCursor:null,
    auditNext:null,auditStack:[],selectedAudit:null,auditLoaded:false,filters:{},
    resolveResult:null,checkResult:null,mutation:null,online:false,pollBusy:false,
    reviews:[],reviewsLoaded:false,reviewFilter:'pending',reviewCursor:null,reviewNext:null,reviewStack:[],selectedReview:null,reviewDetail:null,recheck:null
  };
  let toastTimer;
  function toast(message, error = false) {
    $('toast').textContent = message; $('toast').classList.toggle('error', error); $('toast').hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, error ? 7000 : 4000);
  }
  function errorNotice(error, prefix) {
    const box = el('div','notice danger');
    append(box, el('strong','',prefix || '操作未完成'),el('p','',error.message || String(error)));
    if (error.code) append(box,el('p','mono small',error.code));
    appendErrors(box,error.violations);
    return box;
  }
  function appendErrors(node, errors) {
    if (!list(errors).length) return;
    const ul = el('ul');
    errors.forEach(error => append(ul,el('li','',typeof error === 'string' ? error : [error.path,error.ruleId,error.field,error.message || error.detail].filter(Boolean).join(' · '))));
    node.append(ul);
  }
  async function api(path, body) {
    if (!token) throw Object.assign(new Error('缺少本次启动的访问凭据。请重新打开终端输出的完整管理台链接。'),{code:'token_missing'});
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30000);
    let response;
    try {
      response = await fetch('/api/dashboard/' + path,{
        method:body === undefined ? 'GET' : 'POST', cache:'no-store', credentials:'same-origin',
        headers:{Authorization:'Bearer ' + token,...(body === undefined ? {} : {'Content-Type':'application/json'})},
        ...(body === undefined ? {} : {body:JSON.stringify(body)}),signal:controller.signal
      });
      const data = await response.json();
      if (data.apiVersion !== 1) throw Object.assign(new Error('管理台接口版本不兼容，请使用与服务端匹配的页面。'),{code:'unsupported_api'});
      if (!response.ok || data.ok === false) throw Object.assign(new Error(data.error?.message || '请求失败（HTTP ' + response.status + '）'),data.error || {},{httpStatus:response.status});
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('服务响应超时；保留上一次数据，请稍后重试。');
      throw error;
    } finally { clearTimeout(timer); }
  }
  async function runButton(button, task) {
    if (button.disabled) return;
    const original = button.textContent;
    button.disabled = true; button.textContent = '处理中…';
    try { return await task(); }
    finally { button.disabled = false; button.textContent = original; }
  }
  function connected(ok, error) {
    state.online = ok;
    $('connection').className = 'connection ' + (ok ? 'online' : 'offline');
    empty($('connection')); append($('connection'),el('i'),document.createTextNode(ok ? '已连接' : '连接中断'));
    $('connection-alert').hidden = ok;
    if (!ok) $('connection-alert').textContent = (error?.message || '无法连接本地服务。') + ' 当前展示上一次读取的数据，可能已过期。';
    if (ok) $('last-refresh').textContent = '更新于 ' + new Date().toLocaleTimeString('zh-CN',{hour12:false});
  }
  function showView(view) {
    if (!['overview','rules','resolve','audit','reviews'].includes(view)) return;
    state.view = view;
    document.querySelectorAll('.view').forEach(node => { node.hidden = node.id !== 'view-' + view; });
    document.querySelectorAll('[data-view]').forEach(node => {
      const active = node.dataset.view === view; node.classList.toggle('active',active);
      active ? node.setAttribute('aria-current','page') : node.removeAttribute('aria-current');
    });
    if (view === 'rules') loadRules().catch(error => toast(error.message,true));
    if (view === 'audit' && !state.auditLoaded) loadAudit().catch(error => toast(error.message,true));
    if (view === 'reviews' && !state.reviewsLoaded) loadReviews().catch(error => toast(error.message,true));
  }
  function stat(value, label, className = '') {
    const node = el('div',className); append(node,el('div','stat-value',value ?? '—'),el('span','stat-label',label)); return node;
  }
  function auditHealth(node, health) {
    empty(node);
    if (health && !health.ok) {
      const box = el('div','notice warning health-warning'); append(box,el('strong','','审计存储需要关注'));
      appendErrors(box,health.warnings); node.append(box);
    }
  }
  function renderOverview() {
    const summary = state.summary;
    if (!summary) return;
    const k = summary.knowledge, a = summary.audit;
    const libraryAlert = empty($('knowledge-alert')); libraryAlert.hidden = k.valid;
    if (!k.valid) {
      const notice = el('div','notice danger');
      append(notice,el('strong','','当前知识库无效'),el('p','','目录仅展示当前文件中可读取的规则，不代表知识库已通过校验。'));
      appendErrors(notice,k.errors); libraryAlert.append(notice);
    }
    $('knowledge-root').textContent = k.root; $('knowledge-root').title = k.root;
    const integrity = el('div','integrity ' + (k.valid ? '' : 'invalid'));
    const message = el('div');
    append(message,el('strong','',k.valid ? '当前知识库校验有效' : '当前知识库存在错误'),el('p','',k.valid ? '规则目录来自当前文件快照。是否生效，仍需指定上下文后裁决。' : '以下为当前文件的错误；不会将旧的有效缓存标记为最新。'));
    append(integrity,el('span','integrity-icon',k.valid ? '✓' : '!'),message,badge(k.valid ? '有效快照' : '待修复',k.valid ? 'success' : 'danger'));
    empty($('overview-integrity')).append(integrity);
    if (!k.valid) {
      const errors = el('div','notice danger'); appendErrors(errors,k.errors); $('overview-integrity').append(errors);
    }
    empty($('rule-stats')); append($('rule-stats'),stat(k.ruleCount,'规则总数'),stat(k.approved,'已批准'),stat(k.candidate,'候选'));
    $('overview-version').textContent = 'SNAPSHOT / ' + (k.version || '—');
    const stats = el('div','audit-stats');
    append(stats,stat(a?.diffChecked,'已检测','audit-stat'),stat(a?.diffNotRun,'未执行','audit-stat'),stat(a?.diffNotApplicable,'不适用','audit-stat'),stat(a?.unfinished,'未完成','audit-stat'));
    empty($('audit-stats')).append(stats);
    const context = el('p','panel-note','已记录顶层调用 ' + (a?.total ?? 0) + ' 次 · 其中硬拦截 ' + (a?.diffBlocked ?? 0) + ' 次。未完成为生命周期状态，可能与检测状态重叠。');
    $('audit-stats').append(context);
    const health = el('div'); auditHealth(health,a?.health); $('audit-stats').append(health);
    renderRecent();
    const scoped = state.auditScope === 'knowledge' ? summary.audit : summary.unscopedAudit;
    $('audit-total').textContent = '已记录顶层调用 ' + (scoped?.total ?? 0) + ' 次';
  }
  function miniStages(record) {
    const rail = el('div','mini-stages');
    list(record.stages).forEach(stage => {
      const mark = el('span','mini-stage ' + lower(stage.status));
      mark.title = human(stage.name) + '：' + human(stage.status); rail.append(mark);
    });
    if (!rail.children.length) rail.append(el('span','muted small','尚无阶段记录'));
    rail.setAttribute('aria-label',list(record.stages).map(stage => human(stage.name) + '：' + human(stage.status)).join('；') || '尚无阶段记录');
    return rail;
  }
  function recordStatus(record) {
    if (lower(record.status) === 'started' || !record.endedAt) return statusBadge('started');
    if (list(record.stages).some(stage => lower(stage.status) === 'blocked')) return statusBadge('blocked');
    if (list(record.stages).some(stage => lower(stage.category) === 'dev_downgrade' || list(stage.hits).some(hit => lower(hit.kind) === 'dev_downgrade'))) return statusBadge('dev_downgrade');
    if (list(record.stages).some(stage => lower(stage.category) === 'soft' || list(stage.hits).some(hit => lower(hit.kind) === 'soft'))) return statusBadge('soft');
    return statusBadge(record.status);
  }
  function recordTitle(record) { return human(record.metadata?.command) + ' / ' + human(record.metadata?.entrypoint); }
  function openAudit(record) {
    if (state.auditScope !== 'knowledge') {
      state.auditScope = 'knowledge'; $('audit-scope').value = 'knowledge'; state.auditLoaded = false;
      state.auditCursor = null; state.auditStack = []; state.selectedAudit = null;
    }
    showView('audit'); loadAuditDetail(record.invocationId).catch(error => toast(error.message,true));
  }
  function renderRecent() {
    const node = empty($('recent-calls'));
    if (!state.recent.length) { node.append(emptyState('尚无已记录调用','执行校验、裁决或补丁检测后，记录会出现在这里。')); return; }
    state.recent.slice(0,5).forEach(record => {
      const row = el('div','call-row'); row.tabIndex = 0; row.setAttribute('role','button');
      const info = el('div'); append(info,el('div','call-title',recordTitle(record)),el('div','call-meta',formatTime(record.startedAt) + ' · ' + shorten(record.invocationId)));
      append(row,info,miniStages(record),recordStatus(record));
      row.onclick = () => openAudit(record); row.onkeydown = e => { if (['Enter',' '].includes(e.key)) { e.preventDefault(); openAudit(record); } };
      node.append(row);
    });
  }
  function emptyState(title, description) { return append(el('div','empty-state'),el('h2','',title),el('p','',description)); }
  async function poll(force = false) {
    if (state.pollBusy) return;
    state.pollBusy = true;
    try {
      const summary = await api('summary');
      const old = state.summary;
      state.summary = summary;
      connected(true); renderOverview();
      const versionChanged = old?.knowledge?.version !== summary.knowledge.version;
      const auditChanged = old?.audit?.revision !== summary.audit?.revision;
      if (force || auditChanged) {
        const page = await api('audit?limit=5&scope=knowledge'); state.recent = list(page.items); renderRecent();
      }
      if ((force || auditChanged) && state.view === 'reviews') await loadReviews();
      if ((force || versionChanged || state.rulesVersion !== summary.knowledge.version) && state.view === 'rules') await loadRules(true);
      if (versionChanged && old && state.resolveResult) markResultStale($('resolve-result'));
      if (versionChanged && old && state.checkResult) markResultStale($('check-result'));
      const revision = state.auditScope === 'knowledge' ? summary.audit?.revision : summary.unscopedAudit?.revision;
      if (state.auditLoaded && revision !== state.auditRevision) {
        if (!state.auditCursor && state.view === 'audit') await loadAudit();
        else $('audit-stale').hidden = false;
      }
    } catch (error) { connected(false,error); }
    finally { state.pollBusy = false; }
  }
  function markResultStale(node) {
    if (!node.querySelector('.stale-result')) node.prepend(el('div','notice warning stale-result','知识库版本已变化。下方保留之前的检测结果，请重新执行以使用当前规则。'));
  }
  const facets = [
    ['status','状态',r => [r.status]],['authority','来源 authority',r => [r.authority]],['policyLevel','策略 policyLevel',r => [r.policyLevel]],
    ['team','队伍',r => list(r.applicability?.teams)],['season','赛季',r => list(r.applicability?.seasons)],['profile','Profile',r => list(r.applicability?.profiles)],
    ['checkMode','检查方式',r => [r.checkMode]],['topic','主题',r => [r.topic]]
  ];
  function renderFilters() {
    const node = empty($('rule-filters'));
    facets.forEach(([key,title,extract]) => {
      const label = el('label','',title), select = el('select'); select.id = 'filter-' + key;
      select.append(new Option('全部',''));
      if (['team','season','profile'].includes(key)) select.append(new Option('不限范围','__all__'));
      [...new Set(state.rules.flatMap(extract).filter(Boolean))].sort().forEach(value => select.append(new Option(human(value),value)));
      select.value = state.filters[key] || '';
      select.onchange = () => { state.filters[key] = select.value; renderRules(); };
      label.append(select); node.append(label);
    });
  }
  async function loadRules(force = false) {
    if (!force && state.rulesVersion && state.rulesVersion === state.summary?.knowledge?.version) { renderRules(); return; }
    const response = await api('rules');
    state.rules = list(response.rules); state.rulesVersion = response.version;
    renderFilters(); renderRules();
    if (state.selectedRule) await loadRuleDetail(state.selectedRule,false);
  }
  function renderRules() {
    const focusedId = $('rule-list').contains(document.activeElement) ? document.activeElement.dataset.ruleId : null;
    const query = $('rule-search').value.trim().toLocaleLowerCase();
    const rules = state.rules.filter(rule => {
      if (query && ![rule.id,rule.title,rule.instruction,rule.rationale,rule.positiveExample,rule.negativeExample].filter(Boolean).join(' ').toLocaleLowerCase().includes(query)) return false;
      return facets.every(([key,,extract]) => {
        const value = state.filters[key]; if (!value) return true;
        const values = extract(rule); return value === '__all__' ? !values.length : values.includes(value);
      });
    });
    $('rule-count').textContent = rules.length + ' / ' + state.rules.length + ' 条规则';
    const node = empty($('rule-list'));
    if (!rules.length) { node.append(emptyState('没有匹配的规则','调整关键词或清除筛选后重试。')); return; }
    rules.forEach(rule => {
      const item = el('button','rule-item' + (state.selectedRule === rule.id ? ' selected' : ''));
      item.dataset.ruleId = rule.id; item.setAttribute('aria-pressed',String(state.selectedRule === rule.id));
      append(item,el('div','rule-id',rule.id),el('h3','',rule.title),append(el('div','badge-row'),statusBadge(rule.status),badge(human(rule.authority),'outline'),badge(human(rule.policyLevel),'outline'),badge(human(rule.checkMode),tone(rule.checkMode))));
      item.onclick = () => loadRuleDetail(rule.id,true).catch(error => toast(error.message,true)); node.append(item);
      if (focusedId === rule.id) item.focus({preventScroll:true});
    });
  }
  function section(title, content) { const node = el('section','field-section'); append(node,el('h3','',title),content); return node; }
  function property(label,value,mono = false) { return append(el('div','property'),el('span','label',label),el('span','value' + (mono ? ' mono' : ''),value ?? '—')); }
  function structured(value) {
    const items = Array.isArray(value) ? value : value ? [value] : [];
    if (!items.length) return el('p','muted small','未设置');
    const ul = el('ul','structured-list');
    items.forEach(item => {
      const li = el('li');
      if (typeof item !== 'object' || item === null) li.textContent = String(item);
      else {
        const dl = el('dl');
        Object.entries(item).forEach(([key,value]) => {
          append(dl,el('dt','',key),el('dd','mono',Array.isArray(value) ? value.join('\n') : typeof value === 'object' && value !== null ? JSON.stringify(value,null,2) : value ?? '—'));
        }); li.append(dl);
      }
      ul.append(li);
    }); return ul;
  }
  async function loadRuleDetail(id, scroll) {
    state.selectedRule = id;
    const response = await api('rule?id=' + encodeURIComponent(id));
    if (state.selectedRule !== id) return;
    state.detail = response; renderRules();
    const rule = response.rule, node = empty($('rule-detail'));
    const header = el('div','detail-header');
    append(header,el('div','rule-id',rule.id),el('h2','',rule.title),append(el('div','badge-row'),statusBadge(rule.status),badge(human(rule.authority),'outline'),badge(human(rule.policyLevel),'outline'),badge(human(rule.checkMode),tone(rule.checkMode))));
    const actions = el('div','detail-actions');
    const edit = el('button','button','编辑 YAML / 适用范围'); edit.onclick = () => openEditor('edit',response); actions.append(edit);
    if (lower(rule.status) === 'candidate') ['approve','reject'].forEach(action => {
      const button = el('button','button ' + (action === 'reject' ? 'danger' : ''),action === 'approve' ? '批准候选' : '拒绝候选');
      button.onclick = () => openEditor(action,response); actions.append(button);
    });
    if (!response.source?.ruleYaml) { edit.disabled = true; edit.title = '源规则定位不唯一或不可编辑'; }
    header.append(actions); node.append(header);
    const body = el('div','detail-body');
    append(body,section('归属与适用范围',append(el('div','property-grid'),property('主题',rule.topic,true),property('来源 authority',rule.authority),property('策略 policyLevel',rule.policyLevel),property('队伍',list(rule.applicability?.teams).join('、') || '不限队伍'),property('赛季',list(rule.applicability?.seasons).join('、') || '不限赛季'),property('Profiles',list(rule.applicability?.profiles).join('、') || '不限 profile'))));
    body.append(section('指令 instruction',el('p','prose',rule.instruction)));
    body.append(section('理由 rationale',el('p','prose',rule.rationale)));
    body.append(section('来源证据 evidence',structured(rule.evidence)));
    body.append(section('批准记录 approval',structured(rule.approval)));
    body.append(section('硬检查 checks',structured(rule.checks)));
    body.append(section('复核触发条件 reviewTriggers',structured(rule.reviewTriggers)));
    body.append(section('正向示例 positiveExample',el('pre','raw-data',rule.positiveExample ?? '未设置')));
    body.append(section('反向示例 negativeExample',el('pre','raw-data',rule.negativeExample ?? '未设置')));
    body.append(section('替代规则 supersedes',el('p','mono small',rule.supersedes ?? '未设置')));
    body.append(section('源文件',append(el('div','property-grid'),property('相对路径',response.source?.path || '不可定位',true),property('文件摘要',response.source?.fileDigest,true))));
    body.append(el('p','notice','文件组织与规则归属分别表示。来源、策略和适用范围由字段定义，不能从路径或 ID 推断。'));
    node.append(body);
    if (scroll && matchMedia('(max-width:650px)').matches) node.scrollIntoView({behavior:matchMedia('(prefers-reduced-motion:reduce)').matches ? 'auto' : 'smooth',block:'start'});
  }
  function contextForm(node,prefix) {
    const grid = el('div','context-grid');
    [['team','队号','text','输入真实队号'],['season','赛季','text','例如 YYYY-YYYY']].forEach(([name,labelText,type,placeholder]) => {
      const label = el('label','',labelText), input = el('input');
      input.id = prefix + '-' + name; input.type = type; input.placeholder = placeholder; input.autocomplete = 'off';
      if (name === 'team') input.inputMode = 'numeric'; label.append(input); grid.append(label);
    });
    const profileLabel = el('label','','Profile（必须明确选择）'), profiles = el('select'); profiles.id = prefix + '-profile';
    [['','选择架构范围'],['generic','generic / 不启用命名 profile'],['command-based','command-based'],['ftclib-command','ftclib-command'],['simple-opmode','simple-opmode'],['rookiebot','rookiebot']].forEach(([value,label]) => profiles.append(new Option(label,value)));
    profileLabel.append(profiles); grid.append(profileLabel);
    const modeLabel = el('label','','本次工作模式'), mode = el('select'); mode.id = prefix + '-mode';
    [['normal','normal / 正常'],['test','test / 明确的测试代码'],['dev','dev / 明确的开发补丁']].forEach(([value,label]) => mode.append(new Option(label,value)));
    modeLabel.append(mode); grid.append(modeLabel);
    append(node,grid,el('p','context-hint','normal 为默认。仅在本次任务明确是测试或开发代码时选择 test / dev。ftclib-command 包含 command-based；rookiebot 包含 simple-opmode。'));
  }
  function readContext(prefix) {
    const team = $(prefix + '-team').value.trim(), season = $(prefix + '-season').value.trim(), profile = $(prefix + '-profile').value;
    if (!team || !season || !profile) throw new Error('请填写真实队号、赛季，并明确选择 generic 或命名 profile。');
    return {team,season,profiles:profile === 'generic' ? [] : [profile],workMode:$(prefix + '-mode').value};
  }
  function contextLabel(context) {
    if (!context) return '未提供上下文';
    const profile = context.profiles == null ? 'profile 未指定' : context.profiles.length ? context.profiles.join(', ') : 'generic';
    return '队伍 ' + (context.team || '—') + ' · ' + (context.season || '—') + ' · ' + profile + ' · ' + (context.workMode || 'normal');
  }
  function reasonLabel(reason) {
    const names = {'candidate':'候选规则不参与生效','status-candidate':'候选规则不参与生效','not-approved':'未批准','team-mismatch':'队伍不匹配','season-mismatch':'赛季不匹配','profile-mismatch':'profile 不匹配','work-mode-test':'test 模式明确排除'};
    return names[reason] || reason;
  }
  function ruleLink(id) {
    const button = el('button','text-button mono',id);
    button.onclick = () => { showView('rules'); loadRuleDetail(id,true).catch(error => toast(error.message,true)); };
    return button;
  }
  function resolutionPanel(resolution,version,context) {
    const panel = el('div','panel result-panel');
    const active = list(resolution.activeRules), excluded = list(resolution.excludedRules), overridden = list(resolution.overriddenRules), conflicts = list(resolution.conflicts);
    const heading = el('div','result-heading'); append(heading,el('h2','','裁决结果'),badge(conflicts.length ? '存在同层级冲突' : '裁决完成',conflicts.length ? 'danger' : 'success')); panel.append(heading);
    append(panel,el('p','muted small',contextLabel(context)),el('p','mono small muted','SNAPSHOT / ' + (version || '—')));
    const counts = el('div','result-counts');
    append(counts,badge('生效 ' + active.length,'success'),badge('排除 ' + excluded.length),badge('被覆盖 ' + overridden.length,'info'),badge('冲突 ' + conflicts.length,conflicts.length ? 'danger' : ''));
    panel.append(section('裁决分布',counts));
    if (list(resolution.profiles).length) panel.append(el('p','muted small','引擎启用 profiles：' + resolution.profiles.join(', ')));
    [['同层级冲突',conflicts,'conflict'],['生效规则',active,'active'],['被覆盖规则',overridden,'overridden'],['排除规则',excluded,'excluded']].forEach(([title,items,kind]) => {
      const group = el('section','result-group'); append(group,el('h3','',title + ' / ' + items.length));
      if (!items.length) group.append(el('p','muted small','无'));
      items.forEach(item => {
        const row = el('div','result-item');
        if (kind === 'conflict') {
          append(row,el('strong','',item.topic),el('p','','最高有效层级：' + (item.effectiveLevel || '—')));
          list(item.ruleIds).forEach(id => row.append(ruleLink(id)));
          if (item.authorities) row.append(el('p','mono',Object.entries(item.authorities).map(([id,authority]) => id + ': ' + authority).join('\n')));
        } else {
          row.append(ruleLink(item.id || item.ruleId));
          if (kind === 'active') append(row,el('p','',item.title),append(el('div','badge-row'),badge(human(item.authority),'outline'),badge(human(item.policyLevel),'outline')));
          if (kind === 'excluded') row.append(el('p','',list(item.reasons).map(reasonLabel).join(' · ')));
          if (kind === 'overridden') append(row,el('p','','主题：' + item.topic + ' · 有效层级：' + item.effectiveLevel),el('p','mono','胜出规则：' + list(item.winnerIds).join(', ')));
        }
        group.append(row);
      }); panel.append(group);
    });
    panel.append(el('p','notice','裁决仅说明当前上下文的规则选择，尚不代表代码补丁已检查。'));
    return panel;
  }
  function hitNode(hit,defaultKind) {
    const kind = lower(hit.kind || defaultKind || 'hard'), node = el('div','hit ' + kind);
    const heading = el('div','badge-row'); append(heading,statusBadge(kind)); if (hit.check) heading.append(badge(hit.check,'outline')); node.append(heading);
    if (hit.ruleId) node.append(el('strong','',hit.ruleId));
    if (hit.path) node.append(el('p','mono',hit.path + (hit.line != null ? ':' + hit.line : '')));
    node.append(el('p','',hit.detail || hit.note || hit.message || '未提供说明'));
    return node;
  }
  async function resolveContext(event) {
    event.preventDefault();
    try {
      const context = readContext('resolve');
      await runButton(event.submitter,async () => {
        const response = await api('resolve',{context}); state.resolveResult = response;
        empty($('resolve-result')).append(resolutionPanel(response.resolution,response.version,context)); poll();
      });
    } catch (error) { empty($('resolve-result')).append(errorNotice(error,'裁决未完成')); }
  }
  async function checkPatch(event) {
    event.preventDefault();
    try {
      const context = readContext('resolve');
      await runButton(event.submitter,async () => {
        if (state.recheck && !$('recheck-confirm').checked) throw new Error('请重新提供本次补丁，并勾选确认。历史记录不保存补丁正文。');
        const response = await api('check',{context,diff:$('manual-diff').value,...(state.recheck ? {predecessorInvocationId:state.recheck} : {})}); state.checkResult = response;
        const node = empty($('check-result')), panel = el('div','result-panel');
        const violations = list(response.violations), soft = list(response.soft);
        append(panel,append(el('div','result-heading'),el('h2','','指定补丁检测结果'),badge(response.passed ? (soft.length ? '无硬拦截 · 仍需复核' : '未发现硬违规') : '硬违规拦截',response.passed ? soft.length ? 'warning' : 'success' : 'danger')));
        append(panel,el('p','muted small',contextLabel(context)),el('p','mono small muted','SNAPSHOT / ' + response.version));
        violations.forEach(hit => panel.append(hitNode(hit,'hard')));
        soft.forEach(hit => panel.append(hitNode(hit,context.workMode === 'dev' && (hit.check || hit.kind === 'dev_downgrade') ? 'dev_downgrade' : 'soft')));
        panel.append(el('p','notice warning','soft 与 dev 降级提醒均需逐项复核；检测通过不表示人工复核完成或机器人运行安全。'));
        if (response.invocationId) { const link = el('button','text-button','查看这次调用的阶段记录 →'); link.onclick = () => openAudit({invocationId:response.invocationId}); panel.append(link); }
        node.append(panel); poll();
      });
    } catch (error) { empty($('check-result')).append(errorNotice(error,'补丁检测未完成')); }
  }
  async function validateLibrary() {
    await runButton($('validate-library'),async () => {
      try {
        const response = await api('validate',{}), node = empty($('validate-result'));
        const box = el('div','notice ' + (response.valid ? 'success' : 'danger'));
        append(box,el('strong','',response.valid ? '完整知识库校验通过' : '完整知识库校验未通过'));
        appendErrors(box,response.violations); appendErrors(box,response.loadErrors);
        if (response.invocationId) { const link = el('button','text-button','查看校验记录 →'); link.onclick = () => openAudit({invocationId:response.invocationId}); box.append(link); }
        node.append(box); await poll(true);
      } catch (error) { empty($('validate-result')).append(errorNotice(error,'校验未完成')); }
    });
  }
  const template = 'schemaVersion: 4\nrules:\n  - id: new.rule-id\n    topic: new-topic\n    title: "填写规则标题"\n    instruction: "填写可执行的规则指令"\n    rationale: "填写规则理由"\n    status: candidate\n    authority: shared\n    policyLevel: shared\n    applicability:\n      teams: []\n      seasons: []\n      profiles: []\n    # 填写可核对的 git 或 web 证据；空 evidence 无法保存。\n    evidence: []\n    checks: []\n';
  function invalidatePreview() {
    if (!state.mutation) return;
    state.mutation.preview = null; $('apply-preview').disabled = true;
    if (!$('mutation-preview').hidden) {
      empty($('mutation-preview')).append(el('div','notice warning','输入已修改，请重新预览差异与校验。'));
    }
  }
  function openEditor(action, detail) {
    const rule = detail?.rule;
    state.mutation = {action,ruleId:rule?.id || '',baseVersion:detail?.version || state.summary?.knowledge?.version,preview:null};
    const approvalAction = ['approve','reject'].includes(action);
    $('editor-title').textContent = {create:'新增候选规则',edit:'编辑规则与适用范围',approve:'批准候选规则',reject:'拒绝候选规则'}[action];
    $('editor-id').value = rule?.id || ''; $('editor-id').readOnly = action !== 'create';
    $('editor-yaml').value = detail?.source?.ruleYaml || template;
    $('yaml-editor-section').hidden = approvalAction; $('approver-section').hidden = !approvalAction;
    $('approver-id').value = ''; $('approver-role').value = ''; $('approver-team').value = '';
    $('approver-id').required = approvalAction; $('approver-role').required = approvalAction; $('approver-team').required = false;
    $('preview-with-context').checked = false; $('preview-context').hidden = true;
    empty($('editor-error')); empty($('mutation-preview')); $('mutation-preview').hidden = true;
    $('apply-preview').disabled = true;
    $('apply-preview').textContent = {create:'保存候选规则',edit:'保存此预览',approve:'批准此预览',reject:'拒绝此预览'}[action];
    $('editor-instructions').textContent = approvalAction ? '批准或拒绝均要求明确的批准人和角色声明。先查看差异及完整知识库校验，再应用此预览。' : (action === 'create' ? '请填写标题、指令、理由及至少一条真实 git/web 证据；模板中的 evidence 为空，补齐后才能保存。' : '') + 'YAML 为单条规则的完整文档。普通编辑不能改 ID 或直接晋升状态；修改已批准规则的实质内容或归属，会清除旧 approval 并转为候选。文件路径由服务端确定。';
    $('editor-base').textContent = 'BASE / ' + (state.mutation.baseVersion || '—');
    $('editor-dialog').showModal();
    (action === 'create' ? $('editor-id') : approvalAction ? $('approver-id') : $('editor-yaml')).focus();
  }
  function diffLines(before,after) {
    const a = (before || '').split('\n'), b = (after || '').split('\n');
    // LCS is deliberately bounded; large documents retain a complete replacement view.
    if (a.length * b.length > 500000) return {old:a.map((text,i) => ({text,line:i+1,kind:'removed'})),next:b.map((text,i) => ({text,line:i+1,kind:'added'}))};
    const rows = Array.from({length:a.length+1},() => new Uint32Array(b.length+1));
    for (let i=a.length-1;i>=0;i--) for (let j=b.length-1;j>=0;j--) rows[i][j] = a[i] === b[j] ? rows[i+1][j+1]+1 : Math.max(rows[i+1][j],rows[i][j+1]);
    const unchangedA = new Set(), unchangedB = new Set(); let i=0,j=0;
    while (i<a.length && j<b.length) {
      if (a[i] === b[j]) { unchangedA.add(i++); unchangedB.add(j++); }
      else if (rows[i+1][j] >= rows[i][j+1]) i++; else j++;
    }
    return {old:a.map((text,i) => ({text,line:i+1,kind:unchangedA.has(i) ? '' : 'removed'})),next:b.map((text,i) => ({text,line:i+1,kind:unchangedB.has(i) ? '' : 'added'}))};
  }
  function renderPreview(preview,context) {
    const node = empty($('mutation-preview')); node.hidden = false;
    const block = el('div','preview-block');
    append(block,append(el('div','result-heading'),el('h2','','差异与校验'),badge(preview.canApply ? '允许应用预览' : '预览被阻止',preview.canApply ? 'success' : 'danger')));
    if (preview.approvalReset) block.append(el('div','notice warning','此次修改会清除旧批准记录，规则转为 candidate，须重新批准。'));
    append(block,el('p','preview-info mono','目标：' + preview.targetPath),el('p','preview-info','完整知识库校验：' + (list(preview.violations).length || list(preview.loadErrors).length ? '未通过' : '通过')));
    if (!context) block.append(el('p','notice','未提供上下文：已校验完整知识库格式与规则约束；尚未证明每个上下文都无冲突。'));
    const errors = el('div','notice danger'); appendErrors(errors,preview.violations); appendErrors(errors,preview.loadErrors); if (errors.children.length) block.append(errors);
    const diff = diffLines(preview.beforeYaml,preview.afterYaml), columns = el('div','diff-columns');
    [['修改前',diff.old],['修改后',diff.next]].forEach(([title,lines]) => {
      const column = el('div','diff-column'), pre = el('pre','diff-view');
      append(column,el('h3','',title));
      lines.forEach(line => { const span = el('span','diff-line ' + line.kind,line.text || ' '); span.dataset.line = String(line.line); pre.append(span); });
      column.append(pre); columns.append(column);
    }); block.append(columns);
    if (preview.resolution) block.append(resolutionPanel(preview.resolution,preview.baseVersion,context));
    block.append(el('p','preview-info','预览绑定完整知识库版本，10 分钟后失效。外部修改后必须重新预览。'));
    node.append(block); $('apply-preview').disabled = !preview.canApply;
  }
  async function previewMutation(event) {
    event.preventDefault();
    invalidatePreview(); empty($('editor-error'));
    const mutation = state.mutation;
    if (!mutation) return;
    try {
      const context = $('preview-with-context').checked ? readContext('preview') : undefined;
      const request = {action:mutation.action,ruleId:$('editor-id').value.trim(),expectedVersion:mutation.baseVersion,...(context ? {context} : {})};
      if (['create','edit'].includes(mutation.action)) request.ruleYaml = $('editor-yaml').value;
      else request.approver = {id:$('approver-id').value.trim(),role:$('approver-role').value,...($('approver-team').value.trim() ? {team:$('approver-team').value.trim()} : {})};
      await runButton($('preview-button'),async () => {
        const response = await api('preview',request); mutation.preview = response.preview; renderPreview(response.preview,context);
      });
    } catch (error) { $('editor-error').append(errorNotice(error,'预览未完成')); }
  }
  async function applyMutation() {
    const mutation = state.mutation;
    if (!mutation?.preview?.canApply) return;
    await runButton($('apply-preview'),async () => {
      let response;
      try {
        response = await api('apply',{previewId:mutation.preview.previewId});
      } catch (error) {
        empty($('editor-error')).append(errorNotice(error,'保存未完成'));
        mutation.preview = null;
        if (error.code === 'stale_preview') {
          const reload = el('button','button','读取最新版本并保留草稿作对照');
          reload.type = 'button';
          reload.onclick = async () => {
            await runButton(reload,async () => {
              try {
                if (mutation.action === 'create') {
                  const summary = await api('summary'); mutation.baseVersion = summary.knowledge.version;
                  empty($('mutation-preview')).append(el('div','notice warning','已读取最新知识库版本。草稿未改变，请重新预览完整知识库校验。'));
                } else {
                  const current = await api('rule?id=' + encodeURIComponent(mutation.ruleId));
                  mutation.baseVersion = current.version;
                  const box = empty($('mutation-preview')); box.hidden = false;
                  append(box,el('div','notice warning','知识库已被外部修改。下方展示最新源文件；编辑器保留你的草稿。请合并差异后重新预览。'),el('h3','','最新规则 YAML'),el('pre','raw-data',current.source?.ruleYaml || '源规则不可编辑'));
                }
                $('mutation-preview').hidden = false;
                $('editor-base').textContent = 'BASE / ' + mutation.baseVersion;
                reload.remove();
              } catch (refreshError) { toast(refreshError.message,true); }
            });
          };
          $('editor-error').append(reload);
        }
        return;
      }
      $('editor-dialog').close(); state.mutation = null; state.rulesVersion = null;
      toast('规则变更已保存；未自动 commit 或 push。');
      try { await poll(true); await loadRules(true); await loadRuleDetail(response.ruleId,false); }
      catch (refreshError) { toast('规则已保存，但刷新失败：' + refreshError.message,true); }
    });
    $('apply-preview').disabled = !state.mutation?.preview?.canApply;
  }
  async function loadAudit(reset = false) {
    if (reset) { state.auditCursor = null; state.auditStack = []; }
    const params = new URLSearchParams({scope:state.auditScope,limit:'25'});
    if (state.auditCursor) params.set('cursor',state.auditCursor);
    const response = await api('audit?' + params);
    state.audit = list(response.items); state.auditNext = response.nextCursor || null; state.auditRevision = response.revision; state.auditLoaded = true;
    $('audit-stale').hidden = false;
    const scopedRevision = state.auditScope === 'knowledge' ? state.summary?.audit?.revision : state.summary?.unscopedAudit?.revision;
    if (!scopedRevision || scopedRevision === state.auditRevision) $('audit-stale').hidden = true;
    auditHealth($('audit-health'),response.health); renderAuditList();
    $('audit-prev').disabled = !state.auditStack.length; $('audit-next').disabled = !state.auditNext;
    $('audit-page').textContent = '第 ' + (state.auditStack.length+1) + ' 页';
    if (state.selectedAudit && state.view === 'audit') await loadAuditDetail(state.selectedAudit,false);
  }
  function renderAuditList() {
    const focusedId = $('audit-list').contains(document.activeElement) ? document.activeElement.dataset.invocationId : null;
    const node = empty($('audit-list'));
    if (!state.audit.length) { node.append(emptyState('此分区尚无调用','读取与轮询不计入调用记录。运行校验、裁决或检测后再查看。')); return; }
    state.audit.forEach(record => {
      const item = el('button','audit-item' + (state.selectedAudit === record.invocationId ? ' selected' : ''));
      item.dataset.invocationId = record.invocationId; item.setAttribute('aria-pressed',String(state.selectedAudit === record.invocationId));
      append(item,append(el('div','audit-item-top'),el('span','call-title',recordTitle(record)),recordStatus(record)),el('div','call-meta',formatTime(record.startedAt) + ' · ' + shorten(record.invocationId)),el('div','muted small',record.metadata?.context ? contextLabel(record.metadata.context) : '无调用上下文'),miniStages(record));
      item.onclick = () => loadAuditDetail(record.invocationId).catch(error => toast(error.message,true)); node.append(item);
      if (focusedId === record.invocationId) item.focus({preventScroll:true});
    });
  }
  const stageOrder = ['parameters','knowledge_load','knowledge_validate','resolve','diff_read','standardizer','path_guard','branch_guard','citation_guard','baseline_guard','write','restore','model'];
  function stageTrail(record,reviewItems = []) {
    const trail = el('div','stage-trail');
    const actual = list(record.stages);
    const missing = stageOrder.filter(name => !actual.some(stage => stage.name === name));
    const stages = [...actual,...missing.map(name => ({name,status:'not_run',detail:'没有该阶段的执行证据。',missing:true}))];
    let missingHeading = false;
    stages.forEach((stage,stageIndex) => {
        if (stage.missing && !missingHeading) { trail.append(el('h3','missing-stages-heading','以下阶段未记录执行')); missingHeading = true; }
        const status = lower(stage.status), node = el('div','stage-node ' + status);
        node.append(el('span','stage-indicator',status === 'passed' ? '✓' : status === 'blocked' ? '×' : status === 'not_applicable' ? '–' : '·'));
        const content = el('div','stage-content');
        const heading = append(el('div','stage-title'),el('h3','',human(stage.name)),statusBadge(stage.status));
        content.append(heading);
        if (stage.category) content.append(append(el('div','badge-row'),statusBadge(stage.category)));
        if (stage.detail) content.append(el('p','',stage.detail));
        list(stage.hits).forEach(hit => {
          const hitElement = hitNode(hit,stage.category);
          const reviewItem = reviewItems.find(item => item.invocationId === record.invocationId && item.stageIndex === stageIndex && ['kind','ruleId','check','path','line','detail'].every(key => (item.hit?.[key] ?? null) === (hit[key] ?? null)));
          if (reviewItem) { const link = el('button','text-button','记录复核 / 查看历史 →'); link.onclick = () => openReview(reviewItem.itemId).catch(error => toast(error.message,true)); hitElement.append(link,reviewBadges(reviewItem)); }
          content.append(hitElement);
        });
        node.append(content); trail.append(node);
    }); return trail;
  }
  function auditRecordBody(record,children,reviewItems = []) {
    const container = el('div'), header = el('div','detail-header');
    append(header,el('div','rule-id',record.invocationId),el('h2','',recordTitle(record)),append(el('div','badge-row'),recordStatus(record),badge('生命周期：' + human(record.status),'outline'),badge('exit ' + (record.exitCode ?? '—'),'outline')));
    container.append(header);
    const body = el('div','detail-body');
    append(body,section('调用信息',append(el('div','property-grid'),property('开始时间',formatTime(record.startedAt)),property('结束时间',formatTime(record.endedAt)),property('耗时',record.durationMillis == null ? '未完成或未记录' : record.durationMillis + ' ms'),property('工作模式',record.metadata?.context?.workMode || 'normal'),property('知识库',record.metadata?.knowledgeRoot || '未归属',true),property('调用时快照',record.knowledgeVersion,true))));
    body.append(section('明确上下文',el('p','prose',contextLabel(record.metadata?.context))));
    if (record.metadata?.parentInvocationId) body.append(section('父调用',el('p','mono small',record.metadata.parentInvocationId)));
    if (!record.endedAt) body.append(el('div','notice warning','这次调用尚未留下结束记录，可能仍在运行或进程已中断。不能按通过处理。'));
    if (['undo','discard'].includes(lower(record.metadata?.command))) body.append(el('div','notice','这是恢复事务。查看实际路径、分支及恢复阶段；恢复成功不表示执行了新的编辑 diff 检测。'));
    body.append(section('实际检测轨迹',el('p','muted small','绿色仅表示该阶段通过。缺少执行证据的阶段显示未执行；soft 与 dev 提醒保留独立类别。')));
    body.append(stageTrail(record,reviewItems));
    const snapshots = el('details','snapshot-details');
    snapshots.append(el('summary','','调用时规则快照 / ' + list(record.ruleSnapshots).length + ' 条'));
    if (!list(record.ruleSnapshots).length) snapshots.append(el('p','muted small','此调用没有记录规则快照。'));
    list(record.ruleSnapshots).forEach(rule => {
      const node = el('section','field-section'); append(node,el('h3','mono',rule.id),el('p','prose',rule.title),structured(rule)); snapshots.append(node);
    }); body.append(snapshots);
    if (record.metadata?.model || record.inputTokens != null || record.outputTokens != null) body.append(section('模型记录',append(el('div','property-grid'),property('模型',record.metadata?.model),property('输入 / 输出 tokens',(record.inputTokens ?? '未记录') + ' / ' + (record.outputTokens ?? '未记录')))));
    const childSection = el('section','field-section'); childSection.append(el('h3','','关联子调用 / ' + list(children).length));
    if (!list(children).length) childSection.append(el('p','muted small','无已记录子调用。不会根据时间自动配对 resolve 与 check。'));
    list(children).forEach(child => {
      const box = el('details','snapshot-details');
      box.append(el('summary','',recordTitle(child) + ' · ' + human(child.status) + ' · ' + shorten(child.invocationId)));
      append(box,el('p','mono small muted',formatTime(child.startedAt) + ' · exit ' + (child.exitCode ?? '—')),append(el('div','property-grid'),property('模型',child.metadata?.model),property('耗时',child.durationMillis == null ? '未完成或未记录' : child.durationMillis + ' ms'),property('输入 tokens',child.inputTokens ?? '未记录'),property('输出 tokens',child.outputTokens ?? '未记录')),stageTrail(child,reviewItems)); childSection.append(box);
    }); body.append(childSection); container.append(body); return container;
  }
  async function loadAuditDetail(id,scroll = true) {
    const scope = state.auditScope;
    state.selectedAudit = id; renderAuditList();
    const response = await api('audit/detail?' + new URLSearchParams({id,scope}));
    if (state.selectedAudit !== id || state.auditScope !== scope) return;
    empty($('audit-detail')).append(auditRecordBody(response.record,response.children,list(response.reviewItems)));
    if (scroll && matchMedia('(max-width:650px)').matches) $('audit-detail').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion:reduce)').matches ? 'auto' : 'smooth',block:'start'});
  }
  const reviewDecisions = {verified:'已核对',needs_changes:'需修改',not_applicable:'本次不适用'};
  const reviewVersions = {snapshot:'历史快照',stale:'需重新复核',unknown:'版本未知'};
  const reviewDrafts = new Map();
  function reviewDecision(item) { return reviewDecisions[item.latestReview?.decision] || '待复核'; }
  function reviewBadges(item) {
    const decision = item.latestReview?.decision;
    return append(el('div','badge-row'),badge(reviewDecision(item),decision === 'verified' ? 'success' : decision === 'needs_changes' ? 'danger' : decision ? 'info' : 'warning'),badge(reviewVersions[item.versionState] || '版本未知',item.versionState === 'stale' ? 'warning' : 'outline'));
  }
  function reviewDraft(item) {
    if (!reviewDrafts.has(item.itemId)) reviewDrafts.set(item.itemId,{decision:'',reviewer:'',reason:'',links:'',pending:null,preview:null,busy:false,message:''});
    return reviewDrafts.get(item.itemId);
  }
  function reviewHealth(response) {
    const node = empty($('reviews-health'));
    [['源审计',response.auditHealth],['复核历史',response.reviewHealth]].forEach(([name,health]) => {
      if (health && !health.ok) { const box = el('div','notice warning'); append(box,el('strong','',name + '不完整，部分事项可能只读')); appendErrors(box,health.warnings); node.append(box); }
    });
    if (response.indexComplete === false) node.append(el('p','notice warning','事项索引尚未完整。版本关联可能未知；当前页不代表全部事项。请继续翻页或稍后刷新。'));
  }
  async function loadReviews(reset = false) {
    if (reset) { state.reviewCursor = null; state.reviewStack = []; }
    const params = new URLSearchParams({filter:state.reviewFilter,limit:'25'});
    if (state.reviewCursor) params.set('cursor',state.reviewCursor);
    const response = await api('reviews?' + params);
    state.reviews = list(response.items); state.reviewNext = response.nextCursor || null; state.reviewsLoaded = true;
    reviewHealth(response); renderReviews();
    $('reviews-prev').disabled = !state.reviewStack.length; $('reviews-next').disabled = !state.reviewNext;
    $('reviews-page').textContent = '第 ' + (state.reviewStack.length + 1) + ' 页';
    $('reviews-count').textContent = '本页 ' + state.reviews.length + ' 项 · 仅当前筛选返回范围' + (state.reviewNext ? '，还有后续页' : '');
  }
  function renderReviews() {
    const focusedId = $('reviews-list').contains(document.activeElement) ? document.activeElement.dataset.itemId : null;
    const node = empty($('reviews-list'));
    if (!state.reviews.length) node.append(emptyState('本页没有匹配事项',state.reviewNext ? '索引仍有后续范围，请继续下一页。' : '执行包含 soft 或 dev 降级提醒的检查后，在此逐项记录复核。'));
    state.reviews.forEach(item => {
      const row = el('button','audit-item' + (state.selectedReview === item.itemId ? ' selected' : ''));
      row.dataset.itemId = item.itemId; row.setAttribute('aria-pressed',String(state.selectedReview === item.itemId));
      append(row,el('div','call-title',item.hit?.ruleId || '检查提醒'),el('div','call-meta',formatTime(item.source?.startedAt) + ' · ' + shorten(item.invocationId)),append(el('div','badge-row'),badge(lower(item.hit?.kind) === 'dev_downgrade' ? 'dev 降级提醒' : lower(item.hit?.kind) === 'soft' ? 'soft 提醒' : '原提醒类型未知','warning'),badge('原检测阶段：' + human(item.originalStatus),'outline')),reviewBadges(item),el('p','muted small',contextLabel(item.source?.metadata?.context)));
      if (!item.canReview) row.append(el('p','danger-text small','只读：' + (item.disabledReason || '来源不可校验')));
      row.onclick = () => openReview(item.itemId).catch(error => toast(error.message,true)); node.append(row);
      if (focusedId === item.itemId) row.focus({preventScroll:true});
    });
  }
  async function openReview(id, scroll = true) {
    state.selectedReview = id; showView('reviews'); renderReviews();
    try {
      const response = await api('reviews/detail?itemId=' + encodeURIComponent(id));
      if (state.selectedReview !== id) return;
      state.reviewDetail = response; renderReviewDetail();
      if (scroll && matchMedia('(max-width:650px)').matches) $('review-detail').scrollIntoView({block:'start',behavior:'auto'});
    } catch (error) {
      if (state.selectedReview === id) {
        const draft = reviewDrafts.get(id);
        if (draft) { draft.message = '读取失败，草稿仍保留：' + error.message; if (state.reviewDetail?.item?.itemId === id) renderReviewDetail(); }
        else empty($('review-detail')).append(errorNotice(error,'复核详情读取失败'));
      }
      throw error;
    }
  }
  function safeEvidenceUrl(value) {
    if (typeof value !== 'string' || value.length > 2048 || /[\u0000-\u0020\u007f]/.test(value)) return null;
    try {
      const url = new URL(value);
      if (!['http:','https:'].includes(url.protocol) || !url.hostname || url.username || url.password) return null;
      const sensitive = /^(token|api_key|access_token|password|authorization)$/i;
      const fragment = url.hash.slice(1).replace(/^\?/, '');
      if ([...url.searchParams.keys(),...new URLSearchParams(fragment).keys()].some(key => sensitive.test(key))) return null;
      return url.href;
    } catch (_error) { return null; }
  }
  function reviewLinks(urls) {
    const node = el('div','review-links');
    if (!list(urls).length) node.append(el('p','muted small','未提供链接'));
    list(urls).forEach(value => {
      const href = safeEvidenceUrl(value);
      if (href) { const link = el('a','',value); link.href = href; link.target = '_blank'; link.rel = 'noopener noreferrer'; node.append(link); }
      else node.append(el('p','muted small','链接不可打开：' + value));
    }); return node;
  }
  function reviewRecord(record) {
    const node = el('div','review-record');
    append(node,append(el('div','badge-row'),badge(reviewDecisions[record.decision] || record.decision,'outline'),badge('revision ' + record.revision,'outline')),el('p','small',record.reviewer + ' · ' + formatTime(record.createdAt)),el('p','prose',record.reason),reviewLinks(record.evidenceUrls));
    if (record.sanitized) node.append(el('p','notice warning','保存内容已遮盖疑似敏感值；这里显示服务端最终保存内容。'));
    return node;
  }
  function reviewPayload(item,draft) {
    const reviewer = draft.reviewer.trim(), reason = draft.reason.trim();
    if (!reviewDecisions[draft.decision]) throw new Error('请选择本次复核结论。');
    if (!reviewer || reviewer.length > 120) throw new Error('复核人必填，最多 120 字符。');
    if (!reason || reason.length > 4000) throw new Error('复核理由必填，最多 4000 字符。');
    if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(reviewer + reason)) throw new Error('复核人或理由包含不允许的控制字符。');
    const evidenceUrls = draft.links.split('\n').map(value => value.trim()).filter(Boolean);
    if (evidenceUrls.length > 8) throw new Error('证据链接最多 8 条，每行一条。');
    if (evidenceUrls.some(value => !safeEvidenceUrl(value))) throw new Error('链接必须是完整 http(s) 地址，不能含账号密码、控制字符或敏感凭据参数。');
    return {itemId:item.itemId,invocationId:item.invocationId,sourceDigest:item.sourceDigest,expectedRevision:item.latestReview?.revision || 0,decision:draft.decision,reviewer,reason,evidenceUrls};
  }
  function renderReviewDetail() {
    const response = state.reviewDetail, item = response?.item;
    if (!item || state.selectedReview !== item.itemId) return;
    const draft = reviewDraft(item), node = empty($('review-detail'));
    if (response.auditHealth || response.reviewHealth || response.indexComplete !== undefined) reviewHealth(response);
    const header = el('div','detail-header');
    append(header,el('div','rule-id',item.itemId),el('h2','',item.hit?.ruleId || '检查提醒'),append(el('div','badge-row'),badge(lower(item.hit?.kind) === 'dev_downgrade' ? 'dev 降级提醒' : lower(item.hit?.kind) === 'soft' ? 'soft 提醒' : '原提醒类型未知','warning'),badge('原检测阶段：' + human(item.originalStatus),'outline'),badge('exit ' + (item.source?.exitCode ?? '—'),'outline')),reviewBadges(item)); node.append(header);
    const body = el('div','detail-body'); node.append(body);
    append(body,section('原检测',item.hit ? hitNode(item.hit,'soft') : el('p','notice warning','来源检查已缺失，无法确认原命中类型与位置。')),el('p','muted small',contextLabel(item.source?.metadata?.context)));
    if (lower(item.hit?.kind) === 'soft' && !item.hit?.path) body.append(el('p','notice','规则级提醒；原检测未提供精确位置。'));
    if (lower(item.hit?.kind) === 'dev_downgrade') body.append(el('p','notice warning','dev 降级提醒始终保留其原检查类型与位置，复核不会解除硬检查语义。'));
    body.append(section('检查版本',append(el('div','property-grid'),property('检查时间',formatTime(item.source?.startedAt)),property('输入类型',item.codeVersion?.kind || '未记录'),property('输入摘要',item.codeVersion?.inputDigest,true),property('知识版本',item.source?.knowledgeVersion,true))));
    if (item.versionState === 'unknown') body.append(el('p','notice warning',item.latestReview ? '历史复核，版本未知。此声明不计为当前代码已核对。' : '版本未知；可以对可校验的历史提醒作出声明，但不能作为当前代码结论。'));
    if (item.versionState === 'stale') body.append(el('p','notice warning','后续检查输入或知识版本已变化，需要重新复核。旧声明只属于原快照。'));
    const auditLink = el('button','text-button','查看原调用 →'); auditLink.type = 'button'; auditLink.onclick = () => openAudit({invocationId:item.invocationId}); body.append(auditLink);
    if (item.codeVersion?.kind === 'supplied_patch' && lower(item.source?.metadata?.entrypoint) === 'dashboard') {
      const recheck = el('button','text-button','重新检查此补丁 →'); recheck.type = 'button'; recheck.onclick = () => prepareRecheck(item); body.append(recheck);
    }
    body.append(section('最近声明',item.latestReview ? reviewRecord(item.latestReview) : el('p','muted small','尚未记录复核声明')));
    const history = el('details','snapshot-details'); history.append(el('summary','','复核历史 / ' + list(response.history).length + ' 条'));
    list(response.history).forEach(record => history.append(reviewRecord(record))); body.append(history);
    if (!item.canReview) body.append(el('p','notice danger','当前只读：' + (item.disabledReason || '来源不可校验') + '。已有历史与草稿仍保留。' + (item.canRetry === true ? '若刚才保存未确认，可重试同一声明；新声明暂不可写。' : '')));
    const form = el('form','review-form'); form.setAttribute('aria-label','记录独立复核声明');
    const decisionLabel = el('label','','本次结论'), decision = el('select'); decision.id = 'review-decision'; decision.required = true; decision.append(new Option('请选择结论',''));
    Object.entries(reviewDecisions).forEach(([value,label]) => decision.append(new Option(label,value))); decision.value = draft.decision; decisionLabel.append(decision);
    const reviewerLabel = el('label','','复核人（身份声明，最多 120 字符）'), reviewer = el('input'); reviewer.id = 'review-reviewer'; reviewer.maxLength = 120; reviewer.required = true; reviewer.autocomplete = 'off'; reviewer.value = draft.reviewer; reviewerLabel.append(reviewer);
    const reasonLabel = el('label','','复核理由（必填，最多 4000 字符）'), reason = el('textarea'); reason.id = 'review-reason'; reason.maxLength = 4000; reason.required = true; reason.rows = 5; reason.value = draft.reason; reasonLabel.append(reason);
    const linksLabel = el('label','','证据链接（可选，最多 8 条；每行一条，每条最多 2048 字符）'), links = el('textarea'); links.id = 'review-links'; links.maxLength = 16391; links.rows = 3; links.value = draft.links; links.placeholder = 'https://…'; linksLabel.append(links);
    const status = el('div','review-status'); status.id = 'review-status'; status.setAttribute('role','status'); status.setAttribute('aria-live','polite'); status.textContent = draft.message;
    const preview = el('div','review-preview'); preview.id = 'review-preview'; preview.setAttribute('aria-live','polite');
    const previewButton = el('button','button','预览复核声明'); previewButton.type = 'submit';
    const save = el('button','button primary','保存复核'); save.id = 'review-save'; save.type = 'button';
    append(form,decisionLabel,reviewerLabel,reasonLabel,linksLabel,el('p','muted small','身份仅为操作者声明。链接不会被抓取；未提供链接会如实记录。修改结论会追加历史，不覆盖原记录。'),status,preview,append(el('div','review-form-actions'),previewButton,save)); body.append(section('记录本次复核',form));
    const controls = [decision,reviewer,reason,links]; controls.forEach(control => { control.disabled = draft.busy; });
    function updatePreview() {
      empty(preview);
      if (draft.preview) {
        append(preview,el('h3','','保存前核对完整声明'),el('p','small','目标事项：' + item.itemId),el('p','small','预期 revision：' + draft.preview.expectedRevision),el('p','small','结论：' + reviewDecisions[draft.preview.decision]),el('p','prose','复核人：' + draft.preview.reviewer),el('p','prose','理由：' + draft.preview.reason),reviewLinks(draft.preview.evidenceUrls));
      }
      const canSubmit = item.canReview || (item.canRetry === true && draft.pending !== null);
      save.disabled = !draft.preview || !canSubmit || draft.busy; previewButton.disabled = draft.busy || !canSubmit;
    }
    controls.forEach(control => { control.oninput = control.onchange = () => {
      Object.assign(draft,{decision:decision.value,reviewer:reviewer.value,reason:reason.value,links:links.value,preview:null,pending:null,message:'草稿已修改，尚未保存。'}); status.textContent = draft.message; updatePreview();
    }; });
    form.onsubmit = event => { event.preventDefault(); try { draft.preview = draft.pending ? Object.fromEntries(Object.entries(draft.pending).filter(([key]) => key !== 'reviewId')) : reviewPayload(item,draft); draft.message = '请核对下方完整声明，再保存复核。'; status.textContent = draft.message; updatePreview(); } catch (error) { status.textContent = error.message; } };
    save.onclick = () => saveReview(item,draft); updatePreview();
  }
  async function saveReview(item,draft) {
    if ((!item.canReview && !(item.canRetry === true && draft.pending !== null)) || !draft.preview || draft.busy) return;
    // Keep the exact UUID and payload after transport/storage failures. Edits invalidate both.
    if (!draft.pending) draft.pending = {reviewId:crypto.randomUUID(),...draft.preview};
    draft.busy = true; draft.message = '正在保存，收到持久化确认前不视为已保存。'; renderReviewDetail();
    try {
      const response = await api('reviews/record',draft.pending);
      const record = response.record;
      if (!record) throw new Error('服务端未返回持久化记录，请保留草稿后重试。');
      Object.assign(draft,{decision:record.decision,reviewer:record.reviewer,reason:record.reason,links:list(record.evidenceUrls).join('\n'),preview:null,pending:null,message:'复核已保存（revision ' + record.revision + '）' + (record.sanitized ? '；疑似敏感值已遮盖，显示最终保存内容。' : '。')});
      if (state.reviewDetail?.item?.itemId === item.itemId) {
        state.reviewDetail = {item:response.item || {...item,latestReview:record},history:[...list(state.reviewDetail.history).filter(old => old.reviewId !== record.reviewId),record]};
      }
      try { await loadReviews(); } catch (error) { draft.message += ' 列表刷新失败：' + error.message; }
    } catch (error) {
      draft.message = '尚未确认保存，草稿已保留：' + error.message;
      if (error.httpStatus === 409) {
        draft.pending = null; draft.preview = null;
        try {
          const latest = await api('reviews/detail?itemId=' + encodeURIComponent(item.itemId));
          if (state.selectedReview === item.itemId) state.reviewDetail = latest;
          draft.message = '复核记录或来源已变化，已读取最新历史；草稿未改变。请核对后重新预览并保存。';
        } catch (refreshError) { draft.message += ' 最新历史读取失败：' + refreshError.message; }
      }
    } finally { draft.busy = false; if (state.selectedReview === item.itemId) renderReviewDetail(); }
  }
  function prepareRecheck(item) {
    const context = item.source?.metadata?.context;
    state.recheck = item.invocationId; showView('resolve');
    if (context) {
      $('resolve-team').value = context.team || ''; $('resolve-season').value = context.season || '';
      const profiles = list(context.profiles), profile = ['ftclib-command','rookiebot','command-based','simple-opmode'].find(value => profiles.includes(value));
      $('resolve-profile').value = profiles.length ? profile || '' : 'generic'; $('resolve-mode').value = context.workMode || 'normal';
    }
    const notice = empty($('recheck-context')); notice.hidden = false;
    append(notice,el('p','','关联前次调用 ' + item.invocationId + '。历史不保存补丁正文，请重新提供本次补丁，并核对上下文。当前编辑区保留你已填写的内容。'));
    const cancel = el('button','text-button','取消关联，作为独立补丁检测'); cancel.type = 'button'; cancel.onclick = () => { state.recheck = null; notice.hidden = true; $('recheck-confirm-label').hidden = true; }; notice.append(cancel);
    $('recheck-confirm').checked = false; $('recheck-confirm-label').hidden = false; $('manual-diff').focus();
  }

  function bind() {
    document.querySelectorAll('[data-view]').forEach(button => { button.onclick = () => showView(button.dataset.view); });
    document.querySelectorAll('[data-go]').forEach(button => { button.onclick = () => showView(button.dataset.go); });
    document.querySelector('.brand').onclick = event => { event.preventDefault(); showView('overview'); };
    $('refresh-all').onclick = () => poll(true);
    $('rule-search').oninput = renderRules;
    $('clear-filters').onclick = () => { state.filters = {}; $('rule-search').value = ''; renderFilters(); renderRules(); };
    $('create-rule').onclick = () => openEditor('create');
    $('validate-library').onclick = validateLibrary;
    contextForm($('resolve-context'),'resolve'); contextForm($('preview-context'),'preview');
    $('resolve-form').onsubmit = resolveContext; $('check-form').onsubmit = checkPatch;
    $('editor-close').onclick = () => $('editor-dialog').close();
    $('editor-dialog').addEventListener('close',() => { state.mutation = null; });
    $('editor-form').onsubmit = previewMutation;
    $('editor-form').addEventListener('input',invalidatePreview);
    $('editor-id').addEventListener('input',() => {
      if (state.mutation?.action === 'create') $('editor-yaml').value = $('editor-yaml').value.replace(/^(\s*- id:)\s*.*$/m,(_match,prefix) => prefix + ' ' + JSON.stringify($('editor-id').value.trim()));
    });
    $('editor-form').addEventListener('change',invalidatePreview);
    $('preview-with-context').onchange = () => { $('preview-context').hidden = !$('preview-with-context').checked; };
    $('apply-preview').onclick = applyMutation;
    $('approver-role').onchange = () => { $('approver-team').required = $('approver-role').value === 'team_software_lead'; };
    $('reviews-refresh').onclick = async () => { try { await loadReviews(true); if (state.selectedReview) await openReview(state.selectedReview,false); } catch (error) { toast(error.message,true); } };
    $('reviews-filter').onchange = () => { state.reviewFilter = $('reviews-filter').value; loadReviews(true).catch(error => toast(error.message,true)); };
    $('reviews-prev').onclick = async () => { const cursor = state.reviewCursor; state.reviewCursor = state.reviewStack.pop() ?? null; try { await loadReviews(); } catch (error) { state.reviewStack.push(state.reviewCursor); state.reviewCursor = cursor; toast(error.message,true); } };
    $('reviews-next').onclick = async () => { if (!state.reviewNext) return; const cursor = state.reviewCursor; state.reviewStack.push(cursor); state.reviewCursor = state.reviewNext; try { await loadReviews(); } catch (error) { state.reviewStack.pop(); state.reviewCursor = cursor; toast(error.message,true); } };
    $('audit-refresh').onclick = () => loadAudit(true).catch(error => toast(error.message,true));
    $('audit-scope').onchange = () => {
      state.auditScope = $('audit-scope').value; state.auditLoaded = false; state.selectedAudit = null;
      empty($('audit-detail')).append(emptyState('选择一次调用',state.auditScope === 'unscoped' ? '此分区无确定的知识库归属，不应归因于当前知识库。' : '查看调用当时的快照和实际检测阶段。'));
      renderOverview(); loadAudit(true).catch(error => toast(error.message,true));
    };
    $('audit-prev').onclick = async () => {
      state.auditCursor = state.auditStack.pop() ?? null;
      try { await loadAudit(); } catch (error) { toast(error.message,true); }
    };
    $('audit-next').onclick = async () => {
      if (!state.auditNext) return;
      state.auditStack.push(state.auditCursor); state.auditCursor = state.auditNext;
      try { await loadAudit(); } catch (error) { toast(error.message,true); }
    };
    document.addEventListener('visibilitychange',() => { if (!document.hidden) poll(); });
  }
  bind(); poll();
  setInterval(() => { if (!document.hidden) poll(); },2000);
})();
