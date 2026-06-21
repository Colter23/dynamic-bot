const DELIVERY_LIMIT = 160;
const INCOMING_LIMIT = 100;
const DEFAULT_AUTO_REFRESH_SECONDS = 30;
const AUTO_REFRESH_INTERVAL_SECONDS = [15, 30, 60, 120];
const DEFAULT_DELIVERY_FILTERS = {
  status: "",
  platformId: "",
  targetKind: "",
  targetId: "",
  messageKind: "",
  messageImportance: "",
  messageVisibility: "",
  messageRecordPolicy: "",
  sinkRouteId: "",
  sinkAccountId: "",
  result: "",
  q: "",
  limit: String(DELIVERY_LIMIT),
  includeInternal: false,
};
const DEFAULT_INCOMING_FILTERS = {
  recordPolicy: "",
  intent: "",
  platformId: "",
  targetKind: "",
  targetId: "",
  senderId: "",
  sourcePlugin: "",
  traceId: "",
  result: "",
  stage: "",
  commandPath: "",
  q: "",
  limit: String(INCOMING_LIMIT),
  includeTrace: false,
};

let ctx;
let root;
let api;
let state;
let query;
let handleError;
let esc;
let attr;
let fmtTime;
let fmtBytes;
let label;
let pill;
let cell;
let detailItem;
let prettyJson;
let renderTable;
let notify;
let openModal;
let closeModal;
let confirmDanger;
let platformTag;
let withButtonLoading;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;
let messageAutoRefreshTimer = null;
let messageAutoRefreshRunning = false;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  query = ctx.query;
  handleError = ctx.handleError;
  ({
    esc,
    attr,
    fmtTime,
    fmtBytes,
    label,
    pill,
    cell,
    detailItem,
    prettyJson,
    renderTable,
    notify,
    openModal,
    closeModal,
    confirmDanger,
    platformTag,
    withButtonLoading,
  } = ctx.ui);
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

function pageQuery(selector) {
  return pageRoot().querySelector(selector);
}

function deliveryFilters() {
  if (!state.deliveryFilters) {
    state.deliveryFilters = { ...DEFAULT_DELIVERY_FILTERS };
  } else {
    state.deliveryFilters = { ...DEFAULT_DELIVERY_FILTERS, ...state.deliveryFilters };
  }
  return state.deliveryFilters;
}

function incomingFilters() {
  if (!state.incomingMessageFilters) {
    state.incomingMessageFilters = { ...DEFAULT_INCOMING_FILTERS };
  } else {
    state.incomingMessageFilters = { ...DEFAULT_INCOMING_FILTERS, ...state.incomingMessageFilters };
  }
  return state.incomingMessageFilters;
}

function activeMessageTab() {
  if (!state.messageActiveTab) state.messageActiveTab = "outbound";
  return state.messageActiveTab;
}

function messageAutoRefreshSettings() {
  if (!state.messageAutoRefresh) {
    state.messageAutoRefresh = {
      enabled: true,
      intervalSeconds: DEFAULT_AUTO_REFRESH_SECONDS,
    };
  }
  const intervalSeconds = Number(state.messageAutoRefresh.intervalSeconds || DEFAULT_AUTO_REFRESH_SECONDS);
  state.messageAutoRefresh.enabled = state.messageAutoRefresh.enabled !== false;
  state.messageAutoRefresh.intervalSeconds = AUTO_REFRESH_INTERVAL_SECONDS.includes(intervalSeconds)
    ? intervalSeconds
    : DEFAULT_AUTO_REFRESH_SECONDS;
  return state.messageAutoRefresh;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadMessagesPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  stopMessageAutoRefresh();
  invalidatePageRequests("messages");
  document.getElementById("content")?.classList.remove("content-messages");
  pageRoot()?.classList.remove("messages-page-host");
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "apply-delivery-filter") {
    await applyDeliveryFilter(button);
    return true;
  }
  if (action === "reset-delivery-filter") {
    resetDeliveryFilterControls();
    await applyDeliveryFilter(button);
    return true;
  }
  if (action === "refresh-deliveries") {
    await refreshDeliveries(button, true);
    notify("出站投递已刷新", false);
    return true;
  }
  if (action === "delivery-detail") {
    await openDeliveryDetail(id, button);
    return true;
  }
  if (action === "delivery-resend") {
    await resendDelivery(id, button);
    return true;
  }
  if (action === "set-message-tab") {
    await setMessageTab(id, button);
    return true;
  }
  if (action === "apply-incoming-filter") {
    await applyIncomingFilter(button);
    return true;
  }
  if (action === "reset-incoming-filter") {
    resetIncomingFilterControls();
    await applyIncomingFilter(button);
    return true;
  }
  if (action === "refresh-incoming") {
    await refreshIncomingMessages(button, true);
    notify("入站审计已刷新", false);
    return true;
  }
  if (action === "incoming-detail") {
    await openIncomingDetail(id, button);
    return true;
  }
  return false;
}

async function loadMessagesPage(force) {
  document.getElementById("content")?.classList.add("content-messages");
  pageRoot()?.classList.add("messages-page-host");
  renderLayout();
  bindDeliveryControls();
  bindIncomingControls();
  bindMessageAutoRefreshControls();
  renderActiveMessageTab();
  if (activeMessageTab() === "incoming") {
    if (force || !state.incomingRows) {
      await refreshIncomingMessages(null, true);
    } else {
      renderIncomingMessages();
      renderMessageStatus();
    }
  } else if (force || !state.deliveryRows) {
    await refreshDeliveries(null, true);
  } else {
    renderDeliveries();
    renderMessageStatus();
  }
  scheduleMessageAutoRefresh();
}

