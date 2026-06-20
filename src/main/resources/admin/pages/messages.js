const DELIVERY_LIMIT = 160;
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

let ctx;
let root;
let api;
let state;
let query;
let handleError;
let esc;
let attr;
let fmtTime;
let label;
let pill;
let cell;
let detailItem;
let prettyJson;
let renderTable;
let notify;
let openModal;
let platformTag;
let withButtonLoading;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;

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
    label,
    pill,
    cell,
    detailItem,
    prettyJson,
    renderTable,
    notify,
    openModal,
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

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadMessagesPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
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
    notify("消息记录已刷新", false);
    return true;
  }
  if (action === "delivery-detail") {
    await openDeliveryDetail(id, button);
    return true;
  }
  return false;
}

async function loadMessagesPage(force) {
  document.getElementById("content")?.classList.add("content-messages");
  pageRoot()?.classList.add("messages-page-host");
  renderLayout();
  bindDeliveryControls();
  if (force || !state.deliveryRows) {
    await refreshDeliveries(null, true);
  } else {
    renderDeliveries();
    renderDeliveryStatus();
  }
}

function renderLayout() {
  const filters = deliveryFilters();
  pageRoot().innerHTML = `
    <section class="page messages-page">
      <section class="panel full message-panel">
        <div class="panel-head message-panel-head">
          <div>
            <h2>消息记录</h2>
            <p>查看最近投递结果，定位失败目标、重试状态和平台回执。</p>
          </div>
          <div id="deliveryStatus" class="message-record-status"></div>
        </div>
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
      </section>
    </section>`;
  renderDeliveryStatus();
  updateDeliveryFilterButtons();
}

