const $ = id => document.getElementById(id);

let ctx;
let root;
let api;
let state;
let ui;
let invalidate;
let setPage;
let esc;
let attr;
let fmtTime;
let fmtBytes;
let cell;
let tags;
let renderTable;
let notify;
let openModal;
let closeModal;
let uniqueValues;
let filterOptions;
let matchesContains;

const pluginFilters = {
  state: "",
  capability: "",
  keyword: "",
  catalogStatus: "",
  catalogCapability: "",
  catalogKeyword: "",
};

let pageSeq = 0;
let catalogModalSeq = 0;
let catalogModalLoading = false;
let catalogModalError = null;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  ui = ctx.ui;
  invalidate = ctx.invalidate;
  setPage = ctx.setPage;
  ({
    esc,
    attr,
    fmtTime,
    fmtBytes,
    cell,
    tags,
    renderTable,
    notify,
    openModal,
    closeModal,
    uniqueValues,
    filterOptions,
    matchesContains,
  } = ui);
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadPlugins(ctx.force);
}

export function unmount() {
  pageSeq += 1;
  catalogModalSeq += 1;
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "plugin-start" || action === "plugin-stop" || action === "plugin-reload") {
    await runPluginLifecycleAction(action, id, button);
    return true;
  }
  if (action === "plugin-config") {
    setPage("configs");
    return true;
  }
  if (action === "plugin-detail") {
    openInstalledPluginDetail(id);
    return true;
  }
  if (action === "open-plugin-catalog") {
    await openCatalogModal(false);
    return true;
  }
  if (action === "check-plugin-updates") {
    await checkPluginUpdates(button);
    return true;
  }
  if (action === "catalog-refresh") {
    await refreshCatalog(button);
    return true;
  }
  if (action === "catalog-detail") {
    openCatalogPluginDetail(id);
    return true;
  }
  if (action === "catalog-install" || action === "catalog-update") {
    await openCatalogOperation(id, action === "catalog-update" ? "update" : "install");
    return true;
  }
  if (action === "plugin-filter-reset") {
    resetInstalledFilters();
    return true;
  }
  if (action === "catalog-filter-reset") {
    resetCatalogFilters();
    return true;
  }
  return false;
}

async function loadPlugins(force) {
  const seq = ++pageSeq;
  renderLoading();
  const plugins = await ensurePlugins(force);
  if (seq !== pageSeq || !root?.isConnected) return;
  renderPage(plugins);
}

async function ensurePlugins(force) {
  if (force || !state.cache.plugins) state.cache.plugins = await api("/plugins");
  return state.cache.plugins || [];
}

async function fetchCatalog(force) {
  try {
    if (force || !state.cache.pluginCatalog) {
      state.cache.pluginCatalog = await api("/plugin-catalog" + (force ? "?force=true" : ""));
    }
    return { data: state.cache.pluginCatalog, error: null };
  } catch (error) {
    return { data: state.cache.pluginCatalog || null, error };
  }
}

function renderLoading() {
  pageRoot().innerHTML = `
    <section class="page plugin-page">
      <section class="plugin-hero panel full">
        <div class="plugin-hero-copy">
          <h2>插件管理</h2>
          <p>正在读取已安装插件...</p>
        </div>
        <div class="plugin-loading-dots"><span></span><span></span><span></span></div>
      </section>
      <div class="plugin-skeleton-grid">
        <div class="plugin-skeleton"></div>
        <div class="plugin-skeleton"></div>
        <div class="plugin-skeleton"></div>
      </div>
    </section>`;
}

