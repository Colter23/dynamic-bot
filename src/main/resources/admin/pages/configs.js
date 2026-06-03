const $ = id => document.getElementById(id);

let ctx;
let root;
let api;
let apiBlob;
let state;
let ui;
let invalidate;
let setPage;
let loadPage;
let handleError;
let hydrateMediaImages;
let releaseMediaObjectUrls;
let query;
let esc;
let attr;
let fmtTime;
let fmtBytes;
let fmtDuration;
let label;
let eventLabel;
let pill;
let tags;
let cell;
let mediaImage;
let identity;
let themeSwatch;
let renderTable;
let notify;
let openModal;
let closeModal;
let eventTypes;
let blockKinds;
let publisherKey;
let targetKey;
let policyEvents;
let mentionEvents;
let loadSubscriberTargetCandidates;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  apiBlob = ctx.apiBlob;
  state = ctx.state;
  ui = ctx.ui;
  invalidate = ctx.invalidate;
  setPage = ctx.setPage;
  loadPage = ctx.loadPage;
  handleError = ctx.handleError;
  hydrateMediaImages = ctx.hydrateMediaImages;
  releaseMediaObjectUrls = ctx.releaseMediaObjectUrls;
  query = ctx.query;
  ({
    esc,
    attr,
    fmtTime,
    fmtBytes,
    fmtDuration,
    label,
    eventLabel,
    pill,
    tags,
    cell,
    mediaImage,
    identity,
    themeSwatch,
    renderTable,
    notify,
    openModal,
    closeModal,
    eventTypes,
    blockKinds,
    publisherKey,
    targetKey,
    policyEvents,
    mentionEvents,
    loadSubscriberTargetCandidates,
  } = ui);
}

function pageRoot() {
  return root;
}

async function withButtonLoading(button, loadingText, task) {
  const originalText = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = loadingText;
  }
  try {
    return await task();
  } finally {
    if (button?.isConnected) {
      button.disabled = false;
      if (originalText !== undefined) button.textContent = originalText;
    }
  }
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  document.addEventListener("click", configNavigationGuard, true);
  window.addEventListener("beforeunload", configBeforeUnload);
  await loadConfigs(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  document.removeEventListener("click", configNavigationGuard, true);
  window.removeEventListener("beforeunload", configBeforeUnload);
  state.currentConfigDirty = false;
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "select-config") {
    if (!canDiscardConfigChanges()) return true;
    state.selectedConfigId = id;
    await loadConfigs(false);
    return true;
  }
  if (action === "refresh-config-detail") {
    if (!canDiscardConfigChanges()) return true;
    await withButtonLoading(button, "刷新中...", async () => {
      await renderConfigDetail(id);
      notify("配置已刷新", false);
    });
    return true;
  }
  if (action === "restart-config-plugin") {
    await withButtonLoading(button, "重启中...", restartCurrentConfigPlugin);
    return true;
  }
  if (action === "save-config") {
    await withButtonLoading(button, "保存中...", saveCurrentConfig);
    return true;
  }
  if (action === "jump-config-section") {
    jumpConfigSection(button.dataset.sectionId);
    return true;
  }
  if (action === "toggle-config-section") {
    toggleConfigSection(button);
    return true;
  }
  if (action === "toggle-config-secret") {
    await withButtonLoading(button, "•••", () => toggleConfigSecret(button));
    return true;
  }
  if (action === "add-command-permission") {
    await withButtonLoading(button, "打开中...", openCommandPermissionModal);
    return true;
  }
  if (action === "delete-command-permission") {
    const rules = commandPermissionRules();
    rules.splice(Number(button.dataset.index), 1);
    setCommandPermissionRules(rules);
    return true;
  }
  if (action === "clear-command-permissions") {
    if (!confirm("确定清空所有权限规则吗？")) return true;
    setCommandPermissionRules([]);
    return true;
  }
  return false;
}

