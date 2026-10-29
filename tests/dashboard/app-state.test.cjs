// Exercise the shipped dashboard script and its bound UI handlers without a
// browser or external dependencies. HTTP responses are controlled at fetch.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.resolve(__dirname, '../../apps/knowledge-cli/src/main/resources/dashboard');
const script = fs.readFileSync(path.join(resources, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(resources, 'index.html'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
async function settle() { for (let i = 0; i < 8; i++) await tick(); }
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function dashboard() {
  const ids = new Map();
  class Element {
    constructor(tag = 'div', text = '') {
      this.tagName = tag.toUpperCase(); this.children = []; this.parentNode = null;
      this._text = text; this._value = ''; this.className = ''; this.dataset = {};
      this.hidden = false; this.disabled = false; this.checked = false;
      this.listeners = new Map(); this.attributes = new Map();
      this.classList = {
        toggle: (name, force) => {
          const names = new Set(this.className.split(/\s+/).filter(Boolean));
          const enabled = force === undefined ? !names.has(name) : force;
          enabled ? names.add(name) : names.delete(name); this.className = [...names].join(' ');
          return enabled;
        },
        contains: name => this.className.split(/\s+/).includes(name)
      };
    }
    set id(value) { this._id = value; ids.set(value, this); }
    get id() { return this._id || ''; }
    set textContent(value) { this._text = String(value); this.children = []; }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
    set value(value) { this._value = String(value); }
    get value() { return this._value; }
    append(...children) {
      for (const input of children) {
        const child = typeof input === 'string' ? new Element('#text', input) : input;
        if (child.parentNode) child.remove();
        child.parentNode = this; this.children.push(child);
        if (this.tagName === 'SELECT' && this.children.length === 1) this.value = child.value;
      }
    }
    replaceChildren(...children) { this.children.forEach(child => { child.parentNode = null; }); this.children = []; this._text = ''; this.append(...children); }
    prepend(child) { child.parentNode = this; this.children.unshift(child); }
    remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    removeAttribute(name) { this.attributes.delete(name); }
    addEventListener(type, handler) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(handler); }
    async dispatch(type, extra = {}) {
      if ((type === 'click' && this.disabled) || (type === 'submit' && extra.submitter?.disabled)) return;
      const event = { type, target: this, preventDefault() {}, ...extra };
      for (let node = this; node; node = node.parentNode) {
        if (node['on' + type]) await node['on' + type](event);
        for (const listener of node.listeners.get(type) || []) await listener(event);
      }
    }
    matches(selector) {
      if (selector.startsWith('.')) return this.classList.contains(selector.slice(1));
      if (selector.startsWith('#')) return this.id === selector.slice(1);
      return this.tagName === selector.toUpperCase();
    }
    querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
    focus() { document.activeElement = this; }
    scrollIntoView() {}
    showModal() { this.open = true; }
    close() { this.open = false; for (const listener of this.listeners.get('close') || []) listener({ target: this }); }
  }
  // Create the real page's IDs; model the parent chains used for bubbling input.
  for (const match of html.matchAll(/<([\w-]+)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
    const node = new Element(match[1]); node.id = match[3];
    node.hidden = /\bhidden\b/.test(match[2]); node.disabled = /\bdisabled\b/.test(match[2]);
  }
  const get = id => { assert.ok(ids.has(id), 'Page element exists: ' + id); return ids.get(id); };
  get('resolve-form').append(get('resolve-context'));
  get('check-form').append(get('manual-diff'));
  get('editor-form').append(...['editor-id', 'editor-yaml', 'preview-with-context', 'preview-context', 'approver-id', 'approver-role', 'approver-team'].map(get));
  get('editor-dialog').append(get('editor-form'), get('editor-error'), get('mutation-preview'));
  const brand = new Element('a');
  const navigation = new Map([...html.matchAll(/data-view="([^"]+)"/g)].map(match => {
    const button = new Element('button'); button.dataset.view = match[1]; return [match[1], button];
  }));
  const document = {
    hidden: false, activeElement: null, getElementById: get,
    createElement: tag => new Element(tag), createTextNode: text => new Element('#text', text),
    querySelector: selector => selector === '.brand' ? brand : null,
    querySelectorAll: selector => selector === '[data-view]' ? [...navigation.values()] : [], addEventListener() {}
  };
  const requests = [], intervals = [], nextResponses = new Map();
  let version = 'v1', previewOverride = null, resolveOverride = null, checkOverride = null, applyOverride = null, previewSequence = 0;
  let savedYaml = 'server YAML v1';
  let rule = { id: 'shared.demo', title: 'Demo rule', instruction: 'Example instruction', status: 'candidate', authority: 'shared', policyLevel: 'shared', applicability: {}, checkMode: 'unconditional_soft' };
  const previews = new Map();
  const reply = (data, status = 200) => ({ ok: status < 400, status, json: async () => ({ apiVersion: 1, ok: status < 400, ...data }) });
  const preview = body => {
    const value = { canApply: true, previewId: 'preview-' + (++previewSequence), baseVersion: version, targetPath: 'candidate.yaml', beforeYaml: savedYaml, afterYaml: body.ruleYaml, violations: [], loadErrors: [] };
    previews.set(value.previewId, { ...value, ruleId: body.ruleId }); return { preview: value };
  };
  const fetch = async (url, options) => {
    const route = url.replace('/api/dashboard/', '');
    const body = options.body ? JSON.parse(options.body) : undefined;
    requests.push({ route, body });
    if (nextResponses.has(route)) { const response = nextResponses.get(route); nextResponses.delete(route); return response; }
    if (route === 'summary') return reply({ knowledge: { root: '/fixture/knowledge', version, valid: true, ruleCount: 0, approved: 0, candidate: 0 }, audit: { revision: 'audit-1', total: 0 }, unscopedAudit: { revision: 'audit-1', total: 0 } });
    if (route.startsWith('audit?')) return reply({ items: [] });
    if (route === 'rules') return reply({ version, rules: [rule] });
    if (route.startsWith('rule?id=')) return reply({ version, rule, source: { path: 'candidate.yaml', fileDigest: version, ruleYaml: savedYaml } });
    if (route === 'preview') {
      if (previewOverride) return previewOverride(body);
      if (body.expectedVersion !== version) return reply({ error: { code: 'stale_preview', message: 'Knowledge changed.' } }, 409);
      return reply(preview(body));
    }
    if (route === 'apply') {
      if (applyOverride) return applyOverride(body);
      const value = previews.get(body.previewId);
      if (!value || value.baseVersion !== version) return reply({ error: { code: 'stale_preview', message: 'Knowledge changed before apply.' } }, 409);
      savedYaml = value.afterYaml; rule = { ...rule, id: value.ruleId || rule.id, title: 'Saved rule' }; version = 'saved-' + version;
      return reply({ version, ruleId: rule.id });
    }
    if (route === 'resolve') {
      if (resolveOverride) return resolveOverride(body);
      return reply({ version, resolution: { activeRules: [], excludedRules: [], overriddenRules: [], conflicts: [], profiles: [] } });
    }
    if (route === 'check') {
      if (checkOverride) return checkOverride(body);
      return reply({ version, passed: true, violations: [], soft: [] });
    }
    throw new Error('Unexpected test request: ' + route);
  };
  const context = vm.createContext({ document, location: { hash: '#token=test-only', pathname: '/', search: '' }, history: { replaceState() {} }, URLSearchParams, AbortController, console, fetch,
    Option: class extends Element { constructor(text, value) { super('option', text); this.value = value; } },
    setTimeout: () => 1, clearTimeout() {}, setInterval: callback => { intervals.push(callback); return 1; },
    matchMedia: () => ({ matches: false })
  });
  vm.runInContext(script, context, { filename: 'dashboard/app.js' });
  return {
    get, requests, reply, preview,
    setVersion(value) { version = value; savedYaml = 'server YAML ' + version; },
    setPreviewResponse(handler) { previewOverride = handler; },
    setResolveResponse(handler) { resolveOverride = handler; },
    setCheckResponse(handler) { checkOverride = handler; },
    setApplyResponse(handler) { applyOverride = handler; },
    nextResponse(route, response) { nextResponses.set(route, response); },
    async ready() { await settle(); assert.equal(get('connection').textContent, '已连接'); },
    async poll() { for (const callback of intervals) callback(); await settle(); },
    async create() { await get('create-rule').dispatch('click'); },
    async edit() {
      await navigation.get('rules').dispatch('click'); await settle();
      await get('rule-list').querySelector('button').dispatch('click');
      await get('rule-detail').querySelector('.detail-actions').querySelector('button').dispatch('click');
    },
    async input(id, value, event = 'input') { get(id).value = value; await get(id).dispatch(event); },
    submitPreview() { return get('editor-form').dispatch('submit', { submitter: get('preview-button') }); },
    submitResolve() { return get('resolve-form').dispatch('submit', { submitter: new Element('button') }); },
    submitCheck() { return get('check-form').dispatch('submit', { submitter: new Element('button') }); },
    apply() { return get('apply-preview').dispatch('click'); },
    async context() { await this.input('resolve-team', '20827'); await this.input('resolve-season', '2025-2026'); await this.input('resolve-profile', 'generic', 'change'); }
  };
}

test('unchanged draft and explicit context retain usable previews and current results', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'unchanged draft'); await app.submitPreview();
  assert.equal(app.get('apply-preview').disabled, false);
  assert.match(app.get('mutation-preview').textContent, /允许应用预览/);
  await app.get('editor-close').dispatch('click');
  await app.context(); await app.submitResolve(); await app.submitCheck();
  assert.match(app.get('resolve-result').textContent, /队伍 20827/);
  assert.equal(app.get('resolve-result').querySelector('.stale-result'), null);
  assert.equal(app.get('check-result').querySelector('.stale-result'), null);
});