function renderPage(plugins = state.cache.plugins || []) {
  const catalogEntries = catalogEntriesFromCache();
  normalizeInstalledFilters(plugins);
  const filteredPlugins = filterInstalledPlugins(plugins);
  pageRoot().innerHTML = `
    <section class="page plugin-page">
      ${renderHeader(plugins, catalogEntries)}
      <section class="panel full plugin-panel">
        <div class="panel-head plugin-panel-head">
          <div>
            <h2>已安装插件</h2>
            <p>查看插件状态、能力和加载时间，并执行启动、停止、重启和配置操作。</p>
          </div>
          <div class="toolbar-actions">
            <button class="plugin-check-update-button" data-action="check-plugin-updates">检查更新</button>
            <button class="add-button plugin-download-button" data-action="open-plugin-catalog">下载插件</button>
          </div>
        </div>
        ${installedFilterBar(plugins, filteredPlugins)}
        <div id="installedPluginsTable">${installedPluginsTable(filteredPlugins)}</div>
      </section>
    </section>`;
  bindInstalledFilters();
}

function renderHeader(plugins, catalogEntries) {
  const activeCount = plugins.filter(item => item.state === "ACTIVE").length;
  const loadedCount = plugins.filter(item => item.state === "LOADED").length;
  const failedCount = plugins.filter(item => item.state === "FAILED").length;
  const updateCount = catalogEntries.filter(item => item.catalogStatus === "UPDATE_AVAILABLE").length;
  return `<section class="plugin-hero panel full">
    <div class="plugin-hero-copy">
      <h2>插件管理</h2>
      <p>管理已安装插件生命周期，可下载或更新官方插件。</p>
    </div>
    <div class="plugin-summary-grid">
      ${summaryCard("运行中", `${activeCount}/${plugins.length}`, "已启动 / 已安装", "ok")}
      ${summaryCard("已加载", loadedCount, "可启动插件", "info")}
      ${summaryCard("异常", failedCount, failedCount ? "需要查看错误或重启" : "暂无失败插件", failedCount ? "bad" : "ok")}
      ${summaryCard("可更新", updateCount, catalogEntries.length ? "来自已缓存目录" : "打开目录后刷新", updateCount ? "warn" : "info")}
    </div>
  </section>`;
}

function summaryCard(title, value, sub, tone = "") {
  return `<div class="plugin-summary-card ${attr(tone)}">
    <b>${esc(value)}</b>
    <span>${esc(title)}</span>
    <small>${esc(sub || "")}</small>
  </div>`;
}

function installedFilterBar(rows, filteredRows) {
  const active = pluginFilters.state || pluginFilters.capability || pluginFilters.keyword;
  return `<div class="entity-filter-bar plugin-filter-bar">
    <span class="entity-filter-title">筛选</span>
    <div class="entity-filter-controls">
      <select data-plugin-filter="state">${filterOptions("全部状态", uniqueValues(rows, "state"), pluginFilters.state, pluginStateText)}</select>
      <select data-plugin-filter="capability">${filterOptions("全部能力", capabilityValues(rows), pluginFilters.capability, capabilityText)}</select>
      <input data-plugin-filter="keyword" value="${attr(pluginFilters.keyword)}" placeholder="插件名称 / ID / 路径">
      <button type="button" class="entity-filter-clear" data-action="plugin-filter-reset"${active ? "" : " disabled"}>清除</button>
    </div>
    <span class="entity-filter-summary">显示 ${filteredRows.length} / ${rows.length}</span>
  </div>`;
}

function bindInstalledFilters() {
  pageRoot().querySelectorAll("[data-plugin-filter]").forEach(control => {
    const apply = () => {
      pluginFilters[control.dataset.pluginFilter] = control.value.trim();
      renderPage();
    };
    control.oninput = apply;
    control.onchange = apply;
  });
}

function resetInstalledFilters() {
  pluginFilters.state = "";
  pluginFilters.capability = "";
  pluginFilters.keyword = "";
  renderPage();
}

function resetCatalogFilters() {
  pluginFilters.catalogStatus = "";
  pluginFilters.catalogCapability = "";
  pluginFilters.catalogKeyword = "";
  renderCatalogModalBody();
}

function normalizeInstalledFilters(plugins) {
  if (pluginFilters.state && !uniqueValues(plugins, "state").includes(pluginFilters.state)) pluginFilters.state = "";
  if (pluginFilters.capability && !capabilityValues(plugins).includes(pluginFilters.capability)) pluginFilters.capability = "";
}