function deliveryStatusOptions(selected) {
  return [
    ["", "全部状态"],
    ["FAILED", "失败"],
    ["PENDING", "等待"],
    ["SENDING", "发送中"],
    ["SENT", "已发送"],
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
    ["COMMAND_RESULT", "命令回复"],
    ["PROGRESS", "进度提示"],
    ["SYSTEM_NOTIFICATION", "系统通知"],
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

function limitOptions(selected) {
  return [50, 100, 160, 200].map(value =>
    `<option value="${value}"${String(value) === String(selected) ? " selected" : ""}>最近 ${value} 条</option>`
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

async function refreshDeliveries(button, force) {
  const request = beginPageRequest("messages");
  readDeliveryFilterControls();
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
    target.innerHTML = `<div class="empty message-empty">暂无消息记录</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "消息", render: row => messageCell(row) },
    { title: "目标", render: row => targetCell(row) },
    { title: "分类", render: row => messagePolicyCell(row) },
    { title: "投递状态", render: row => deliveryStatusCell(row) },
    { title: "路由回执", render: row => routeReceiptCell(row) },
    { title: "更新时间", render: row => `<span class="sub-line message-time">${fmtTime(row.updatedAtEpochSeconds)}</span>` },
    { title: "操作", render: row => `<button type="button" class="message-action-button" data-action="delivery-detail" data-id="${attr(row.id)}">详情</button>` },
  ])
    .replace('class="table-wrap"', 'class="table-wrap messages-table-wrap"')
    .replace("<table>", '<table class="messages-table">');
}

function messageCell(row) {
  const title = compactValue(row.messagePreview || "无文本内容", 76);
  return `
    <div class="message-preview-cell">
      <span class="primary-line message-preview-title">${esc(title)}</span>
      <span class="sub-line">${esc(messageSubLine(row))}</span>
    </div>`;
}

function messageSubLine(row) {
  const parts = [`ID ${compactValue(row.messageId, 28)}`];
  if (row.sourceUpdateKey) parts.push(`来源 ${compactValue(row.sourceUpdateKey, 34)}`);
  if (row.sourcePlugin) parts.push(`插件 ${row.sourcePlugin}`);
  if (row.renderVariant) parts.push(`渲染 ${row.renderVariant}`);
  if (row.replyToMessageId) parts.push(`回复 ${compactValue(row.replyToMessageId, 22)}`);
  if (row.correlationId) parts.push(`关联 ${compactValue(row.correlationId, 24)}`);
  return parts.join(" · ");
}

function targetCell(row) {
  const title = `${label(row.targetKind)} ${row.targetId}`;
  const suffix = targetSubLine(row);
  return `
    <div class="message-target-cell">
      <span class="message-target-title">${platformTag(row.platformId, row.platformId)}<span class="primary-line">${esc(title)}</span></span>
      <span class="sub-line">${esc(suffix || "-")}</span>
    </div>`;
}

function messagePolicyCell(row) {
  const tags = [
    messageMetaTag(row.messageKind || "NORMAL", ""),
    row.messageImportance && row.messageImportance !== "NORMAL" ? messageMetaTag(row.messageImportance, "") : "",
    row.messageVisibility && row.messageVisibility !== "DEFAULT" ? messageMetaTag(row.messageVisibility, "") : "",
    row.messageRecordPolicy && row.messageRecordPolicy !== "DURABLE" ? messageMetaTag(row.messageRecordPolicy, "") : "",
  ].filter(Boolean).join("");
  const expires = row.messageRecordPolicy === "TRANSIENT" && row.transientExpiresAtEpochSeconds
    ? `<span class="sub-line">保留至 ${fmtTime(row.transientExpiresAtEpochSeconds)}</span>`
    : `<span class="sub-line">${row.messageRecordPolicy === "DURABLE" ? "持久记录" : "-"}</span>`;
  return `<div class="message-policy-cell">${tags || messageMetaTag("NORMAL", "")}${expires}</div>`;
}

function messageMetaTag(value, normalValue) {
  if (!value) return "";
  const cls = value === normalValue ? "" : ` ${messageMetaClass(value)}`;
  return `<span class="message-meta-tag${cls}">${esc(label(value))}</span>`;
}

function messageMetaClass(value) {
  if (["HIGH", "FAILED", "HIDDEN"].includes(value)) return "bad";
  if (["LOW", "INTERNAL", "TRANSIENT", "PROGRESS"].includes(value)) return "soft";
  if (["COMMAND_RESULT", "SYSTEM_NOTIFICATION"].includes(value)) return "info";
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
  const title = row.sinkMessageId ? `回执 ${compactValue(row.sinkMessageId, 32)}` : "无平台回执";
  const parts = [];
  if (row.sinkRouteId) parts.push(`路由 ${row.sinkRouteId}`);
  if (row.sinkAccountId) parts.push(`账号 ${row.sinkAccountId}`);
  if (!row.sinkRouteId && !row.sinkAccountId && row.targetAccountId) parts.push(`目标账号 ${row.targetAccountId}`);
  return cell(title, parts.join(" · ") || resultText(row));
}

function targetSubLine(row) {
  const parts = [];
  if (row.targetScopeId) parts.push(`作用域 ${compactValue(row.targetScopeId, 24)}`);
  if (row.targetThreadId) parts.push(`线程 ${compactValue(row.targetThreadId, 24)}`);
  if (row.targetAccountId) parts.push(`目标账号 ${compactValue(row.targetAccountId, 24)}`);
  return parts.join(" · ");
}

function statusHint(row) {
  if (row.status === "FAILED") return row.lastError ? "有失败原因" : "无失败详情";
  if (row.status === "SENT") return row.sinkMessageId ? "已有平台回执" : "已完成";
  if (row.status === "PARTIALLY_SENT") return row.lastError ? "部分成功，有失败原因" : "部分成功";
  if (row.status === "SENDING") return row.lockedUntilEpochSeconds ? `锁定至 ${fmtTime(row.lockedUntilEpochSeconds)}` : "正在发送";
  if (row.status === "PENDING") return row.nextAttemptAtEpochSeconds ? `下次 ${fmtTime(row.nextAttemptAtEpochSeconds)}` : "等待调度";
  return "-";
}

function resultText(row) {
  if (row.status === "FAILED") return row.lastError || "投递失败";
  if (row.status === "SENT") return row.sinkMessageId ? `回执：${row.sinkMessageId}` : "已发送";
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
  const pending = counts.PENDING || 0;
  const sending = counts.SENDING || 0;
  const partial = counts.PARTIALLY_SENT || 0;
  const internal = rows.filter(row => row.messageVisibility === "INTERNAL" || row.messageRecordPolicy === "TRANSIENT").length;
  summary.textContent = `显示 ${rows.length} 条 · 失败 ${failed} · 部分 ${partial} · 等待 ${pending} · 发送中 ${sending} · 内部/临时 ${internal}`;
}

function updateDeliveryFilterButtons() {
  const clearButton = pageQuery(".delivery-clear-filter-button");
  if (clearButton) clearButton.disabled = !deliveryFilterActiveFromControls();
}

function renderDeliveryStatus() {
  const target = pageQuery("#deliveryStatus");
  if (!target) return;
  const rows = state.deliveryRows || [];
  const latest = rows[0];
  target.innerHTML = `
    ${state.deliveriesLoading ? '<span class="loading-spinner" aria-hidden="true"></span>' : ""}
    <span class="pill ${state.deliveriesLoading ? "warn" : "info"}">${state.deliveriesLoading ? "正在读取" : `最近 ${rows.length} 条`}</span>
    <span>${latest ? `最新更新 ${fmtTime(latest.updatedAtEpochSeconds)}` : "暂无记录"}</span>`;
}

async function openDeliveryDetail(id, button) {
  const fallback = (state.deliveryRows || []).find(item => String(item.id) === String(id));
  if (!fallback) throw new Error("消息记录不存在");
  await withButtonLoading(button, "读取中...", async () => {
    const detail = await api(`/deliveries/${encodeURIComponent(id)}`);
    const row = detail.delivery || fallback;
    openModal("消息记录详情", renderDeliveryDetail(row, detail.message), null, {
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
