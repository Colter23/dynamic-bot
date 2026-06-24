let ctx;
let root;
let api;
let state;
let setPage;
let esc;
let fmtTime;
let fmtDuration;
let fmtBytes;
let pill;

const PROJECT_VERSION = "0.0.7";
const PROJECT_URL = "https://github.com/Colter23/dynamic-bot";
const AUTHOR_URL = "https://github.com/Colter23";

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  setPage = ctx.setPage;
  ({ esc, fmtTime, fmtDuration, fmtBytes, pill } = ctx.ui);
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadAbout(ctx.force);
}

export async function handleAction(nextCtx, { action, button }) {
  bindContext(nextCtx);
  if (action === "about-goto") {
    await setPage(button.dataset.page);
    return true;
  }
  return false;
}

async function loadAbout(force) {
  const [status, plugins] = await Promise.all([
    loadSystemStatus(force).catch(() => null),
    loadPlugins(force).catch(() => []),
  ]);
  pageRoot().innerHTML = renderAbout(status, plugins);
}

async function loadSystemStatus(force) {
  if (force || !state.cache.system) state.cache.system = await api("/system/status");
  return state.cache.system;
}

async function loadPlugins(force) {
  if (force || !state.cache.plugins) state.cache.plugins = await api("/plugins");
  return state.cache.plugins || [];
}

function renderAbout(status, plugins) {
  const activePlugins = plugins.filter(plugin => plugin.state === "ACTIVE").length;
  const pluginSummary = plugins.length ? `${activePlugins}/${plugins.length}` : "-";
  return `
    <section class="page about-page">
      <section class="panel about-hero">
        <div class="about-hero-main">
          <span class="dashboard-kicker">dynamic-bot</span>
          <h2>Kotlin/JVM 动态转发系统</h2>
          <p>将 Bilibili、X 等非即时通讯平台的动态统一处理后，转发到 QQ、Discord 等即时通讯平台。</p>
          <div class="about-actions">
            <a class="about-action primary" href="${PROJECT_URL}" target="_blank" rel="noreferrer">项目主页</a>
            <button type="button" class="about-action secondary" data-action="about-goto" data-page="plugins">查看插件</button>
            <button type="button" class="about-action secondary" data-action="about-goto" data-page="system">系统维护</button>
          </div>
        </div>
        <div class="about-version-card">
          ${aboutMetric("主项目版本", `v${PROJECT_VERSION}`, "开发期版本")}
          ${aboutMetric("已启用插件", pluginSummary, "运行中 / 已加载")}
          ${aboutMetric("运行时间", status ? fmtDuration(status.uptimeMs) : "-", status ? `启动于 ${fmtTime(status.startedAtEpochMillis, true)}` : "运行信息未加载")}
          ${aboutMetric("后台端口", status ? status.webAdminPort : "-", status ? status.webAdminHost : "-")}
        </div>
      </section>

      <section class="about-grid">
        ${infoCard("项目", [
          infoRow("定位", "动态源到即时通讯平台的转发与运维系统"),
          infoRow("主链路", "来源更新 -> 处理绘图 -> 投递队列 -> 消息出口"),
          infoRow("插件模式", "前端来源插件和后端消息插件由主项目加载运行"),
          infoRow("仓库", externalLink("Colter23/dynamic-bot", PROJECT_URL)),
        ])}
        ${infoCard("作者", [
          infoRow("作者", "Colter"),
          infoRow("GitHub", externalLink("Colter23", AUTHOR_URL)),
          infoRow("项目语言", "Kotlin / JVM"),
          infoRow("后台", "原生 HTML / CSS / JavaScript"),
        ])}
        ${moduleCard()}
        ${runtimeCard(status)}
      </section>
    </section>`;
}

function aboutMetric(title, value, hint) {
  return `<div class="about-metric">
    <span>${esc(title)}</span>
    <b>${esc(String(value))}</b>
    <small>${esc(String(hint || ""))}</small>
  </div>`;
}

function infoCard(title, rows) {
  return `<article class="panel about-card">
    <div class="panel-head about-card-head">
      <h2>${esc(title)}</h2>
    </div>
    <div class="about-info-list">${rows.join("")}</div>
  </article>`;
}

function infoRow(name, value) {
  return `<div class="about-info-row">
    <span>${esc(name)}</span>
    <strong>${value}</strong>
  </div>`;
}

function externalLink(label, href) {
  return `<a class="about-inline-link" href="${href}" target="_blank" rel="noreferrer">${esc(label)}</a>`;
}

function moduleCard() {
  const modules = [
    ["core", "公共契约、数据模型、插件接口和配置表单定义"],
    ["主项目", "加载插件、处理动态、绘图、投递和提供 Web 运维台"],
    ["来源插件", "检测动态、直播状态和平台登录，例如 Bilibili"],
    ["消息插件", "发送通用消息到目标平台，例如 OneBot / QQ"],
  ];
  return `<article class="panel about-card about-module-card">
    <div class="panel-head about-card-head">
      <h2>模块结构</h2>
    </div>
    <div class="about-module-list">
      ${modules.map(([name, desc]) => `<div class="about-module-item">
        <span>${esc(name)}</span>
        <strong>${esc(desc)}</strong>
      </div>`).join("")}
    </div>
  </article>`;
}

function runtimeCard(status) {
  if (!status) {
    return infoCard("运行环境", [
      infoRow("状态", "运行信息暂未加载"),
      infoRow("提示", "刷新页面后可重新读取当前进程状态"),
    ]);
  }
  const heapPercent = Math.round((Number(status.usedMemoryBytes || 0) / Math.max(Number(status.maxMemoryBytes || 1), 1)) * 100);
  return `<article class="panel about-card">
    <div class="panel-head about-card-head">
      <h2>运行环境</h2>
      ${pill(status.webAdminEnabled ? "ACTIVE" : "LOADED")}
    </div>
    <div class="about-info-list">
      ${infoRow("Java", esc(status.javaVersion || "-"))}
      ${infoRow("系统", esc(status.osName || "-"))}
      ${infoRow("处理器", `${esc(String(status.availableProcessors || "-"))} 个`)}
      ${infoRow("堆内存", `${esc(fmtBytes(status.usedMemoryBytes))} / ${esc(fmtBytes(status.maxMemoryBytes))} (${esc(String(heapPercent))}%)`)}
      ${infoRow("配置文件", esc(status.mainConfigPath || "-"))}
    </div>
  </article>`;
}