function normalizeCatalogFilters(entries) {
  if (pluginFilters.catalogStatus && !entries.some(item => item.catalogStatus === pluginFilters.catalogStatus)) pluginFilters.catalogStatus = "";
  if (pluginFilters.catalogCapability && !capabilityValues(entries).includes(pluginFilters.catalogCapability)) pluginFilters.catalogCapability = "";
}

function filterInstalledPlugins(rows) {
  return (rows || []).filter(plugin => {
    const catalog = catalogEntryById(plugin.id);
    return (!pluginFilters.state || plugin.state === pluginFilters.state) &&
      (!pluginFilters.capability || (plugin.capabilities || []).includes(pluginFilters.capability)) &&
      matchesAny([plugin.id, plugin.name, plugin.version, plugin.sourceJarPath, plugin.error, catalog && catalog.description], pluginFilters.keyword);
  });
}

function filterCatalogEntries(rows) {
  return (rows || []).filter(item => {
    return (!pluginFilters.catalogStatus || item.catalogStatus === pluginFilters.catalogStatus) &&
      (!pluginFilters.catalogCapability || (item.capabilities || []).includes(pluginFilters.catalogCapability)) &&
      matchesAny([item.id, item.name, item.version, item.description, item.homepageUrl, item.releaseNotesUrl], pluginFilters.catalogKeyword);
  });
}

function matchesAny(values, keyword) {
  return !keyword || (values || []).some(value => matchesContains(value, keyword));
}

function installedPluginsTable(rows) {
  const table = renderTable(rows, [
    { title: "插件", render: plugin => pluginTitleCell(plugin) },
    { title: "版本", render: plugin => pluginVersionCell(plugin) },
    { title: "状态", render: plugin => pluginStatePill(plugin.state) },
    { title: "更新", render: plugin => installedCatalogCell(plugin) },
    { title: "能力", render: plugin => capabilityTags(plugin.capabilities) },
    { title: "加载时间", render: plugin => `<span class="sub-line">${plugin.loadTime ? fmtTime(plugin.loadTime, true) : "-"}</span>` },
    { title: "错误", render: plugin => plugin.error ? `<span class="plugin-error-inline">${esc(plugin.error)}</span>` : `<span class="sub-line">-</span>` },
    { title: "操作", render: plugin => installedPluginActions(plugin) },
  ]);
  return table.replace("<table>", `<table class="plugin-installed-table">`);
}

function pluginTitleCell(plugin) {
  return `<div class="plugin-name-cell">
    <span class="plugin-icon">${esc(pluginInitial(plugin))}</span>
    <div>${cell(plugin.name || plugin.id, plugin.id)}</div>
  </div>`;
}

function pluginVersionCell(plugin) {
  const catalog = catalogEntryById(plugin.id);
  const sub = catalog && catalog.version && catalog.version !== plugin.version
    ? `官方 v${catalog.version}`
    : "当前版本";
  return cell(plugin.version ? `v${plugin.version}` : "-", sub);
}

function installedCatalogCell(plugin) {
  const catalog = catalogEntryById(plugin.id);
  if (!catalog) {
    return state.cache.pluginCatalog
      ? `<span class="sub-line">未匹配官方目录</span>`
      : `<span class="sub-line">未加载官方目录</span>`;
  }
  return `<div class="plugin-update-cell">
    ${catalogStatusPill(catalog.catalogStatus)}
    <span class="sub-line">${esc(catalog.version ? `官方 v${catalog.version}` : "官方版本未知")}</span>
  </div>`;
}

