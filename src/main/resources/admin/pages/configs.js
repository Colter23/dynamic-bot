import { createMessageTemplateEditor } from "../assets/message-template-editor.js";

const $ = id => document.getElementById(id);

let ctx;
let root;
let api;
let state;
let invalidate;
let handleError;
let esc;
let attr;
let label;
let pill;
let cell;
let platformTag;
let renderTable;
let notify;
let openModal;
let closeModal;
let confirmDanger;
let loadSubscriberTargetCandidates;
let hydrateMediaImages;
let messageTargetChoiceHtml;
let bindTargetSourceToggles;
let selectedTargetPriorityAccount;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;
let messageTemplateEditor;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  invalidate = ctx.invalidate;
  handleError = ctx.handleError;
  ({
    esc,
    attr,
    label,
    pill,
    cell,
    platformTag,
    renderTable,
    notify,
    openModal,
    closeModal,
    confirmDanger,
    loadSubscriberTargetCandidates,
    messageTargetChoiceHtml,
    bindTargetSourceToggles,
    selectedTargetPriorityAccount,
  } = ctx.ui);
  messageTemplateEditor = createMessageTemplateEditor({ esc, attr, document });
  hydrateMediaImages = ctx.hydrateMediaImages;
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

async function withButtonLoading(button, loadingText, task) {
  const originalHtml = button?.innerHTML;
  if (button) {
    button.disabled = true;
    button.textContent = loadingText;
  }
  try {
    return await task();
  } finally {
    if (button?.isConnected) {
      button.disabled = false;
      if (originalHtml !== undefined) button.innerHTML = originalHtml;
    }
  }
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  window.addEventListener("beforeunload", configBeforeUnload);
  await loadConfigs(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  window.removeEventListener("beforeunload", configBeforeUnload);
  state.currentConfigDirty = false;
  invalidatePageRequests("configs");
}

export async function canLeave(nextCtx) {
  bindContext(nextCtx);
  return await canDiscardConfigChanges();
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "select-config") {
    if (!(await canDiscardConfigChanges())) return true;
    state.selectedConfigId = id;
    await loadConfigs(false);
    return true;
  }
  if (action === "refresh-config-detail") {
    if (!(await canDiscardConfigChanges())) return true;
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
  if (action === "reset-all-config") {
    if (!(await confirmDanger("重置所有修改", "确定要放弃所有未保存的修改吗？此操作不可恢复。", { confirmText: "重置" }))) return true;
    await withButtonLoading(button, "重置中...", () => resetAllConfig());
    return true;
  }
  if (action === "select-config-section") {
    selectConfigSection(button);
    return true;
  }
  if (action === "toggle-config-description") {
    toggleConfigDescription(button);
    return true;
  }
  if (action === "toggle-config-secret") {
    await withButtonLoading(button, "•••", () => toggleConfigSecret(button));
    return true;
  }
  if (action === "load-config-secret") {
    await withButtonLoading(button, "获取中...", () => loadConfigSecret(button));
    return true;
  }
  if (action === "edit-message-template") {
    await withButtonLoading(button, "打开中...", () => openMessageTemplateModal(button.dataset.path));
    return true;
  }
  if (action === "add-onebot-connection") {
    await withButtonLoading(button, "打开中...", () => openOneBotConnectionModal());
    return true;
  }
  if (action === "edit-onebot-connection") {
    await withButtonLoading(button, "打开中...", () => openOneBotConnectionModal(Number(button.dataset.index)));
    return true;
  }
  if (action === "delete-onebot-connection") {
    const connections = oneBotConnections();
    connections.splice(Number(button.dataset.index), 1);
    setOneBotConnections(connections);
    return true;
  }
  if (action === "clear-onebot-connections") {
    if (!(await confirmDanger("清空 OneBot 连接", "确定清空所有正向 WebSocket 连接吗？保存配置后才会正式生效。", { confirmText: "清空" }))) return true;
    setOneBotConnections([]);
    return true;
  }
  if (action === "toggle-onebot-connection-token") {
    toggleOneBotConnectionToken(button);
    return true;
  }
  if (action === "add-message-routing-policy") {
    await withButtonLoading(button, "打开中...", () => openMessageRoutingPlatformPolicyModal());
    return true;
  }
  if (action === "edit-message-routing-policy") {
    await withButtonLoading(button, "打开中...", () => openMessageRoutingPlatformPolicyModal(Number(button.dataset.index)));
    return true;
  }
  if (action === "delete-message-routing-policy") {
    const policies = messageRoutingPlatformPolicies();
    policies.splice(Number(button.dataset.index), 1);
    setMessageRoutingPlatformPolicies(policies);
    return true;
  }
  if (action === "clear-message-routing-policies") {
    if (!(await confirmDanger("清空平台路由策略", "确定清空所有按平台路由策略吗？保存配置后才会正式生效。", { confirmText: "清空" }))) return true;
    setMessageRoutingPlatformPolicies([]);
    return true;
  }
  if (action === "add-notification-target") {
    await withButtonLoading(button, "打开中...", () => openNotificationTargetModal());
    return true;
  }
  if (action === "edit-notification-target") {
    await withButtonLoading(button, "打开中...", () => openNotificationTargetModal(Number(button.dataset.index)));
    return true;
  }
  if (action === "delete-notification-target") {
    const targets = notificationTargets();
    targets.splice(Number(button.dataset.index), 1);
    setNotificationTargets(targets);
    return true;
  }
  if (action === "clear-notification-targets") {
    if (!(await confirmDanger("清空通知目标", "确定清空所有通知目标吗？保存配置后才会正式生效。", { confirmText: "清空" }))) return true;
    setNotificationTargets([]);
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
    if (!(await confirmDanger("清空权限规则", "确定清空所有命令权限规则吗？保存配置后才会正式生效。", { confirmText: "清空" }))) return true;
    setCommandPermissionRules([]);
    return true;
  }
  return false;
}

function configBeforeUnload(event) {
  if (!state.currentConfigDirty) return;
  event.preventDefault();
  event.returnValue = "";
}

async function loadConfigs(force) {
  const request = beginPageRequest("configs");
  if (force || !state.cache.configs) state.cache.configs = await api("/configs");
  if (!isCurrentPageRequest(request)) return;
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
              <strong>${esc(item.name)} <span>${esc(item.id)}</span></strong>
              <span>${esc(item.description || item.sourcePath)}</span>
            </button>`).join("") || `<div class="empty">暂无配置</div>`}
          </div>
        </aside>
        <section id="configDetail" class="config-detail">
          <div class="empty">${selected ? "正在加载配置..." : "请选择配置文件"}</div>
        </section>
      </div>
    </section>`;
  if (selected) await renderConfigDetail(selected.id, request);
}

async function renderConfigDetail(id, request = beginPageRequest("configs")) {
  const detail = await api(`/configs/${encodeURIComponent(id)}`);
  if (!isCurrentPageRequest(request)) return;
  state.currentConfigDetail = detail;
  const sections = configSections(detail);
  const sectionEntries = Object.entries(sections);
  const hasRestartFields = configHasRestartFields(detail);
  const activeSection = activeConfigSectionName(detail, sectionEntries);
  const totalFields = (detail.schema.fields || []).length;
  const configDescription = (detail.schema && detail.schema.description) || detail.description || "当前配置内容";
  const target = $("configDetail");
  if (!target) return;
  target.innerHTML = `
    <div class="config-workbench">
      <aside class="panel config-tab-panel">
        <div class="config-tab-panel-head">
          <span>配置分组</span>
          <strong id="configActiveSectionTitle">${esc(activeSection || "常规")}</strong>
        </div>
        <div class="config-tab-list" role="tablist" aria-label="配置分组">
          ${sectionEntries.map(([name, fields], index) => `
            <button type="button" class="config-tab-button${name === activeSection ? " active" : ""}" data-action="select-config-section" data-config-section-tab data-section-name="${attr(name)}" role="tab" aria-selected="${name === activeSection ? "true" : "false"}" aria-controls="config-section-${index}">
              <span class="config-tab-name">${esc(name)}</span>
              <span class="config-tab-state" data-config-tab-state>${esc(`${fields.length} 项`)}</span>
            </button>
          `).join("")}
        </div>
      </aside>
      <div class="config-tab-stage">
        <section class="panel config-hero" id="configHeroPanel">
          <div class="config-hero-main">
            <div class="config-hero-title">
              <div class="config-title-line">
                <span class="config-kind-pill">${detail.pluginId ? "插件配置" : "主配置"}</span>
                <h2>${esc(detail.name)}</h2>
                <span class="config-source-path">${esc(detail.sourcePath)}</span>
              </div>
              <div class="config-description-line">
                <p>${esc(configDescription)}</p>
                ${hasRestartFields ? `<span class="restart-note">⚠️ 标记项保存后需要重启才会生效</span>` : ""}
              </div>
            </div>
            <div class="config-hero-side">
              <div class="config-hero-meta">
                <span>${esc(`${sectionEntries.length} 个分组`)}</span>
                <span>${esc(`${totalFields} 个配置项`)}</span>
                ${detail.pluginId ? `<span>${esc(detail.pluginId)}</span>` : ""}
              </div>
              <div id="configActionBar" class="config-hero-actions" hidden>
                <div class="config-action-buttons">
                  <button type="button" class="secondary compact" id="resetAllConfigButton" data-action="reset-all-config" hidden>
                    <span>重置所有</span>
                  </button>
                  ${detail.pluginId ? `<button type="button" class="secondary compact config-action-restart" id="actionBarRestartPlugin" data-action="restart-config-plugin" hidden>
                    <span>⚠️ 重启插件</span>
                  </button>` : ""}
                  <button type="button" class="primary" id="actionBarSaveButton" data-action="save-config" hidden>
                    <span>保存配置</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </section>
        ${sectionEntries.map(([name, fields], index) => `
          <section id="config-section-${index}" class="panel config-section" data-config-section data-section-name="${attr(name)}" data-config-section-available="true"${name === activeSection ? "" : " hidden"}>
            <div class="config-section-head">
              <div>
                <span class="config-section-kicker">当前分组</span>
                <h2>${esc(name)}</h2>
              </div>
              <span class="config-section-count">${esc(`${fields.length} 个配置项`)}</span>
            </div>
            <div class="config-field-list" data-config-section-body>${configSectionFieldsHtml(detail, fields)}</div>
          </section>`).join("")}
      </div>
    </div>`;
  wireConfigRestartWatcher(detail);
  wireConfigFieldVisibility(detail);
  wireMediaDeliveryField(detail);
  applyConfigSectionTabs(activeSection);
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

function configSectionFieldsHtml(detail, fields) {
  const hiddenFields = fields.filter(isHiddenConfigField);
  const visibleFields = fields.filter(field => !isHiddenConfigField(field));
  const hiddenHtml = hiddenFields.map(field => configFieldHtml(detail, field)).join("");
  const normalFields = visibleFields.filter(field => !field.advanced);
  const advancedFields = visibleFields.filter(field => field.advanced);
  const normalHtml = normalFields.map(field => configFieldHtml(detail, field)).join("");
  if (!advancedFields.length) return `${hiddenHtml}${normalHtml}`;
  return `${hiddenHtml}${normalHtml}
    <details class="config-advanced" data-config-advanced>
      <summary>
        <span>高级配置</span>
        <small>${esc(`${advancedFields.length} 项`)}</small>
      </summary>
      <div class="config-field-list config-advanced-grid">
        ${advancedFields.map(field => configFieldHtml(detail, field)).join("")}
      </div>
    </details>`;
}

function isHiddenConfigField(field) {
  return field.component === "HIDDEN";
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
  if (field.type === "SECRET") return configSecretOriginalValue(detail, field);
  if (field.component === "MESSAGE_ROUTING_POLICY_TABLE") return normalizeMessageRoutingPlatformPolicies(normalizeConfigJsonValue(raw));
  if (field.component === "ONEBOT_CONNECTION_TABLE") return normalizeOneBotConnections(normalizeConfigJsonValue(raw));
  if (field.component === "COMMAND_PERMISSION_TABLE") return normalizeCommandPermissionRules(normalizeConfigJsonValue(raw));
  if (field.component === "NOTIFICATION_TARGET_TABLE") return normalizeNotificationTargets(normalizeConfigJsonValue(raw));
  if (field.component === "MEDIA_DELIVERY_PROFILES") return normalizeMediaDeliveryProfiles(normalizeConfigJsonValue(raw));
  if (field.type === "JSON") return normalizeConfigJsonValue(raw);
  return displayConfigValue(raw);
}

function configSecretOriginalValue(detail, field) {
  const node = configInputFor(field);
  if (node && Object.prototype.hasOwnProperty.call(node.dataset, "secretOriginal")) {
    return node.dataset.secretOriginal || "";
  }
  return displayConfigValue(detail.values[field.path]);
}

function currentConfigComparableValue(field) {
  const node = configInputFor(field);
  if (!node) return null;
  if (field.type === "BOOLEAN") return node.checked;
  if (field.type === "NUMBER") return Number(node.value || 0);
  if (field.component === "MESSAGE_ROUTING_POLICY_TABLE") return normalizeMessageRoutingPlatformPolicies(normalizeConfigJsonValue(node.value));
  if (field.component === "ONEBOT_CONNECTION_TABLE") return normalizeOneBotConnections(normalizeConfigJsonValue(node.value));
  if (field.component === "COMMAND_PERMISSION_TABLE") return normalizeCommandPermissionRules(normalizeConfigJsonValue(node.value));
  if (field.component === "NOTIFICATION_TARGET_TABLE") return normalizeNotificationTargets(normalizeConfigJsonValue(node.value));
  if (field.component === "MEDIA_DELIVERY_PROFILES") return normalizeMediaDeliveryProfiles(normalizeConfigJsonValue(node.value));
  if (field.type === "JSON") return normalizeConfigJsonValue(node.value);
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
  if (field.readOnly) return false;
  return JSON.stringify(currentConfigComparableValue(field)) !== JSON.stringify(configComparableValue(detail, field));
}

function configDirtyChanged(detail) {
  return (detail.schema.fields || []).some(field => configFieldChanged(detail, field));
}

async function canDiscardConfigChanges() {
  const detail = state.currentConfigDetail;
  if (!detail || !state.currentConfigDirty) return true;
  return await confirmDanger("放弃未保存修改", "当前配置有未保存的修改，确定放弃这些修改吗？", { confirmText: "放弃修改" });
}

function updateConfigDirtyState(detail) {
  const dirty = configDirtyChanged(detail);
  state.currentConfigDirty = dirty;
  updateConfigTabState(detail);
  updateConfigActionBar(detail, dirty);
}

function configActiveSections() {
  if (!state.configActiveSections) state.configActiveSections = {};
  return state.configActiveSections;
}

function configActiveSectionKey(detail) {
  return detail ? detail.id || "main" : "main";
}

function activeConfigSectionName(detail, sectionEntries) {
  const entries = sectionEntries || Object.entries(configSections(detail));
  const saved = configActiveSections()[configActiveSectionKey(detail)];
  if (saved && entries.some(([name]) => name === saved)) return saved;
  return entries[0]?.[0] || "";
}

function selectConfigSection(button) {
  const detail = state.currentConfigDetail;
  const sectionName = button.dataset.sectionName || "";
  if (!detail || !sectionName || button.disabled) return;
  configActiveSections()[configActiveSectionKey(detail)] = sectionName;
  applyConfigSectionTabs(sectionName);
}

function applyConfigSectionTabs(activeName) {
  const detail = state.currentConfigDetail;
  if (!detail) return;
  const sections = Array.from(pageRoot().querySelectorAll("[data-config-section]"));
  const tabs = Array.from(pageRoot().querySelectorAll("[data-config-section-tab]"));
  const availableTabs = tabs.filter(tab => tab.dataset.configSectionAvailable !== "false");
  let nextActive = activeName && availableTabs.some(tab => tab.dataset.sectionName === activeName)
    ? activeName
    : availableTabs[0]?.dataset.sectionName || tabs[0]?.dataset.sectionName || "";
  if (nextActive) configActiveSections()[configActiveSectionKey(detail)] = nextActive;
  sections.forEach(section => {
    const available = section.dataset.configSectionAvailable !== "false";
    section.hidden = !available || section.dataset.sectionName !== nextActive;
  });
  tabs.forEach(tab => {
    const available = tab.dataset.configSectionAvailable !== "false";
    const active = available && tab.dataset.sectionName === nextActive;
    tab.classList.toggle("active", active);
    tab.disabled = !available;
    tab.setAttribute("aria-selected", active ? "true" : "false");
  });
  const title = $("configActiveSectionTitle");
  if (title) title.textContent = nextActive || "常规";
}

function updateConfigTabState(detail) {
  if (!detail) return;
  const fields = detail.schema.fields || [];
  pageRoot().querySelectorAll("[data-config-section-tab]").forEach(tab => {
    const sectionName = tab.dataset.sectionName || "常规";
    const sectionFields = fields.filter(field => (field.section || "常规") === sectionName);
    const changed = sectionFields.some(field => configFieldChanged(detail, field));
    const restartChanged = sectionFields.some(field => field.restartRequired && configFieldChanged(detail, field));
    const changedCount = sectionFields.filter(field => configFieldChanged(detail, field)).length;

    tab.classList.toggle("changed", changed);
    tab.classList.toggle("restart-changed", restartChanged);

    const stateNode = tab.querySelector("[data-config-tab-state]");
    if (stateNode) {
      if (changed) {
        const badge = changedCount > 0 ? ` (${changedCount})` : "";
        stateNode.textContent = restartChanged ? `⚠️ 需重启${badge}` : `✏️ 已修改${badge}`;
      } else {
        stateNode.textContent = `${sectionFields.length} 项`;
      }
    }
  });
}

function refreshConfigSectionAvailability(detail) {
  const fields = detail.schema.fields || [];
  pageRoot().querySelectorAll("[data-config-section]").forEach(section => {
    const wrappers = Array.from(section.querySelectorAll("[data-config-field-path]"));
    const available = wrappers.length === 0 || wrappers.some(item => !item.hidden);
    section.dataset.configSectionAvailable = available ? "true" : "false";
    const sectionName = section.dataset.sectionName || "";
    const tab = Array.from(pageRoot().querySelectorAll("[data-config-section-tab]"))
      .find(item => item.dataset.sectionName === sectionName);
    if (tab) {
      tab.dataset.configSectionAvailable = available ? "true" : "false";
      const sectionFields = fields.filter(field => (field.section || "常规") === sectionName);
      const stateNode = tab.querySelector("[data-config-tab-state]");
      if (stateNode && !tab.classList.contains("changed")) {
        stateNode.textContent = available ? `${sectionFields.length} 项` : "无可用项";
      }
    }
  });
  applyConfigSectionTabs(configActiveSections()[configActiveSectionKey(detail)]);
}

function updateConfigRestartButton(detail) {
  const showRestart = configShouldShowRestart(detail);
  updateConfigActionBar(detail, undefined, showRestart);
}

function configShouldShowRestart(detail) {
  if (!detail || !detail.pluginId) return false;
  return !!state.pendingConfigRestarts[detail.pluginId] || configRestartChanged(detail);
}

function updateConfigActionBar(detail, dirty = undefined, showRestart = undefined) {
  const bar = $("configActionBar");
  if (!bar || !detail) return;
  const isDirty = dirty === undefined ? configDirtyChanged(detail) : dirty;
  const restartVisible = showRestart === undefined ? configShouldShowRestart(detail) : showRestart;

  const saveButton = $("actionBarSaveButton");
  if (saveButton) {
    saveButton.hidden = !isDirty;
    saveButton.disabled = !isDirty;
  }

  const resetButton = $("resetAllConfigButton");
  if (resetButton) {
    resetButton.hidden = !isDirty;
    resetButton.disabled = !isDirty;
  }

  const restartButton = $("actionBarRestartPlugin");
  if (restartButton) restartButton.hidden = !restartVisible;

  bar.hidden = !(isDirty || restartVisible);
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
    refreshConfigSectionAvailability(detail);
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

async function resetAllConfig() {
  const detail = state.currentConfigDetail;
  if (!detail) throw new Error("请选择配置文件");
  await loadConfigs(true);
  notify("已重置所有修改", false);
}

async function loadConfigSecret(button) {
  const path = button.dataset.path;
  const input = configInputFor({ path });
  if (!input) throw new Error("未找到密钥输入框");
  if (input.dataset.secretLoaded !== "true" && input.dataset.secretHasValue === "true" && !input.value) {
    const detail = state.currentConfigDetail;
    if (!detail) throw new Error("请选择配置文件");
    const response = await api(`/configs/${encodeURIComponent(detail.id)}/secrets/${encodeURIComponent(path)}`);
    const value = displayConfigValue(response.value);
    input.value = value;
    input.dataset.secretOriginal = value;
    input.dataset.secretLoaded = "true";
    input.type = "password";
    showConfigSecretEditor(input);
    updateConfigRestartButton(detail);
    updateConfigDirtyState(detail);
  }
  showConfigSecretEditor(input);
  input.focus();
}

async function toggleConfigSecret(button) {
  const path = button.dataset.path;
  const input = configInputFor({ path });
  if (!input) throw new Error("未找到密钥输入框");
  if (input.type === "hidden") await loadConfigSecret(button);
  if (button.dataset.revealed === "true") {
    input.type = "password";
    button.dataset.revealed = "false";
    button.setAttribute("aria-pressed", "false");
    button.title = "查看真实值";
    return;
  }
  input.type = "text";
  button.dataset.revealed = "true";
  button.setAttribute("aria-pressed", "true");
  button.title = "隐藏真实值";
}

function configFieldHtml(detail, field) {
  if (field.component === "HIDDEN") return hiddenConfigFieldHtml(detail, field);
  if (field.component === "MESSAGE_TEMPLATE_EDITOR") return messageTemplateFieldHtml(detail, field);
  if (field.component === "MESSAGE_ROUTING_POLICY_TABLE") return messageRoutingPlatformPoliciesFieldHtml(detail, field);
  if (field.component === "ONEBOT_CONNECTION_TABLE") return oneBotConnectionsFieldHtml(detail, field);
  if (field.component === "COMMAND_PERMISSION_TABLE") return commandPermissionsFieldHtml(detail, field);
  if (field.component === "NOTIFICATION_TARGET_TABLE") return notificationTargetsFieldHtml(detail, field);
  if (field.component === "MEDIA_DELIVERY_PROFILES") return mediaDeliveryProfilesFieldHtml(detail, field);
  const raw = detail.values[field.path];
  const value = displayConfigValue(raw);
  const id = `cfg-${field.path}`;
  if (field.type === "BOOLEAN") {
    return configToggleFieldHtml(field, id, raw === true);
  }
  if (field.type === "SELECT") {
    return configControlFieldHtml(
      field,
      id,
      `<select id="${attr(id)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}"${readOnlyDisabledAttrs(field)}>${(field.options || []).map(opt => `<option value="${attr(opt.value)}"${String(raw) === String(opt.value) ? " selected" : ""}>${esc(opt.label)}</option>`).join("")}</select>`,
    );
  }
  if (field.type === "TEXTAREA" || field.type === "JSON") {
    return configControlFieldHtml(
      field,
      id,
      `<textarea id="${attr(id)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}"${readOnlyInputAttrs(field)}>${esc(value)}</textarea>`,
      "config-textarea-field",
    );
  }
  if (field.type === "SECRET") {
    return secretConfigFieldHtml(detail, field);
  }
  const inputType = field.type === "NUMBER" ? "number" : "text";
  const step = field.numberKind === "INTEGER" ? "1" : "any";
  const numberAttrs = field.type === "NUMBER" ? ` step="${step}"${field.numberKind === "INTEGER" ? ' inputmode="numeric"' : ""}${field.min !== undefined && field.min !== null ? ` min="${attr(field.min)}"` : ""}${field.max !== undefined && field.max !== null ? ` max="${attr(field.max)}"` : ""}` : "";
  return configControlFieldHtml(
    field,
    id,
    `<input id="${attr(id)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" type="${inputType}"${numberAttrs}${readOnlyInputAttrs(field)} value="${attr(value)}">`,
  );
}

function hiddenConfigFieldHtml(detail, field) {
  const value = displayConfigValue(detail.values[field.path]);
  return `<input id="${attr(configHiddenDomId(field.path))}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" type="hidden" value="${attr(value)}">`;
}

function configHiddenDomId(path) {
  return `cfg-hidden-${String(path || "").replace(/[^a-zA-Z0-9_-]+/g, "-")}`;
}

function configFieldClass(field, extra = "") {
  return ["config-field-card", extra, field.advanced ? "advanced-field" : "", field.readOnly ? "readonly-field" : ""].filter(Boolean).join(" ");
}

function configFieldTitleHtml(field, id) {
  return `<div class="config-field-title"><label for="${attr(id)}">${configFieldLabelHtml(field)}</label>${configFieldHelpButtonHtml(field)}</div>`;
}

function configFieldMetaHtml(field, id, options = {}) {
  const title = id
    ? `<label for="${attr(id)}">${configFieldLabelHtml(field)}</label>`
    : `<span>${configFieldLabelHtml(field)}</span>`;
  const summary = configFieldSummary(field);
  return `<div class="config-field-meta">
    <div class="config-field-title">${title}${configFieldHelpButtonHtml(field)}</div>
    ${summary && options.summary !== false ? `<p class="config-field-summary">${esc(summary)}</p>` : ""}
  </div>`;
}

function configControlFieldHtml(field, id, controlHtml, extra = "") {
  return `<div class="${configFieldClass(field, extra)}" data-config-field-path="${attr(field.path)}">
    <div class="config-field-main">
      ${configFieldMetaHtml(field, id)}
      <div class="config-field-control">${controlHtml}</div>
    </div>
    ${configFieldDescriptionHtml(field)}
  </div>`;
}

function configToggleFieldHtml(field, id, checked) {
  return `<div class="${configFieldClass(field, "config-toggle-field")}" data-config-field-path="${attr(field.path)}">
    <div class="config-field-main">
      ${configFieldMetaHtml(field, id)}
      <label class="config-switch-control" for="${attr(id)}">
        <input id="${attr(id)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" type="checkbox"${checked ? " checked" : ""}${readOnlyDisabledAttrs(field)}>
        <span class="config-switch-track" aria-hidden="true"><span class="config-switch-thumb"></span></span>
      </label>
    </div>
    ${configFieldDescriptionHtml(field)}
  </div>`;
}

function configCompositeFieldHtml(field, titleId, toolbarHtml, bodyHtml, extra = "", attrsHtml = "") {
  return `<div class="${configFieldClass(field, `config-composite-field ${extra}`)}" data-config-field-path="${attr(field.path)}"${attrsHtml}>
    <div class="config-composite-head">
      ${configFieldMetaHtml(field, titleId)}
      ${toolbarHtml ? `<div class="config-composite-actions">${toolbarHtml}</div>` : ""}
    </div>
    ${configFieldDescriptionHtml(field)}
    <div class="config-composite-body">${bodyHtml}</div>
  </div>`;
}

function configFieldLabelHtml(field, options = {}) {
  const includeRestart = options.includeRestart !== false;
  return `${esc(field.label)}${includeRestart && field.restartRequired ? ` <span class="restart-mark" title="保存后需重启">⚠️</span>` : ""}${readOnlyBadgeHtml(field)}`;
}

function configFieldHelpButtonHtml(field) {
  const summary = configFieldSummary(field);
  const description = configFieldDescription(field);
  if (!summary && !description) return "";
  const target = configDescriptionDomId(field.path);
  return `<button type="button" class="config-field-help" data-action="toggle-config-description" data-target="${attr(target)}" aria-expanded="false" aria-controls="${attr(target)}" title="点击查看详细说明">?</button>`;
}

function configFieldSummary(field) {
  const summary = String(field.summary || "").trim();
  if (summary) return summary;
  const description = configFieldDescription(field);
  if (!description) return "";
  return description.split(/[。！？\n]/).map(item => item.trim()).find(Boolean) || description;
}

function configFieldDescription(field) {
  return String(field.description || "").trim();
}

function configDescriptionDomId(path) {
  return `config-desc-${String(path || "").replace(/[^a-zA-Z0-9_-]+/g, "-")}`;
}

function readOnlyBadgeHtml(field) {
  return field.readOnly ? `<span class="readonly-mark" title="此配置项只能查看，保存时会保留当前值">只读</span>` : "";
}

function readOnlyInputAttrs(field) {
  return field.readOnly ? ` readonly aria-readonly="true"` : "";
}

function readOnlyDisabledAttrs(field) {
  return field.readOnly ? ` disabled aria-readonly="true"` : "";
}

function readOnlyTableNoteHtml(field) {
  return field.readOnly ? `<span class="inline-note readonly-table-note">此配置项为只读，只能查看当前内容。</span>` : "";
}

function secretConfigFieldHtml(detail, field) {
  const hasSecret = !!(detail.secretStates && detail.secretStates[field.path]);
  const value = displayConfigValue(detail.values[field.path]);
  const editorHidden = hasSecret && !value;
  const fetchText = field.readOnly ? "当前已保存密钥，点击后显示但不可编辑。" : "当前已保存密钥，点击后显示并可编辑。";
  const placeholder = field.readOnly ? "未设置" : (hasSecret ? "" : "请输入密钥");
  const id = `cfg-${field.path}`;
  return configControlFieldHtml(
    field,
    id,
    `<div class="config-secret-box" data-secret-field>
      <div class="secret-fetch-panel" data-secret-fetch-panel${editorHidden ? "" : " hidden"}>
        <button type="button" class="secret-fetch-button" data-action="load-config-secret" data-path="${attr(field.path)}">获取密钥</button>
        <span>${esc(fetchText)}</span>
      </div>
      <div class="secret-control" data-secret-editor${editorHidden ? " hidden" : ""}>
        <input id="${attr(id)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-secret-input="true" data-secret-has-value="${hasSecret ? "true" : "false"}" data-secret-loaded="${editorHidden ? "false" : "true"}" type="${editorHidden ? "hidden" : "password"}" value="${attr(value)}" data-secret-original="${attr(value)}" placeholder="${attr(placeholder)}" autocomplete="off"${readOnlyInputAttrs(field)}>
        <button type="button" class="secret-eye" data-action="toggle-config-secret" data-path="${attr(field.path)}" data-revealed="false" aria-pressed="false" title="查看真实值">${secretEyeIconHtml()}</button>
      </div>
    </div>`,
  );
}

function toggleConfigDescription(button) {
  const targetId = button.dataset.target;
  const target = targetId ? pageRoot().querySelector(`#${targetId}`) : null;
  if (!target) return;
  const expanded = target.hidden;
  target.hidden = !expanded;
  button.dataset.expanded = expanded ? "true" : "false";
  button.setAttribute("aria-expanded", expanded ? "true" : "false");
}

function showConfigSecretEditor(input) {
  const field = input.closest("[data-secret-field]");
  if (!field) return;
  const fetchPanel = field.querySelector("[data-secret-fetch-panel]");
  const editor = field.querySelector("[data-secret-editor]");
  if (fetchPanel) fetchPanel.hidden = true;
  if (editor) editor.hidden = false;
  if (input.type === "hidden") input.type = "password";
}

function secretEyeIconHtml() {
  return `<span class="secret-eye-shape" aria-hidden="true"></span><span class="secret-eye-slash" aria-hidden="true"></span>`;
}

function configFieldDescriptionHtml(field) {
  const description = configFieldDescription(field);
  if (!description) return "";
  return `<div id="${attr(configDescriptionDomId(field.path))}" class="config-field-description" hidden>${esc(description)}</div>`;
}

function messageTemplateFieldHtml(detail, field) {
  const value = displayConfigValue(detail.values[field.path]);
  const kind = messageTemplateKind(field);
  const inputId = messageTemplateDomId("cfg-template", field.path);
  const statsId = messageTemplateDomId("messageTemplateStats", field.path);
  const inlineId = messageTemplateDomId("messageTemplateInline", field.path);
  const toolbarHtml = field.readOnly ? "" : `<button type="button" class="compact message-template-edit-button" data-action="edit-message-template" data-path="${attr(field.path)}">编辑</button>`;
  const inlineText = messageTemplateInlineText(value, kind);
  return configCompositeFieldHtml(
    field,
    inputId,
    toolbarHtml,
    `<textarea id="${attr(inputId)}" data-config-path="${attr(field.path)}" data-config-type="${field.type}" hidden>${esc(value)}</textarea>
    <div class="message-template-compact">
      <span id="${attr(statsId)}" class="message-template-stats">${esc(messageTemplateStats(value, kind))}</span>
      <div id="${attr(inlineId)}" class="message-template-inline" title="${attr(inlineText)}">
        ${esc(inlineText)}
      </div>
    </div>`,
    "message-template-field",
  );
}

function messageTemplateInlineText(value, kind) {
  return messageTemplateEditor.messageTemplateInlineText(value, kind);
}

function openMessageTemplateModal(path) {
  const detail = state.currentConfigDetail;
  if (!detail) throw new Error("请选择配置文件");
  const field = (detail.schema.fields || []).find(item => item.path === path);
  if (!field) throw new Error("未找到模板配置项");
  const input = configInputFor(field);
  const value = input ? input.value : displayConfigValue(detail.values[field.path]);
  const kind = messageTemplateKind(field);
  openModal(`编辑${field.label}`, messageTemplateEditor.editorHtml({ value, kind }), async () => {
    const next = $("messageTemplateEditorInput").value;
    if (field.required && !next.trim()) throw new Error(`${field.label}不能为空`);
    setMessageTemplateValue(field, next);
    closeModal();
    notify(`${field.label}已更新，请保存配置`, false);
  }, { size: "wide" });
  messageTemplateEditor.bindEditor({ kind });
}

function setMessageTemplateValue(field, value) {
  const node = configInputFor(field);
  if (node) node.value = value;
  const kind = messageTemplateKind(field);
  const stats = $(messageTemplateDomId("messageTemplateStats", field.path));
  if (stats) stats.textContent = messageTemplateStats(value, kind);
  const inline = $(messageTemplateDomId("messageTemplateInline", field.path));
  if (inline) {
    const inlineText = messageTemplateInlineText(value, kind);
    inline.textContent = inlineText;
    inline.title = inlineText;
  }
  if (state.currentConfigDetail) {
    updateConfigRestartButton(state.currentConfigDetail);
    updateConfigDirtyState(state.currentConfigDetail);
  }
}

function messageTemplateDomId(prefix, path) {
  return `${prefix}-${String(path || "").replace(/[^a-zA-Z0-9_-]/g, "-")}`;
}

function messageTemplateKind(field) {
  const fromMetadata = field.metadata && field.metadata.templateKind;
  if (fromMetadata) return String(fromMetadata).toUpperCase();
  if (field.path === "templates.liveStarted") return "LIVE_STARTED";
  if (field.path === "templates.liveEnded") return "LIVE_ENDED";
  return "DYNAMIC";
}

function messageTemplateStats(template, kind) {
  return messageTemplateEditor.messageTemplateStats(template, kind);
}

function mediaDeliveryProfilesFieldHtml(detail, field) {
  const profiles = normalizeMediaDeliveryProfiles(detail.values[field.path]);
  const defaultProfileId = mediaDeliveryDefaultProfileId(detail, profiles);
  const profile = mediaDeliveryDefaultProfile(profiles, defaultProfileId);
  const mapping = mediaDeliveryPrimaryMapping(profile);
  const readOnly = !!field.readOnly;
  return configCompositeFieldHtml(
    field,
    "cfg-media-delivery-profiles",
    "",
    `<input id="cfg-media-delivery-profiles" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-config-readonly="${readOnly ? "true" : "false"}" type="hidden" value="${attr(JSON.stringify(profiles))}">
    ${readOnly ? readOnlyTableNoteHtml(field) : `<div class="media-delivery-panel">
        <div class="media-delivery-control">
          <label for="mediaDeliveryType">发送方式</label>
          <select id="mediaDeliveryType">${mediaDeliveryTypeOptionsHtml(profile.type)}</select>
        </div>
        <div class="media-delivery-hint">
          <span id="mediaDeliveryTypeHint" class="inline-note">${esc(mediaDeliveryTypeDescription(profile.type))}</span>
        </div>
        <div class="media-delivery-control" data-media-delivery-local>
          <label for="mediaDeliveryLocalBotRoot">项目文件路径前缀（项目根目录）</label>
          <input id="mediaDeliveryLocalBotRoot" value="${attr(mapping.botRoot)}" placeholder="留空表示直接使用项目生成的本地路径">
        </div>
        <div class="media-delivery-control" data-media-delivery-local>
          <label for="mediaDeliveryLocalClientRoot">OneBot 可访问路径前缀</label>
          <input id="mediaDeliveryLocalClientRoot" value="${attr(mapping.clientRoot)}" placeholder="同机同路径时可以留空">
        </div>
        <div class="media-delivery-control full" data-media-delivery-signed>
          <label for="mediaDeliveryPublicBaseUrl">外部访问地址</label>
          <input id="mediaDeliveryPublicBaseUrl" value="${attr(profile.signedUrl.publicBaseUrl)}" placeholder="例如 http://192.168.1.10:2233">
        </div>
        <div class="media-delivery-control" data-media-delivery-base64>
          <label for="mediaDeliveryBase64MaxMegabytes">Base64 兜底上限（MB）</label>
          <input id="mediaDeliveryBase64MaxMegabytes" type="number" step="any" min="0" value="${attr(profile.base64Fallback.maxMegabytes)}">
        </div>
      </div>`}`,
    "media-delivery-field",
    " data-media-delivery-field",
  );
}

function wireMediaDeliveryField(detail) {
  const root = pageRoot().querySelector("[data-media-delivery-field]");
  if (!root) return;
  const defaultNode = configInputFor({ path: "mediaDelivery.defaultProfileId" });
  const profiles = mediaDeliveryProfiles();
  if (defaultNode && !profiles.some(profile => profile.id === defaultNode.value.trim())) {
    defaultNode.value = profiles[0]?.id || "auto";
  }
  refreshMediaDeliveryUi();
  [
    "mediaDeliveryType",
    "mediaDeliveryLocalBotRoot",
    "mediaDeliveryLocalClientRoot",
    "mediaDeliveryPublicBaseUrl",
    "mediaDeliveryBase64MaxMegabytes",
  ].forEach(id => {
    const node = $(id);
    if (!node) return;
    const refresh = () => updateMediaDeliveryProfilesFromControls(detail);
    node.addEventListener("input", refresh);
    node.addEventListener("change", refresh);
  });
}

function mediaDeliveryProfiles() {
  const node = $("cfg-media-delivery-profiles");
  if (!node || !node.value.trim()) return [normalizeMediaDeliveryProfile({})];
  try {
    return normalizeMediaDeliveryProfiles(JSON.parse(node.value));
  } catch (_) {
    return [normalizeMediaDeliveryProfile({})];
  }
}

function updateMediaDeliveryProfilesFromControls(detail) {
  const node = $("cfg-media-delivery-profiles");
  if (!node || !$("mediaDeliveryType")) return;
  const profiles = mediaDeliveryProfiles();
  const defaultNode = configInputFor({ path: "mediaDelivery.defaultProfileId" });
  const defaultId = mediaDeliveryDefaultProfileIdFromDom(profiles);
  const index = profiles.findIndex(profile => profile.id === defaultId);
  const profileIndex = index >= 0 ? index : 0;
  profiles[profileIndex] = mediaDeliveryProfileFromControls(profiles[profileIndex] || normalizeMediaDeliveryProfile({}));
  node.value = JSON.stringify(profiles);
  if (defaultNode && !defaultNode.value.trim()) defaultNode.value = profiles[profileIndex].id;
  refreshMediaDeliveryUi();
  updateConfigRestartButton(detail);
  updateConfigDirtyState(detail);
}

function mediaDeliveryProfileFromControls(current) {
  const type = mediaDeliveryTypeValue();
  const existingMappings = Array.isArray(current.localFile.pathMappings) ? current.localFile.pathMappings : [];
  const botRoot = $("mediaDeliveryLocalBotRoot")?.value.trim() || "";
  const clientRoot = $("mediaDeliveryLocalClientRoot")?.value.trim() || "";
  const explicitMapping = botRoot || clientRoot
    ? [{ botRoot, clientRoot, enabled: true }]
    : [];
  const maxMegabytes = mediaDeliveryNumberValue(
    "mediaDeliveryBase64MaxMegabytes",
    current.base64Fallback.maxMegabytes,
  );
  return Object.assign({}, current, {
    type,
    localFile: Object.assign({}, current.localFile, {
      pathMappings: explicitMapping.concat(existingMappings.slice(1)),
    }),
    signedUrl: Object.assign({}, current.signedUrl, {
      publicBaseUrl: $("mediaDeliveryPublicBaseUrl")?.value.trim() || "",
    }),
    base64Fallback: Object.assign({}, current.base64Fallback, {
      maxMegabytes,
    }),
  });
}

function refreshMediaDeliveryUi() {
  const profiles = mediaDeliveryProfiles();
  const profile = mediaDeliveryDefaultProfile(profiles, mediaDeliveryDefaultProfileIdFromDom(profiles));
  const type = mediaDeliveryTypeValue(profile.type);
  const hint = $("mediaDeliveryTypeHint");
  if (hint) hint.textContent = mediaDeliveryTypeDescription(type);
  pageRoot().querySelectorAll("[data-media-delivery-local]").forEach(item => {
    item.hidden = type !== "LOCAL_FILE";
  });
  pageRoot().querySelectorAll("[data-media-delivery-signed]").forEach(item => {
    item.hidden = type !== "SIGNED_URL";
  });
  pageRoot().querySelectorAll("[data-media-delivery-base64]").forEach(item => {
    item.hidden = !(type === "AUTO" || type === "BASE64");
  });
}

function normalizeMediaDeliveryProfiles(value) {
  const rows = Array.isArray(value) ? value : [];
  const profiles = rows.map(normalizeMediaDeliveryProfile);
  return profiles.length ? profiles : [normalizeMediaDeliveryProfile({})];
}

function normalizeMediaDeliveryProfile(profile) {
  const source = profile || {};
  const type = mediaDeliveryKnownType(source.type || "AUTO");
  const localFile = source.localFile || {};
  const signedUrl = source.signedUrl || {};
  const base64Fallback = source.base64Fallback || {};
  const auto = source.auto || {};
  return Object.assign({}, source, {
    id: String(source.id || "auto").trim() || "auto",
    name: String(source.name || mediaDeliveryTypeLabel(type)).trim() || mediaDeliveryTypeLabel(type),
    type,
    localFile: Object.assign({}, localFile, {
      pathMappings: normalizeMediaDeliveryPathMappings(localFile.pathMappings || source.pathMappings),
    }),
    signedUrl: Object.assign({}, signedUrl, {
      publicBaseUrl: String(signedUrl.publicBaseUrl || source.publicBaseUrl || "").trim(),
      ttlSeconds: mediaDeliveryFiniteNumber(signedUrl.ttlSeconds ?? signedUrl.urlTtlSeconds ?? source.urlTtlSeconds, 1800),
      signingSecret: String(signedUrl.signingSecret || source.signingSecret || ""),
    }),
    base64Fallback: Object.assign({}, base64Fallback, {
      maxMegabytes: mediaDeliveryFiniteNumber(base64Fallback.maxMegabytes, 8.0),
    }),
    auto: Object.assign({}, auto, {
      probeCacheMinutes: mediaDeliveryFiniteNumber(auto.probeCacheMinutes, 30),
      failedProbeCacheMinutes: mediaDeliveryFiniteNumber(auto.failedProbeCacheMinutes, 5),
    }),
  });
}

function normalizeMediaDeliveryPathMappings(value) {
  const rows = Array.isArray(value) ? value : [];
  return rows.map(mapping => ({
    botRoot: String(mapping?.botRoot || "").trim(),
    clientRoot: String(mapping?.clientRoot || "").trim(),
    enabled: mapping?.enabled !== false,
  }));
}

function mediaDeliveryPrimaryMapping(profile) {
  const mappings = normalizeMediaDeliveryPathMappings(profile.localFile?.pathMappings);
  return mappings.find(mapping => mapping.enabled) || mappings[0] || { botRoot: "", clientRoot: "", enabled: true };
}

function mediaDeliveryDefaultProfileId(detail, profiles) {
  const raw = detail.values["mediaDelivery.defaultProfileId"];
  const id = String(raw || "").trim();
  if (profiles.some(profile => profile.id === id)) return id;
  return profiles[0]?.id || "auto";
}

function mediaDeliveryDefaultProfileIdFromDom(profiles) {
  const node = configInputFor({ path: "mediaDelivery.defaultProfileId" });
  const id = String(node?.value || "").trim();
  if (profiles.some(profile => profile.id === id)) return id;
  return profiles[0]?.id || "auto";
}

function mediaDeliveryDefaultProfile(profiles, defaultProfileId) {
  return profiles.find(profile => profile.id === defaultProfileId) || profiles[0] || normalizeMediaDeliveryProfile({});
}

function mediaDeliveryTypeOptionsHtml(selected) {
  const current = mediaDeliveryKnownType(selected);
  return ["AUTO", "LOCAL_FILE", "SIGNED_URL", "BASE64"]
    .map(type => `<option value="${type}"${type === current ? " selected" : ""}>${esc(mediaDeliveryTypeLabel(type))}</option>`)
    .join("");
}

function mediaDeliveryTypeValue(fallback = "AUTO") {
  return mediaDeliveryKnownType($("mediaDeliveryType")?.value || fallback);
}

function mediaDeliveryKnownType(value) {
  const type = String(value || "AUTO").trim().toUpperCase();
  return ["AUTO", "LOCAL_FILE", "SIGNED_URL", "BASE64"].includes(type) ? type : "AUTO";
}

function mediaDeliveryTypeLabel(type) {
  switch (mediaDeliveryKnownType(type)) {
    case "LOCAL_FILE": return "本地路径";
    case "SIGNED_URL": return "外部访问地址";
    case "BASE64": return "Base64（不推荐）";
    default: return "自动";
  }
}

function mediaDeliveryTypeDescription(type) {
  switch (mediaDeliveryKnownType(type)) {
    case "LOCAL_FILE": return "直接把本地文件路径交给Napcat、LLonebot等项目，适合同一台机器部署或文件系统互通。使用Docker可配置路径映射。";
    case "SIGNED_URL": return "这个指的是Napcat、LLonebot等项目访问 本项目 的地址，一般就是你目前访问的这个地址，适合 OneBot 客户端不在同一台机器但能访问本项目地址。";
    case "BASE64": return "把图片编码进消息里，只建议作为最后兜底；大量图片会增加网络、内存和日志压力。";
    default: return "自动按本地路径、签名链接、Base64 的顺序尝试。推荐使用本地路径或外部访问地址。";
  }
}

function mediaDeliveryFiniteNumber(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function mediaDeliveryNumberValue(id, fallback) {
  const node = $(id);
  return mediaDeliveryFiniteNumber(node?.value, fallback);
}

function oneBotConnectionsFieldHtml(detail, field) {
  const connections = normalizeOneBotConnections(detail.values[field.path]);
  const readOnly = !!field.readOnly;
  const toolbarHtml = readOnly ? "" : `
      <button type="button" data-action="add-onebot-connection">添加连接</button>
      <button type="button" class="secondary" data-action="clear-onebot-connections">清空</button>
    `;
  return configCompositeFieldHtml(
    field,
    "cfg-onebot-connections",
    toolbarHtml,
    `<input id="cfg-onebot-connections" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-config-readonly="${readOnly ? "true" : "false"}" type="hidden" value="${attr(JSON.stringify(connections))}">
    ${readOnly ? readOnlyTableNoteHtml(field) : ""}
    <div id="oneBotConnectionTable">${renderOneBotConnectionTable(connections, readOnly)}</div>
    `,
    "onebot-connection-field",
  );
}

function messageRoutingPlatformPoliciesFieldHtml(detail, field) {
  const policies = normalizeMessageRoutingPlatformPolicies(detail.values[field.path]);
  const readOnly = !!field.readOnly;
  const toolbarHtml = readOnly ? "" : `
      <button type="button" data-action="add-message-routing-policy">添加策略</button>
      <button type="button" class="secondary" data-action="clear-message-routing-policies">清空</button>
    `;
  return configCompositeFieldHtml(
    field,
    "cfg-message-routing-platform-policies",
    toolbarHtml,
    `<input id="cfg-message-routing-platform-policies" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-config-readonly="${readOnly ? "true" : "false"}" type="hidden" value="${attr(JSON.stringify(policies))}">
    ${readOnly ? readOnlyTableNoteHtml(field) : ""}
    <div id="messageRoutingPlatformPolicyTable">${renderMessageRoutingPlatformPolicyTable(policies, readOnly)}</div>
    `,
    "message-routing-policy-field",
  );
}

function messageRoutingPlatformPolicies() {
  const node = $("cfg-message-routing-platform-policies");
  if (!node || !node.value.trim()) return [];
  try {
    return normalizeMessageRoutingPlatformPolicies(JSON.parse(node.value));
  } catch (_) {
    return [];
  }
}

function setMessageRoutingPlatformPolicies(policies) {
  const normalized = dedupeMessageRoutingPlatformPolicies(normalizeMessageRoutingPlatformPolicies(policies));
  const node = $("cfg-message-routing-platform-policies");
  if (node) node.value = JSON.stringify(normalized);
  const table = $("messageRoutingPlatformPolicyTable");
  if (table) table.innerHTML = renderMessageRoutingPlatformPolicyTable(normalized, node?.dataset.configReadonly === "true");
  if (state.currentConfigDetail) {
    updateConfigRestartButton(state.currentConfigDetail);
    updateConfigDirtyState(state.currentConfigDetail);
  }
}

function normalizeMessageRoutingPlatformPolicies(value) {
  const rows = Array.isArray(value) ? value : [];
  return rows.map(normalizeMessageRoutingPlatformPolicy);
}

function normalizeMessageRoutingPlatformPolicy(policy) {
  const source = policy || {};
  const routePolicy = source.policy || {};
  const strategy = String(routePolicy.strategy || "ROUND_ROBIN").trim().toUpperCase();
  return {
    platformId: String(source.platformId || "").trim(),
    policy: {
      strategy: strategy === "PRIMARY_BACKUP" ? "PRIMARY_BACKUP" : "ROUND_ROBIN",
      primaryAccountId: String(routePolicy.primaryAccountId || "").trim(),
      failureCooldownSeconds: Number(routePolicy.failureCooldownSeconds || 60) || 60,
    }
  };
}

function dedupeMessageRoutingPlatformPolicies(policies) {
  const seen = new Map();
  normalizeMessageRoutingPlatformPolicies(policies).forEach(policy => {
    const key = policy.platformId.toLowerCase();
    if (!key) return;
    seen.set(key, policy);
  });
  return Array.from(seen.values());
}

function renderMessageRoutingPlatformPolicyTable(policies, readOnly = false) {
  const rows = normalizeMessageRoutingPlatformPolicies(policies).map((policy, index) => Object.assign({ _index: index }, policy));
  const columns = [
    { title: "平台", render: row => `<span class="primary-line">${esc(row.platformId || "-")}</span>` },
    { title: "策略", render: row => pill(row.policy.strategy) },
    { title: "主账号", render: row => cell(row.policy.primaryAccountId || "留空", row.policy.primaryAccountId ? "优先从该账号发送" : "使用平台第一个可用账号") },
    { title: "冷却", render: row => `<span class="primary-line">${esc(`${Number(row.policy.failureCooldownSeconds || 0)} 秒`)}</span>` },
  ];
  if (!readOnly) {
    columns.push({ title: "操作", render: row => `<div class="row-actions">
      <button type="button" class="secondary compact" data-action="edit-message-routing-policy" data-index="${row._index}">编辑</button>
      <button type="button" class="danger compact" data-action="delete-message-routing-policy" data-index="${row._index}">删除</button>
    </div>` });
  }
  return renderTable(rows, columns);
}

async function openMessageRoutingPlatformPolicyModal(index = null) {
  const editing = Number.isInteger(index) && index >= 0;
  const policies = messageRoutingPlatformPolicies();
  const targetPlatforms = await loadPermissionTargetPlatforms();
  const defaultPolicy = currentMessageRoutingDefaultPolicy();
  const current = editing
    ? normalizeMessageRoutingPlatformPolicy(policies[index] || {})
    : {
      platformId: targetPlatforms[0]?.platformId || "",
      policy: defaultPolicy.policy,
    };
  const platformOptions = targetPlatforms
    .map(platform => ({
      platformId: platform.platformId,
      pluginName: platform.pluginName,
    }))
    .concat([{ platformId: "__manual__", pluginName: "手动输入" }]);
  const selectedPlatformId = platformOptions.some(platform => platform.platformId === current.platformId)
    ? current.platformId
    : "__manual__";
  openModal(editing ? "编辑平台路由策略" : "添加平台路由策略", `
    <div class="form-grid message-routing-policy-modal">
      <div class="field">
        <label>目标平台</label>
        <select id="routePlatform">${platformOptions.map(platform => `<option value="${attr(platform.platformId)}">${esc(commandPermissionPlatformOptionText(platform))}</option>`).join("")}</select>
      </div>
      <div class="field" id="routePlatformManualWrap" hidden>
        <label>平台 ID</label>
        <input id="routePlatformManual" value="${attr(current.platformId)}" placeholder="例如：qq">
      </div>
      <div class="field">
        <label>路由策略</label>
        <select id="routeStrategy">
          <option value="ROUND_ROBIN">轮询</option>
          <option value="PRIMARY_BACKUP">主备</option>
        </select>
      </div>
      <div class="field">
        <label>主 Bot 账号</label>
        <input id="routePrimaryAccountId" value="${attr(current.policy.primaryAccountId)}" placeholder="留空则使用平台第一个可用账号">
      </div>
      <div class="field">
        <label>失败冷却（秒）</label>
        <input id="routeCooldown" type="number" step="1" min="1" inputmode="numeric" value="${attr(current.policy.failureCooldownSeconds || 60)}">
      </div>
      <div class="field full">
        <span class="inline-note">留空主账号时，轮询和主备都会从平台当前第一个可用账号开始。</span>
      </div>
    </div>
  `, async () => {
    const platformId = routingPlatformValue();
    if (!platformId) throw new Error("请填写平台 ID");
    const next = normalizeMessageRoutingPlatformPolicy({
      platformId,
      policy: {
        strategy: $("routeStrategy").value,
        primaryAccountId: $("routePrimaryAccountId").value,
        failureCooldownSeconds: $("routeCooldown").value,
      }
    });
    if (editing) {
      policies[index] = next;
    } else {
      policies.push(next);
    }
    setMessageRoutingPlatformPolicies(dedupeMessageRoutingPlatformPolicies(policies));
    closeModal();
    notify(editing ? "平台路由策略已更新，请保存配置" : "平台路由策略已添加，请保存配置", false);
  }, { size: "medium" });

  const refreshPlatformField = () => {
    const rawPlatformId = $("routePlatform").value;
    $("routePlatformManualWrap").hidden = rawPlatformId !== "__manual__";
    if (rawPlatformId === "__manual__") {
      $("routePlatformManual").value = current.platformId;
    }
  };
  $("routePlatform").value = selectedPlatformId;
  $("routeStrategy").value = current.policy.strategy;
  $("routePlatform").onchange = refreshPlatformField;
  refreshPlatformField();
}

function routingPlatformValue() {
  return $("routePlatform").value === "__manual__" ? $("routePlatformManual").value.trim() : $("routePlatform").value;
}

function currentMessageRoutingDefaultPolicy() {
  const detail = state.currentConfigDetail;
  const values = (detail && detail.values) || {};
  return normalizeMessageRoutingPlatformPolicy({
    platformId: "",
    policy: {
      strategy: values["messageRouting.defaultPolicy.strategy"],
      primaryAccountId: values["messageRouting.defaultPolicy.primaryAccountId"],
      failureCooldownSeconds: values["messageRouting.defaultPolicy.failureCooldownSeconds"],
    }
  });
}

function oneBotConnections() {
  const node = $("cfg-onebot-connections");
  if (!node || !node.value.trim()) return [];
  try {
    return normalizeOneBotConnections(JSON.parse(node.value));
  } catch (_) {
    return [];
  }
}

function setOneBotConnections(connections) {
  const normalized = normalizeOneBotConnections(connections);
  const node = $("cfg-onebot-connections");
  if (node) node.value = JSON.stringify(normalized);
  const table = $("oneBotConnectionTable");
  if (table) table.innerHTML = renderOneBotConnectionTable(normalized, node?.dataset.configReadonly === "true");
  if (state.currentConfigDetail) {
    updateConfigRestartButton(state.currentConfigDetail);
    updateConfigDirtyState(state.currentConfigDetail);
  }
}

function normalizeOneBotConnections(value) {
  const rows = Array.isArray(value) ? value : [];
  return rows.map(normalizeOneBotConnection);
}

function normalizeOneBotConnection(connection) {
  return {
    name: String(connection && connection.name || "").trim(),
    url: String(connection && connection.url || "").trim(),
    accessToken: String(connection && connection.accessToken || "").trim(),
    enabled: connection && connection.enabled === false ? false : true
  };
}

function renderOneBotConnectionTable(connections, readOnly = false) {
  const rows = normalizeOneBotConnections(connections).map((connection, index) => Object.assign({ _index: index }, connection));
  const columns = [
    { title: "标记名称", render: connection => cell(connection.name || "未标记", "仅用于后台识别") },
    { title: "地址", render: connection => `<span class="primary-line">${esc(connection.url || "-")}</span>` },
    { title: "Token", render: connection => `<span class="sub-line">${connection.accessToken ? "已填写" : "未填写"}</span>` },
    { title: "状态", render: connection => pill(connection.enabled ? "启用" : "停用") },
  ];
  if (!readOnly) {
    columns.push({ title: "操作", render: connection => `<div class="row-actions">
      <button type="button" class="secondary compact" data-action="edit-onebot-connection" data-index="${connection._index}">编辑</button>
      <button type="button" class="danger compact" data-action="delete-onebot-connection" data-index="${connection._index}">删除</button>
    </div>` });
  }
  return renderTable(rows, columns);
}

function openOneBotConnectionModal(index = null) {
  const editing = Number.isInteger(index) && index >= 0;
  const connections = oneBotConnections();
  const current = editing
    ? normalizeOneBotConnection(connections[index] || {})
    : normalizeOneBotConnection({ url: "ws://127.0.0.1:6700" });
  openModal(editing ? "编辑 OneBot 连接" : "添加 OneBot 连接", `
    <div class="form-grid onebot-connection-modal">
      <div class="field">
        <label>标记名称</label>
        <input id="onebotConnectionName" value="${attr(current.name)}" placeholder="例如：主 QQ、备用连接">
        <span class="inline-note">只用于在后台区分连接，不影响账号识别和发送路由。</span>
      </div>
      <div class="field">
        <label>启用状态</label>
        <label class="check"><input id="onebotConnectionEnabled" type="checkbox"${current.enabled ? " checked" : ""}>启用这个连接</label>
      </div>
      <div class="field full">
        <label>WebSocket 地址</label>
        <input id="onebotConnectionUrl" value="${attr(current.url)}" placeholder="ws://127.0.0.1:6700">
      </div>
      <div class="field full">
        <label>Token</label>
        <div class="secret-control">
          <input id="onebotConnectionToken" type="password" value="${attr(current.accessToken)}" autocomplete="off" placeholder="留空表示不携带 Token">
          <button type="button" class="secret-eye" data-action="toggle-onebot-connection-token" data-input-id="onebotConnectionToken" data-revealed="false" aria-pressed="false" title="查看真实值">${secretEyeIconHtml()}</button>
        </div>
      </div>
    </div>
  `, async () => {
    const next = normalizeOneBotConnection({
      name: $("onebotConnectionName").value,
      url: $("onebotConnectionUrl").value,
      accessToken: $("onebotConnectionToken").value,
      enabled: $("onebotConnectionEnabled").checked
    });
    if (!next.url) throw new Error("请填写 WebSocket 地址");
    if (editing) {
      connections[index] = next;
    } else {
      connections.push(next);
    }
    setOneBotConnections(connections);
    closeModal();
    notify(editing ? "OneBot 连接已更新，请保存配置" : "OneBot 连接已添加，请保存配置", false);
  }, { size: "medium" });
}

function toggleOneBotConnectionToken(button) {
  const input = $(button.dataset.inputId);
  if (!input) return;
  if (button.dataset.revealed === "true") {
    input.type = "password";
    button.dataset.revealed = "false";
    button.setAttribute("aria-pressed", "false");
    button.title = "查看真实值";
  } else {
    input.type = "text";
    button.dataset.revealed = "true";
    button.setAttribute("aria-pressed", "true");
    button.title = "隐藏真实值";
  }
}

function notificationTargetsFieldHtml(detail, field) {
  const targets = normalizeNotificationTargets(detail.values[field.path]);
  const readOnly = !!field.readOnly;
  const toolbarHtml = readOnly ? "" : `
      <button type="button" data-action="add-notification-target">添加目标</button>
      <button type="button" class="secondary" data-action="clear-notification-targets">清空</button>
    `;
  return configCompositeFieldHtml(
    field,
    "cfg-notification-targets",
    toolbarHtml,
    `<input id="cfg-notification-targets" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-config-readonly="${readOnly ? "true" : "false"}" type="hidden" value="${attr(JSON.stringify(targets))}">
    ${readOnly ? readOnlyTableNoteHtml(field) : ""}
    <div id="notificationTargetTable">${renderNotificationTargetTable(targets, readOnly)}</div>
    `,
    "notification-target-field",
  );
}

function notificationTargets() {
  const node = $("cfg-notification-targets");
  if (!node || !node.value.trim()) return [];
  try {
    return normalizeNotificationTargets(JSON.parse(node.value));
  } catch (_) {
    return [];
  }
}

function setNotificationTargets(targets) {
  const normalized = dedupeNotificationTargets(normalizeNotificationTargets(targets));
  const node = $("cfg-notification-targets");
  if (node) node.value = JSON.stringify(normalized);
  const table = $("notificationTargetTable");
  if (table) table.innerHTML = renderNotificationTargetTable(normalized, node?.dataset.configReadonly === "true");
  if (state.currentConfigDetail) {
    updateConfigRestartButton(state.currentConfigDetail);
    updateConfigDirtyState(state.currentConfigDetail);
  }
}

function normalizeNotificationTargets(value) {
  const rows = Array.isArray(value) ? value : [];
  return rows.map(normalizeNotificationTarget).filter(Boolean);
}

function normalizeNotificationTarget(target) {
  const source = target || {};
  const targetKind = String(source.targetKind || "USER").trim().toUpperCase();
  const normalized = {
    platformId: String(source.platformId || "").trim(),
    targetKind: ["GROUP", "USER", "CHANNEL", "OTHER"].includes(targetKind) ? targetKind : "USER",
    externalId: String(source.externalId || "").trim(),
    name: String(source.name || "").trim(),
    enabled: source.enabled === false ? false : true,
  };
  const scopeId = blankToNull(source.scopeId);
  const threadId = blankToNull(source.threadId);
  const accountId = blankToNull(source.accountId);
  if (scopeId) normalized.scopeId = scopeId;
  if (threadId) normalized.threadId = threadId;
  if (accountId) normalized.accountId = accountId;
  return normalized.platformId && normalized.externalId ? normalized : null;
}

function dedupeNotificationTargets(targets) {
  const seen = new Map();
  normalizeNotificationTargets(targets).forEach(target => {
    const key = [target.platformId.toLowerCase(), target.targetKind, target.externalId, target.scopeId || "", target.threadId || ""].join("\u001F");
    seen.set(key, target);
  });
  return Array.from(seen.values());
}

function renderNotificationTargetTable(targets, readOnly = false) {
  const rows = normalizeNotificationTargets(targets).map((target, index) => Object.assign({ _index: index }, target));
  const columns = [
    { title: "名称", render: row => cell(row.name || row.externalId, row.externalId) },
    { title: "目标", render: row => `<div class="notification-target-cell">
      <span class="notification-target-line">${platformTag(row.platformId, row.platformId)}<span class="pill info">${esc(label(row.targetKind))}</span></span>
      <span class="sub-line">${esc(notificationTargetScopeText(row))}</span>
    </div>` },
    { title: "状态", render: row => `<span class="pill ${row.enabled ? "ok" : "warn"}">${row.enabled ? "启用" : "停用"}</span>` },
    { title: "优先账号", render: row => `<span class="primary-line">${esc(row.accountId || "全局路由")}</span>` },
  ];
  if (!readOnly) {
    columns.push({ title: "操作", render: row => `<div class="row-actions">
      <button type="button" class="secondary compact soft-edit-button" data-action="edit-notification-target" data-index="${row._index}">编辑</button>
      <button type="button" class="danger compact" data-action="delete-notification-target" data-index="${row._index}">删除</button>
    </div>` });
  }
  return renderTable(rows, columns);
}

function notificationTargetScopeText(target) {
  const items = [
    ["作用域", target.scopeId],
    ["线程", target.threadId],
  ].filter(([, value]) => value);
  return items.length ? items.map(([name, value]) => `${name}:${value}`).join(" / ") : "默认上下文";
}

async function openNotificationTargetModal(index = null) {
  const editing = Number.isInteger(index) && index >= 0;
  const targets = notificationTargets();
  const targetPlatforms = await loadPermissionTargetPlatforms();
  const fallbackTarget = defaultNotificationTarget(targetPlatforms);
  const current = editing
    ? Object.assign(fallbackTarget, normalizeNotificationTarget(targets[index] || {}) || {})
    : fallbackTarget;
  const platformOptions = targetPlatforms
    .map(platform => ({
      platformId: platform.platformId,
      pluginName: platform.pluginName,
      supportedTypes: platform.supportedTypes,
      transportCount: platform.transportCount,
    }))
    .concat([{ platformId: "__manual__", pluginName: "手动输入", supportedTypes: ["GROUP", "USER", "CHANNEL", "OTHER"] }]);
  const selectedPlatformId = platformOptions.some(platform => platform.platformId === current.platformId)
    ? current.platformId
    : "__manual__";
  let targetCandidates = [];
  let manualTargetMode = selectedPlatformId === "__manual__";
  openModal(editing ? "编辑通知目标" : "添加通知目标", `
    <div class="form-grid notification-target-modal">
      <div class="field">
        <label>目标平台</label>
        <select id="notifyPlatform">${platformOptions.map(platform => `<option value="${attr(platform.platformId)}">${esc(commandPermissionPlatformOptionText(platform))}</option>`).join("")}</select>
      </div>
      <div class="field" id="notifyPlatformManualWrap" hidden>
        <label>平台 ID</label>
        <input id="notifyPlatformManual" value="${attr(current.platformId)}" placeholder="例如 qq、discord">
      </div>
      <div class="field">
        <label>目标类型</label>
        <select id="notifyTargetKind"></select>
      </div>
      <div class="field">
        <label>状态</label>
        <label class="check"><input id="notifyTargetEnabled" type="checkbox"${current.enabled ? " checked" : ""}>启用通知目标</label>
      </div>
      <div class="field full" id="notifyTargetCandidateWrap">
        <div class="field-head">
          <div class="field-title-line">
            <label>可用目标</label>
            <span id="notifyTargetStatus" class="field-inline-status"></span>
          </div>
          <div class="row-actions">
            <button type="button" id="notifyRefreshTargets" class="secondary compact choice-tool-button choice-refresh-button">刷新</button>
            <button type="button" id="notifyManualTargetToggle" class="secondary compact choice-tool-button">手动输入</button>
          </div>
        </div>
        <div id="notifyTargetCandidateList" class="target-choice-list"></div>
      </div>
      <div class="field full" id="notifyManualTargetWrap" hidden>
        <div class="notification-target-manual-panel">
          <span id="notifyManualHint" class="inline-note">找不到目标时可以手动填写目标信息；不指定优先账号时会使用全局路由。</span>
          <div class="form-grid notification-target-manual-grid">
            <div class="field">
              <label>目标 ID</label>
              <input id="notifyTargetId" value="${attr(current.externalId)}">
            </div>
            <div class="field">
              <label>显示名称</label>
              <input id="notifyTargetName" value="${attr(current.name)}" placeholder="留空时使用目标名称或 ID">
            </div>
            <div class="field"><label>作用域 ID</label><input id="notifyScopeId" value="${attr(current.scopeId || "")}"></div>
            <div class="field"><label>线程 ID</label><input id="notifyThreadId" value="${attr(current.threadId || "")}"></div>
            <div class="field full"><label>优先账号</label><input id="notifyAccountId" value="${attr(current.accountId || "")}" placeholder="不填则使用全局路由"></div>
          </div>
        </div>
      </div>
    </div>
  `, async () => {
    const next = collectNotificationTarget(targetCandidates);
    if (editing) {
      targets[index] = next;
    } else {
      targets.push(next);
    }
    setNotificationTargets(targets);
    closeModal();
    notify(editing ? "通知目标已更新，请保存配置" : "通知目标已添加，请保存配置", false);
  }, { size: "medium" });

  const setManualTargetMode = (enabled, reason = "") => {
    manualTargetMode = !!enabled;
    const wrap = $("notifyManualTargetWrap");
    if (wrap) wrap.hidden = !manualTargetMode;
    const hint = $("notifyManualHint");
    if (hint && reason) hint.textContent = reason;
    const toggle = $("notifyManualTargetToggle");
    if (toggle) {
      const manualPlatform = $("notifyPlatform") && $("notifyPlatform").value === "__manual__";
      toggle.hidden = manualPlatform || (!targetCandidates.length && manualTargetMode);
      toggle.disabled = !targetCandidates.length && !manualTargetMode;
      toggle.textContent = manualTargetMode ? "使用可用目标" : "手动输入";
    }
  };

  const refreshKinds = async () => {
    const rawPlatformId = $("notifyPlatform").value;
    $("notifyPlatformManualWrap").hidden = rawPlatformId !== "__manual__";
    const platform = platformOptions.find(item => item.platformId === rawPlatformId);
    const kinds = (platform && platform.supportedTypes && platform.supportedTypes.length)
      ? platform.supportedTypes
      : ["GROUP", "USER", "CHANNEL", "OTHER"];
    $("notifyTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
    if (kinds.includes(current.targetKind)) $("notifyTargetKind").value = current.targetKind;
    if (rawPlatformId !== "__manual__") setManualTargetMode(false);
    await refreshTargets(false);
  };
  const refreshTargets = async (force = false) => {
    const rawPlatformId = $("notifyPlatform").value;
    const platformId = notificationPlatformValue();
    const kind = $("notifyTargetKind").value;
    targetCandidates = [];
    setNotificationTargetStatus("");
    if (!platformId || rawPlatformId === "__manual__") {
      $("notifyTargetCandidateWrap").hidden = true;
      setManualTargetMode(true, platformId ? "手动输入目标平台时，请填写目标 ID；不指定优先账号时会使用全局路由。" : "请先填写目标平台。");
      return;
    }
    $("notifyTargetCandidateWrap").hidden = false;
    setNotificationTargetLoading(force ? "正在刷新可用目标..." : "正在获取可用目标...");
    $("notifyRefreshTargets").disabled = true;
    try {
      const result = await loadSubscriberTargetCandidates(platformId, kind, force);
      targetCandidates = result.items || [];
      if (targetCandidates.length) {
        const candidateList = $("notifyTargetCandidateList");
        candidateList.innerHTML = targetCandidates.map((target, targetIndex) => {
          const viewTarget = target.externalId === current.externalId
            ? Object.assign({}, target, { accountId: current.accountId || target.accountId })
            : target;
          return messageTargetChoiceHtml(viewTarget, targetIndex, {
            inputName: "notifyTargetCandidate",
            prefix: "notifyTarget",
            inputType: "radio",
            checked: target.externalId === current.externalId,
          });
        }).join("");
        bindTargetSourceToggles(candidateList);
        await hydrateMediaImages(candidateList);
        candidateList.querySelectorAll(`input[name="notifyTargetCandidate"]`).forEach(input => {
          input.onchange = () => applyNotificationCandidate(selectedNotificationTargetCandidate(targetCandidates));
        });
        const selected = selectedNotificationTargetCandidate(targetCandidates);
        if (selected) applyNotificationCandidate(selected, { keepName: !!current.name });
        setNotificationTargetStatus(`${targetCandidates.length} 个可用目标`);
        setManualTargetMode(editing && !!current.externalId && !selected, "当前目标没有出现在可用目标列表中，请手动确认目标 ID；不指定优先账号时会使用全局路由。");
      } else {
        $("notifyTargetCandidateList").innerHTML = `<div class="empty">暂无可用目标</div>`;
        setNotificationTargetStatus("可手动填写目标 ID");
        setManualTargetMode(true, "没有获取到可用目标，请手动填写目标 ID；不指定优先账号时会使用全局路由。");
      }
    } catch (error) {
      $("notifyTargetCandidateList").innerHTML = `<div class="empty">目标列表获取失败</div>`;
      setNotificationTargetStatus(error.message || "可用目标获取失败");
      setManualTargetMode(true, "目标列表获取失败，请手动填写目标 ID；不指定优先账号时会使用全局路由。");
    } finally {
      $("notifyRefreshTargets").disabled = false;
    }
  };

  $("notifyPlatform").value = selectedPlatformId;
  $("notifyPlatform").onchange = () => refreshKinds().catch(handleError);
  $("notifyPlatformManual").oninput = () => refreshTargets(false).catch(handleError);
  $("notifyTargetKind").onchange = () => refreshTargets(false).catch(handleError);
  $("notifyRefreshTargets").onclick = () => refreshTargets(true).catch(handleError);
  $("notifyManualTargetToggle").onclick = () => {
    if (!manualTargetMode) {
      const selected = selectedNotificationTargetCandidate(targetCandidates);
      if (selected) applyNotificationCandidate(selected);
    }
    setManualTargetMode(!manualTargetMode);
  };
  await refreshKinds();
}

function defaultNotificationTarget(targetPlatforms) {
  const firstPlatform = Array.isArray(targetPlatforms) ? targetPlatforms[0] : null;
  return {
    platformId: firstPlatform?.platformId || "",
    targetKind: firstPlatform?.supportedTypes?.[0] || "USER",
    externalId: "",
    name: "",
    enabled: true,
  };
}

function setNotificationTargetLoading(text) {
  $("notifyTargetStatus").textContent = "";
  $("notifyTargetCandidateList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
}

function setNotificationTargetStatus(text) {
  const node = $("notifyTargetStatus");
  if (node) node.textContent = text ? `· ${text}` : "";
}

function notificationPlatformValue() {
  return $("notifyPlatform").value === "__manual__" ? $("notifyPlatformManual").value.trim() : $("notifyPlatform").value;
}

function selectedNotificationTargetCandidate(candidates) {
  const input = document.querySelector(`input[name="notifyTargetCandidate"]:checked`);
  const index = input ? Number(input.dataset.index) : -1;
  return index >= 0 ? candidates[index] || null : null;
}

function notificationManualTargetMode() {
  const wrap = $("notifyManualTargetWrap");
  return !!wrap && !wrap.hidden;
}

function applyNotificationCandidate(target, options = {}) {
  if (!target) return;
  $("notifyTargetId").value = target.externalId || "";
  $("notifyScopeId").value = target.scopeId || "";
  $("notifyThreadId").value = target.threadId || "";
  if (!options.keepName) $("notifyTargetName").value = target.name || "";
}

function collectNotificationTarget(candidates) {
  const platformId = notificationPlatformValue();
  if (!platformId) throw new Error("请填写目标平台");
  const candidate = notificationManualTargetMode() ? null : selectedNotificationTargetCandidate(candidates);
  if (!candidate && !notificationManualTargetMode()) {
    throw new Error("请选择可用目标，或点击手动输入填写目标 ID");
  }
  const candidateIndex = candidate ? candidates.indexOf(candidate) : -1;
  const accountFromSource = candidateIndex >= 0 ? selectedTargetPriorityAccount("notifyTarget", candidateIndex) : "";
  const target = normalizeNotificationTarget({
    platformId,
    targetKind: $("notifyTargetKind").value,
    externalId: candidate ? candidate.externalId : $("notifyTargetId").value,
    scopeId: $("notifyScopeId").value,
    threadId: $("notifyThreadId").value,
    accountId: accountFromSource || $("notifyAccountId").value,
    name: $("notifyTargetName").value || (candidate && candidate.name) || "",
    enabled: $("notifyTargetEnabled").checked,
  });
  if (!target) throw new Error("请填写通知目标 ID");
  return target;
}

function blankToNull(value) {
  const text = String(value || "").trim();
  return text ? text : null;
}

function commandPermissionsFieldHtml(detail, field) {
  const rules = Array.isArray(detail.values[field.path]) ? detail.values[field.path] : [];
  const readOnly = !!field.readOnly;
  const toolbarHtml = readOnly ? "" : `
      <button type="button" data-action="add-command-permission">添加规则</button>
      <button type="button" class="secondary" data-action="clear-command-permissions">清空</button>
    `;
  return configCompositeFieldHtml(
    field,
    "cfg-command-permissions",
    toolbarHtml,
    `<input id="cfg-command-permissions" data-config-path="${attr(field.path)}" data-config-type="${field.type}" data-config-readonly="${readOnly ? "true" : "false"}" type="hidden" value="${attr(JSON.stringify(rules))}">
    ${readOnly ? readOnlyTableNoteHtml(field) : ""}
    <div id="commandPermissionTable">${renderCommandPermissionTable(rules, readOnly)}</div>
    `,
    "command-permission-field",
  );
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
  const normalized = normalizeCommandPermissionRules(rules);
  const node = $("cfg-command-permissions");
  if (node) node.value = JSON.stringify(normalized);
  const table = $("commandPermissionTable");
  if (table) table.innerHTML = renderCommandPermissionTable(normalized, node?.dataset.configReadonly === "true");
  if (state.currentConfigDetail) {
    updateConfigRestartButton(state.currentConfigDetail);
    updateConfigDirtyState(state.currentConfigDetail);
  }
}

function normalizeCommandPermissionRule(rule) {
  return {
    commandPath: rule.commandPath || "*",
    platformId: rule.platformId || "*",
    targetKind: rule.targetKind || null,
    targetId: rule.targetId || "*",
    scopeId: rule.scopeId || "*",
    threadId: rule.threadId || "*",
    botAccountId: rule.botAccountId || "*",
    senderId: rule.senderId || "*",
    role: rule.role || "MANAGER"
  };
}

function normalizeCommandPermissionRules(value) {
  const rows = Array.isArray(value) ? value : [];
  return rows.map(normalizeCommandPermissionRule);
}

function renderCommandPermissionTable(rules, readOnly = false) {
  const rows = normalizeCommandPermissionRules(rules).map((rule, index) => Object.assign({ _index: index }, rule));
  const columns = [
    { title: "命令", render: rule => `<span class="primary-line">${esc(wildcardText(rule.commandPath))}</span>` },
    { title: "平台", render: rule => `<span class="primary-line">${esc(wildcardText(rule.platformId))}</span>` },
    { title: "目标", render: rule => cell(commandPermissionTargetTitle(rule), commandPermissionScopeText(rule)) },
    { title: "发送者", render: rule => `<span class="primary-line">${esc(wildcardText(rule.senderId))}</span>` },
    { title: "角色", render: rule => pill(commandPermissionRoleText(rule.role)) },
  ];
  if (!readOnly) {
    columns.push({ title: "操作", render: rule => `<button type="button" class="danger" data-action="delete-command-permission" data-index="${rule._index}">删除</button>` });
  }
  return renderTable(rows, columns);
}

function commandPermissionTargetTitle(rule) {
  const kind = rule.targetKind ? label(rule.targetKind) : "全部类型";
  return `${kind} / ${wildcardText(rule.targetId)}`;
}

function commandPermissionScopeText(rule) {
  const items = [
    ["作用域", rule.scopeId],
    ["线程", rule.threadId],
    ["接收 Bot", rule.botAccountId]
  ].filter(([, value]) => value && value !== "*");
  return items.length ? items.map(([name, value]) => `${name}:${value}`).join(" / ") : "全部上下文";
}

function commandPermissionRoleText(role) {
  if (role === "NONE") return "无权限";
  if (role === "ADMIN") return "系统管理员";
  if (role === "MANAGER") return "目标管理员";
  return "普通用户";
}

function wildcardText(value) {
  return !value || value === "*" ? "全部" : value;
}

async function openCommandPermissionModal() {
  const [targetPlatforms, commands] = await Promise.all([
    loadPermissionTargetPlatforms(),
    loadPermissionCommands()
  ]);
  const platformOptions = [{ platformId: "*", pluginName: "全部平台", supportedTypes: ["GROUP", "USER", "CHANNEL", "OTHER"] }]
    .concat(targetPlatforms)
    .concat([{ platformId: "__manual__", pluginName: "手动输入", supportedTypes: ["GROUP", "USER", "CHANNEL", "OTHER"] }]);
  const commandOptions = commandPermissionCommandOptions(commands);
  let targetCandidates = [];
  openModal("添加权限规则", `
    <div class="form-grid command-permission-modal">
      <div class="field"><label>命令路径</label><select id="permCommandPath">${commandOptions.map(command => `<option value="${attr(command.pathText)}" data-kind="${attr(command.optionKind || "")}" data-required-role="${attr(command.requiredRole || "")}" data-default-role="${attr(command.defaultRole || "")}">${esc(commandPermissionCommandOptionText(command))}</option>`).join("")}</select></div>
      <div class="field" id="permCommandManualWrap" hidden><label>命令路径</label><input id="permCommandManual" placeholder="例如：subscribe，或 filter *"></div>
      <div class="field"><label>目标平台</label><select id="permPlatform">${platformOptions.map(platform => `<option value="${attr(platform.platformId)}">${esc(commandPermissionPlatformOptionText(platform))}</option>`).join("")}</select></div>
      <div class="field" id="permPlatformManualWrap" hidden><label>平台 ID</label><input id="permPlatformManual"></div>
      <div class="field"><label>目标类型</label><select id="permTargetKind"></select></div>
      <div class="field full" id="permTargetCandidateWrap" hidden>
        <div class="field-head">
          <div class="field-title-line">
            <label>可用目标</label>
            <span id="permTargetInlineStatus" class="field-inline-status"></span>
          </div>
          <div class="row-actions">
            <button type="button" id="permRefreshTargets" class="secondary compact choice-tool-button choice-refresh-button">刷新</button>
          </div>
        </div>
        <div id="permTargetCandidateList" class="target-choice-list"></div>
      </div>
      <div class="field full" id="permTargetManualWrap" hidden><label>目标 ID</label><input id="permTargetId" value="*"></div>
      <div class="field" id="permSenderWrap"><label>发送者 ID</label><input id="permSenderId" placeholder="填写用户 ID，或 * 表示全部"></div>
      <div class="field full"><span id="permSenderHint" class="inline-note"></span></div>
      <div class="field" id="permRoleWrap"><label>最高权限</label><select id="permRole"><option value="USER">普通用户</option><option value="MANAGER" selected>目标管理员</option><option value="ADMIN">系统管理员</option></select></div>
      <div class="field full"><span id="permCommandRoleHint" class="inline-note"></span></div>
      <div class="field full">
        <details>
          <summary>高级匹配</summary>
          <div class="form-grid permission-advanced">
            <div class="field"><label>作用域 ID</label><input id="permScopeId" value="*"></div>
            <div class="field"><label>线程 ID</label><input id="permThreadId" value="*"></div>
            <div class="field"><label>接收 Bot 账号</label><input id="permBotAccountId" value="*" placeholder="例如 QQ 号，或 * 表示全部"></div>
          </div>
        </details>
      </div>
    </div>
  `, async () => {
    const next = commandPermissionRules().concat([collectCommandPermissionRule(targetCandidates)]);
    setCommandPermissionRules(next);
    closeModal();
    notify("权限规则已添加，请保存配置", false);
  }, { size: "medium" });

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
    $("permTargetInlineStatus").textContent = "";
    setPermissionAdvancedFields(null);
    refreshPermissionSenderField(targetCandidates);
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
    setPermissionTargetLoading(force ? "正在刷新可用目标..." : "正在获取可用目标...");
    $("permRefreshTargets").disabled = true;
    let targetLoadFailed = false;
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
      targetLoadFailed = true;
      targetCandidates = [];
      $("permTargetCandidateWrap").hidden = false;
      $("permTargetCandidateList").innerHTML = `<div class="empty">目标列表获取失败</div>`;
      setPermissionTargetStatus(error.message || "可用目标获取失败");
    } finally {
      $("permRefreshTargets").disabled = false;
    }
    if (targetLoadFailed) {
      refreshPermissionSenderField(targetCandidates);
      return;
    }
    if (targetCandidates.length) {
      $("permTargetCandidateWrap").hidden = false;
      const candidateList = $("permTargetCandidateList");
      candidateList.innerHTML = permissionAllTargetChoiceHtml() + targetCandidates.map((target, index) =>
        messageTargetChoiceHtml(target, index, {
          inputName: "permTargetCandidate",
          prefix: "permTarget",
          inputType: "radio",
          checked: false,
          showPriority: false
        })
      ).join("");
      bindTargetSourceToggles(candidateList);
      await hydrateMediaImages(candidateList);
      setPermissionAdvancedFields(null);
      candidateList.querySelectorAll(`input[name="permTargetCandidate"]`).forEach(input => {
        input.onchange = () => {
          const target = selectedPermissionTargetCandidate(targetCandidates);
          setPermissionAdvancedFields(target);
          refreshPermissionSenderField(targetCandidates);
        };
      });
      refreshPermissionSenderField(targetCandidates);
    } else {
      $("permTargetCandidateWrap").hidden = false;
      $("permTargetCandidateList").innerHTML = `<div class="empty">暂无可用目标</div>`;
      $("permTargetManualWrap").hidden = false;
      setPermissionTargetStatus("未获取到可用目标，请手动填写目标 ID");
      refreshPermissionSenderField(targetCandidates);
    }
  };
  $("permPlatform").onchange = () => refreshKinds().catch(handleError);
  $("permPlatformManual").oninput = () => refreshTargets(false).catch(handleError);
  $("permTargetKind").onchange = () => refreshTargets(false).catch(handleError);
  $("permRefreshTargets").onclick = () => refreshTargets(true).catch(handleError);
  $("permCommandPath").onchange = refreshCommandField;
  $("permRole").onchange = refreshPermissionRoleHint;
  refreshCommandField();
  await refreshKinds();
}

async function loadPermissionCommands() {
  if (state.cache.commands) return state.cache.commands;
  state.cache.commands = await api("/commands").catch(() => []);
  return state.cache.commands;
}

async function loadPermissionTargetPlatforms() {
  if (state.cache.subscriberTargetPlatforms) return state.cache.subscriberTargetPlatforms;
  state.cache.subscriberTargetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  return state.cache.subscriberTargetPlatforms;
}

function setPermissionTargetStatus(text) {
  const inline = $("permTargetInlineStatus");
  if (inline) inline.textContent = text ? `· ${text}` : "";
}

function setPermissionTargetLoading(text) {
  $("permTargetCandidateWrap").hidden = false;
  $("permTargetManualWrap").hidden = true;
  $("permTargetInlineStatus").textContent = "";
  $("permTargetCandidateList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
}

function permissionAllTargetChoiceHtml() {
  return `<label class="target-choice">
    <input type="radio" name="permTargetCandidate" value="*" data-index="-1" checked>
    <span class="target-choice-avatar media-placeholder" aria-hidden="true"></span>
    <span class="target-choice-text" title="全部目标">全部目标</span>
  </label>`;
}

function commandPermissionPlatformOptionText(platform) {
  if (platform.platformId === "*") return "全部平台";
  if (platform.platformId === "__manual__") return "手动输入";
  return `${platform.platformId} · ${platform.pluginName || platform.pluginId || ""}`;
}

function commandPermissionCommandOptionText(command) {
  if (command.pathText === "__manual__") return "手动输入";
  if (command.optionKind === "GROUP") {
    return `${command.pathText} · ${command.description || "命令组"} · ${commandPermissionRoleText(command.defaultRole || "MANAGER")}`;
  }
  if (command.pathText === "*") return "全部命令";
  const suffix = command.optionKind === "GROUP" ? command.description || "命令组" : command.description;
  const role = command.requiredRole ? ` · ${commandPermissionRoleText(command.requiredRole)}` : "";
  return suffix ? `${command.pathText} · ${suffix}${role}` : `${command.pathText}${role}`;
}

function commandPermissionCommandOptions(commands) {
  const groups = [
    { pathText: "*", description: "普通用户命令组", optionKind: "GROUP", defaultRole: "USER" },
    { pathText: "*", description: "目标管理命令组", optionKind: "GROUP", defaultRole: "MANAGER" },
    { pathText: "*", description: "全部命令", optionKind: "GROUP", defaultRole: "ADMIN" },
    { pathText: "filter *", description: "过滤命令组", optionKind: "GROUP", defaultRole: "MANAGER" },
    { pathText: "link *", description: "链接解析命令组", optionKind: "GROUP", defaultRole: "MANAGER" }
  ];
  const exactCommands = (Array.isArray(commands) ? commands : []).map(command => Object.assign({}, command, {
    optionKind: "COMMAND"
  }));
  return groups.concat(exactCommands).concat([
    { pathText: "__manual__", description: "手动输入", optionKind: "MANUAL" }
  ]);
}

function refreshCommandField() {
  const raw = $("permCommandPath").value;
  $("permCommandManualWrap").hidden = raw !== "__manual__";
  const selected = selectedPermissionCommandOption();
  const exactCommand = selected.kind === "COMMAND";
  $("permRoleWrap").hidden = exactCommand;
  if (exactCommand) {
    const requiredRole = selected.requiredRole || "USER";
    $("permRole").value = requiredRole;
    $("permCommandRoleHint").textContent = `该命令固定需要${commandPermissionRoleText(requiredRole)}权限。${commandPermissionRoleDescription(requiredRole)}`;
    return;
  }
  const defaultRole = selected.defaultRole || "MANAGER";
  if (["USER", "MANAGER", "ADMIN"].includes(defaultRole)) $("permRole").value = defaultRole;
  refreshPermissionRoleHint();
}

function permissionCommandPathValue() {
  return $("permCommandPath").value === "__manual__" ? $("permCommandManual").value.trim() : $("permCommandPath").value;
}

function selectedPermissionCommandOption() {
  const select = $("permCommandPath");
  const option = select && select.selectedOptions && select.selectedOptions[0];
  return {
    kind: option && option.dataset.kind || "",
    requiredRole: option && option.dataset.requiredRole || "",
    defaultRole: option && option.dataset.defaultRole || ""
  };
}

function refreshPermissionRoleHint() {
  const role = $("permRole") && $("permRole").value || "MANAGER";
  $("permCommandRoleHint").textContent = commandPermissionRoleDescription(role);
}

function commandPermissionRoleDescription(role) {
  if (role === "ADMIN") return "系统管理员：可执行系统级指令，例如登录来源账号、查看系统状态和停止主程序。";
  if (role === "MANAGER") return "目标管理员：可管理匹配目标内的订阅、过滤、主题和链接解析配置。";
  return "普通用户：只能执行查询和安全类指令，例如帮助、列表、手动解析和查看配置。";
}

function permissionPlatformValue() {
  return $("permPlatform").value === "__manual__" ? $("permPlatformManual").value.trim() : $("permPlatform").value;
}

function setPermissionAdvancedFields(target) {
  $("permScopeId").value = target && target.scopeId || "*";
  $("permThreadId").value = target && target.threadId || "*";
  $("permBotAccountId").value = "*";
  $("permTargetId").value = target && target.externalId || "*";
}

function selectedPermissionTargetCandidate(candidates) {
  if (!$("permTargetCandidateWrap") || $("permTargetCandidateWrap").hidden) return null;
  const input = document.querySelector(`input[name="permTargetCandidate"]:checked`);
  const index = input ? Number(input.dataset.index) : -1;
  return index >= 0 ? candidates[index] || null : null;
}

function refreshPermissionSenderField(candidates) {
  const kind = $("permTargetKind") && $("permTargetKind").value || "*";
  const target = selectedPermissionTargetCandidate(candidates);
  const senderWrap = $("permSenderWrap");
  const senderInput = $("permSenderId");
  const hint = $("permSenderHint");
  if (!senderWrap || !senderInput || !hint) return;
  const specificUserTarget = kind === "USER" && !!target;
  if (specificUserTarget) {
    senderInput.value = target.externalId || "";
    senderInput.dataset.autoUserTarget = "true";
    senderWrap.hidden = true;
    hint.textContent = "用户目标中发送者就是该用户，已自动使用目标 ID 作为发送者 ID。";
    return;
  }
  senderWrap.hidden = false;
  if (senderInput.dataset.autoUserTarget === "true") {
    senderInput.value = "";
    senderInput.dataset.autoUserTarget = "false";
  }
  hint.textContent = kind === "GROUP"
    ? "群组目标中，发送者 ID 填 * 表示匹配这个群内所有发指令的人。"
    : "";
}

function collectCommandPermissionRule(targetCandidates) {
  const commandPath = permissionCommandPathValue() || "*";
  if ($("permCommandPath").value === "__manual__" && commandPath === "*") throw new Error("请填写命令路径");
  const platformId = permissionPlatformValue() || "*";
  if ($("permPlatform").value === "__manual__" && platformId === "*") throw new Error("请填写平台 ID");
  const rawKind = $("permTargetKind").value || "*";
  const candidate = selectedPermissionTargetCandidate(targetCandidates);
  const targetId = platformId === "*" || rawKind === "*"
    ? "*"
    : (candidate ? candidate.externalId : $("permTargetId").value.trim() || "*");
  const senderId = $("permSenderId").value.trim();
  if (!senderId) throw new Error("请填写发送者 ID，或使用 * 表示全部发送者");
  const selectedCommand = selectedPermissionCommandOption();
  const role = selectedCommand.kind === "COMMAND"
    ? (selectedCommand.requiredRole || "USER")
    : ($("permRole").value || "MANAGER");
  if (senderId === "*" && role === "ADMIN") throw new Error("系统管理员规则不能匹配全部发送者");
  return normalizeCommandPermissionRule({
    commandPath,
    platformId,
    targetKind: rawKind === "*" ? null : rawKind,
    targetId,
    scopeId: $("permScopeId").value.trim() || "*",
    threadId: $("permThreadId").value.trim() || "*",
    botAccountId: $("permBotAccountId").value.trim() || "*",
    senderId,
    role
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