test('first preview HTTP 409 before polling offers recovery and preserves the draft', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'draft kept for merge');
  app.setVersion('v2'); await app.submitPreview();
  assert.equal(app.requests.filter(request => request.route === 'preview').at(-1).body.expectedVersion, 'v1');
  assert.match(app.get('editor-error').textContent, /stale_preview/);
  const recovery = app.get('editor-dialog').querySelectorAll('button').find(button => /最新版本/.test(button.textContent));
  assert.ok(recovery, 'A stale first preview needs a way to read the latest version without closing the editor');
  await recovery.dispatch('click');
  assert.equal(app.get('editor-yaml').value, 'draft kept for merge');
  await app.submitPreview();
  assert.equal(app.requests.filter(request => request.route === 'preview').at(-1).body.expectedVersion, 'v2');
  assert.equal(app.get('apply-preview').disabled, false);
});

test('editing an existing rule recovers HTTP 409 with latest YAML and retained draft', async () => {
  const app = dashboard(); await app.ready(); await app.edit();
  await app.input('editor-yaml', 'local edited draft'); app.setVersion('v2');
  await app.submitPreview();
  assert.match(app.get('editor-error').textContent, /stale_preview/);
  await app.get('editor-error').querySelector('.mutation-reload').dispatch('click');
  assert.match(app.get('mutation-preview').textContent, /server YAML v2/);
  assert.equal(app.get('editor-yaml').value, 'local edited draft');
  // The recovery endpoint saw v2, but the background summary has not polled it.
  assert.match(app.get('overview-version').textContent, /v1/);
  await app.submitPreview();
  assert.equal(app.requests.filter(request => request.route === 'preview').at(-1).body.expectedVersion, 'v2');
  assert.equal(app.get('apply-preview').disabled, false, 'A newer confirmed base must not be rejected because summary is older');
});