function configNavigationGuard(event) {
  if (!state.currentConfigDirty) return;
  const leaving = event.target.closest?.("[data-nav], #refreshPage, #logout, #stopApplication");
  if (!leaving) return;
  if (canDiscardConfigChanges()) return;
  event.preventDefault();
  event.stopImmediatePropagation();
}

function configBeforeUnload(event) {
  if (!state.currentConfigDirty) return;
  event.preventDefault();
  event.returnValue = "";
}

async function loadConfigs(force) {
  if (force || !state.cache.configs) state.cache.configs = await api("/configs");
  const rows = state.cache.configs;
  const selected = rows.find(item => item.id === state.selectedConfigId) || rows[0] || null;
  state.selectedConfigId = selected ? selected.id : "";
  if (!selected) state.currentConfigDetail = null;
  pageRoot().innerHTML = `
    <section class="page">
      <div class="config-layout">
        <aside class="panel config-list-panel">
          <div class="panel-head"><h2>配置文件</h2></div>
          <div class="config-list">
            ${rows.map(item => `<button class="config-item${item.id === state.selectedConfigId ? " active" : ""}" data-action="select-config" data-id="${attr(item.id)}">
              <strong>${esc(item.name)}</strong>
              <span>${esc(item.id)}</span>
              <span>${esc(item.description || item.sourcePath)}</span>
            </button>`).join("") || `<div class="empty">暂无配置</div>`}
          </div>
        </aside>
        <section id="configDetail" class="config-detail">
          <div class="empty">${selected ? "正在加载配置..." : "请选择配置文件"}</div>
        </section>
      </div>
    </section>`;
  if (selected) await renderConfigDetail(selected.id);
}

async function renderConfigDetail(id) {
  const detail = await api(`/configs/${encodeURIComponent(id)}`);
  state.currentConfigDetail = detail;
  const sections = configSections(detail);
  const sectionEntries = Object.entries(sections);
  const hasRestartFields = configHasRestartFields(detail);
  const target = $("configDetail");
  if (!target) return;
  target.innerHTML = `
    <section class="panel config-section">
      <div class="toolbar">
        <div>
          <h2>${esc(detail.name)}</h2>
          <p class="sub-line">${esc(detail.sourcePath)}</p>
        </div>
        <div class="toolbar-actions">
          ${detail.pluginId ? `<button class="secondary" id="restartConfigPlugin" data-action="restart-config-plugin" data-plugin-id="${attr(detail.pluginId)}" hidden>重启插件</button>` : ""}
          <span id="configDirtyBadge" class="config-dirty-badge" hidden>未保存</span>
          <button id="saveConfigButton" data-action="save-config" data-id="${attr(detail.id)}">保存配置</button>
          <button class="secondary" data-action="refresh-config-detail" data-id="${attr(detail.id)}">刷新</button>
        </div>
      </div>
      <span class="sub-line">${esc((detail.schema && detail.schema.description) || detail.description || "当前配置内容")}</span>
      ${hasRestartFields ? `<div class="restart-note">⚠️ 标记的配置项保存后需要重启才会生效</div>` : ""}
      ${sectionEntries.length > 1 ? `<div class="config-section-nav">
        ${sectionEntries.map(([name], index) => `<button type="button" class="config-section-nav-button" data-action="jump-config-section" data-section-id="config-section-${index}">${esc(name)}</button>`).join("")}
      </div>` : ""}
    </section>
    ${sectionEntries.map(([name, fields], index) => `
      <section id="config-section-${index}" class="panel config-section" data-config-section data-section-name="${attr(name)}">
        <div class="panel-head">
          <h2>${esc(name)}</h2>
          <button type="button" class="secondary compact config-section-toggle" data-action="toggle-config-section">收起</button>
        </div>
        <div class="form-grid" data-config-section-body>${fields.map(field => configFieldHtml(detail, field)).join("")}</div>
      </section>`).join("")}`;
  wireConfigRestartWatcher(detail);
  wireConfigFieldVisibility(detail);
  restoreConfigSectionCollapse(detail);
  updateConfigDirtyState(detail);
}

