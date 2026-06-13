let ctx;
let root;
let api;
let state;
let esc;
let attr;
let fmtTime;
let fmtBytes;
let fmtDuration;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  ({ esc, attr, fmtTime, fmtBytes, fmtDuration } = ctx.ui);
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
  const system = data.system || {};
  const deliveryCounts = countMap(data.deliveryStatusCounts);
  const pluginCounts = countMap(data.pluginStateCounts);
  const plugins = data.plugins || [];
  const logins = data.platformLogins || [];
  const recentFailures = data.recentDeliveries || [];
  const recentLogs = data.recentLogs || [];

  const activePlugins = pluginCounts.ACTIVE || plugins.filter(item => item.state === "ACTIVE").length;
  const totalPlugins = pluginCounts.TOTAL || plugins.length;
  const failedPlugins = pluginCounts.FAILED || plugins.filter(item => item.state === "FAILED").length;
  const loginSuccess = logins.filter(item => item.status === "SUCCESS").length;
  const loginFailed = logins.filter(item => item.status !== "SUCCESS").length;
  const pendingDeliveries = deliveryCounts.PENDING || 0;
  const sendingDeliveries = deliveryCounts.SENDING || 0;
  const sentDeliveries = deliveryCounts.SENT || 0;
  const failedDeliveries = deliveryCounts.FAILED || 0;
  const memoryPercent = memoryUsagePercent(system);
  const hasIssues = failedPlugins || loginFailed || failedDeliveries;

  pageRoot().innerHTML = `
    <section class="page dashboard-page dashboard-clean-page">
      <section class="panel dashboard-clean-hero ${hasIssues ? "warn" : "ok"}">
        <div class="dashboard-clean-hero-main">
          <span class="dashboard-kicker">运行总览</span>
          <h2>${hasIssues ? "系统有项目需要处理" : "系统运行正常"}</h2>
          <p>刷新于 ${esc(fmtTime(data.generatedAtEpochMillis, true))}，已运行 ${esc(fmtDuration(system.uptimeMs))}。</p>
          <div class="dashboard-clean-actions">
            <button type="button" class="about-action primary" data-action="refresh-current">刷新仪表盘</button>
            <button type="button" class="about-action secondary" data-action="goto" data-page="system">系统维护</button>
          </div>
        </div>
        <div class="dashboard-clean-metrics">
          ${dashboardMetric("订阅关系", data.subscriptionCount, "当前订阅配置")}
          ${dashboardMetric("发布者", data.publisherCount, "已记录内容源")}
          ${dashboardMetric("消息目标", data.subscriberCount, "可投递目标")}
          ${dashboardMetric("堆内存", `${memoryPercent}%`, `${fmtBytes(system.usedMemoryBytes)} / ${fmtBytes(system.maxMemoryBytes)}`, memoryPercent >= 80 ? "warn" : "")}
        </div>
      </section>

      <section class="dashboard-clean-grid">
        ${summaryCard("插件状态", `${activePlugins}/${totalPlugins} 运行中`, failedPlugins ? `${failedPlugins} 个异常` : "全部正常", "plugins", [
          infoRow("已加载", `${totalPlugins} 个插件`),
          infoRow("运行中", `${activePlugins} 个`, activePlugins === totalPlugins ? "ok" : ""),
          infoRow("异常", `${failedPlugins} 个`, failedPlugins ? "bad" : "ok"),
        ], renderPluginSummary(plugins))}

        ${summaryCard("账号连接", `${loginSuccess}/${logins.length || 0} 已登录`, loginFailed ? `${loginFailed} 个未登录` : "状态正常", "login", [
          infoRow("来源账号", `${logins.length || 0} 个`),
          infoRow("已登录", `${loginSuccess} 个`, loginSuccess === logins.length ? "ok" : ""),
          infoRow("需处理", `${loginFailed} 个`, loginFailed ? "bad" : "ok"),
        ], renderLoginSummary(logins))}

        ${summaryCard("消息投递", `${pendingDeliveries} 等待发送`, failedDeliveries ? `${failedDeliveries} 条失败` : "队列正常", "messages", [
          infoRow("已发送", sentDeliveries, "ok"),
          infoRow("发送中", sendingDeliveries),
          infoRow("等待中", pendingDeliveries, pendingDeliveries ? "warn" : ""),
          infoRow("失败", failedDeliveries, failedDeliveries ? "bad" : "ok"),
        ], renderFailureSummary(recentFailures))}

        ${summaryCard("系统环境", esc(fmtDuration(system.uptimeMs)), `启动于 ${fmtTime(system.startedAtEpochMillis, true)}`, "system", [
          infoRow("后台地址", `${system.webAdminHost}:${system.webAdminPort}`),
          infoRow("堆内存", `${memoryPercent}%`, memoryPercent >= 80 ? "warn" : "ok"),
          infoRow("Java", system.javaVersion || "-"),
          infoRow("系统", system.osName || "-"),
        ])}

        ${recentCard(recentLogs, recentFailures)}
      </section>
    </section>`;
}

