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
let notify;
let handleError;
let logRequestSeq = 0;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  query = ctx.query;
  handleError = ctx.handleError;
  ({ esc, attr, fmtTime, pill, renderTable, notify } = ctx.ui);
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

function isAutoPinLatest() {
  return state.logsAutoPinLatest !== false;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadLogsPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  stopLogPolling();
  document.getElementById("content")?.classList.remove("content-logs");
  pageRoot()?.classList.remove("logs-page-host");
}

export async function handleAction(nextCtx, { action, button, id }) {
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
    updateToolbarButtons();
    return true;
  }
  if (action === "toggle-log-autopin") {
    state.logsAutoPinLatest = !isAutoPinLatest();
    renderLogStatus();
    updateToolbarButtons();
    if (isAutoPinLatest()) scrollLogsToTop();
    return true;
  }
  if (action === "jump-log-latest") {
    scrollLogsToTop();
    return true;
  }
  if (action === "copy-log-message" || action === "copy-log-logger") {
    await copyLogValue(action, id);
    return true;
  }
  return false;
}

async function loadLogsPage(force) {
  if (force) {
    state.logRows = [];
    state.logSince = 0;
  }
  if (state.logsAutoPinLatest === undefined) state.logsAutoPinLatest = true;
  document.getElementById("content")?.classList.add("content-logs");
  pageRoot()?.classList.add("logs-page-host");
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
              ${logLevelOptions(filters.level)}
            </select>
            <input id="logLogger" data-log-filter="logger" value="${attr(filters.logger)}" placeholder="模块 / logger">
            <input id="logQuery" data-log-filter="q" value="${attr(filters.q)}" placeholder="关键词">
            <button type="button" class="entity-filter-clear" data-action="apply-log-filter">筛选</button>
            <button type="button" class="choice-clear-button compact" data-action="reset-log-filter">清除</button>
            <button type="button" class="choice-refresh-button compact" data-action="refresh-logs">刷新</button>
            <button type="button" class="secondary compact log-jump-button" data-action="jump-log-latest">最新</button>
            <button type="button" class="secondary compact log-autopin-button" data-action="toggle-log-autopin"></button>
            <button type="button" class="secondary compact log-pause-button" data-action="toggle-log-pause"></button>
          </div>
          <span id="logFilterSummary" class="entity-filter-summary"></span>
        </div>
        <div class="log-table-region">
          <div id="logsTable" class="logs-table-host"></div>
        </div>
      </section>
    </section>`;
  updateToolbarButtons();
  renderLogStatus();
}

function logLevelOptions(selected) {
  return [
    ["", "全部级别"],
    ["ERROR,WARN", "错误与警告"],
    ["ERROR", "ERROR"],
    ["WARN", "WARN"],
    ["INFO", "INFO"],
    ["DEBUG", "DEBUG"],
  ].map(([value, label]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(label)}</option>`).join("");
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
  const seq = ++logRequestSeq;
  const scrollState = !force && !isAutoPinLatest() ? currentLogScrollState() : null;
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
    if (!isCurrentLogRequest(seq)) return;

    state.logSince = Math.max(Number(state.logSince || 0), Number(response.nextSince || 0));
    state.logCapacity = Number(response.capacity || 0);
    state.logRetainedCount = Number(response.retainedCount || 0);
    if (force) {
      state.logRows = (response.entries || []).slice(-LOG_KEEP_LIMIT);
    } else {
      const known = new Set((state.logRows || []).map(row => row.seq));
      state.logRows = (state.logRows || [])
        .concat((response.entries || []).filter(row => !known.has(row.seq)))
        .slice(-LOG_KEEP_LIMIT);
    }
    renderLogs();
    if (isAutoPinLatest()) {
      scrollLogsToTop();
    } else if (scrollState) {
      restoreLogScrollState(scrollState);
    }
  } finally {
    if (isCurrentLogRequest(seq)) {
      state.logsLoading = false;
      renderLogStatus();
    }
    if (button?.isConnected) {
      button.disabled = false;
      if (originalText) button.textContent = originalText;
    }
  }
}