function installedPluginActions(plugin) {
  const startDisabled = plugin.state !== "LOADED" ? " disabled" : "";
  const stopDisabled = plugin.state !== "ACTIVE" ? " disabled" : "";
  const catalog = catalogEntryById(plugin.id);
  return `<div class="row-actions plugin-row-actions">
    <button class="plugin-action-start" data-action="plugin-start" data-id="${attr(plugin.id)}"${startDisabled}>启动</button>
    <button class="plugin-action-stop" data-action="plugin-stop" data-id="${attr(plugin.id)}"${stopDisabled}>停止</button>
    <button class="plugin-action-restart" data-action="plugin-reload" data-id="${attr(plugin.id)}">重启</button>
    ${catalog && catalog.catalogStatus === "UPDATE_AVAILABLE"
      ? `<button class="plugin-action-update" data-action="catalog-update" data-id="${attr(plugin.id)}">更新</button>`
      : ""}
    <button class="plugin-action-config" data-action="plugin-config" data-id="${attr(plugin.id)}">配置</button>
    <button class="secondary" data-action="plugin-detail" data-id="${attr(plugin.id)}">详情</button>
  </div>`;
}

async function openCatalogModal(force) {
  const seq = ++catalogModalSeq;
  catalogModalLoading = true;
  catalogModalError = null;
  openModal("下载插件", `<div id="pluginCatalogModalBody"></div>`, null, {
    size: "wide",
    cancelText: "关闭",
    cleanup: () => {
      catalogModalSeq += 1;
    },
  });
  renderCatalogModalBody();
  const result = await fetchCatalog(force || !state.cache.pluginCatalog);
  if (seq !== catalogModalSeq || !$("pluginCatalogModalBody")) return;
  catalogModalLoading = false;
  catalogModalError = result.error;
  renderPage();
  renderCatalogModalBody();
}

async function refreshCatalog(button) {
  if (!$("pluginCatalogModalBody")) {
    await openCatalogModal(true);
    return;
  }
  const seq = catalogModalSeq;
  if (button) button.disabled = true;
  catalogModalLoading = true;
  catalogModalError = null;
  renderCatalogModalBody();
  const result = await fetchCatalog(true);
  if (seq !== catalogModalSeq || !$("pluginCatalogModalBody")) return;
  catalogModalLoading = false;
  catalogModalError = result.error;
  renderPage();
  renderCatalogModalBody();
  if (button && button.isConnected) button.disabled = false;
  if (result.error) throw result.error;
  notify("官方插件目录已刷新", false);
}

async function checkPluginUpdates(button) {
  const originalText = button?.textContent;
  if (button) button.disabled = true;
  if (button) button.textContent = "检查中...";
  catalogModalError = null;
  try {
    const result = await fetchCatalog(true);
    catalogModalError = result.error;
    renderPage();
    if (result.error) throw result.error;
    const updateCount = catalogEntriesFromCache().filter(item => item.catalogStatus === "UPDATE_AVAILABLE").length;
    notify(updateCount ? `发现 ${updateCount} 个可更新插件` : "当前没有可更新插件", false);
  } finally {
    if (button && button.isConnected) {
      button.disabled = false;
      if (originalText) button.textContent = originalText;
    }
  }
}

function renderCatalogModalBody() {
  const body = $("pluginCatalogModalBody");
  if (!body) return;
  const catalog = state.cache.pluginCatalog || null;
  const entries = catalogEntriesFromCache();
  normalizeCatalogFilters(entries);
  const filteredEntries = filterCatalogEntries(entries);
  body.innerHTML = `
    <div class="plugin-catalog-modal">
      ${catalogStatusBanner(catalog)}
      ${catalogFilterBar(entries, filteredEntries)}
      <div id="catalogPluginsGrid">${catalogGrid(filteredEntries)}</div>
    </div>`;
  bindCatalogFilters();
}