test('apply HTTP 409 recovers to a new preview without losing the draft', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'draft after stale save'); await app.submitPreview();
  const staleId = app.requests.filter(request => request.route === 'preview').length;
  app.setVersion('v2'); await app.apply();
  assert.equal(app.requests.filter(request => request.route === 'apply').length, 1);
  assert.match(app.get('editor-error').textContent, /stale_preview/);
  assert.equal(app.get('apply-preview').disabled, true);
  await app.get('editor-error').querySelector('.mutation-reload').dispatch('click');
  assert.equal(app.get('editor-yaml').value, 'draft after stale save');
  await app.submitPreview();
  const previews = app.requests.filter(request => request.route === 'preview');
  assert.equal(previews.length, staleId + 1); assert.equal(previews.at(-1).body.expectedVersion, 'v2');
  assert.equal(app.get('apply-preview').disabled, false);
});

for (const action of ['create', 'edit']) {
  test('a late ' + action + ' recovery cannot replace a newer observed knowledge version', async () => {
    const app = dashboard(); await app.ready(); await app[action]();
    await app.input('editor-yaml', 'draft kept across recovery');
    app.setVersion('v2'); await app.poll();
    const pending = deferred();
    const oldResponse = action === 'create' ? { knowledge: { version: 'v2' } } : { version: 'v2', source: { ruleYaml: 'server YAML v2' } };
    app.nextResponse(action === 'create' ? 'summary' : 'rule?id=shared.demo', pending.promise);
    const firstRecovery = app.get('editor-error').querySelector('.mutation-reload').dispatch('click');
    await settle(); app.setVersion('v3'); await app.poll();
    pending.resolve(app.reply(oldResponse)); await firstRecovery;
    assert.equal(app.get('editor-yaml').value, 'draft kept across recovery');
    const recovery = app.get('editor-error').querySelector('.mutation-reload');
    assert.ok(app.get('editor-base').textContent !== 'BASE / v2' || recovery, 'A response older than the observed v3 cannot silently become the latest base');
    assert.ok(recovery, 'The editor must retain a route to the newer observed version');
    await recovery.dispatch('click'); await app.submitPreview();
    assert.equal(app.requests.filter(request => request.route === 'preview').at(-1).body.expectedVersion, 'v3');
    assert.equal(app.get('apply-preview').disabled, false);
  });
}