function configSections(detail) {
  const sections = {};
  detail.schema.fields.forEach(field => {
    const section = field.section || "常规";
    (sections[section] = sections[section] || []).push(field);
  });
  return sections;
}

function configHasRestartFields(detail) {
  return (detail.schema.fields || []).some(field => field.restartRequired);
}

function configInputFor(field) {
  return Array.from(pageRoot().querySelectorAll("[data-config-path]")).find(item => item.dataset.configPath === field.path);
}

function configComparableValue(detail, field) {
  const raw = detail.values[field.path];
  if (field.type === "BOOLEAN") return raw === true;
  if (field.type === "NUMBER") return Number(raw || 0);
  if (field.type === "JSON") return normalizeConfigJsonValue(raw);
  return displayConfigValue(raw);
}

function currentConfigComparableValue(field) {
  const node = configInputFor(field);
  if (!node) return null;
  if (field.type === "BOOLEAN") return node.checked;
  if (field.type === "NUMBER") return Number(node.value || 0);
  if (field.type === "JSON") return normalizeConfigJsonValue(node.value);
  if (field.type === "SECRET" && node.dataset.secretOriginal !== undefined && node.value === node.dataset.secretOriginal) return "";
  return node.value;
}

function normalizeConfigJsonValue(raw) {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== "string") return raw;
  const text = raw.trim();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch (_) {
    return text;
  }
}

function configRestartChanged(detail) {
  return (detail.schema.fields || [])
    .filter(field => field.restartRequired)
    .some(field => configFieldChanged(detail, field));
}

function configFieldChanged(detail, field) {
  return JSON.stringify(currentConfigComparableValue(field)) !== JSON.stringify(configComparableValue(detail, field));
}

function configDirtyChanged(detail) {
  return (detail.schema.fields || []).some(field => configFieldChanged(detail, field));
}

function canDiscardConfigChanges() {
  const detail = state.currentConfigDetail;
  if (!detail || !state.currentConfigDirty) return true;
  return confirm("当前配置有未保存的修改，确定放弃这些修改吗？");
}

function updateConfigDirtyState(detail) {
  const dirty = configDirtyChanged(detail);
  state.currentConfigDirty = dirty;
  const badge = $("configDirtyBadge");
  if (badge) badge.hidden = !dirty;
  const saveButton = $("saveConfigButton");
  if (saveButton) saveButton.disabled = !dirty;
}

function jumpConfigSection(sectionId) {
  const section = sectionId ? pageRoot().querySelector(`#${sectionId}`) : null;
  section?.scrollIntoView({ behavior: "smooth", block: "start" });
}

function configCollapseKey(detail, sectionName) {
  return `${detail.id}\u001F${sectionName}`;
}

function collapsedConfigSections() {
  if (!state.collapsedConfigSections) state.collapsedConfigSections = {};
  return state.collapsedConfigSections;
}

function toggleConfigSection(button) {
  const detail = state.currentConfigDetail;
  const section = button.closest("[data-config-section]");
  if (!detail || !section) return;
  const body = section.querySelector("[data-config-section-body]");
  const collapsed = !body.hidden;
  body.hidden = collapsed;
  button.textContent = collapsed ? "展开" : "收起";
  collapsedConfigSections()[configCollapseKey(detail, section.dataset.sectionName || "")] = collapsed;
}

function restoreConfigSectionCollapse(detail) {
  pageRoot().querySelectorAll("[data-config-section]").forEach(section => {
    const body = section.querySelector("[data-config-section-body]");
    const button = section.querySelector(".config-section-toggle");
    const collapsed = !!collapsedConfigSections()[configCollapseKey(detail, section.dataset.sectionName || "")];
    if (body) body.hidden = collapsed;
    if (button) button.textContent = collapsed ? "展开" : "收起";
  });
}

