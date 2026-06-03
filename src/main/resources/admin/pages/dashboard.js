let ctx;
let root;
let api;
let state;
let esc;
let attr;
let fmtTime;
let fmtBytes;
let fmtDuration;
let label;
let pill;
let tags;
let cell;
let platformTag;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  ({ esc, attr, fmtTime, fmtBytes, fmtDuration, label, pill, tags, cell, platformTag } = ctx.ui);
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadDashboard(ctx.force);
}

export function unmount() {
  invalidatePageRequests("dashboard");
}

export async function handleAction() {
  return false;
}

async function loadDashboard(force) {
  const request = beginPageRequest("dashboard");
  if (force || !state.cache.dashboard) state.cache.dashboard = await api("/dashboard");
  if (!isCurrentPageRequest(request)) return;
  const data = state.cache.dashboard;
  const deliveryCounts = countMap(data.deliveryStatusCounts);
  const pluginCounts = countMap(data.pluginStateCounts);
  const plugins = data.plugins || [];
  const logins = data.platformLogins || [];
  const recentFailures = data.recentDeliveries || [];
  const recentLogs = data.recentLogs || [];
  const memoryPercent = Math.max(0, Math.min(100, Math.round((Number(data.system.usedMemoryBytes || 0) / Math.max(Number(data.system.maxMemoryBytes || 1), 1)) * 100)));
  const startedAt = fmtTime(data.system.startedAtEpochMillis, true);
  const activePlugins = pluginCounts.ACTIVE || plugins.filter(item => item.state === "ACTIVE").length;
  const totalPlugins = pluginCounts.TOTAL || plugins.length;
  const failedPlugins = pluginCounts.FAILED || plugins.filter(item => item.state === "FAILED").length;
  const loginSuccess = logins.filter(item => item.status === "SUCCESS").length;
  const loginFailed = logins.filter(item => item.status !== "SUCCESS").length;
  const pendingDeliveries = deliveryCounts.PENDING || 0;
  const failedDeliveries = deliveryCounts.FAILED || 0;

  pageRoot().innerHTML = `
    <section class="page dashboard-page">
      <section class="panel full dashboard-hero">
        <div class="dashboard-hero-copy">
          <span class="dashboard-kicker">仪表盘</span>
          <h2>运行概况</h2>
          <div class="dashboard-hero-meta">
            <span class="pill info">刷新于 ${esc(fmtTime(data.generatedAtEpochMillis, true))}</span>
            <span class="pill ${failedPlugins || failedDeliveries || loginFailed ? "warn" : "ok"}">${failedPlugins || failedDeliveries || loginFailed ? "需要关注" : "状态平稳"}</span>
          </div>
        </div>
        <div class="dashboard-summary-grid">
          ${metricCard("订阅关系", data.subscriptionCount, "订阅管理", "accent", "subscriptions")}
          ${metricCard("发布者", data.publisherCount, "发布者与目标", "blue", "entities")}
          ${metricCard("消息目标", data.subscriberCount, "发布者与目标", "green", "entities")}
          ${metricCard("运行时间", fmtDuration(data.system.uptimeMs), "启动于 " + startedAt, "soft", "system")}
          ${metricCard("堆内存", `${memoryPercent}%`, `${fmtBytes(data.system.usedMemoryBytes)} / ${fmtBytes(data.system.maxMemoryBytes)}`, memoryPercent >= 80 ? "amber" : "soft", "system")}
          ${metricCard("后台端口", data.system.webAdminPort, data.system.webAdminHost, "soft", "system")}
        </div>
      </section>

      <div class="grid dashboard-grid">
        <section class="panel half dashboard-panel">
          <div class="panel-head dashboard-panel-head">
            <div>
              <h2>插件健康</h2>
              <p>${activePlugins}/${totalPlugins} 运行中</p>
            </div>
            <button class="secondary compact" data-action="goto" data-page="plugins">插件管理</button>
          </div>
          ${renderPluginHealth(plugins)}
        </section>

        <section class="panel half dashboard-panel">
          <div class="panel-head dashboard-panel-head">
            <div>
              <h2>平台登录</h2>
              <p>${loginSuccess}/${logins.length} 已登录</p>
            </div>
            <button class="secondary compact" data-action="goto" data-page="login">平台登录</button>
          </div>
          ${renderLoginHealth(logins)}
        </section>

        <section class="panel half dashboard-panel">
          <div class="panel-head dashboard-panel-head">
            <div>
              <h2>消息记录</h2>
              <p>${pendingDeliveries} 等待，${failedDeliveries} 失败</p>
            </div>
            <button class="secondary compact" data-action="goto" data-page="messages">消息记录</button>
          </div>
          ${renderDeliveryHealth(deliveryCounts, recentFailures)}
        </section>

        <section class="panel half dashboard-panel">
          <div class="panel-head dashboard-panel-head">
            <div>
              <h2>最近日志</h2>
              <p>警告与错误</p>
            </div>
            <button class="secondary compact" data-action="goto" data-page="logs">日志查看</button>
          </div>
          ${renderLogHealth(recentLogs)}
        </section>
      </div>
    </section>`;
}

