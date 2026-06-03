const $ = id => document.getElementById(id);

const LOG_LIMIT = 160;
const LOG_KEEP_LIMIT = 260;

let ctx;
let root;
let api;
let state;
let query;
let esc;
let attr;
let fmtTime;
let pill;
let renderTable;
let handleError;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  query = ctx.query;
  handleError = ctx.handleError;
  ({ esc, attr, fmtTime, pill, renderTable } = ctx.ui);
}

function pageRoot() {
  return root;
}

function logFilters() {
  if (!state.logFilters) {
    state.logFilters = { level: "", logger: "", q: "" };
  }
  return state.logFilters;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadLogsPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  stopLogPolling();
}

export async function handleAction(nextCtx, { action, button }) {
  bindContext(nextCtx);
  if (action === "apply-log-filter") {
    await applyLogFilter(button);
    return true;
  }
  if (action === "reset-log-filter") {
    resetLogFilterControls();
    await applyLogFilter(button);
    return true;
  }
  if (action === "refresh-logs") {
    await applyLogFilter(button);
    return true;
  }
  if (action === "toggle-log-pause") {
    state.logsPaused = !state.logsPaused;
    renderLogStatus();
    updatePauseButton();
    return true;
  }
  return false;
}

async function loadLogsPage(force) {
  if (force) {
    state.logRows = [];
    state.logSince = 0;
  }
  renderLayout();
  bindLogControls();
  renderLogs();
  await refreshLogs(true);
  startLogPolling();
}

function renderLayout() {
  const filters = logFilters();
  pageRoot().innerHTML = `
    <section class="page logs-page">
      <section class="panel full log-panel">
        <div class="panel-head log-panel-head">
          <div>
            <h2>实时日志</h2>
            <p>查看当前进程内日志，支持级别、模块和关键词筛选。</p>
          </div>
          <div id="logStatus" class="log-status"></div>
        </div>
        <div class="entity-filter-bar log-filter-bar">
          <span class="entity-filter-title">筛选</span>
          <div class="entity-filter-controls">
            <select id="logLevel" data-log-filter="level">
              <option value="">全部级别</option>
              ${["ERROR", "WARN", "INFO", "DEBUG"].map(level => `
                <option value="${level}"${filters.level === level ? " selected" : ""}>${level}</option>
              `).join("")}
            </select>
            <input id="logLogger" data-log-filter="logger" value="${attr(filters.logger)}" placeholder="模块 / logger">
            <input id="logQuery" data-log-filter="q" value="${attr(filters.q)}" placeholder="关键词">
            <button type="button" class="entity-filter-clear" data-action="apply-log-filter">筛选</button>
            <button type="button" class="choice-clear-button compact" data-action="reset-log-filter">清除</button>
            <button type="button" class="choice-refresh-button compact" data-action="refresh-logs">刷新</button>
            <button type="button" class="secondary compact log-pause-button" data-action="toggle-log-pause"></button>
          </div>
          <span id="logFilterSummary" class="entity-filter-summary"></span>
        </div>
        <div class="log-table-region">
          <div id="logsTable" class="logs-table-host"></div>
        </div>
      </section>
    </section>`;
  updatePauseButton();
  renderLogStatus();
}

function bindLogControls() {
  pageRoot().querySelectorAll("[data-log-filter]").forEach(control => {
    control.onkeydown = event => {
      if (event.key === "Enter") {
        event.preventDefault();
        applyLogFilter().catch(handleError);
      }
    };
  });
  $("logLevel").onchange = () => applyLogFilter().catch(handleError);
}

async function applyLogFilter(button) {
  readFilterControls();
  state.logRows = [];
  state.logSince = 0;
  await refreshLogs(true, button);
}

function readFilterControls() {
  const filters = logFilters();
  filters.level = $("logLevel")?.value.trim() || "";
  filters.logger = $("logLogger")?.value.trim() || "";
  filters.q = $("logQuery")?.value.trim() || "";
}

function resetLogFilterControls() {
  const filters = logFilters();
  filters.level = "";
  filters.logger = "";
  filters.q = "";
  if ($("logLevel")) $("logLevel").value = "";
  if ($("logLogger")) $("logLogger").value = "";
  if ($("logQuery")) $("logQuery").value = "";
}

async function refreshLogs(force, button) {
  const filters = logFilters();
  const params = {
    level: filters.level,
    logger: filters.logger,
    q: filters.q,
    limit: LOG_LIMIT,
  };
  if (!force && state.logSince) params.since = state.logSince;
  const originalText = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = "刷新中...";
  }
  state.logsLoading = true;
  renderLogStatus();
  try {
    const response = await api("/logs" + query(params));
    state.logSince = response.nextSince || state.logSince;
    if (force) {
      state.logRows = response.entries || [];
    } else {
      const known = new Set(state.logRows.map(row => row.seq));
      state.logRows = state.logRows
        .concat((response.entries || []).filter(row => !known.has(row.seq)))
        .slice(-LOG_KEEP_LIMIT);
    }
    renderLogs();
  } finally {
    state.logsLoading = false;
    renderLogStatus();
    if (button?.isConnected) {
      button.disabled = false;
      if (originalText) button.textContent = originalText;
    }
  }
}

function renderLogs() {
  const target = $("logsTable");
  if (!target) return;
  const rows = (state.logRows || []).slice().reverse();
  const summary = $("logFilterSummary");
  if (summary) summary.textContent = `显示 ${rows.length} 条`;
  if (!rows.length) {
    target.innerHTML = `<div class="empty log-empty">暂无日志</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "时间", render: row => `<span class="sub-line log-time">${fmtTime(row.timestampEpochMillis, true)}</span>` },
    { title: "级别", render: row => pill(row.level) },
    { title: "模块", render: row => `<span class="sub-line log-logger">${esc(row.loggerName)}</span>` },
    { title: "内容", render: row => `<div class="log-row">${esc(row.message)}${row.throwable ? "\n" + esc(row.throwable) : ""}</div>` },
  ])
    .replace('class="table-wrap"', 'class="table-wrap logs-table-wrap"')
    .replace("<table>", '<table class="logs-table">');
}

function renderLogStatus() {
  const target = $("logStatus");
  if (!target) return;
  const latest = (state.logRows || []).at(-1);
  const mode = state.logsPaused ? "已暂停" : "实时刷新";
  const modeTone = state.logsPaused ? "warn" : "ok";
  target.innerHTML = `
    ${state.logsLoading ? '<span class="loading-spinner" aria-hidden="true"></span>' : ""}
    <span class="pill ${modeTone}">${mode}</span>
    <span>${state.logsLoading ? "正在读取" : `最近 ${latest ? fmtTime(latest.timestampEpochMillis, true) : "-"}`}</span>`;
}

function updatePauseButton() {
  const button = pageRoot().querySelector(".log-pause-button");
  if (!button) return;
  button.textContent = state.logsPaused ? "继续" : "暂停";
}

function startLogPolling() {
  stopLogPolling();
  state.logTimer = setInterval(() => {
    if (!state.logsPaused && state.page === "logs") refreshLogs(false).catch(handleError);
  }, 2500);
}

function stopLogPolling() {
  if (state.logTimer) clearInterval(state.logTimer);
  state.logTimer = null;
}