function catalogStatusBanner(catalog) {
  if (catalogModalLoading) {
    return `<div class="plugin-catalog-banner loading"><span class="loading-spinner" aria-hidden="true"></span><strong>正在获取官方插件目录</strong><span>已安装插件可正常管理。</span></div>`;
  }
  if (catalogModalError) {
    return `<div class="plugin-catalog-banner error">
      <strong>官方插件目录加载失败</strong>
      <span>${esc(catalogModalError.message || String(catalogModalError))}</span>
    </div>`;
  }
  if (!catalog) {
    return `<div class="plugin-catalog-banner"><strong>官方插件目录尚未加载</strong><span>点击刷新目录后可安装或更新插件。</span></div>`;
  }
  const timeText = catalog.fetchedAtEpochMillis ? fmtTime(catalog.fetchedAtEpochMillis, true) : "-";
  const sourceText = catalog.source === "LOCAL_FALLBACK" ? "本地兜底目录" : "官方远程目录";
  const bannerClass = catalog.warning ? "warn" : "ok";
  return `<div class="plugin-catalog-banner ${bannerClass}">
    <strong>官方插件目录已加载</strong>
    <span>${esc((catalog.plugins || []).length)} 个插件 / ${esc(sourceText)} / 获取时间 ${esc(timeText)}</span>
    ${catalog.warning ? `<span>${esc(catalog.warning)}</span>` : ""}
  </div>`;
}

function catalogFilterBar(rows, filteredRows) {
  const active = pluginFilters.catalogStatus || pluginFilters.catalogCapability || pluginFilters.catalogKeyword;
  const statusValues = ["NOT_INSTALLED", "UPDATE_AVAILABLE", "INSTALLED", "INCOMPATIBLE", "RESOLVE_FAILED"]
    .filter(value => rows.some(item => item.catalogStatus === value));
  return `<div class="entity-filter-bar plugin-filter-bar">
    <span class="entity-filter-title">筛选</span>
    <div class="entity-filter-controls">
      <select data-catalog-filter="catalogStatus">${filterOptions("全部目录状态", statusValues, pluginFilters.catalogStatus, catalogStatusText)}</select>
      <select data-catalog-filter="catalogCapability">${filterOptions("全部能力", capabilityValues(rows), pluginFilters.catalogCapability, capabilityText)}</select>
      <input data-catalog-filter="catalogKeyword" value="${attr(pluginFilters.catalogKeyword)}" placeholder="插件名称 / ID / 描述">
      <button type="button" class="entity-filter-clear" data-action="catalog-filter-reset"${active ? "" : " disabled"}>清除</button>
      <button type="button" class="choice-refresh-button compact" data-action="catalog-refresh"${catalogModalLoading ? " disabled" : ""}>${catalogModalLoading ? "刷新中" : "刷新目录"}</button>
    </div>
    <span class="entity-filter-summary">显示 ${filteredRows.length} / ${rows.length}</span>
  </div>`;
}

function bindCatalogFilters() {
  const body = $("pluginCatalogModalBody");
  if (!body) return;
  body.querySelectorAll("[data-catalog-filter]").forEach(control => {
    const apply = () => {
      pluginFilters[control.dataset.catalogFilter] = control.value.trim();
      renderCatalogModalBody();
    };
    control.oninput = apply;
    control.onchange = apply;
  });
}

function catalogGrid(rows) {
  if (catalogModalLoading && rows.length === 0) {
    return `<div class="plugin-card-grid">
      <div class="plugin-card skeleton"></div>
      <div class="plugin-card skeleton"></div>
      <div class="plugin-card skeleton"></div>
    </div>`;
  }
  if (!rows.length) return `<div class="empty">暂无可显示的官方插件</div>`;
  return `<div class="plugin-card-grid">${rows.map(catalogCard).join("")}</div>`;
}

function catalogCard(item) {
  const installedText = item.installedVersion
    ? `已安装 v${item.installedVersion}${item.installedState ? ` / ${pluginStateText(item.installedState)}` : ""}`
    : "尚未安装";
  return `<article class="plugin-card ${catalogToneClass(item.catalogStatus)}">
    <div class="plugin-card-head">
      <div class="plugin-card-title">
        <span class="plugin-icon">${esc(pluginInitial(item))}</span>
        <div>
          <h3>${esc(item.name || item.id)}</h3>
          <span>${esc(item.id)} / v${esc(item.version || "-")}</span>
        </div>
      </div>
      ${catalogStatusPill(item.catalogStatus)}
    </div>
    <p class="plugin-card-description">${esc(item.description || "暂无描述")}</p>
    <div class="plugin-card-meta">
      <span>API ${esc(item.apiVersion || "-")}</span>
      <span>${esc(fmtBytes(item.sizeBytes || 0))}</span>
      <span>${esc(installedText)}</span>
    </div>
    ${capabilityTags(item.capabilities)}
    ${item.error ? `<div class="plugin-error-box">${esc(item.error)}</div>` : ""}
    <div class="plugin-card-footer">
      <div class="row-actions">${catalogLinks(item)}</div>
      <div class="row-actions">
        ${catalogOperationButton(item)}
        <button class="secondary" data-action="catalog-detail" data-id="${attr(item.id)}">详情</button>
      </div>
    </div>
  </article>`;
}

