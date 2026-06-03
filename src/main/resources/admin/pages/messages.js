const DELIVERY_LIMIT = 160;

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
let renderTable;
let notify;
let openModal;
let platformTag;

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
    renderTable,
    notify,
    openModal,
    platformTag,
  } = ctx.ui);
}

function pageRoot() {
  return root;
}

function pageQuery(selector) {
  return pageRoot().querySelector(selector);
}

function deliveryFilters() {
  if (!state.deliveryFilters) {
    state.deliveryFilters = {
      status: "",
      platformId: "",
      targetKind: "",
      q: "",
      limit: String(DELIVERY_LIMIT),
    };
  }
  return state.deliveryFilters;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadMessagesPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
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
    openDeliveryDetail(id);
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
          <span class="entity-filter-title">筛选</span>
          <div class="entity-filter-controls">
            <select id="deliveryFilterStatus" data-delivery-filter="status">
              ${deliveryStatusOptions(filters.status)}
            </select>
            <input id="deliveryFilterPlatform" data-delivery-filter="platformId" value="${attr(filters.platformId)}" placeholder="平台 ID">
            <select id="deliveryFilterTargetKind" data-delivery-filter="targetKind">
              ${targetKindOptions(filters.targetKind)}
            </select>
            <input id="deliveryFilterKeyword" data-delivery-filter="q" value="${attr(filters.q)}" placeholder="消息 / 目标 / 错误">
            <select id="deliveryFilterLimit" data-delivery-filter="limit">
              ${limitOptions(filters.limit)}
            </select>
            <button type="button" class="entity-filter-clear" data-action="apply-delivery-filter">筛选</button>
            <button type="button" class="choice-clear-button compact" data-action="reset-delivery-filter">清除</button>
            <button type="button" class="choice-refresh-button compact" data-action="refresh-deliveries">刷新</button>
          </div>
          <span id="deliveryFilterSummary" class="entity-filter-summary"></span>
        </div>
        <div class="message-table-region">
          <div id="deliveriesTable" class="messages-table-host"></div>
        </div>
      </section>
    </section>`;
  renderDeliveryStatus();
}

function deliveryStatusOptions(selected) {
  return [
    ["", "全部状态"],
    ["FAILED", "失败"],
    ["PENDING", "等待"],
    ["SENDING", "发送中"],
    ["SENT", "已发送"],
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
  });
  const status = pageQuery("#deliveryFilterStatus");
  const targetKind = pageQuery("#deliveryFilterTargetKind");
  const limit = pageQuery("#deliveryFilterLimit");
  if (status) status.onchange = () => applyDeliveryFilter().catch(handleError);
  if (targetKind) targetKind.onchange = () => applyDeliveryFilter().catch(handleError);
  if (limit) limit.onchange = () => applyDeliveryFilter().catch(handleError);
}

async function applyDeliveryFilter(button) {
  readDeliveryFilterControls();
  await refreshDeliveries(button, true);
}

function readDeliveryFilterControls() {
  const filters = deliveryFilters();
  filters.status = pageQuery("#deliveryFilterStatus")?.value.trim() || "";
  filters.platformId = pageQuery("#deliveryFilterPlatform")?.value.trim() || "";
  filters.targetKind = pageQuery("#deliveryFilterTargetKind")?.value.trim() || "";
  filters.q = pageQuery("#deliveryFilterKeyword")?.value.trim() || "";
  filters.limit = pageQuery("#deliveryFilterLimit")?.value.trim() || String(DELIVERY_LIMIT);
}

function resetDeliveryFilterControls() {
  const filters = deliveryFilters();
  filters.status = "";
  filters.platformId = "";
  filters.targetKind = "";
  filters.q = "";
  filters.limit = String(DELIVERY_LIMIT);
  if (pageQuery("#deliveryFilterStatus")) pageQuery("#deliveryFilterStatus").value = "";
  if (pageQuery("#deliveryFilterPlatform")) pageQuery("#deliveryFilterPlatform").value = "";
  if (pageQuery("#deliveryFilterTargetKind")) pageQuery("#deliveryFilterTargetKind").value = "";
  if (pageQuery("#deliveryFilterKeyword")) pageQuery("#deliveryFilterKeyword").value = "";
  if (pageQuery("#deliveryFilterLimit")) pageQuery("#deliveryFilterLimit").value = String(DELIVERY_LIMIT);
}

async function refreshDeliveries(button, force) {
  readDeliveryFilterControls();
  const filters = deliveryFilters();
  const originalText = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = "加载中...";
  }
  state.deliveriesLoading = true;
  renderDeliveryStatus();
  try {
    const rows = await api("/deliveries" + query({
      status: filters.status,
      platformId: filters.platformId,
      targetKind: filters.targetKind,
      q: filters.q,
      limit: filters.limit,
    }));
    state.deliveryRows = rows || [];
    state.cache.deliveries = state.deliveryRows;
    renderDeliveries();
  } finally {
    state.deliveriesLoading = false;
    renderDeliveryStatus();
    if (button?.isConnected) {
      button.disabled = false;
      if (originalText) button.textContent = originalText;
    }
  }
}

function renderDeliveries() {
  const target = pageQuery("#deliveriesTable");
  if (!target) return;
  const rows = state.deliveryRows || [];
  renderDeliverySummary(rows);
  if (rows.length === 0) {
    target.innerHTML = `<div class="empty message-empty">暂无消息记录</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "消息", render: row => cell(row.messageId, messageSubLine(row)) },
    { title: "平台", render: row => platformTag(row.platformId, row.platformId) },
    { title: "目标", render: row => cell(`${label(row.targetKind)} ${row.targetId}`, targetSubLine(row)) },
    { title: "状态", render: row => `<div class="message-status-cell">${pill(row.status)}<span class="sub-line">${esc(statusHint(row))}</span></div>` },
    { title: "次数", render: row => `<span class="message-attempts">${Number(row.attempts || 0)}</span>` },
    { title: "结果", render: row => `<span class="message-result ${row.status === "FAILED" ? "bad" : ""}">${esc(resultText(row))}</span>` },
    { title: "更新时间", render: row => `<span class="sub-line message-time">${fmtTime(row.updatedAtEpochSeconds)}</span>` },
    { title: "操作", render: row => `<button type="button" class="message-action-button" data-action="delivery-detail" data-id="${attr(row.id)}">详情</button>` },
  ])
    .replace('class="table-wrap"', 'class="table-wrap messages-table-wrap"')
    .replace("<table>", '<table class="messages-table">');
}

