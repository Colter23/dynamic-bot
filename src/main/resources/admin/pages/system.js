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
  if (force || !state.cache.deliveries) state.cache.deliveries = await api("/deliveries?limit=80");
  const s = state.cache.system;
  const deliveries = state.cache.deliveries;
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
      <section class="panel full">
        <div class="panel-head"><h2>最近投递</h2><button class="secondary" data-action="refresh-current">刷新</button></div>
        ${renderTable(deliveries, [
          { title: "消息", render: row => cell(row.messageId, row.sourceUpdateKey || "") },
          { title: "目标", render: row => cell(`${row.platformId}:${label(row.targetKind)}:${row.targetId}`, row.targetKey.replace(/\u001F/g, " / ")) },
          { title: "状态", render: row => pill(row.status) },
          { title: "尝试", render: row => `<span class="primary-line">${row.attempts}</span>` },
          { title: "错误", render: row => `<span class="sub-line">${esc(row.lastError || "-")}</span>` },
          { title: "更新时间", render: row => `<span class="sub-line">${fmtTime(row.updatedAtEpochSeconds)}</span>` }
        ])}
      </section>
    </section>`;
}