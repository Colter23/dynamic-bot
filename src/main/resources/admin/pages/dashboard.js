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
  } = ui);
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadDashboard(ctx.force);
}

export async function handleAction() {
  return false;
}

async function loadDashboard(force) {
  if (force || !state.cache.dashboard) state.cache.dashboard = await api("/dashboard");
  const data = state.cache.dashboard;
  const deliveryCounts = Object.fromEntries((data.deliveryStatusCounts || []).map(item => [item.state, item.count]));
  const pluginCounts = Object.fromEntries((data.pluginStateCounts || []).map(item => [item.state, item.count]));
  pageRoot().innerHTML = `
    <section class="page">
      <div class="stats">
        ${stat("插件", `${pluginCounts.ACTIVE || 0}/${pluginCounts.TOTAL || 0}`, "运行 / 总数")}
        ${stat("订阅", data.subscriptionCount, "订阅关系")}
        ${stat("发布者", data.publisherCount, "来源实体")}
        ${stat("消息目标", data.subscriberCount, "出口目标")}
        ${stat("待投递", deliveryCounts.PENDING || 0, "队列等待")}
        ${stat("失败投递", deliveryCounts.FAILED || 0, "需要处理")}
      </div>
      <div class="grid">
        <section class="panel half">
          <div class="panel-head"><h2>插件健康</h2><button class="secondary" data-action="goto" data-page="plugins">打开</button></div>
          ${renderPluginSummary(data.plugins || [])}
        </section>
        <section class="panel half">
          <div class="panel-head"><h2>平台登录</h2><button class="secondary" data-action="goto" data-page="login">打开</button></div>
          ${renderLoginSummary(data.platformLogins || [])}
        </section>
        <section class="panel half">
          <div class="panel-head"><h2>最近投递失败</h2><button class="secondary" data-action="goto" data-page="system">诊断</button></div>
          ${renderDeliveryCompact(data.recentDeliveries || [])}
        </section>
        <section class="panel half">
          <div class="panel-head"><h2>最近日志</h2><button class="secondary" data-action="goto" data-page="logs">打开</button></div>
          ${renderLogCompact(data.recentLogs || [])}
        </section>
        <section class="panel full">
          <div class="panel-head"><h2>系统</h2><button class="secondary" data-action="goto" data-page="system">打开</button></div>
          <div class="stats">
            ${stat("运行时间", fmtDuration(data.system.uptimeMs), "自 " + fmtTime(data.system.startedAtEpochMillis, true))}
            ${stat("内存", fmtBytes(data.system.usedMemoryBytes), "已用 / " + fmtBytes(data.system.maxMemoryBytes))}
            ${stat("后台端口", data.system.webAdminPort, data.system.webAdminHost)}
          </div>
        </section>
      </div>
    </section>`;
}

function stat(title, value, sub) {
  return `<div class="stat"><b>${esc(value)}</b><span>${esc(title)} · ${esc(sub || "")}</span></div>`;
}

function renderPluginSummary(plugins) {
  return renderTable(plugins.slice(0, 6), [
    { title: "插件", render: p => cell(p.name, p.id) },
    { title: "状态", render: p => pill(p.state) },
    { title: "能力", render: p => tags((p.capabilities || []).slice(0, 4)) }
  ]);
}

function renderLoginSummary(items) {
  return renderTable(items, [
    { title: "平台", render: item => cell(item.platformId, item.pluginName) },
    { title: "状态", render: item => pill(item.status) },
    { title: "账号", render: item => cell(item.account && item.account.name || "-", item.message) }
  ]);
}

function renderDeliveryCompact(rows) {
  return rows.length ? renderTable(rows, [
    { title: "消息", render: row => cell(row.messageId, row.targetKey.replace(/\u001F/g, " / ")) },
    { title: "状态", render: row => pill(row.status) },
    { title: "错误", render: row => `<span class="sub-line">${esc(row.lastError || "-")}</span>` }
  ]) : `<div class="empty">暂无失败投递</div>`;
}

function renderLogCompact(rows) {
  return rows.length ? renderTable(rows, [
    { title: "时间", render: row => `<span class="sub-line">${fmtTime(row.timestampEpochMillis, true)}</span>` },
    { title: "级别", render: row => pill(row.level) },
    { title: "内容", render: row => cell(row.message, row.loggerName) }
  ]) : `<div class="empty">暂无日志</div>`;
}