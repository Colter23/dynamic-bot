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
  await loadPlugins(ctx.force);
}

export async function handleAction(nextCtx, { action, id }) {
  bindContext(nextCtx);
  if (action === "plugin-start" || action === "plugin-stop" || action === "plugin-reload") {
    const verb = action.replace("plugin-", "");
    const result = await api(`/plugins/${encodeURIComponent(id)}/${verb}`, { method: "POST" });
    if (verb === "reload") delete state.pendingConfigRestarts[id];
    invalidate("plugins", "dashboard", "platformLogins", "configs");
    await loadPlugins(true);
    notify(result.message || "插件操作已完成", false);
    return true;
  }
  if (action === "plugin-config") {
    setPage("configs");
    return true;
  }
  return false;
}

async function loadPlugins(force) {
  if (force || !state.cache.plugins) state.cache.plugins = await api("/plugins");
  const rows = state.cache.plugins;
  pageRoot().innerHTML = `
    <section class="page">
      <section class="panel full">
        <div class="toolbar">
          <h2>插件</h2>
          <div class="toolbar-actions"><button data-action="refresh-current" class="secondary">刷新</button></div>
        </div>
        ${renderTable(rows, [
          { title: "插件", render: p => cell(p.name, `${p.id} · v${p.version}`) },
          { title: "状态", render: p => pill(p.state) },
          { title: "能力", render: p => tags(p.capabilities || []) },
          { title: "加载时间", render: p => `<span class="sub-line">${fmtTime(p.loadTime, true)}</span>` },
          { title: "路径 / 错误", render: p => cell(p.sourceJarPath || "-", p.error || "") },
          { title: "操作", render: p => pluginActions(p) }
        ])}
      </section>
    </section>`;
}

function pluginActions(plugin) {
  const startDisabled = plugin.state !== "LOADED" ? " disabled" : "";
  const stopDisabled = plugin.state !== "ACTIVE" ? " disabled" : "";
  return `<div class="row-actions">
    <button data-action="plugin-start" data-id="${attr(plugin.id)}"${startDisabled}>启动</button>
    <button class="secondary" data-action="plugin-stop" data-id="${attr(plugin.id)}"${stopDisabled}>停止</button>
    <button class="secondary" data-action="plugin-reload" data-id="${attr(plugin.id)}">重载</button>
    <button class="secondary" data-action="plugin-config" data-id="${attr(plugin.id)}">配置</button>
  </div>`;
}