function catalogLinks(item) {
  const links = [];
  if (isSafeExternalUrl(item.homepageUrl)) links.push(`<a class="plugin-link" href="${attr(item.homepageUrl)}" target="_blank" rel="noreferrer noopener">主页</a>`);
  if (isSafeExternalUrl(item.releaseNotesUrl)) links.push(`<a class="plugin-link" href="${attr(item.releaseNotesUrl)}" target="_blank" rel="noreferrer noopener">更新说明</a>`);
  return links.length ? links.join("") : `<span class="sub-line">无外部链接</span>`;
}

function isSafeExternalUrl(value) {
  return /^https?:\/\//i.test(String(value || "").trim());
}

function catalogOperationButton(item) {
  if (item.catalogStatus === "NOT_INSTALLED") {
    return `<button class="plugin-action-install" data-action="catalog-install" data-id="${attr(item.id)}">安装</button>`;
  }
  if (item.catalogStatus === "UPDATE_AVAILABLE") {
    return `<button class="plugin-action-update" data-action="catalog-update" data-id="${attr(item.id)}">更新</button>`;
  }
  if (item.catalogStatus === "INCOMPATIBLE") {
    return `<button class="secondary" disabled>不兼容</button>`;
  }
  if (item.catalogStatus === "RESOLVE_FAILED") {
    return `<button class="secondary" disabled>不可用</button>`;
  }
  return `<button class="secondary" disabled>已安装</button>`;
}

async function runPluginLifecycleAction(action, id, button) {
  const verb = action.replace("plugin-", "");
  const text = { start: "启动", stop: "停止", reload: "重启" }[verb] || "操作";
  if (button) button.disabled = true;
  try {
    const result = await api(`/plugins/${encodeURIComponent(id)}/${verb}`, { method: "POST" });
    if (verb === "reload") delete state.pendingConfigRestarts[id];
    invalidate("plugins", "dashboard", "platformLogins", "configs");
    await reloadAfterOperation(false);
    const message = result.message ? result.message.replaceAll("重载", "重启") : `插件已${text}`;
    notify(message, false);
  } finally {
    if (button && button.isConnected) button.disabled = false;
  }
}

async function openCatalogOperation(id, mode) {
  const item = catalogEntryById(id);
  if (!item) throw new Error("未找到官方插件条目");
  const returnToCatalog = Boolean($("pluginCatalogModalBody"));
  const title = mode === "update" ? "更新插件" : "安装插件";
  const confirmText = mode === "update" ? "确认更新" : "确认安装";
  const endpoint = mode === "update" ? "update" : "install";
  openModal(title, `
    <div class="plugin-operation">
      <div class="plugin-card-title">
        <span class="plugin-icon">${esc(pluginInitial(item))}</span>
        <div>
          <h3>${esc(item.name || item.id)}</h3>
          <span>${esc(item.id)} / v${esc(item.version || "-")}</span>
        </div>
      </div>
      <div class="plugin-detail-grid">
        ${detailItem("目录状态", catalogStatusText(item.catalogStatus))}
        ${detailItem("本地版本", item.installedVersion || "未安装")}
        ${detailItem("官方版本", item.version || "-")}
        ${detailItem("文件大小", fmtBytes(item.sizeBytes || 0))}
        ${detailItem("API 版本", item.apiVersion || "-")}
        ${detailItem("SHA-256", item.sha256 || "-", true)}
      </div>
      <div class="plugin-catalog-banner warn">
        <strong>${mode === "update" ? "更新会先停止并卸载当前插件" : "安装完成后会自动加载并启动插件"}</strong>
        <span>后端会校验 sha256 和 Jar 内 plugin.yml，失败时会拒绝安装或回滚旧插件。</span>
      </div>
    </div>
  `, async () => {
    const confirmButton = $("modalConfirm");
    const originalText = confirmButton?.textContent;
    if (confirmButton) confirmButton.textContent = mode === "update" ? "正在更新..." : "正在安装...";
    try {
      const result = await api(`/plugin-catalog/${encodeURIComponent(item.id)}/${endpoint}`, { method: "POST" });
      closeModal();
      invalidate("plugins", "pluginCatalog", "dashboard", "platformLogins", "configs");
      await reloadAfterOperation(true);
      notify(result.message || `${title}已完成`, false);
      if (returnToCatalog) await openCatalogModal(false);
    } finally {
      if (confirmButton?.isConnected && originalText) confirmButton.textContent = originalText;
    }
  }, { size: "medium", confirmText });
}