test('a successful save refreshes the selected rule and closes the unchanged editor', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-id', 'shared.saved'); await app.input('editor-yaml', 'saved draft');
  await app.submitPreview(); await app.apply();
  assert.equal(app.requests.filter(request => request.route === 'apply').length, 1);
  assert.equal(app.get('editor-dialog').open, false);
  assert.match(app.get('rule-detail').textContent, /shared.saved/);
  assert.match(app.get('rule-list').textContent, /Saved rule/);
  assert.match(app.get('overview-version').textContent, /saved-v1/);
  assert.doesNotMatch(app.get('toast').textContent, /刷新失败/);
});

test('input during a successful pending save is retained for a new preview', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'submitted draft A'); await app.submitPreview();
  const pending = deferred(); app.setApplyResponse(() => pending.promise);
  const request = app.apply(); await settle();
  await app.input('editor-yaml', 'new draft B'); app.setVersion('v2');
  pending.resolve(app.reply({ ruleId: 'shared.demo', version: 'v2' })); await request;
  assert.equal(app.get('editor-dialog').open, true);
  assert.equal(app.get('editor-yaml').value, 'new draft B');
  assert.equal(app.get('apply-preview').disabled, true);
  const recovery = app.get('editor-error').querySelector('.mutation-reload');
  assert.ok(recovery, 'The retained draft needs an explicit route to preview against the saved version');
  await recovery.dispatch('click');
  assert.equal(app.get('editor-yaml').value, 'new draft B');
});

test('polling the saved version during apply does not keep an unchanged editor open', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'submitted unchanged draft'); await app.submitPreview();
  const pending = deferred(); app.setApplyResponse(() => pending.promise);
  const request = app.apply(); await settle();
  app.setVersion('v2'); await app.poll();
  pending.resolve(app.reply({ ruleId: 'shared.demo', version: 'v2' })); await request;
  assert.equal(app.get('editor-dialog').open, false, 'A poll is not a user draft edit');
  assert.match(app.get('overview-version').textContent, /v2/);
});

test('a preview response arriving after draft input cannot enable saving the old input', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'draft A');
  const pending = deferred(); let submitted;
  app.setPreviewResponse(body => { submitted = body; return pending.promise; });
  const request = app.submitPreview(); await settle();
  assert.equal(submitted.ruleYaml, 'draft A');
  await app.input('editor-yaml', 'draft B');
  pending.resolve(app.reply(app.preview(submitted))); await request;
  assert.equal(app.get('editor-yaml').value, 'draft B');
  assert.equal(app.get('apply-preview').disabled, true, 'The old preview must stay invalid after input changed');
});

test('closing and reopening the editor cannot accept a previous editor response', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  const pending = deferred(); let submitted;
  app.setPreviewResponse(body => { submitted = body; return pending.promise; });
  const request = app.submitPreview(); await settle();
  await app.get('editor-close').dispatch('click'); await app.create();
  await app.input('editor-yaml', 'new editor draft');
  pending.resolve(app.reply(app.preview(submitted))); await request;
  assert.equal(app.get('editor-yaml').value, 'new editor draft');
  assert.equal(app.get('apply-preview').disabled, true);
  assert.doesNotMatch(app.get('mutation-preview').textContent, /允许应用预览/);
});

test('a late preview error cannot change a new editor or release its busy button', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  const first = deferred(), second = deferred(); let secondInput;
  app.setPreviewResponse(() => first.promise);
  const firstRequest = app.submitPreview(); await settle();
  await app.get('editor-close').dispatch('click'); await app.create();
  await app.input('editor-yaml', 'second editor draft');
  app.setPreviewResponse(body => { secondInput = body; return second.promise; });
  const secondRequest = app.submitPreview(); await settle();
  assert.equal(app.get('preview-button').disabled, true);
  first.resolve(app.reply({ error: { code: 'stale_preview', message: 'Old editor failure' } }, 409)); await firstRequest;
  assert.equal(app.get('preview-button').disabled, true, 'Only the new pending request owns the busy button');
  assert.equal(app.get('editor-yaml').value, 'second editor draft');
  assert.doesNotMatch(app.get('editor-error').textContent, /Old editor failure/);
  second.resolve(app.reply(app.preview(secondInput))); await secondRequest;
  assert.equal(app.get('preview-button').disabled, false);
  assert.equal(app.get('apply-preview').disabled, false);
});