function countMap(rows) {
  return Object.fromEntries((rows || []).map(item => [item.state, item.count]));
}

function metricCard(title, value, sub, tone = "", page = "") {
  return `<button type="button" class="dashboard-metric-card ${attr(tone)}" data-action="goto" data-page="${attr(page)}">
    <span>${esc(title)}</span>
    <b>${esc(value)}</b>
    <small>${esc(sub)}</small>
  </button>`;
}

function renderPluginHealth(plugins) {
  if (!plugins.length) return `<div class="empty dashboard-empty">暂无插件</div>`;
  return `<div class="dashboard-list">${
    plugins.slice(0, 6).map(plugin => `<div class="dashboard-list-item">
      <div>
        ${cell(plugin.name, plugin.id)}
        ${tags((plugin.capabilities || []).slice(0, 3))}
      </div>
      <div class="dashboard-list-side">${pill(plugin.state)}</div>
    </div>`).join("")
  }</div>`;
}

function renderLoginHealth(items) {
  if (!items.length) return `<div class="empty dashboard-empty">暂无平台登录</div>`;
  return `<div class="dashboard-list">${
    items.map(item => `<div class="dashboard-list-item">
      <div>
        <span class="primary-line">${platformTag(item.platformId, item.platformId)}</span>
        <span class="sub-line">${esc(item.account && item.account.name || item.message || "-")}</span>
      </div>
      <div class="dashboard-list-side">${pill(item.status)}</div>
    </div>`).join("")
  }</div>`;
}

function renderDeliveryHealth(counts, failures) {
  return `<div class="dashboard-stack">
    <div class="dashboard-queue-grid">
      ${queueCard("等待", counts.PENDING || 0, "warn")}
      ${queueCard("发送中", counts.SENDING || 0, "info")}
      ${queueCard("已发送", counts.SENT || 0, "ok")}
      ${queueCard("失败", counts.FAILED || 0, (counts.FAILED || 0) ? "bad" : "ok")}
    </div>
    ${renderFailureList(failures)}
  </div>`;
}

function queueCard(title, value, tone = "") {
  return `<div class="dashboard-queue-card ${attr(tone)}">
    <span>${esc(title)}</span>
    <b>${esc(value)}</b>
  </div>`;
}

function renderFailureList(rows) {
  if (!rows.length) return `<div class="empty dashboard-empty compact">暂无失败投递</div>`;
  return `<div class="dashboard-list compact">${
    rows.slice(0, 4).map(row => `<div class="dashboard-list-item block">
      ${cell(row.messageId, String(row.targetKey || "").replace(/\u001F/g, " / "))}
      <span class="dashboard-error-line">${esc(row.lastError || "-")}</span>
    </div>`).join("")
  }</div>`;
}

function renderLogHealth(rows) {
  if (!rows.length) return `<div class="empty dashboard-empty">暂无警告或错误日志</div>`;
  return `<div class="dashboard-list">${
    rows.slice(0, 6).map(row => `<div class="dashboard-list-item">
      <div>
        ${cell(row.message, `${row.loggerName} · ${fmtTime(row.timestampEpochMillis, true)}`)}
      </div>
      <div class="dashboard-list-side">${pill(row.level)}</div>
    </div>`).join("")
  }</div>`;
}