function messageSubLine(row) {
  const parts = [];
  if (row.sourceUpdateKey) parts.push(`来源 ${compactValue(row.sourceUpdateKey, 46)}`);
  if (row.renderVariant) parts.push(`渲染 ${row.renderVariant}`);
  return parts.join(" · ");
}

function targetSubLine(row) {
  const normalized = String(row.targetKey || "").replace(/\u001F/g, " / ");
  return normalized && normalized !== `${row.platformId} / ${row.targetKind} / ${row.targetId}`
    ? compactValue(normalized, 72)
    : "";
}

function statusHint(row) {
  if (row.status === "FAILED") return row.lastError ? "有失败原因" : "无失败详情";
  if (row.status === "SENT") return row.sinkMessageId ? "已有平台回执" : "已完成";
  if (row.status === "SENDING") return row.lockedUntilEpochSeconds ? `锁定至 ${fmtTime(row.lockedUntilEpochSeconds)}` : "正在发送";
  if (row.status === "PENDING") return row.nextAttemptAtEpochSeconds ? `下次 ${fmtTime(row.nextAttemptAtEpochSeconds)}` : "等待调度";
  return "-";
}

function resultText(row) {
  if (row.status === "FAILED") return row.lastError || "投递失败";
  if (row.status === "SENT") return row.sinkMessageId ? `回执：${row.sinkMessageId}` : "已发送";
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
  summary.textContent = `显示 ${rows.length} 条 · 失败 ${failed} · 等待 ${pending}`;
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

function openDeliveryDetail(id) {
  const row = (state.deliveryRows || []).find(item => String(item.id) === String(id));
  if (!row) throw new Error("消息记录不存在");
  openModal("消息记录详情", `
    <div class="delivery-detail">
      <div class="plugin-detail-grid">
        ${detailItem("记录 ID", row.id)}
        ${detailItem("状态", label(row.status))}
        ${detailItem("消息 ID", row.messageId, true)}
        ${detailItem("来源动态", row.sourceUpdateKey || "-", true)}
        ${detailItem("渲染类型", row.renderVariant || "-")}
        ${detailItem("尝试次数", row.attempts)}
        ${detailItem("目标平台", row.platformId)}
        ${detailItem("目标类型", label(row.targetKind))}
        ${detailItem("目标 ID", row.targetId, true)}
        ${detailItem("目标地址", String(row.targetKey || "").replace(/\u001F/g, " / "), true)}
        ${detailItem("平台回执", row.sinkMessageId || "-", true)}
        ${detailItem("下次尝试", row.nextAttemptAtEpochSeconds ? fmtTime(row.nextAttemptAtEpochSeconds) : "-")}
        ${detailItem("锁定至", row.lockedUntilEpochSeconds ? fmtTime(row.lockedUntilEpochSeconds) : "-")}
        ${detailItem("创建时间", fmtTime(row.createdAtEpochSeconds))}
        ${detailItem("更新时间", fmtTime(row.updatedAtEpochSeconds))}
      </div>
      <div class="plugin-detail-section">
        <h3>错误信息</h3>
        <pre class="delivery-error-text">${esc(row.lastError || "无错误信息")}</pre>
      </div>
    </div>`, null, {
    size: "medium",
    cancelText: "关闭",
  });
}

function detailItem(title, value, mono = false) {
  return `<div class="plugin-detail-item">
    <span>${esc(title)}</span>
    <strong class="${mono ? "mono" : ""}">${esc(value ?? "-")}</strong>
  </div>`;
}