test('an old apply failure cannot release the pending save in a reopened editor', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'first draft'); await app.submitPreview();
  const first = deferred(), second = deferred();
  app.setApplyResponse(() => first.promise);
  const firstRequest = app.apply(); await settle();
  assert.equal(app.get('apply-preview').disabled, true);
  await app.get('editor-close').dispatch('click'); await app.create();
  await app.input('editor-yaml', 'second draft'); await app.submitPreview();
  app.setApplyResponse(() => second.promise);
  const secondRequest = app.apply(); await settle();
  assert.equal(app.get('apply-preview').disabled, true);
  first.resolve(app.reply({ error: { code: 'stale_preview', message: 'Old save failure' } }, 409)); await firstRequest;
  assert.equal(app.get('apply-preview').disabled, true, 'A completed old save cannot enable a pending new save');
  assert.equal(app.get('editor-yaml').value, 'second draft');
  assert.doesNotMatch(app.get('editor-error').textContent, /Old save failure/);
  second.resolve(app.reply({ error: { code: 'stale_preview', message: 'Current save failure' } }, 409)); await secondRequest;
  assert.match(app.get('editor-error').textContent, /Current save failure/);
  assert.ok(app.get('editor-error').querySelector('.mutation-reload'));
});

test('polling a changed knowledge version invalidates a completed preview', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  await app.input('editor-yaml', 'local draft'); await app.submitPreview();
  assert.equal(app.get('apply-preview').disabled, false);
  app.setVersion('v2'); await app.poll();
  assert.equal(app.get('apply-preview').disabled, true);
  assert.equal(app.get('editor-yaml').value, 'local draft');
});

test('a preview response for a version changed during the request remains invalid', async () => {
  const app = dashboard(); await app.ready(); await app.create();
  const pending = deferred(); let submitted;
  app.setPreviewResponse(body => { submitted = body; return pending.promise; });
  const request = app.submitPreview(); await settle();
  const oldPreview = app.preview(submitted);
  app.setVersion('v2'); await app.poll();
  pending.resolve(app.reply(oldPreview)); await request;
  assert.equal(app.get('apply-preview').disabled, true);
});

test('changing a context parameter marks the displayed resolution as outdated', async () => {
  const app = dashboard(); await app.ready(); await app.context(); await app.submitResolve();
  assert.match(app.get('resolve-result').textContent, /队伍 20827/);
  await app.input('resolve-team', '16093');
  assert.ok(app.get('resolve-result').querySelector('.stale-result'), 'Changing the context must visibly invalidate the old resolution');
});

test('a resolution response arriving after a context edit cannot look current', async () => {
  const app = dashboard(); await app.ready(); await app.context();
  const pending = deferred(); app.setResolveResponse(() => pending.promise);
  const request = app.submitResolve(); await settle();
  await app.input('resolve-team', '16093');
  pending.resolve(app.reply({ version: 'v1', resolution: { activeRules: [], excludedRules: [], overriddenRules: [], conflicts: [] } }));
  await request;
  const result = app.get('resolve-result');
  assert.ok(!/队伍 20827/.test(result.textContent) || result.querySelector('.stale-result'), 'A late response for old parameters must be discarded or marked outdated');
});

test('editing a checked patch invalidates its result without invalidating the resolution', async () => {
  const app = dashboard(); await app.ready(); await app.context();
  await app.submitResolve(); await app.submitCheck();
  await app.input('manual-diff', 'changed patch');
  assert.ok(app.get('check-result').querySelector('.stale-result'), 'The result belongs to the previous patch');
  assert.equal(app.get('resolve-result').querySelector('.stale-result'), null);
});

test('a check response arriving after patch input cannot look current', async () => {
  const app = dashboard(); await app.ready(); await app.context();
  const pending = deferred(); app.setCheckResponse(() => pending.promise);
  const request = app.submitCheck(); await settle();
  await app.input('manual-diff', 'changed patch');
  pending.resolve(app.reply({ version: 'v1', passed: true, violations: [], soft: [] })); await request;
  const result = app.get('check-result');
  assert.ok(!/指定补丁检测结果/.test(result.textContent) || result.querySelector('.stale-result'), 'A late check for an old patch must be discarded or marked outdated');
});