async function reloadAfterOperation(forceCatalog) {
  pageSeq += 1;
  state.cache.plugins = await api("/plugins");
  await ctx.loadPluginAdminPages(true);
  if (forceCatalog || state.cache.pluginCatalog) {
    const result = await fetchCatalog(forceCatalog);
    catalogModalError = result.error;
  }
  renderPage();
}

function openInstalledPluginDetail(id) {
  const plugin = (state.cache.plugins || []).find(item => item.id === id);
  if (!plugin) throw new Error("未找到插件");
  const catalog = catalogEntryById(id);
  openModal("插件详情", `
    <div class="plugin-detail">
      <div class="plugin-card-title">
        <span class="plugin-icon">${esc(pluginInitial(plugin))}</span>
        <div>
          <h3>${esc(plugin.name || plugin.id)}</h3>
          <span>${esc(plugin.id)} / v${esc(plugin.version || "-")}</span>
        </div>
      </div>
      <div class="plugin-detail-grid">
        ${detailItem("运行状态", pluginStateText(plugin.state))}
        ${detailItem("插件版本", plugin.version ? `v${plugin.version}` : "-")}
        ${detailItem("加载时间", plugin.loadTime ? fmtTime(plugin.loadTime, true) : "-")}
        ${detailItem("加载路径", plugin.sourceJarPath || "-", true)}
        ${detailItem("官方版本", catalog && catalog.version || "未匹配官方目录")}
        ${detailItem("目录状态", catalog ? catalogStatusText(catalog.catalogStatus) : "-")}
      </div>
      <section class="plugin-detail-section">
        <h3>能力</h3>
        ${capabilityTags(plugin.capabilities)}
      </section>
      ${plugin.error ? `<section class="plugin-detail-section"><h3>错误</h3><div class="plugin-error-box">${esc(plugin.error)}</div></section>` : ""}
      <div class="row-actions plugin-detail-actions">${installedPluginActions(plugin)}</div>
    </div>
  `, null, { size: "medium", cancelText: "关闭" });
}

