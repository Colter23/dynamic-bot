let ctx;
let root;
let api;
let state;
let fmtTime;
let fmtBytes;
let fmtDuration;
let cell;
let esc;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  ({ esc, fmtTime, fmtBytes, fmtDuration, cell } = ctx.ui);
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadSystem(ctx.force);
}

export function unmount() {
  invalidatePageRequests("system");
}

export async function handleAction(nextCtx, _payload) {
  bindContext(nextCtx);
  return false;
}

async function loadSystem(force) {
  const request = beginPageRequest("system");
  if (force || !state.cache.system) state.cache.system = await api("/system/status");
  if (!isCurrentPageRequest(request)) return;
  const s = state.cache.system;
  const memoryPercent = Math.max(0, Math.min(100, Math.round((Number(s.usedMemoryBytes || 0) / Math.max(Number(s.maxMemoryBytes || 1), 1)) * 100)));
  const memoryMax = fmtBytes(s.maxMemoryBytes);
  pageRoot().innerHTML = `
    <section class="page system-page">
      <div class="grid system-grid">
        <section class="panel half system-overview">
          <div class="panel-head">
            <h2>运行概览</h2>
          </div>
          <div class="stats system-stats">
            ${stat("运行时间", fmtDuration(s.uptimeMs), fmtTime(s.startedAtEpochMillis, true))}
            ${stat("堆内存", fmtBytes(s.usedMemoryBytes), `${memoryPercent}% / ${memoryMax}`)}
            ${stat("总内存占用", fmtBytes(s.totalMemoryBytes), "可用 " + fmtBytes(s.freeMemoryBytes))}
            ${stat("后台", s.webAdminPort, s.webAdminHost)}
          </div>
          <div class="system-meter">
            <div class="system-meter-head">
              <span>堆内存占用</span>
              <strong>${memoryPercent}%</strong>
            </div>
            <div class="system-meter-track" aria-hidden="true">
              <span style="width:${memoryPercent}%"></span>
            </div>
            <div class="system-meter-foot">
              <span>已用 ${esc(fmtBytes(s.usedMemoryBytes))}</span>
              <span>上限 ${esc(memoryMax)}</span>
            </div>
          </div>
        </section>

        <section class="panel half system-path-panel">
          <div class="panel-head">
            <h2>环境与路径</h2>
          </div>
          ${pathList([
            {
              key: "database",
              name: "数据库路径",
              value: s.databasePath || "-",
              hint: s.databasePath ? "当前进程使用的持久化存储" : "未显示数据库路径",
            },
            {
              key: "config",
              name: "主配置路径",
              value: s.mainConfigPath,
              hint: "主项目配置文件",
            },
            {
              key: "java",
              name: "Java / OS",
              value: `${s.javaVersion} / ${s.osName}`,
              hint: "运行环境",
            },
            {
              key: "web",
              name: "后台地址",
              value: `${s.webAdminHost}:${s.webAdminPort}`,
              hint: s.webAdminEnabled ? "后台服务正在监听" : "后台服务未启用",
            },
          ])}
        </section>
      </div>
    </section>`;
}

function stat(title, value, sub) {
  return `<div class="stat"><b>${esc(value)}</b><span>${esc(title)} · ${esc(sub || "")}</span></div>`;
}

function pathList(rows) {
  return `<div class="system-path-list">${
    rows.map(row => `<div class="system-path-item">
      <div>${cell(row.name, row.hint)}</div>
      <span class="system-path-value">${esc(row.value)}</span>
    </div>`).join("")
  }</div>`;
}