function updateConfigRestartButton(detail) {
  const button = $("restartConfigPlugin");
  if (!button || !detail.pluginId) return;
  const pending = !!state.pendingConfigRestarts[detail.pluginId];
  button.hidden = !(pending || configRestartChanged(detail));
}

function wireConfigRestartWatcher(detail) {
  pageRoot().querySelectorAll("[data-config-path]").forEach(node => {
    const refresh = () => {
      updateConfigRestartButton(detail);
      updateConfigDirtyState(detail);
    };
    node.addEventListener("input", refresh);
    node.addEventListener("change", refresh);
  });
  updateConfigRestartButton(detail);
}

function wireConfigFieldVisibility(detail) {
  const fields = detail.schema.fields || [];
  const refresh = () => {
    fields.forEach(field => {
      const wrapper = configFieldWrapperFor(field.path);
      if (!wrapper) return;
      const rule = field.visibleWhen;
      if (!rule || !rule.path) {
        wrapper.hidden = false;
        return;
      }
      const values = Array.isArray(rule.values) ? rule.values.map(String) : [];
      const current = currentConfigValueForPath(rule.path);
      wrapper.hidden = values.length > 0 ? !values.includes(current) : !current;
    });
    pageRoot().querySelectorAll("[data-config-section]").forEach(section => {
      const wrappers = Array.from(section.querySelectorAll("[data-config-field-path]"));
      section.hidden = wrappers.length > 0 && wrappers.every(item => item.hidden);
    });
  };
  Array.from(new Set(fields.map(field => field.visibleWhen && field.visibleWhen.path).filter(Boolean))).forEach(path => {
    const input = configInputFor({ path });
    if (!input) return;
    input.addEventListener("input", refresh);
    input.addEventListener("change", refresh);
  });
  refresh();
}

function configFieldWrapperFor(path) {
  return Array.from(pageRoot().querySelectorAll("[data-config-field-path]"))
    .find(item => item.dataset.configFieldPath === path);
}

function currentConfigValueForPath(path) {
  const input = configInputFor({ path });
  if (!input) return "";
  if (input.type === "checkbox") return input.checked ? "true" : "false";
  return String(input.value || "");
}

async function saveCurrentConfig(options = {}) {
  const detail = state.currentConfigDetail;
  if (!detail) throw new Error("请选择配置文件");
  const values = {};
  detail.schema.fields.forEach(field => values[field.path] = collectConfigField(field));
  const result = await api(`/configs/${encodeURIComponent(detail.id)}`, { method: "PUT", body: JSON.stringify({ values }) });
  if (result.restartRequired && result.pluginId) state.pendingConfigRestarts[result.pluginId] = true;
  invalidate("configs", "dashboard", "system");
  state.selectedConfigId = detail.id;
  if (!options.skipReload) await loadConfigs(true);
  if (!options.silent) notify(result.message || "配置已保存", false);
  return result;
}

async function restartCurrentConfigPlugin() {
  const detail = state.currentConfigDetail;
  const pluginId = detail && detail.pluginId;
  if (!pluginId) throw new Error("当前配置不属于插件");
  if (configRestartChanged(detail)) {
    await saveCurrentConfig({ silent: true, skipReload: true });
  }
  const result = await api(`/plugins/${encodeURIComponent(pluginId)}/reload`, { method: "POST" });
  delete state.pendingConfigRestarts[pluginId];
  invalidate("plugins", "dashboard", "platformLogins", "configs");
  state.selectedConfigId = detail.id;
  await loadConfigs(true);
  notify(result.message || "插件已重启", false);
}

