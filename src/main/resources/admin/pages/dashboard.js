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
      <section class="dashboard-status-banner ${failedPlugins || failedDeliveries || loginFailed ? "warn" : "ok"}">
        <div class="dashboard-status-icon">${failedPlugins || failedDeliveries || loginFailed ? "⚠️" : "✓"}</div>
        <div class="dashboard-status-text">
          <h3>${failedPlugins || failedDeliveries || loginFailed ? "系统需要关注" : "系统运行正常"}</h3>
          <p>刷新于 ${esc(fmtTime(data.generatedAtEpochMillis, true))} · 运行时间 ${fmtDuration(data.system.uptimeMs)}</p>
        </div>
        ${failedPlugins || failedDeliveries || loginFailed ? `<div class="dashboard-status-details">
          ${failedPlugins ? `<span class="dashboard-status-badge bad">${failedPlugins} 个插件异常</span>` : ""}
          ${loginFailed ? `<span class="dashboard-status-badge warn">${loginFailed} 个账号未登录</span>` : ""}
          ${failedDeliveries ? `<span class="dashboard-status-badge bad">${failedDeliveries} 条消息失败</span>` : ""}
        </div>` : ""}
      </section>

      <div class="dashboard-metrics-row">
        ${metricCardNew("订阅关系", data.subscriptionCount, "subscription", "订阅管理", "subscriptions")}
        ${metricCardNew("发布者", data.publisherCount, "publisher", "内容源", "entities")}
        ${metricCardNew("消息目标", data.subscriberCount, "target", "推送目标", "entities")}
        ${metricCardNew("插件", `${activePlugins}/${totalPlugins}`, "plugin", `${failedPlugins ? failedPlugins + " 个异常" : "全部正常"}`, "plugins")}
        ${metricCardNew("内存", `${memoryPercent}%`, "memory", `${fmtBytes(data.system.usedMemoryBytes)} / ${fmtBytes(data.system.maxMemoryBytes)}`, "system", memoryPercent >= 80 ? "warn" : "")}
        ${metricCardNew("服务", data.system.webAdminPort, "port", data.system.webAdminHost, "system")}
      </div>

      <div class="dashboard-panels-grid">
        <section class="dashboard-panel">
          <div class="dashboard-panel-header">
            <div class="dashboard-panel-title">
              <span class="dashboard-panel-icon">🔌</span>
              <div>
                <h2>插件状态</h2>
                <p>${activePlugins}/${totalPlugins} 插件运行中${failedPlugins ? `，${failedPlugins} 个异常` : ""}</p>
              </div>
            </div>
            <button class="secondary compact" data-action="goto" data-page="plugins">管理插件</button>
          </div>
          <div class="dashboard-panel-body">
            ${renderPluginHealth(plugins)}
          </div>
        </section>

        <section class="dashboard-panel">
          <div class="dashboard-panel-header">
            <div class="dashboard-panel-title">
              <span class="dashboard-panel-icon">👤</span>
              <div>
                <h2>账号连接</h2>
                <p>${loginSuccess}/${logins.length} 源账号已登录${loginFailed ? `，${loginFailed} 个未登录` : ""}</p>
              </div>
            </div>
            <button class="secondary compact" data-action="goto" data-page="login">账号管理</button>
          </div>
          <div class="dashboard-panel-body">
            ${renderLoginHealth(logins)}
          </div>
        </section>

        <section class="dashboard-panel dashboard-panel-wide">
          <div class="dashboard-panel-header">
            <div class="dashboard-panel-title">
              <span class="dashboard-panel-icon">📨</span>
              <div>
                <h2>消息投递</h2>
                <p>${pendingDeliveries} 等待发送${failedDeliveries ? `，${failedDeliveries} 条失败` : ""}</p>
              </div>
            </div>
            <button class="secondary compact" data-action="goto" data-page="messages">查看消息</button>
          </div>
          <div class="dashboard-panel-body">
            ${renderDeliveryHealthNew(deliveryCounts, recentFailures)}
          </div>
        </section>

        <section class="dashboard-panel dashboard-panel-wide">
          <div class="dashboard-panel-header">
            <div class="dashboard-panel-title">
              <span class="dashboard-panel-icon">📋</span>
              <div>
                <h2>系统日志</h2>
                <p>最近的警告与错误记录</p>
              </div>
            </div>
            <button class="secondary compact" data-action="goto" data-page="logs">完整日志</button>
          </div>
          <div class="dashboard-panel-body">
            ${renderLogHealth(recentLogs)}
          </div>
        </section>
      </div>
    </section>`;
}

function countMap(rows) {
  return Object.fromEntries((rows || []).map(item => [item.state, item.count]));
}

function metricCardNew(title, value, icon, sub, page = "", tone = "") {
  const iconMap = {
    subscription: "📋",
    publisher: "📡",
    target: "📮",
    plugin: "🔌",
    memory: "💾",
    port: "🌐"
  };
  return `<button type="button" class="dashboard-metric-card-new ${attr(tone)}" data-action="goto" data-page="${attr(page)}">
    <div class="dashboard-metric-icon">${iconMap[icon] || "📊"}</div>
    <div class="dashboard-metric-content">
      <div class="dashboard-metric-label">${esc(title)}</div>
      <div class="dashboard-metric-value">${esc(value)}</div>
      <div class="dashboard-metric-sub">${esc(sub)}</div>
    </div>
  </button>`;
}

function renderPluginHealth(plugins) {
  if (!plugins.length) return `<div class="dashboard-empty">暂无插件</div>`;
  return `<div class="dashboard-card-list">${
    plugins.slice(0, 6).map(plugin => {
      const stateClass = plugin.state === "ACTIVE" ? "ok" : plugin.state === "FAILED" ? "bad" : "warn";
      const stateIcon = plugin.state === "ACTIVE" ? "✓" : plugin.state === "FAILED" ? "✗" : "◷";
      return `<div class="dashboard-card-item">
        <div class="dashboard-card-info">
          <div class="dashboard-card-title">${esc(plugin.name)}</div>
          <div class="dashboard-card-meta">
            <span class="dashboard-card-id">${esc(plugin.id)}</span>
            ${(plugin.capabilities || []).slice(0, 3).map(cap => `<span class="dashboard-tag">${esc(cap)}</span>`).join("")}
          </div>
        </div>
        <div class="dashboard-card-status ${stateClass}">
          <span class="dashboard-status-dot">${stateIcon}</span>
          <span>${esc(plugin.state)}</span>
        </div>
      </div>`;
    }).join("")
  }</div>`;
}

function renderLoginHealth(items) {
  if (!items.length) return `<div class="dashboard-empty">暂无源账号登录</div>`;
  return `<div class="dashboard-card-list">${
    items.map(item => {
      const statusClass = item.status === "SUCCESS" ? "ok" : "bad";
      const statusIcon = item.status === "SUCCESS" ? "✓" : "✗";
      return `<div class="dashboard-card-item">
        <div class="dashboard-card-info">
          <div class="dashboard-card-title">${platformTag(item.platformId, item.platformId)}</div>
          <div class="dashboard-card-meta">
            <span>${esc(item.account && item.account.name || item.message || "-")}</span>
          </div>
        </div>
        <div class="dashboard-card-status ${statusClass}">
          <span class="dashboard-status-dot">${statusIcon}</span>
          <span>${esc(item.status)}</span>
        </div>
      </div>`;
    }).join("")
  }</div>`;
}

function renderDeliveryHealthNew(counts, failures) {
  const pending = counts.PENDING || 0;
  const sending = counts.SENDING || 0;
  const sent = counts.SENT || 0;
  const failed = counts.FAILED || 0;

  return `<div class="dashboard-delivery-section">
    <div class="dashboard-delivery-stats">
      <div class="dashboard-delivery-stat ok">
        <div class="dashboard-delivery-stat-icon">✓</div>
        <div class="dashboard-delivery-stat-content">
          <div class="dashboard-delivery-stat-value">${sent}</div>
          <div class="dashboard-delivery-stat-label">已发送</div>
        </div>
      </div>
      <div class="dashboard-delivery-stat ${pending > 0 ? "warn" : "soft"}">
        <div class="dashboard-delivery-stat-icon">◷</div>
        <div class="dashboard-delivery-stat-content">
          <div class="dashboard-delivery-stat-value">${pending}</div>
          <div class="dashboard-delivery-stat-label">等待中</div>
        </div>
      </div>
      <div class="dashboard-delivery-stat info">
        <div class="dashboard-delivery-stat-icon">⟳</div>
        <div class="dashboard-delivery-stat-content">
          <div class="dashboard-delivery-stat-value">${sending}</div>
          <div class="dashboard-delivery-stat-label">发送中</div>
        </div>
      </div>
      <div class="dashboard-delivery-stat ${failed > 0 ? "bad" : "soft"}">
        <div class="dashboard-delivery-stat-icon">✗</div>
        <div class="dashboard-delivery-stat-content">
          <div class="dashboard-delivery-stat-value">${failed}</div>
          <div class="dashboard-delivery-stat-label">失败</div>
        </div>
      </div>
    </div>
    ${failures.length ? `<div class="dashboard-failures-list">
      <div class="dashboard-failures-title">最近失败的消息</div>
      ${failures.slice(0, 4).map(row => `<div class="dashboard-failure-item">
        <div class="dashboard-failure-icon">⚠️</div>
        <div class="dashboard-failure-content">
          <div class="dashboard-failure-target">${esc(String(row.targetKey || "").replace(//g, " / "))}</div>
          <div class="dashboard-failure-error">${esc(row.lastError || "-")}</div>
          <div class="dashboard-failure-id">消息 ID: ${esc(row.messageId)}</div>
        </div>
      </div>`).join("")}
    </div>` : ""}
  </div>`;
}

function renderLogHealth(rows) {
  if (!rows.length) return `<div class="dashboard-empty">暂无警告或错误日志</div>`;
  return `<div class="dashboard-card-list">${
    rows.slice(0, 6).map(row => {
      const levelClass = row.level === "ERROR" ? "bad" : "warn";
      const levelIcon = row.level === "ERROR" ? "✗" : "⚠";
      return `<div class="dashboard-card-item">
        <div class="dashboard-card-info">
          <div class="dashboard-card-title">${esc(row.message)}</div>
          <div class="dashboard-card-meta">
            <span>${esc(row.loggerName)}</span>
            <span>·</span>
            <span>${fmtTime(row.timestampEpochMillis, true)}</span>
          </div>
        </div>
        <div class="dashboard-card-status ${levelClass}">
          <span class="dashboard-status-dot">${levelIcon}</span>
          <span>${esc(row.level)}</span>
        </div>
      </div>`;
    }).join("")
  }</div>`;
}