function openCatalogPluginDetail(id) {
  const item = catalogEntryById(id);
  if (!item) throw new Error("未找到官方插件条目");
  openModal("官方插件详情", `
    <div class="plugin-detail">
      <div class="plugin-card-title">
        <span class="plugin-icon">${esc(pluginInitial(item))}</span>
        <div>
          <h3>${esc(item.name || item.id)}</h3>
          <span>${esc(item.id)} / v${esc(item.version || "-")}</span>
        </div>
      </div>
      <p class="plugin-card-description">${esc(item.description || "暂无描述")}</p>
      <div class="plugin-detail-grid">
        ${detailItem("目录状态", catalogStatusText(item.catalogStatus))}
        ${detailItem("本地版本", item.installedVersion || "未安装")}
        ${detailItem("本地状态", item.installedState ? pluginStateText(item.installedState) : "-")}
        ${detailItem("API 版本", item.apiVersion || "-")}
        ${detailItem("文件大小", fmtBytes(item.sizeBytes || 0))}
        ${detailItem("下载地址", item.downloadUrl || "-", true)}
        ${detailItem("SHA-256", item.sha256 || "-", true)}
      </div>
      <section class="plugin-detail-section">
        <h3>能力</h3>
        ${capabilityTags(item.capabilities)}
      </section>
      ${item.error ? `<section class="plugin-detail-section"><h3>提示</h3><div class="plugin-error-box">${esc(item.error)}</div></section>` : ""}
      <div class="plugin-card-footer">
        <div class="row-actions">${catalogLinks(item)}</div>
        <div class="row-actions">${catalogOperationButton(item)}</div>
      </div>
    </div>
  `, null, { size: "medium", cancelText: "关闭" });
}

function detailItem(title, value, mono = false) {
  return `<div class="plugin-detail-item">
    <span>${esc(title)}</span>
    <strong class="${mono ? "mono" : ""}">${esc(value || "-")}</strong>
  </div>`;
}

function catalogEntriesFromCache() {
  const catalog = state.cache.pluginCatalog;
  return catalog && Array.isArray(catalog.plugins) ? catalog.plugins : [];
}

function catalogEntryById(id) {
  return catalogEntriesFromCache().find(item => item.id === id) || null;
}

function capabilityValues(rows) {
  const values = new Set();
  (rows || []).forEach(row => (row.capabilities || []).forEach(value => values.add(value)));
  return Array.from(values).sort((a, b) => capabilityText(a).localeCompare(capabilityText(b), "zh-CN"));
}

function capabilityTags(values) {
  const items = (values || []).map(capabilityText);
  return items.length ? tags(items) : `<span class="sub-line">-</span>`;
}

function pluginInitial(plugin) {
  const text = plugin && (plugin.name || plugin.id) || "?";
  return String(text).trim().slice(0, 1).toUpperCase() || "?";
}

function pluginStateText(value) {
  const map = {
    ACTIVE: "运行中",
    LOADED: "已加载",
    FAILED: "失败",
  };
  return map[value] || value || "-";
}

function pluginStatePill(value) {
  const cls = value === "ACTIVE" ? "ok" : value === "FAILED" ? "bad" : "warn";
  return `<span class="pill ${cls}">${esc(pluginStateText(value))}</span>`;
}

function catalogStatusText(value) {
  const map = {
    NOT_INSTALLED: "未安装",
    INSTALLED: "已安装",
    UPDATE_AVAILABLE: "可更新",
    INCOMPATIBLE: "不兼容",
    RESOLVE_FAILED: "解析失败",
  };
  return map[value] || value || "-";
}

function catalogStatusPill(value) {
  const cls = value === "INSTALLED" ? "ok"
    : value === "UPDATE_AVAILABLE" ? "warn"
    : value === "NOT_INSTALLED" ? "info"
    : "bad";
  return `<span class="pill ${cls}">${esc(catalogStatusText(value))}</span>`;
}

function catalogToneClass(value) {
  if (value === "UPDATE_AVAILABLE") return "needs-update";
  if (value === "NOT_INSTALLED") return "installable";
  if (value === "INCOMPATIBLE" || value === "RESOLVE_FAILED") return "blocked";
  return "installed";
}

function capabilityText(value) {
  const map = {
    PUBLISHER_SOURCE: "动态检测",
    PUBLISHER_LOOKUP: "发布者查询",
    PUBLISHER_FOLLOW: "发布者关注",
    PUBLISHER_LOGIN: "平台登录",
    MESSAGE_SINK: "消息发送",
    INCOMING_MESSAGE_CONSUMER: "入站消息消费",
    COMMAND_CONTRIBUTOR: "命令",
    LINK_RESOLVER: "链接解析",
    LINK_VIDEO_DOWNLOADER: "视频下载",
    CONFIGURABLE: "配置",
  };
  return map[value] || value || "-";
}