async function toggleConfigSecret(button) {
  const detail = state.currentConfigDetail;
  if (!detail) throw new Error("请选择配置文件");
  const path = button.dataset.path;
  const input = configInputFor({ path });
  if (!input) throw new Error("未找到密钥输入框");
  if (button.dataset.revealed === "true") {
    input.type = "password";
    button.dataset.revealed = "false";
    button.title = "查看真实值";
    return;
  }
  const result = await api(`/configs/${encodeURIComponent(detail.id)}/secrets/${encodeURIComponent(path)}`);
  const value = result.value === null || result.value === undefined
    ? ""
    : (typeof result.value === "object" ? JSON.stringify(result.value) : String(result.value));
  input.value = value;
  input.dataset.secretOriginal = value;
  input.type = "text";
  button.dataset.revealed = "true";
  button.title = "隐藏真实值";
  updateConfigRestartButton(detail);
}

function configFieldHtml(detail, field) {
  if (field.path === "command.permissions") return commandPermissionsFieldHtml(detail, field);
  const raw = detail.values[field.path];
  const value = displayConfigValue(raw);
  const fieldLabel = `${esc(field.label)}${field.required ? `<span class="required-mark">*</span>` : ""}${field.restartRequired ? ` <span class="restart-mark" title="保存后需重启">⚠️</span>` : ""}`;
  const labelHtml = `<label for="cfg-${attr(field.path)}">${fieldLabel}</label>`;
  const descriptionHtml = configFieldDescriptionHtml(field);
  if (field.type === "BOOLEAN") {
    return `<div class="field" data-config-field-path="${attr(field.path)}"><label class="check"><input id="cfg-${attr(field.path)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" type="checkbox"${raw === true ? " checked" : ""}>${fieldLabel}</label>${descriptionHtml}</div>`;
  }
  if (field.type === "SELECT") {
    return `<div class="field" data-config-field-path="${attr(field.path)}">${labelHtml}<select id="cfg-${attr(field.path)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}">${(field.options || []).map(opt => `<option value="${attr(opt.value)}"${String(raw) === String(opt.value) ? " selected" : ""}>${esc(opt.label)}</option>`).join("")}</select>${descriptionHtml}</div>`;
  }
  if (field.type === "TEXTAREA" || field.type === "JSON") {
    return `<div class="field full" data-config-field-path="${attr(field.path)}">${labelHtml}<textarea id="cfg-${attr(field.path)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}">${esc(value)}</textarea>${descriptionHtml}</div>`;
  }
  if (field.type === "SECRET") {
    return secretConfigFieldHtml(detail, field, labelHtml, descriptionHtml);
  }
  const inputType = field.type === "NUMBER" ? "number" : (field.type === "SECRET" ? "password" : "text");
  const numberAttrs = field.type === "NUMBER" ? ` step="any"${field.min !== undefined && field.min !== null ? ` min="${attr(field.min)}"` : ""}${field.max !== undefined && field.max !== null ? ` max="${attr(field.max)}"` : ""}` : "";
  return `<div class="field" data-config-field-path="${attr(field.path)}">${labelHtml}<input id="cfg-${attr(field.path)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" type="${inputType}"${numberAttrs} value="${attr(value)}">${descriptionHtml}</div>`;
}

function secretConfigFieldHtml(detail, field, labelHtml, descriptionHtml) {
  const hasSecret = !!(detail.secretStates && detail.secretStates[field.path]);
  return `<div class="field" data-config-field-path="${attr(field.path)}">
    ${labelHtml}
    <div class="secret-control">
      <input id="cfg-${attr(field.path)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-secret-input="true" type="password" value="" placeholder="${hasSecret ? "********" : ""}" autocomplete="off">
      <button type="button" class="secret-eye" data-action="toggle-config-secret" data-path="${attr(field.path)}" title="查看真实值"${hasSecret ? "" : " disabled"}>👁</button>
    </div>
    ${descriptionHtml}
  </div>`;
}

function configFieldDescriptionHtml(field) {
  return field.description ? `<span class="inline-note">${esc(field.description)}</span>` : "";
}

