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
  await loadLogsPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  stopLogPolling();
}

export async function handleAction(nextCtx, { action, button }) {
  bindContext(nextCtx);
  if (action === "apply-log-filter") {
    state.logRows = [];
    state.logSince = 0;
    await refreshLogs(true);
    return true;
  }
  if (action === "toggle-log-pause") {
    state.logsPaused = !state.logsPaused;
    button.textContent = state.logsPaused ? "继续" : "暂停";
    return true;
  }
  return false;
}

async function loadLogsPage(force) {
  if (force) {
    state.logRows = [];
    state.logSince = 0;
  }
  pageRoot().innerHTML = `
    <section class="page">
      <section class="panel full">
        <div class="filters">
          <div class="field"><label>级别</label><select id="logLevel"><option value="">全部</option><option>ERROR</option><option>WARN</option><option>INFO</option><option>DEBUG</option></select></div>
          <div class="field"><label>模块</label><input id="logLogger" placeholder="logger"></div>
          <div class="field"><label>关键词</label><input id="logQuery" placeholder="message"></div>
          <div class="toolbar-actions">
            <button data-action="apply-log-filter">筛选</button>
            <button class="secondary" data-action="toggle-log-pause">${state.logsPaused ? "继续" : "暂停"}</button>
          </div>
        </div>
        <div id="logsTable"></div>
      </section>
    </section>`;
  await refreshLogs(true);
  startLogPolling();
}

async function refreshLogs(force) {
  const params = {
    level: $("logLevel") && $("logLevel").value,
    logger: $("logLogger") && $("logLogger").value,
    q: $("logQuery") && $("logQuery").value,
    limit: 120
  };
  if (!force && state.logSince) params.since = state.logSince;
  const response = await api("/logs" + query(params));
  state.logSince = response.nextSince || state.logSince;
  if (force) state.logRows = response.entries || [];
  else {
    const known = new Set(state.logRows.map(row => row.seq));
    state.logRows = state.logRows.concat((response.entries || []).filter(row => !known.has(row.seq))).slice(-200);
  }
  renderLogs();
}

function renderLogs() {
  const target = $("logsTable");
  if (!target) return;
  const rows = state.logRows.slice().reverse();
  target.innerHTML = renderTable(rows, [
    { title: "时间", render: row => `<span class="sub-line">${fmtTime(row.timestampEpochMillis, true)}</span>` },
    { title: "级别", render: row => pill(row.level) },
    { title: "模块", render: row => `<span class="sub-line">${esc(row.loggerName)}</span>` },
    { title: "内容", render: row => `<div class="log-row">${esc(row.message)}${row.throwable ? "\n" + esc(row.throwable) : ""}</div>` }
  ]);
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