function renderLayout() {
  const filters = deliveryFilters();
  const incoming = incomingFilters();
  const autoRefresh = messageAutoRefreshSettings();
  pageRoot().innerHTML = `
    <section class="page messages-page">
      <section class="panel full message-panel">
        <div class="panel-head message-panel-head">
          <div class="message-tab-bar" role="tablist" aria-label="消息链路视图">
            <button type="button" class="message-tab-button" data-message-tab="outbound" data-action="set-message-tab" data-id="outbound">
              <span>出站投递</span><small data-message-tab-count="outbound">-</small>
            </button>
            <button type="button" class="message-tab-button" data-message-tab="incoming" data-action="set-message-tab" data-id="incoming">
              <span>入站审计</span><small data-message-tab-count="incoming">-</small>
            </button>
          </div>
          <div class="message-panel-side">
            <div id="messagePageStatus" class="message-record-status"></div>
            <div class="message-auto-refresh-controls" aria-label="消息链路自动刷新">
              <label class="message-auto-refresh-toggle">
                <input id="messageAutoRefreshEnabled" type="checkbox"${autoRefresh.enabled ? " checked" : ""}>
                <span>自动刷新</span>
              </label>
              <select id="messageAutoRefreshInterval" aria-label="自动刷新间隔">
                ${messageAutoRefreshIntervalOptions(autoRefresh.intervalSeconds)}
              </select>
            </div>
          </div>
        </div>
        <div class="message-tab-view" data-message-tab-view="outbound">
          <div class="entity-filter-bar message-filter-bar">
            <div class="entity-filter-main">
              <span class="entity-filter-title">筛选</span>
              <div class="entity-filter-controls">
                <select id="deliveryFilterStatus" data-delivery-filter="status">
                  ${deliveryStatusOptions(filters.status)}
                </select>
                <input id="deliveryFilterPlatform" data-delivery-filter="platformId" value="${attr(filters.platformId)}" placeholder="平台 ID">
                <select id="deliveryFilterTargetKind" data-delivery-filter="targetKind">
                  ${targetKindOptions(filters.targetKind)}
                </select>
                <input id="deliveryFilterTargetId" data-delivery-filter="targetId" value="${attr(filters.targetId)}" placeholder="目标 ID">
                <select id="deliveryFilterMessageKind" data-delivery-filter="messageKind">
                  ${messageKindOptions(filters.messageKind)}
                </select>
                <select id="deliveryFilterImportance" data-delivery-filter="messageImportance">
                  ${messageImportanceOptions(filters.messageImportance)}
                </select>
                <select id="deliveryFilterVisibility" data-delivery-filter="messageVisibility">
                  ${messageVisibilityOptions(filters.messageVisibility)}
                </select>
                <select id="deliveryFilterRecordPolicy" data-delivery-filter="messageRecordPolicy">
                  ${messageRecordPolicyOptions(filters.messageRecordPolicy)}
                </select>
                <input id="deliveryFilterRoute" data-delivery-filter="sinkRouteId" value="${attr(filters.sinkRouteId)}" placeholder="发送路由">
                <input id="deliveryFilterAccount" data-delivery-filter="sinkAccountId" value="${attr(filters.sinkAccountId)}" placeholder="发送账号">
                <select id="deliveryFilterResult" data-delivery-filter="result">
                  ${deliveryResultOptions(filters.result)}
                </select>
                <input id="deliveryFilterKeyword" data-delivery-filter="q" value="${attr(filters.q)}" placeholder="消息 / 目标 / 路由 / 错误">
                <select id="deliveryFilterLimit" data-delivery-filter="limit">
                  ${limitOptions(filters.limit)}
                </select>
                <label class="message-internal-toggle">
                  <input id="deliveryFilterIncludeInternal" type="checkbox" data-delivery-filter="includeInternal"${filters.includeInternal ? " checked" : ""}>
                  <span>显示内部临时</span>
                </label>
                <button type="button" class="filter-apply-button compact" data-action="apply-delivery-filter">筛选</button>
                <button type="button" class="filter-clear-button compact delivery-clear-filter-button" data-action="reset-delivery-filter">清除筛选</button>
              </div>
              <span id="deliveryFilterSummary" class="entity-filter-summary"></span>
            </div>
            <div class="entity-filter-tools">
              <button type="button" class="filter-refresh-button compact" data-action="refresh-deliveries">刷新</button>
            </div>
          </div>
          <div class="message-table-region">
            <div id="deliveriesTable" class="messages-table-host"></div>
          </div>
        </div>
        <div class="message-tab-view" data-message-tab-view="incoming" hidden>
          <div class="entity-filter-bar message-filter-bar incoming-filter-bar">
            <div class="entity-filter-main">
              <span class="entity-filter-title">筛选</span>
              <div class="entity-filter-controls">
                <select id="incomingFilterPolicy" data-incoming-filter="recordPolicy">
                  ${incomingRecordPolicyOptions(incoming.recordPolicy)}
                </select>
                <select id="incomingFilterIntent" data-incoming-filter="intent">
                  ${incomingIntentOptions(incoming.intent)}
                </select>
                <input id="incomingFilterPlatform" data-incoming-filter="platformId" value="${attr(incoming.platformId)}" placeholder="平台 ID">
                <select id="incomingFilterTargetKind" data-incoming-filter="targetKind">
                  ${targetKindOptions(incoming.targetKind)}
                </select>
                <input id="incomingFilterTargetId" data-incoming-filter="targetId" value="${attr(incoming.targetId)}" placeholder="目标 ID">
                <input id="incomingFilterSender" data-incoming-filter="senderId" value="${attr(incoming.senderId)}" placeholder="发送者 ID">
                <input id="incomingFilterPlugin" data-incoming-filter="sourcePlugin" value="${attr(incoming.sourcePlugin)}" placeholder="来源插件">
                <input id="incomingFilterTrace" data-incoming-filter="traceId" value="${attr(incoming.traceId)}" placeholder="traceId">
                <select id="incomingFilterResult" data-incoming-filter="result">
                  ${incomingResultOptions(incoming.result)}
                </select>
                <select id="incomingFilterStage" data-incoming-filter="stage">
                  ${incomingStageOptions(incoming.stage)}
                </select>
                <input id="incomingFilterCommand" data-incoming-filter="commandPath" value="${attr(incoming.commandPath)}" placeholder="命令路径">
                <input id="incomingFilterKeyword" data-incoming-filter="q" value="${attr(incoming.q)}" placeholder="预览 / trace / 错误">
                <select id="incomingFilterLimit" data-incoming-filter="limit">
                  ${limitOptions(incoming.limit)}
                </select>
                <label class="message-internal-toggle">
                  <input id="incomingFilterIncludeTrace" type="checkbox" data-incoming-filter="includeTrace"${incoming.includeTrace ? " checked" : ""}>
                  <span>显示全部 TRACE</span>
                </label>
                <button type="button" class="filter-apply-button compact" data-action="apply-incoming-filter">筛选</button>
                <button type="button" class="filter-clear-button compact incoming-clear-filter-button" data-action="reset-incoming-filter">清除筛选</button>
              </div>
              <span id="incomingFilterSummary" class="entity-filter-summary"></span>
            </div>
            <div class="entity-filter-tools">
              <button type="button" class="filter-refresh-button compact" data-action="refresh-incoming">刷新</button>
            </div>
          </div>
          <div class="message-table-region">
            <div id="incomingMessagesTable" class="messages-table-host"></div>
          </div>
        </div>
      </section>
    </section>`;
  renderMessageStatus();
  updateDeliveryFilterButtons();
  updateIncomingFilterButtons();
}