function commandPermissionsFieldHtml(detail, field) {
  const rules = Array.isArray(detail.values[field.path]) ? detail.values[field.path] : [];
  return `<div class="field command-permission-field" data-config-field-path="${attr(field.path)}">
    <label for="cfg-command-permissions">${esc(field.label)}</label>
    ${configFieldDescriptionHtml(field)}
    <input id="cfg-command-permissions" data-config-path="${attr(field.path)}" data-config-type="${field.type}" type="hidden" value="${attr(JSON.stringify(rules))}">
    <div class="command-permission-toolbar">
      <button type="button" data-action="add-command-permission">添加规则</button>
      <button type="button" class="secondary" data-action="clear-command-permissions">清空</button>
    </div>
    <div id="commandPermissionTable">${renderCommandPermissionTable(rules)}</div>
  </div>`;
}

function commandPermissionRules() {
  const node = $("cfg-command-permissions");
  if (!node || !node.value.trim()) return [];
  try {
    const value = JSON.parse(node.value);
    return Array.isArray(value) ? value : [];
  } catch (_) {
    return [];
  }
}

function setCommandPermissionRules(rules) {
  const normalized = rules.map(normalizeCommandPermissionRule);
  const node = $("cfg-command-permissions");
  if (node) node.value = JSON.stringify(normalized);
  const table = $("commandPermissionTable");
  if (table) table.innerHTML = renderCommandPermissionTable(normalized);
  if (state.currentConfigDetail) {
    updateConfigRestartButton(state.currentConfigDetail);
    updateConfigDirtyState(state.currentConfigDetail);
  }
}

function normalizeCommandPermissionRule(rule) {
  return {
    platformId: rule.platformId || "*",
    targetKind: rule.targetKind || null,
    targetId: rule.targetId || "*",
    scopeId: rule.scopeId || "*",
    threadId: rule.threadId || "*",
    accountId: rule.accountId || "*",
    senderId: rule.senderId || "*",
    role: rule.role || "ADMIN"
  };
}

function renderCommandPermissionTable(rules) {
  const rows = rules.map((rule, index) => Object.assign({ _index: index }, normalizeCommandPermissionRule(rule)));
  return renderTable(rows, [
    { title: "平台", render: rule => `<span class="primary-line">${esc(wildcardText(rule.platformId))}</span>` },
    { title: "目标", render: rule => cell(commandPermissionTargetTitle(rule), commandPermissionScopeText(rule)) },
    { title: "发送者", render: rule => `<span class="primary-line">${esc(wildcardText(rule.senderId))}</span>` },
    { title: "角色", render: rule => pill(rule.role) },
    { title: "操作", render: rule => `<button type="button" class="danger" data-action="delete-command-permission" data-index="${rule._index}">删除</button>` }
  ]);
}

function commandPermissionTargetTitle(rule) {
  const kind = rule.targetKind ? label(rule.targetKind) : "全部类型";
  return `${kind} / ${wildcardText(rule.targetId)}`;
}

function commandPermissionScopeText(rule) {
  const items = [
    ["作用域", rule.scopeId],
    ["线程", rule.threadId],
    ["账号", rule.accountId]
  ].filter(([, value]) => value && value !== "*");
  return items.length ? items.map(([name, value]) => `${name}:${value}`).join(" / ") : "全部上下文";
}

function wildcardText(value) {
  return !value || value === "*" ? "全部" : value;
}