function countMap(rows) {
  return Object.fromEntries((rows || []).map(item => [item.state, item.count]));
}

function memoryUsagePercent(system) {
  const used = Number(system.usedMemoryBytes || 0);
  const max = Math.max(Number(system.maxMemoryBytes || 1), 1);
  return Math.max(0, Math.min(100, Math.round((used / max) * 100)));
}

function dashboardMetric(title, value, hint, tone = "") {
  return `<div class="dashboard-clean-metric ${attr(tone)}">
    <span>${esc(title)}</span>
    <b>${esc(String(value ?? "-"))}</b>
    <small>${esc(String(hint || ""))}</small>
  </div>`;
}

function summaryCard(title, value, subtitle, page, rows, extra = "") {
  return `<article class="panel dashboard-clean-card">
    <div class="dashboard-clean-card-head">
      <div>
        <h2>${esc(title)}</h2>
        <p>${esc(subtitle || "")}</p>
      </div>
      <button type="button" class="secondary compact" data-action="goto" data-page="${attr(page)}">查看</button>
    </div>
    <div class="dashboard-clean-card-value">${value}</div>
    <div class="dashboard-clean-info-list">${rows.join("")}</div>
    ${extra ? `<div class="dashboard-clean-extra">${extra}</div>` : ""}
  </article>`;
}

function infoRow(name, value, tone = "") {
  return `<div class="dashboard-clean-info-row ${attr(tone)}">
    <span>${esc(name)}</span>
    <strong>${esc(String(value ?? "-"))}</strong>
  </div>`;
}

function renderPluginSummary(plugins) {
  if (!plugins.length) return `<div class="dashboard-clean-empty">暂无插件</div>`;
  const failed = plugins.filter(plugin => plugin.state === "FAILED");
  const rows = (failed.length ? failed : plugins).slice(0, 4);
  return `<div class="dashboard-clean-chip-list">${rows.map(plugin => {
    const tone = plugin.state === "ACTIVE" ? "ok" : plugin.state === "FAILED" ? "bad" : "warn";
    return `<span class="dashboard-clean-chip ${tone}">${esc(plugin.name)} · ${esc(plugin.state)}</span>`;
  }).join("")}</div>`;
}

function renderLoginSummary(items) {
  if (!items.length) return `<div class="dashboard-clean-empty">暂无源账号登录</div>`;
  const failed = items.filter(item => item.status !== "SUCCESS");
  const rows = (failed.length ? failed : items).slice(0, 4);
  return `<div class="dashboard-clean-chip-list">${rows.map(item => {
    const tone = item.status === "SUCCESS" ? "ok" : "bad";
    const name = item.account && item.account.name || item.message || item.platformId || "账号";
    return `<span class="dashboard-clean-chip ${tone}">${esc(item.platformId)} · ${esc(name)}</span>`;
  }).join("")}</div>`;
}

function renderFailureSummary(failures) {
  if (!failures.length) return `<div class="dashboard-clean-empty">暂无最近失败投递</div>`;
  return `<div class="dashboard-clean-lines">${failures.slice(0, 3).map(row => `<div class="dashboard-clean-line bad">
    <strong>${esc(formatTargetKey(row.targetKey))}</strong>
    <span>${esc(row.lastError || row.messageId || "投递失败")}</span>
  </div>`).join("")}</div>`;
}

function recentCard(logs, failures) {
  const rows = [
    ...failures.slice(0, 3).map(item => ({ tone: "bad", title: formatTargetKey(item.targetKey), text: item.lastError || item.messageId || "投递失败" })),
    ...logs.slice(0, 5).map(item => ({ tone: item.level === "ERROR" ? "bad" : "warn", title: `${item.level} · ${item.loggerName}`, text: item.message })),
  ].slice(0, 6);

  return `<article class="panel dashboard-clean-card dashboard-clean-card-wide">
    <div class="dashboard-clean-card-head">
      <div>
        <h2>最近异常</h2>
        <p>聚合展示投递失败和警告日志</p>
      </div>
      <button type="button" class="secondary compact" data-action="goto" data-page="logs">查看日志</button>
    </div>
    ${rows.length ? `<div class="dashboard-clean-lines">${rows.map(row => `<div class="dashboard-clean-line ${attr(row.tone)}">
      <strong>${esc(row.title || "-")}</strong>
      <span>${esc(row.text || "-")}</span>
    </div>`).join("")}</div>` : `<div class="dashboard-clean-empty">暂无需要关注的异常</div>`}
  </article>`;
}

function formatTargetKey(value) {
  return String(value || "-").replace(//g, " / ");
}