function deliveryStatusOptions(selected) {
  return [
    ["", "全部状态"],
    ["FAILED", "失败"],
    ["PENDING", "等待"],
    ["SENDING", "发送中"],
    ["SENT", "已发送"],
    ["SEND_UNKNOWN", "状态未知"],
    ["PARTIALLY_SENT", "部分发送"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function targetKindOptions(selected) {
  return [
    ["", "全部目标"],
    ["GROUP", "群组"],
    ["USER", "用户"],
    ["CHANNEL", "频道"],
    ["OTHER", "其他"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function messageKindOptions(selected) {
  return [
    ["", "全部消息"],
    ["NORMAL", "普通消息"],
    ["SOURCE_UPDATE", "订阅推送"],
    ["LINK_RESULT", "链接结果"],
    ["COMMAND_RESULT", "命令回复"],
    ["INTERACTION_REPLY", "交互回复"],
    ["PROGRESS", "进度提示"],
    ["SYSTEM_NOTIFICATION", "系统通知"],
    ["MANUAL", "手动消息"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function messageImportanceOptions(selected) {
  return [
    ["", "全部重要度"],
    ["HIGH", "高重要"],
    ["NORMAL", "普通"],
    ["LOW", "低重要"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function messageVisibilityOptions(selected) {
  return [
    ["", "全部可见性"],
    ["DEFAULT", "默认展示"],
    ["INTERNAL", "内部"],
    ["HIDDEN", "隐藏"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function messageRecordPolicyOptions(selected) {
  return [
    ["", "全部记录策略"],
    ["DURABLE", "持久记录"],
    ["TRANSIENT", "临时记录"],
    ["EPHEMERAL", "即时不记录"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function deliveryResultOptions(selected) {
  return [
    ["", "全部结果"],
    ["HAS_RECEIPT", "有回执"],
    ["NO_RECEIPT", "无回执"],
    ["HAS_ERROR", "有错误"],
    ["RETRY_SCHEDULED", "等待重试"],
    ["LOCKED", "发送锁定"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function incomingRecordPolicyOptions(selected) {
  return [
    ["", "默认审计视图"],
    ["AUDIT", "长期审计"],
    ["TRACE", "短期追踪"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function incomingIntentOptions(selected) {
  return [
    ["", "全部意图"],
    ["COMMAND", "命令"],
    ["LINK_TEXT", "链接文本"],
    ["PLAIN_TEXT", "普通文本"],
    ["NON_TEXT", "非文本"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function incomingResultOptions(selected) {
  return [
    ["", "全部处理结果"],
    ["FAILED", "失败"],
    ["REJECTED", "拒绝"],
    ["SUCCEEDED", "成功"],
    ["MATCHED", "已匹配"],
    ["IGNORED", "已忽略"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function incomingStageOptions(selected) {
  return [
    ["", "全部处理阶段"],
    ["COMMAND_PARSE", "命令解析"],
    ["COMMAND_EXECUTE", "命令执行"],
    ["LINK_PARSE", "链接解析"],
    ["PLUGIN_CONSUMER", "插件消费"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function limitOptions(selected) {
  return [50, 100, 160, 200].map(value =>
    `<option value="${value}"${String(value) === String(selected) ? " selected" : ""}>最近 ${value} 条</option>`
  ).join("");
}

function messageAutoRefreshIntervalOptions(selected) {
  return AUTO_REFRESH_INTERVAL_SECONDS.map(value =>
    `<option value="${value}"${Number(selected) === value ? " selected" : ""}>每 ${value} 秒</option>`
  ).join("");
}

function bindDeliveryControls() {
  pageRoot().querySelectorAll("[data-delivery-filter]").forEach(control => {
    control.onkeydown = event => {
      if (event.key === "Enter") {
        event.preventDefault();
        applyDeliveryFilter().catch(handleError);
      }
    };
    control.oninput = updateDeliveryFilterButtons;
  });
  const status = pageQuery("#deliveryFilterStatus");
  const targetKind = pageQuery("#deliveryFilterTargetKind");
  const messageKind = pageQuery("#deliveryFilterMessageKind");
  const importance = pageQuery("#deliveryFilterImportance");
  const visibility = pageQuery("#deliveryFilterVisibility");
  const recordPolicy = pageQuery("#deliveryFilterRecordPolicy");
  const result = pageQuery("#deliveryFilterResult");
  const limit = pageQuery("#deliveryFilterLimit");
  const includeInternal = pageQuery("#deliveryFilterIncludeInternal");
  if (status) status.onchange = () => applyDeliveryFilter().catch(handleError);
  if (targetKind) targetKind.onchange = () => applyDeliveryFilter().catch(handleError);
  if (messageKind) messageKind.onchange = () => applyDeliveryFilter().catch(handleError);
  if (importance) importance.onchange = () => applyDeliveryFilter().catch(handleError);
  if (visibility) visibility.onchange = () => applyDeliveryFilter().catch(handleError);
  if (recordPolicy) recordPolicy.onchange = () => applyDeliveryFilter().catch(handleError);
  if (result) result.onchange = () => applyDeliveryFilter().catch(handleError);
  if (limit) limit.onchange = () => applyDeliveryFilter().catch(handleError);
  if (includeInternal) includeInternal.onchange = () => applyDeliveryFilter().catch(handleError);
}

function bindIncomingControls() {
  pageRoot().querySelectorAll("[data-incoming-filter]").forEach(control => {
    control.onkeydown = event => {
      if (event.key === "Enter") {
        event.preventDefault();
        applyIncomingFilter().catch(handleError);
      }
    };
    control.oninput = updateIncomingFilterButtons;
  });
  pageRoot()
    .querySelectorAll("#incomingFilterPolicy, #incomingFilterIntent, #incomingFilterTargetKind, #incomingFilterResult, #incomingFilterStage, #incomingFilterLimit, #incomingFilterIncludeTrace")
    .forEach(control => {
      control.onchange = () => applyIncomingFilter().catch(handleError);
    });
}

function bindMessageAutoRefreshControls() {
  const enabled = pageQuery("#messageAutoRefreshEnabled");
  const interval = pageQuery("#messageAutoRefreshInterval");
  if (enabled) {
    enabled.onchange = () => {
      const settings = messageAutoRefreshSettings();
      settings.enabled = !!enabled.checked;
      scheduleMessageAutoRefresh();
      renderMessageStatus();
    };
  }
  if (interval) {
    interval.onchange = () => {
      const settings = messageAutoRefreshSettings();
      settings.intervalSeconds = Number(interval.value || DEFAULT_AUTO_REFRESH_SECONDS);
      scheduleMessageAutoRefresh();
      renderMessageStatus();
    };
  }
}

async function setMessageTab(tab, button) {
  const next = tab === "incoming" ? "incoming" : "outbound";
  state.messageActiveTab = next;
  renderActiveMessageTab();
  renderMessageStatus();
  scheduleMessageAutoRefresh();
  if (next === "incoming" && !state.incomingRows) {
    await refreshIncomingMessages(button, true);
  } else if (next === "outbound" && !state.deliveryRows) {
    await refreshDeliveries(button, true);
  }
}

function renderActiveMessageTab() {
  const active = activeMessageTab();
  pageRoot().querySelectorAll("[data-message-tab]").forEach(button => {
    const selected = button.dataset.messageTab === active;
    button.classList.toggle("active", selected);
    button.setAttribute("aria-selected", selected ? "true" : "false");
  });
  pageRoot().querySelectorAll("[data-message-tab-view]").forEach(view => {
    view.hidden = view.dataset.messageTabView !== active;
  });
  renderMessageTabCounts();
}

async function applyDeliveryFilter(button) {
  readDeliveryFilterControls();
  updateDeliveryFilterButtons();
  await refreshDeliveries(button, true);
}

function readDeliveryFilterControls() {
  const filters = deliveryFilters();
  pageRoot().querySelectorAll("[data-delivery-filter]").forEach(control => {
    const key = control.dataset.deliveryFilter;
    if (!key) return;
    filters[key] = control.type === "checkbox" ? !!control.checked : (control.value || "").trim();
  });
  filters.limit = filters.limit || String(DELIVERY_LIMIT);
}

function deliveryFilterActiveFromControls() {
  const filters = deliveryFilters();
  const controlValues = { ...DEFAULT_DELIVERY_FILTERS };
  pageRoot().querySelectorAll("[data-delivery-filter]").forEach(control => {
    const key = control.dataset.deliveryFilter;
    if (!key) return;
    controlValues[key] = control.type === "checkbox" ? !!control.checked : (control.value || "").trim();
  });
  return filterObjectActive(controlValues) || filterObjectActive(filters);
}

function resetDeliveryFilterControls() {
  const filters = deliveryFilters();
  Object.assign(filters, DEFAULT_DELIVERY_FILTERS);
  pageRoot().querySelectorAll("[data-delivery-filter]").forEach(control => {
    const key = control.dataset.deliveryFilter;
    if (!key) return;
    if (control.type === "checkbox") {
      control.checked = !!DEFAULT_DELIVERY_FILTERS[key];
    } else {
      control.value = DEFAULT_DELIVERY_FILTERS[key] || "";
    }
  });
}

function filterObjectActive(filters) {
  return Object.entries(DEFAULT_DELIVERY_FILTERS).some(([key, value]) => {
    if (key === "limit") return String(filters[key] || String(DELIVERY_LIMIT)) !== String(value);
    if (typeof value === "boolean") return Boolean(filters[key]) !== value;
    return Boolean(filters[key]);
  });
}

async function applyIncomingFilter(button) {
  readIncomingFilterControls();
  updateIncomingFilterButtons();
  await refreshIncomingMessages(button, true);
}

function readIncomingFilterControls() {
  const filters = incomingFilters();
  pageRoot().querySelectorAll("[data-incoming-filter]").forEach(control => {
    const key = control.dataset.incomingFilter;
    if (!key) return;
    filters[key] = control.type === "checkbox" ? !!control.checked : (control.value || "").trim();
  });
  filters.limit = filters.limit || String(INCOMING_LIMIT);
}

function incomingFilterActiveFromControls() {
  const filters = incomingFilters();
  const controlValues = { ...DEFAULT_INCOMING_FILTERS };
  pageRoot().querySelectorAll("[data-incoming-filter]").forEach(control => {
    const key = control.dataset.incomingFilter;
    if (!key) return;
    controlValues[key] = control.type === "checkbox" ? !!control.checked : (control.value || "").trim();
  });
  return incomingFilterObjectActive(controlValues) || incomingFilterObjectActive(filters);
}

function resetIncomingFilterControls() {
  const filters = incomingFilters();
  Object.assign(filters, DEFAULT_INCOMING_FILTERS);
  pageRoot().querySelectorAll("[data-incoming-filter]").forEach(control => {
    const key = control.dataset.incomingFilter;
    if (!key) return;
    if (control.type === "checkbox") {
      control.checked = !!DEFAULT_INCOMING_FILTERS[key];
    } else {
      control.value = DEFAULT_INCOMING_FILTERS[key] || "";
    }
  });
}

function incomingFilterObjectActive(filters) {
  return Object.entries(DEFAULT_INCOMING_FILTERS).some(([key, value]) => {
    if (key === "limit") return String(filters[key] || String(INCOMING_LIMIT)) !== String(value);
    if (typeof value === "boolean") return Boolean(filters[key]) !== value;
    return Boolean(filters[key]);
  });
}

async function refreshIncomingMessages(button, force, options = {}) {
  const request = beginPageRequest("messages");
  if (options.readControls !== false) readIncomingFilterControls();
  const filters = incomingFilters();
  state.incomingLoading = true;
  renderMessageStatus();
  try {
    await withButtonLoading(button, "加载中...", async () => {
      const rows = await api("/incoming-messages" + query({
        recordPolicy: filters.recordPolicy,
        intent: filters.intent,
        platformId: filters.platformId,
        targetKind: filters.targetKind,
        targetId: filters.targetId,
        senderId: filters.senderId,
        sourcePlugin: filters.sourcePlugin,
        traceId: filters.traceId,
        result: filters.result,
        stage: filters.stage,
        commandPath: filters.commandPath,
        q: filters.q,
        limit: filters.limit,
        includeTrace: filters.includeTrace ? "true" : "",
      }));
      if (!isCurrentPageRequest(request)) return;
      state.incomingRows = rows || [];
      state.cache.incomingMessages = state.incomingRows;
      renderIncomingMessages();
    });
  } finally {
    if (isCurrentPageRequest(request)) {
      state.incomingLoading = false;
      renderMessageStatus();
    }
    updateIncomingFilterButtons();
  }
}

async function refreshDeliveries(button, force, options = {}) {
  const request = beginPageRequest("messages");
  if (options.readControls !== false) readDeliveryFilterControls();
  const filters = deliveryFilters();
  state.deliveriesLoading = true;
  renderDeliveryStatus();
  try {
    await withButtonLoading(button, "加载中...", async () => {
      const rows = await api("/deliveries" + query({
        status: filters.status,
        platformId: filters.platformId,
        targetKind: filters.targetKind,
        targetId: filters.targetId,
        messageKind: filters.messageKind,
        messageImportance: filters.messageImportance,
        messageVisibility: filters.messageVisibility,
        messageRecordPolicy: filters.messageRecordPolicy,
        sinkRouteId: filters.sinkRouteId,
        sinkAccountId: filters.sinkAccountId,
        result: filters.result,
        q: filters.q,
        limit: filters.limit,
        includeInternal: filters.includeInternal ? "true" : "",
      }));
      if (!isCurrentPageRequest(request)) return;
      state.deliveryRows = rows || [];
      state.cache.deliveries = state.deliveryRows;
      renderDeliveries();
    });
  } finally {
    if (isCurrentPageRequest(request)) {
      state.deliveriesLoading = false;
      renderDeliveryStatus();
    }
    updateDeliveryFilterButtons();
  }
}

function renderDeliveries() {
  const target = pageQuery("#deliveriesTable");
  if (!target) return;
  const rows = state.deliveryRows || [];
  renderDeliverySummary(rows);
  updateDeliveryFilterButtons();
  if (rows.length === 0) {
    target.innerHTML = `<div class="empty message-empty">暂无出站投递记录</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "出站内容", render: row => messageCell(row) },
    { title: "发送目标", render: row => targetCell(row) },
    { title: "出站策略", render: row => messagePolicyCell(row) },
    { title: "投递状态", render: row => deliveryStatusCell(row) },
    { title: "路由与回执", render: row => routeReceiptCell(row) },
    { title: "时间", render: row => deliveryTimeCell(row) },
    { title: "操作", render: row => deliveryActions(row) },
  ])
    .replace('class="table-wrap"', 'class="table-wrap messages-table-wrap"')
    .replace("<table>", '<table class="messages-table">');
}

function deliveryActions(row) {
  const actions = [`<button type="button" class="message-action-button" data-action="delivery-detail" data-id="${attr(row.id)}">详情</button>`];
  if (row.status === "SEND_UNKNOWN" || row.status === "FAILED") {
    actions.push(`<button type="button" class="message-action-button" data-action="delivery-resend" data-id="${attr(row.id)}">补发</button>`);
  }
  return actions.join(" ");
}

function renderIncomingMessages() {
  const target = pageQuery("#incomingMessagesTable");
  if (!target) return;
  const rows = state.incomingRows || [];
  renderIncomingSummary(rows);
  updateIncomingFilterButtons();
  if (rows.length === 0) {
    target.innerHTML = `<div class="empty message-empty">暂无入站审计记录</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "入站内容", render: row => incomingMessageCell(row) },
    { title: "入口位置", render: row => incomingEndpointCell(row) },
    { title: "审计策略", render: row => incomingPolicyCell(row) },
    { title: "处理结果", render: row => incomingProcessingCell(row) },
    { title: "时间", render: row => incomingTimeCell(row) },
    { title: "操作", render: row => `<button type="button" class="message-action-button" data-action="incoming-detail" data-id="${attr(row.traceId)}">详情</button>` },
  ])
    .replace('class="table-wrap"', 'class="table-wrap messages-table-wrap"')
    .replace("<table>", '<table class="messages-table incoming-messages-table">');
}

function messageCell(row) {
  const title = compactValue(row.messagePreview || "无文本内容", 76);
  return `
    <div class="message-preview-cell">
      <span class="message-preview-meta">
        ${messageMetaTag(row.messageKind || "NORMAL", "")}
      </span>
      <span class="primary-line message-preview-title">${esc(title)}</span>
      <span class="sub-line">${esc(messageSubLine(row))}</span>
    </div>`;
}

function messageSubLine(row) {
  const parts = [];
  if (row.sourcePlugin) parts.push(`插件 ${compactValue(row.sourcePlugin, 22)}`);
  if (row.sourceUpdateKey) parts.push(`来源 ${compactValue(row.sourceUpdateKey, 34)}`);
  if (row.renderVariant) parts.push(`渲染 ${row.renderVariant}`);
  parts.push(`消息 ${compactValue(row.messageId, 28)}`);
  if (row.replyToMessageId) parts.push(`回复 ${compactValue(row.replyToMessageId, 22)}`);
  if (row.correlationId) parts.push(`关联 ${compactValue(row.correlationId, 24)}`);
  return parts.join(" · ");
}

function incomingMessageCell(row) {
  const title = compactValue(row.textPreview || row.segmentSummary || "无文本内容", 92);
  const parts = [];
  if (row.segmentSummary && row.segmentSummary !== row.textPreview) parts.push(row.segmentSummary);
  parts.push(`trace ${compactValue(row.traceId, 32)}`);
  if (row.platformMessageId) parts.push(`平台消息 ${compactValue(row.platformMessageId, 24)}`);
  if (row.replyToMessageId) parts.push(`回复 ${compactValue(row.replyToMessageId, 22)}`);
  if (row.sourceEventId) parts.push(`事件 ${compactValue(row.sourceEventId, 30)}`);
  return `
    <div class="message-preview-cell">
      <span class="message-preview-meta">
        ${messageMetaTag(row.intent, "")}
      </span>
      <span class="primary-line message-preview-title">${esc(title)}</span>
      <span class="sub-line mono">${esc(parts.join(" · "))}</span>
    </div>`;
}

function targetCell(row) {
  const name = row.targetName || row.targetId;
  const title = `${label(row.targetKind)} ${name}`;
  const suffix = targetSubLine(row);
  return `
    <div class="message-target-cell">
      <span class="message-target-title">${platformTag(row.platformId, row.platformId)}<span class="primary-line">${esc(title)}</span></span>
      <span class="sub-line">${esc(suffix || "-")}</span>
    </div>`;
}

function incomingEndpointCell(row) {
  const targetTitle = `${label(row.targetKind)} ${row.targetId}`;
  const parts = [];
  if (row.senderId) parts.push(`发送者 ${compactValue(row.senderId, 24)}`);
  if (row.botAccountId) parts.push(`Bot ${compactValue(row.botAccountId, 22)}`);
  if (row.sourcePlugin) parts.push(`插件 ${row.sourcePlugin}`);
  return `
    <div class="message-target-cell">
      <span class="message-target-title">${platformTag(row.platformId, row.platformId)}<span class="primary-line">${esc(targetTitle)}</span></span>
      <span class="sub-line">${esc(parts.join(" · ") || "-")}</span>
    </div>`;
}

function messagePolicyCell(row) {
  const tags = [
    row.messageImportance && row.messageImportance !== "NORMAL" ? messageMetaTag(row.messageImportance, "") : "",
    row.messageVisibility && row.messageVisibility !== "DEFAULT" ? messageMetaTag(row.messageVisibility, "") : "",
    row.messageRecordPolicy && row.messageRecordPolicy !== "DURABLE" ? messageMetaTag(row.messageRecordPolicy, "") : "",
  ].filter(Boolean).join("");
  const expires = row.messageRecordPolicy === "TRANSIENT" && row.transientExpiresAtEpochSeconds
    ? `<span class="sub-line">保留至 ${fmtTime(row.transientExpiresAtEpochSeconds)}</span>`
    : `<span class="sub-line">${row.messageRecordPolicy === "DURABLE" ? "持久记录" : "-"}</span>`;
  return `<div class="message-policy-cell">${tags || messageMetaTag("DEFAULT", "")}${expires}</div>`;
}

function incomingPolicyCell(row) {
  const tags = [
    messageMetaTag(row.recordPolicy, ""),
    row.failedProcessingCount > 0 ? messageMetaTag("FAILED", "") : "",
  ].filter(Boolean).join("");
  const expires = row.expiresAtEpochSeconds
    ? `保留至 ${fmtTime(row.expiresAtEpochSeconds)}`
    : (row.recordPolicy === "AUDIT" ? "长期审计" : "-");
  return `<div class="message-policy-cell">${tags}<span class="sub-line">${esc(expires)}</span></div>`;
}

function incomingProcessingCell(row) {
  const result = row.lastProcessingResult || (row.processingCount > 0 ? "MATCHED" : "IGNORED");
  const error = row.failedProcessingCount > 0 ? `<span class="message-result bad">失败 ${Number(row.failedProcessingCount || 0)} 条</span>` : "";
  return `
    <div class="message-status-cell">
      <div class="message-status-line">${pill(result)}<span class="message-attempts">${Number(row.processingCount || 0)}</span></div>
      <span class="sub-line">处理记录 ${Number(row.processingCount || 0)} 条</span>
      ${error}
    </div>`;
}

function incomingTimeCell(row) {
  const parts = [];
  if (row.messageTimestampEpochSeconds) parts.push(`消息 ${fmtTime(row.messageTimestampEpochSeconds)}`);
  if (row.createdAtEpochSeconds && row.createdAtEpochSeconds !== row.receivedAtEpochSeconds) parts.push(`记录 ${fmtTime(row.createdAtEpochSeconds)}`);
  return `<span class="primary-line message-time">${esc(fmtTime(row.receivedAtEpochSeconds))}</span><span class="sub-line">${esc(parts.join(" · ") || "-")}</span>`;
}

function messageMetaTag(value, normalValue) {
  if (!value) return "";
  const cls = value === normalValue ? "" : ` ${messageMetaClass(value)}`;
  return `<span class="message-meta-tag${cls}">${esc(label(value))}</span>`;
}

function messageMetaClass(value) {
  if (["HIGH", "FAILED", "HIDDEN", "REJECTED"].includes(value)) return "bad";
  if (["LOW", "INTERNAL", "TRANSIENT", "TRACE", "PROGRESS", "LINK_RESULT", "INTERACTION_REPLY", "IGNORED", "PLAIN_TEXT", "NON_TEXT"].includes(value)) return "soft";
  if (["COMMAND", "LINK_TEXT", "AUDIT", "SOURCE_UPDATE", "COMMAND_RESULT", "SYSTEM_NOTIFICATION", "MANUAL", "SUCCEEDED", "MATCHED"].includes(value)) return "info";
  return "";
}

function deliveryStatusCell(row) {
  return `
    <div class="message-status-cell">
      <div class="message-status-line">${pill(row.status)}<span class="message-attempts">${Number(row.attempts || 0)}</span></div>
      <span class="sub-line">${esc(statusHint(row))}</span>
      ${row.lastError ? `<span class="message-result bad">${esc(compactValue(row.lastError, 96))}</span>` : ""}
    </div>`;
}

function routeReceiptCell(row) {
  const route = row.sinkRouteId ? `路由 ${compactValue(row.sinkRouteId, 28)}`
    : row.sinkAccountId ? `账号 ${compactValue(row.sinkAccountId, 28)}`
      : row.targetAccountId ? `目标账号 ${compactValue(row.targetAccountId, 28)}`
        : "未绑定发送路由";
  const parts = [];
  if (row.sinkMessageId) parts.push(`回执 ${compactValue(row.sinkMessageId, 32)}`);
  if (row.sinkRouteId && row.sinkAccountId) parts.push(`账号 ${compactValue(row.sinkAccountId, 24)}`);
  if (!row.sinkMessageId) parts.push(resultText(row));
  return cell(route, parts.join(" · ") || "无平台回执");
}

function deliveryTimeCell(row) {
  const parts = [];
  if (row.createdAtEpochSeconds && row.createdAtEpochSeconds !== row.updatedAtEpochSeconds) {
    parts.push(`创建 ${fmtTime(row.createdAtEpochSeconds)}`);
  }
  if (row.nextAttemptAtEpochSeconds) parts.push(`下次 ${fmtTime(row.nextAttemptAtEpochSeconds)}`);
  if (row.lockedUntilEpochSeconds) parts.push(`锁定 ${fmtTime(row.lockedUntilEpochSeconds)}`);
  return `<span class="primary-line message-time">${esc(fmtTime(row.updatedAtEpochSeconds))}</span><span class="sub-line">${esc(parts.join(" · ") || "-")}</span>`;
}

function targetSubLine(row) {
  const parts = [];
  if (row.targetName && row.targetName !== row.targetId) parts.push(`ID ${compactValue(row.targetId, 24)}`);
  if (row.targetScopeId) parts.push(`作用域 ${compactValue(row.targetScopeId, 24)}`);
  if (row.targetThreadId) parts.push(`线程 ${compactValue(row.targetThreadId, 24)}`);
  if (row.targetAccountId) parts.push(`目标账号 ${compactValue(row.targetAccountId, 24)}`);
  return parts.join(" · ");
}

function statusHint(row) {
  if (row.status === "FAILED") return row.lastError ? "有失败原因" : "无失败详情";
  if (row.status === "SENT") return row.sinkMessageId ? "已有平台回执" : "已完成";
  if (row.status === "SEND_UNKNOWN") return row.lastError ? "发送结果待确认" : "发送状态未知";
  if (row.status === "PARTIALLY_SENT") return row.lastError ? "部分成功，有失败原因" : "部分成功";
  if (row.status === "SENDING") return row.lockedUntilEpochSeconds ? `锁定至 ${fmtTime(row.lockedUntilEpochSeconds)}` : "正在发送";
  if (row.status === "PENDING") return row.nextAttemptAtEpochSeconds ? `下次 ${fmtTime(row.nextAttemptAtEpochSeconds)}` : "等待调度";
  return "-";
}

function resultText(row) {
  if (row.status === "FAILED") return row.lastError || "投递失败";
  if (row.status === "SENT") return row.sinkMessageId ? `回执：${row.sinkMessageId}` : "已发送";
  if (row.status === "SEND_UNKNOWN") return row.lastError || "平台响应超时或无响应，发送状态未知，未自动重试";
  if (row.status === "PARTIALLY_SENT") return row.lastError || "部分消息已发送，后续不再重试";
  if (row.status === "SENDING") return row.lockedUntilEpochSeconds ? `发送锁定至 ${fmtTime(row.lockedUntilEpochSeconds)}` : "发送中";
  if (row.status === "PENDING") return row.nextAttemptAtEpochSeconds ? `等待下次尝试：${fmtTime(row.nextAttemptAtEpochSeconds)}` : "等待投递队列调度";
  return "-";
}

function compactValue(value, maxLength) {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  return text.length > maxLength ? text.slice(0, maxLength - 1) + "..." : text;
}

function renderDeliverySummary(rows) {
  const summary = pageQuery("#deliveryFilterSummary");
  if (!summary) return;
  const counts = (rows || []).reduce((acc, row) => {
    acc[row.status] = (acc[row.status] || 0) + 1;
    return acc;
  }, {});
  const failed = counts.FAILED || 0;
  const unknown = counts.SEND_UNKNOWN || 0;
  const pending = counts.PENDING || 0;
  const sending = counts.SENDING || 0;
  const partial = counts.PARTIALLY_SENT || 0;
  const internal = rows.filter(row => row.messageVisibility === "INTERNAL" || row.messageRecordPolicy === "TRANSIENT").length;
  summary.textContent = `显示 ${rows.length} 条 · 失败 ${failed} · 未知 ${unknown} · 部分 ${partial} · 等待 ${pending} · 发送中 ${sending} · 内部/临时 ${internal}`;
}

function renderIncomingSummary(rows) {
  const summary = pageQuery("#incomingFilterSummary");
  if (!summary) return;
  const audit = rows.filter(row => row.recordPolicy === "AUDIT").length;
  const trace = rows.filter(row => row.recordPolicy === "TRACE").length;
  const failed = rows.filter(row => Number(row.failedProcessingCount || 0) > 0 || row.lastProcessingResult === "FAILED").length;
  const command = rows.filter(row => row.intent === "COMMAND").length;
  const link = rows.filter(row => row.intent === "LINK_TEXT").length;
  summary.textContent = `显示 ${rows.length} 条 · 审计 ${audit} · 追踪 ${trace} · 异常 ${failed} · 命令 ${command} · 链接 ${link}`;
}

function updateDeliveryFilterButtons() {
  const clearButton = pageQuery(".delivery-clear-filter-button");
  if (clearButton) clearButton.disabled = !deliveryFilterActiveFromControls();
}

function updateIncomingFilterButtons() {
  const clearButton = pageQuery(".incoming-clear-filter-button");
  if (clearButton) clearButton.disabled = !incomingFilterActiveFromControls();
}

function renderDeliveryStatus() {
  renderMessageStatus();
}

function renderMessageStatus() {
  const target = pageQuery("#messagePageStatus");
  renderMessageTabCounts();
  if (!target) return;
  if (activeMessageTab() === "incoming") {
    const rows = state.incomingRows || [];
    const latest = rows[0];
    target.innerHTML = `
      ${state.incomingLoading ? '<span class="loading-spinner" aria-hidden="true"></span>' : ""}
      <span class="pill ${state.incomingLoading ? "warn" : "info"}">${state.incomingLoading ? "正在读取" : `入站 ${rows.length} 条`}</span>
      <span>${latest ? `最新接收 ${fmtTime(latest.receivedAtEpochSeconds)}` : "暂无入站审计"}</span>`;
    return;
  }
  const rows = state.deliveryRows || [];
  const latest = rows[0];
  target.innerHTML = `
    ${state.deliveriesLoading ? '<span class="loading-spinner" aria-hidden="true"></span>' : ""}
    <span class="pill ${state.deliveriesLoading ? "warn" : "info"}">${state.deliveriesLoading ? "正在读取" : `出站 ${rows.length} 条`}</span>
    <span>${latest ? `最新更新 ${fmtTime(latest.updatedAtEpochSeconds)}` : "暂无记录"}</span>`;
}

function renderMessageTabCounts() {
  const outboundCount = pageQuery('[data-message-tab-count="outbound"]');
  const incomingCount = pageQuery('[data-message-tab-count="incoming"]');
  if (outboundCount) {
    outboundCount.textContent = state.deliveriesLoading ? "..." : (Array.isArray(state.deliveryRows) ? String(state.deliveryRows.length) : "-");
  }
  if (incomingCount) {
    incomingCount.textContent = state.incomingLoading ? "..." : (Array.isArray(state.incomingRows) ? String(state.incomingRows.length) : "-");
  }
}

function scheduleMessageAutoRefresh() {
  stopMessageAutoRefresh();
  const settings = messageAutoRefreshSettings();
  if (!settings.enabled) return;
  messageAutoRefreshTimer = window.setInterval(() => {
    runMessageAutoRefresh().catch(handleError);
  }, settings.intervalSeconds * 1000);
}

function stopMessageAutoRefresh() {
  if (messageAutoRefreshTimer !== null) {
    window.clearInterval(messageAutoRefreshTimer);
    messageAutoRefreshTimer = null;
  }
}

async function runMessageAutoRefresh() {
  const settings = messageAutoRefreshSettings();
  if (!settings.enabled || messageAutoRefreshRunning) return;
  if (document.hidden || !pageRoot()?.isConnected) return;
  if (document.getElementById("modalBackdrop")?.hidden === false) return;
  if (state.deliveriesLoading || state.incomingLoading) return;
  messageAutoRefreshRunning = true;
  try {
    if (activeMessageTab() === "incoming") {
      await refreshIncomingMessages(null, false, { readControls: false });
    } else {
      await refreshDeliveries(null, false, { readControls: false });
    }
  } finally {
    messageAutoRefreshRunning = false;
  }
}

async function openDeliveryDetail(id, button) {
  const fallback = (state.deliveryRows || []).find(item => String(item.id) === String(id));
  if (!fallback) throw new Error("出站投递记录不存在");
  await withButtonLoading(button, "读取中...", async () => {
    const detail = await api(`/deliveries/${encodeURIComponent(id)}`);
    const row = detail.delivery || fallback;
    openModal("出站投递详情", renderDeliveryDetail(row, detail.message), null, {
      size: "wide",
      cancelText: "关闭",
    });
  });
}

async function resendDelivery(id, button) {
  const fallback = (state.deliveryRows || []).find(item => String(item.id) === String(id));
  if (!fallback) throw new Error("出站投递记录不存在");
  const detail = await withButtonLoading(button, "读取中...", async () => api(`/deliveries/${encodeURIComponent(id)}`));
  const row = detail.delivery || fallback;
  if (!detail.message) throw new Error("原始消息已被清理，无法补发");
  const confirmed = await confirmDanger("补发消息", "补发会创建新的手动出站记录。如果原消息其实已经送达，可能会造成重复推送。", {
    confirmText: "确认补发",
  });
  if (!confirmed) return;
  await withButtonLoading(button, "补发中...", async () => {
    const response = await api("/message-forwards", {
      method: "POST",
      body: JSON.stringify({
        targets: [{
          platformId: row.platformId,
          targetKind: row.targetKind,
          externalId: row.targetId,
          scopeId: row.targetScopeId || null,
          threadId: row.targetThreadId || null,
          accountId: row.targetAccountId || null,
        }],
        batches: Array.isArray(detail.message.batches) ? detail.message.batches : [],
      }),
    });
    notify(`已创建补发记录：${response.newDeliveryCount || 0} 条`, false);
    closeModal?.();
    await refreshDeliveries(null, false, { readControls: false });
  });
}

async function openIncomingDetail(traceId, button) {
  const fallback = (state.incomingRows || []).find(item => String(item.traceId) === String(traceId));
  if (!fallback) throw new Error("入站审计记录不存在");
  await withButtonLoading(button, "读取中...", async () => {
    const detail = await api(`/incoming-messages/${encodeURIComponent(traceId)}`);
    const row = detail.message || fallback;
    openModal("入站审计详情", renderIncomingDetail(row, detail.processing || [], detail.outboundDeliveries || []), null, {
      size: "wide",
      cancelText: "关闭",
    });
  });
}

function renderDeliveryDetail(row, message) {
  return `
    <div class="delivery-detail">
      <div class="plugin-detail-grid">
        ${detailItem("记录 ID", row.id)}
        ${detailItem("状态", label(row.status))}
        ${detailItem("消息 ID", row.messageId, true)}
        ${detailItem("来源动态", row.sourceUpdateKey || "-", true)}
        ${detailItem("来源插件", row.sourcePlugin || "-", true)}
        ${detailItem("渲染类型", row.renderVariant || "-")}
        ${detailItem("消息类型", row.messageKind ? label(row.messageKind) : "-")}
        ${detailItem("重要度", row.messageImportance ? label(row.messageImportance) : "-")}
        ${detailItem("可见性", row.messageVisibility ? label(row.messageVisibility) : "-")}
        ${detailItem("记录策略", row.messageRecordPolicy ? label(row.messageRecordPolicy) : "-")}
        ${detailItem("临时过期", row.transientExpiresAtEpochSeconds ? fmtTime(row.transientExpiresAtEpochSeconds) : "-")}
        ${detailItem("回复消息", row.replyToMessageId || "-", true)}
        ${detailItem("关联 ID", row.correlationId || "-", true)}
        ${detailItem("尝试次数", row.attempts)}
        ${detailItem("目标平台", row.platformId)}
        ${detailItem("目标类型", label(row.targetKind))}
        ${detailItem("目标名称", row.targetName || "-")}
        ${detailItem("目标 ID", row.targetId, true)}
        ${detailItem("目标作用域", row.targetScopeId || "-", true)}
        ${detailItem("目标线程", row.targetThreadId || "-", true)}
        ${detailItem("优先账号", row.targetAccountId || "-", true)}
        ${detailItem("目标地址", String(row.targetKey || "").replace(/\u001F/g, " / "), true)}
        ${detailItem("平台回执", row.sinkMessageId || "-", true)}
        ${detailItem("发送路由", row.sinkRouteId || "-", true)}
        ${detailItem("发送账号", row.sinkAccountId || "-", true)}
        ${detailItem("下次尝试", row.nextAttemptAtEpochSeconds ? fmtTime(row.nextAttemptAtEpochSeconds) : "-")}
        ${detailItem("锁定至", row.lockedUntilEpochSeconds ? fmtTime(row.lockedUntilEpochSeconds) : "-")}
        ${detailItem("创建时间", fmtTime(row.createdAtEpochSeconds))}
        ${detailItem("更新时间", fmtTime(row.updatedAtEpochSeconds))}
      </div>
      <div class="plugin-detail-section">
        <h3>错误信息</h3>
        <pre class="delivery-error-text">${esc(row.lastError || "无错误信息")}</pre>
      </div>
      <div class="plugin-detail-section">
        <h3>原始消息</h3>
        <pre class="delivery-json-text">${esc(prettyJson(message, "未找到原始消息，可能已被历史清理任务移除。"))}</pre>
      </div>
    </div>`;
}

function renderIncomingDetail(row, processing, outboundDeliveries) {
  return `
    <div class="delivery-detail incoming-detail">
      <div class="plugin-detail-grid">
        ${detailItem("Trace ID", row.traceId, true)}
        ${detailItem("来源插件", row.sourcePlugin || "-", true)}
        ${detailItem("平台", row.platformId)}
        ${detailItem("Bot 账号", row.botAccountId || "-", true)}
        ${detailItem("目标类型", label(row.targetKind))}
        ${detailItem("目标 ID", row.targetId, true)}
        ${detailItem("目标地址", String(row.targetKey || "").replace(/\u001F/g, " / "), true)}
        ${detailItem("发送者", row.senderId || "-", true)}
        ${detailItem("平台消息", row.platformMessageId || "-", true)}
        ${detailItem("回复目标", row.replyToMessageId || "-", true)}
        ${detailItem("事件 ID", row.sourceEventId || "-", true)}
        ${detailItem("去重 Key", row.dedupeKey || "-", true)}
        ${detailItem("意图", label(row.intent))}
        ${detailItem("记录策略", label(row.recordPolicy))}
        ${detailItem("保留秒数", row.retentionSeconds ?? "-")}
        ${detailItem("过期时间", row.expiresAtEpochSeconds ? fmtTime(row.expiresAtEpochSeconds) : "-")}
        ${detailItem("消息摘要", row.segmentSummary || "-")}
        ${detailItem("消息时间", row.messageTimestampEpochSeconds ? fmtTime(row.messageTimestampEpochSeconds) : "-")}
        ${detailItem("接收时间", fmtTime(row.receivedAtEpochSeconds))}
        ${detailItem("记录时间", fmtTime(row.createdAtEpochSeconds))}
        ${detailItem("原始格式", row.rawFormat || "-")}
        ${detailItem("原始大小", fmtBytes ? fmtBytes(row.rawPayloadSize || 0) : `${row.rawPayloadSize || 0} B`)}
        ${detailItem("原始 SHA-256", row.rawPayloadSha256 || "-", true)}
      </div>
      <div class="plugin-detail-section">
        <h3>文本预览</h3>
        <pre class="delivery-error-text">${esc(row.textPreview || "无文本预览")}</pre>
      </div>
      <div class="plugin-detail-section">
        <h3>处理轨迹</h3>
        ${incomingProcessingTimeline(processing)}
      </div>
      <div class="plugin-detail-section">
        <h3>关联出站投递</h3>
        ${incomingRelatedDeliveries(outboundDeliveries)}
      </div>
    </div>`;
}

function incomingProcessingTimeline(processing) {
  const rows = processing || [];
  if (!rows.length) return `<div class="empty message-empty">暂无处理记录</div>`;
  return `<div class="incoming-processing-list">${rows.map(item => `
    <div class="incoming-processing-item ${item.result === "FAILED" || item.result === "REJECTED" ? "bad" : ""}">
      <div class="incoming-processing-head">
        <span>${esc(label(item.stage))}</span>
        ${pill(item.result)}
      </div>
      <div class="incoming-processing-meta">
        <span>处理器 ${esc(item.handlerId || "-")}</span>
        ${item.commandPath ? `<span>命令 ${esc(item.commandPath)}</span>` : ""}
        ${item.role ? `<span>角色 ${esc(item.role)}</span>` : ""}
        ${item.durationMs !== null && item.durationMs !== undefined ? `<span>耗时 ${esc(item.durationMs)} ms</span>` : ""}
        <span>${esc(fmtTime(item.createdAtEpochSeconds))}</span>
      </div>
      ${item.errorMessage ? `<pre class="incoming-processing-error">${esc(item.errorMessage)}</pre>` : ""}
    </div>`).join("")}</div>`;
}

function incomingRelatedDeliveries(deliveries) {
  const rows = deliveries || [];
  if (!rows.length) return `<div class="empty message-empty">暂无关联出站投递</div>`;
  return renderTable(rows, [
    { title: "消息", render: row => messageCell(row) },
    { title: "目标", render: row => targetCell(row) },
    { title: "状态", render: row => deliveryStatusCell(row) },
    { title: "回执", render: row => routeReceiptCell(row) },
    { title: "更新时间", render: row => `<span class="sub-line message-time">${fmtTime(row.updatedAtEpochSeconds)}</span>` },
  ]).replace('class="table-wrap"', 'class="table-wrap entity-detail-table-wrap incoming-related-table-wrap"');
}