async function openCommandPermissionModal() {
  const targetPlatforms = await loadPermissionTargetPlatforms();
  const platformOptions = [{ platformId: "*", pluginName: "全部平台", supportedTypes: ["GROUP", "USER", "CHANNEL", "OTHER"] }]
    .concat(targetPlatforms)
    .concat([{ platformId: "__manual__", pluginName: "手动输入", supportedTypes: ["GROUP", "USER", "CHANNEL", "OTHER"] }]);
  let targetCandidates = [];
  openModal("添加权限规则", `
    <div class="form-grid">
      <div class="field"><label>目标平台</label><select id="permPlatform">${platformOptions.map(platform => `<option value="${attr(platform.platformId)}">${esc(commandPermissionPlatformOptionText(platform))}</option>`).join("")}</select></div>
      <div class="field" id="permPlatformManualWrap" hidden><label>平台 ID<span class="required-mark">*</span></label><input id="permPlatformManual"></div>
      <div class="field"><label>目标类型</label><select id="permTargetKind"></select></div>
      <div class="field full" id="permTargetCandidateWrap" hidden>
        <div class="field-head">
          <label>可用目标</label>
          <button type="button" id="permRefreshTargets" class="choice-refresh-button compact">刷新目标</button>
        </div>
        <select id="permTargetCandidate"></select>
      </div>
      <div class="field full" id="permTargetManualWrap" hidden><label>目标 ID</label><input id="permTargetId" value="*"></div>
      <div class="field"><label>发送者 ID<span class="required-mark">*</span></label><input id="permSenderId" value="*" placeholder="填写用户 ID，或 * 表示全部"></div>
      <div class="field"><label>角色</label><select id="permRole"><option value="ADMIN">管理员</option><option value="USER">普通用户</option></select></div>
      <div class="field full">
        <details>
          <summary>高级匹配</summary>
          <div class="form-grid permission-advanced">
            <div class="field"><label>作用域 ID</label><input id="permScopeId" value="*"></div>
            <div class="field"><label>线程 ID</label><input id="permThreadId" value="*"></div>
            <div class="field"><label>账号 ID</label><input id="permAccountId" value="*"></div>
          </div>
        </details>
      </div>
      <div class="field full"><span id="permTargetStatus" class="inline-note"></span></div>
    </div>
  `, async () => {
    const next = commandPermissionRules().concat([collectCommandPermissionRule(targetCandidates)]);
    setCommandPermissionRules(next);
    closeModal();
    notify("权限规则已添加，请保存配置", false);
  }, { size: "wide" });

  const refreshKinds = async () => {
    const rawPlatformId = $("permPlatform").value;
    const platformId = permissionPlatformValue();
    $("permPlatformManualWrap").hidden = rawPlatformId !== "__manual__";
    const platform = platformOptions.find(item => item.platformId === rawPlatformId);
    const kinds = rawPlatformId === "*"
      ? ["*"]
      : ["*"].concat((platform && platform.supportedTypes && platform.supportedTypes.length) ? platform.supportedTypes : ["GROUP", "USER", "CHANNEL", "OTHER"]);
    $("permTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(kind === "*" ? "全部类型" : label(kind))}</option>`).join("");
    await refreshTargets(false);
  };
  const refreshTargets = async (force = false) => {
    const rawPlatformId = $("permPlatform").value;
    const platformId = permissionPlatformValue();
    const kind = $("permTargetKind").value;
    targetCandidates = [];
    $("permTargetCandidateWrap").hidden = true;
    $("permTargetManualWrap").hidden = true;
    $("permTargetStatus").textContent = "";
    setPermissionAdvancedFields(null);
    if (rawPlatformId === "*" || kind === "*") {
      $("permTargetId").value = "*";
      setPermissionTargetStatus("当前规则匹配全部目标");
      return;
    }
    if (!platformId) {
      $("permTargetManualWrap").hidden = false;
      setPermissionTargetStatus("请填写平台 ID");
      return;
    }
    setPermissionTargetStatus("正在获取可用目标...", true);
    $("permRefreshTargets").disabled = true;
    try {
      const result = await loadSubscriberTargetCandidates(platformId, kind, force);
      targetCandidates = result.items || [];
      if (result.stale) {
        setPermissionTargetStatus(`接口暂不可用，显示 ${targetCandidates.length} 个缓存目标`);
      } else if (result.fromCache) {
        setPermissionTargetStatus(`已从缓存读取 ${targetCandidates.length} 个目标`);
      } else {
        setPermissionTargetStatus(`已获取 ${targetCandidates.length} 个目标`);
      }
    } catch (error) {
      targetCandidates = [];
      setPermissionTargetStatus(error.message || "可用目标获取失败");
    } finally {
      $("permRefreshTargets").disabled = false;
    }
    if (targetCandidates.length) {
      $("permTargetCandidateWrap").hidden = false;
      $("permTargetCandidate").innerHTML = `<option value="-1">全部目标</option>` + targetCandidates.map((target, index) =>
        `<option value="${index}">${esc(target.name || target.externalId)} · ${esc(target.externalId)}</option>`
      ).join("");
      setPermissionAdvancedFields(null);
      $("permTargetCandidate").onchange = () => setPermissionAdvancedFields(targetCandidates[Number($("permTargetCandidate").value)] || null);
    } else {
      $("permTargetManualWrap").hidden = false;
      setPermissionTargetStatus("未获取到可用目标，请手动填写目标 ID");
    }
  };
  $("permPlatform").onchange = () => refreshKinds().catch(handleError);
  $("permPlatformManual").oninput = () => refreshTargets(false).catch(handleError);
  $("permTargetKind").onchange = () => refreshTargets(false).catch(handleError);
  $("permRefreshTargets").onclick = () => refreshTargets(true).catch(handleError);
  await refreshKinds();
}

async function loadPermissionTargetPlatforms() {
  if (state.cache.subscriberTargetPlatforms) return state.cache.subscriberTargetPlatforms;
  state.cache.subscriberTargetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  return state.cache.subscriberTargetPlatforms;
}

function setPermissionTargetStatus(text, loading = false) {
  const target = $("permTargetStatus");
  if (!target) return;
  target.innerHTML = loading
    ? `<span class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</span>`
    : esc(text);
}

function commandPermissionPlatformOptionText(platform) {
  if (platform.platformId === "*") return "全部平台";
  if (platform.platformId === "__manual__") return "手动输入";
  return `${platform.platformId} · ${platform.pluginName || platform.pluginId || ""}`;
}

function permissionPlatformValue() {
  return $("permPlatform").value === "__manual__" ? $("permPlatformManual").value.trim() : $("permPlatform").value;
}

function setPermissionAdvancedFields(target) {
  $("permScopeId").value = target && target.scopeId || "*";
  $("permThreadId").value = target && target.threadId || "*";
  $("permAccountId").value = target && target.accountId || "*";
  $("permTargetId").value = target && target.externalId || "*";
}

function collectCommandPermissionRule(targetCandidates) {
  const platformId = permissionPlatformValue() || "*";
  if ($("permPlatform").value === "__manual__" && platformId === "*") throw new Error("请填写平台 ID");
  const rawKind = $("permTargetKind").value || "*";
  const candidateIndex = $("permTargetCandidate") && !$("permTargetCandidateWrap").hidden ? Number($("permTargetCandidate").value) : -1;
  const candidate = candidateIndex >= 0 ? targetCandidates[candidateIndex] : null;
  const targetId = platformId === "*" || rawKind === "*"
    ? "*"
    : (candidate ? candidate.externalId : $("permTargetId").value.trim() || "*");
  const senderId = $("permSenderId").value.trim();
  if (!senderId) throw new Error("请填写发送者 ID，或使用 * 表示全部发送者");
  return normalizeCommandPermissionRule({
    platformId,
    targetKind: rawKind === "*" ? null : rawKind,
    targetId,
    scopeId: $("permScopeId").value.trim() || "*",
    threadId: $("permThreadId").value.trim() || "*",
    accountId: $("permAccountId").value.trim() || "*",
    senderId,
    role: $("permRole").value || "ADMIN"
  });
}

function displayConfigValue(raw) {
  return raw === null || raw === undefined ? "" : (typeof raw === "object" ? JSON.stringify(raw, null, 2) : String(raw));
}

function collectConfigField(field) {
  const node = configInputFor(field);
  if (!node) return null;
  if (field.type === "BOOLEAN") return node.checked;
  if (field.type === "NUMBER") return Number(node.value || 0);
  if (field.type === "JSON") return node.value.trim() ? JSON.parse(node.value) : null;
  return node.value;
}
