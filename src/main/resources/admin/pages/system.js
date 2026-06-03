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
  await loadSystem(ctx.force);
}

export async function handleAction() {
  return false;
}

async function loadSystem(force) {
  if (force || !state.cache.system) state.cache.system = await api("/system/status");
  const s = state.cache.system;
  pageRoot().innerHTML = `
    <section class="page">
      <div class="stats">
        ${stat("运行时间", fmtDuration(s.uptimeMs), fmtTime(s.startedAtEpochMillis, true))}
        ${stat("内存", fmtBytes(s.usedMemoryBytes), "已用 / " + fmtBytes(s.maxMemoryBytes))}
        ${stat("CPU", s.availableProcessors, "可用处理器")}
        ${stat("后台", s.webAdminPort, s.webAdminHost)}
      </div>
      <section class="panel full">
        <div class="panel-head"><h2>路径</h2></div>
        ${renderTable([s], [
          { title: "数据库", render: row => `<span class="sub-line">${esc(row.databasePath || "-")}</span>` },
          { title: "主配置", render: row => `<span class="sub-line">${esc(row.mainConfigPath)}</span>` },
          { title: "Java", render: row => cell(row.javaVersion, row.osName) }
        ])}
      </section>
    </section>`;
}

function stat(title, value, sub) {
  return `<div class="stat"><b>${esc(value)}</b><span>${esc(title)} · ${esc(sub || "")}</span></div>`;
}