function isCurrentLogRequest(seq) {
  return seq === logRequestSeq && state.page === "logs" && !!pageRoot()?.isConnected;
}

function renderLogs() {
  const target = $("logsTable");
  if (!target) return;
  const rows = (state.logRows || []).slice().reverse();
  renderLogSummary(rows.length);
  if (!rows.length) {
    target.innerHTML = `<div class="empty log-empty">暂无日志</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "时间", render: row => `<span class="sub-line log-time">${fmtTime(row.timestampEpochMillis, true)}</span>` },
    { title: "级别", render: row => pill(row.level) },
    { title: "模块", render: row => `<span class="sub-line log-logger">${esc(row.loggerName)}</span>` },
    { title: "内容", render: row => `<div class="log-row">${esc(logText(row))}</div>` },
    { title: "操作", render: row => `
      <div class="log-copy-actions">
        <button type="button" class="log-action-button" data-action="copy-log-message" data-id="${attr(row.seq)}">复制内容</button>
        <button type="button" class="log-action-button logger" data-action="copy-log-logger" data-id="${attr(row.seq)}">复制模块</button>
      </div>` },
  ])
    .replace('class="table-wrap"', 'class="table-wrap logs-table-wrap"')
    .replace("<table>", '<table class="logs-table">');
}

function logText(row) {
  return `${row.message || ""}${row.throwable ? "\n" + row.throwable : ""}`;
}

function renderLogSummary(visibleCount) {
  const summary = $("logFilterSummary");
  if (!summary) return;
  const capacity = Number(state.logCapacity || 0);
  const retained = Number(state.logRetainedCount || 0);
  summary.textContent = capacity > 0
    ? `显示 ${visibleCount} 条 / 缓冲 ${retained}/${capacity}`
    : `显示 ${visibleCount} 条`;
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
    <span class="pill ${isAutoPinLatest() ? "info" : ""}">${isAutoPinLatest() ? "最新在顶部" : "手动定位"}</span>
    <span>${state.logsLoading ? "正在读取" : `最近 ${latest ? fmtTime(latest.timestampEpochMillis, true) : "-"}`}</span>`;
}

function updateToolbarButtons() {
  const pauseButton = pageRoot().querySelector(".log-pause-button");
  if (pauseButton) pauseButton.textContent = state.logsPaused ? "继续" : "暂停";
  const autoPinButton = pageRoot().querySelector(".log-autopin-button");
  if (autoPinButton) autoPinButton.textContent = isAutoPinLatest() ? "取消定位" : "固定最新";
}

function scrollLogsToTop() {
  const wrap = pageRoot().querySelector(".logs-table-wrap");
  if (wrap) wrap.scrollTop = 0;
}

function currentLogScrollState() {
  const wrap = pageRoot().querySelector(".logs-table-wrap");
  return wrap ? { top: wrap.scrollTop, height: wrap.scrollHeight } : null;
}

function restoreLogScrollState(previous) {
  const wrap = pageRoot().querySelector(".logs-table-wrap");
  if (!wrap || !previous) return;
  wrap.scrollTop = previous.top + (wrap.scrollHeight - previous.height);
}

async function copyLogValue(action, id) {
  const row = (state.logRows || []).find(item => String(item.seq) === String(id));
  if (!row) throw new Error("日志记录不存在");
  const text = action === "copy-log-logger" ? (row.loggerName || "") : logText(row);
  await copyText(text);
  notify(action === "copy-log-logger" ? "模块名已复制" : "日志内容已复制", false);
}

async function copyText(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.style.position = "fixed";
  textarea.style.left = "-9999px";
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  document.execCommand("copy");
  textarea.remove();
}

function startLogPolling() {
  stopLogPolling(false);
  state.logTimer = setInterval(() => {
    if (!state.logsPaused && !state.logsLoading && state.page === "logs") {
      refreshLogs(false).catch(handleError);
    }
  }, 2500);
}

function stopLogPolling(invalidateRequest = true) {
  if (state.logTimer) clearInterval(state.logTimer);
  state.logTimer = null;
  state.logsLoading = false;
  if (invalidateRequest) logRequestSeq += 1;
}
