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
let identityMeta;
let platformTag;
let themeSwatch;
let renderTable;
let notify;
let openModal;
let closeModal;
let confirmDanger;
let loadingRow;
let uniqueValues;
let filterOptions;
let matchesExact;
let matchesAnyContains;
let linkParseModeLabel;
let linkParseModeOptions;
let loadSubscriberTargetCandidates;
let subscriberTargetAddressKey;
let messageTargetChoiceHtml;
let bindTargetSourceToggles;
let selectedTargetPriorityAccount;
let eventTypes;
let blockKinds;
let publisherKey;
let targetKey;
let policyEvents;
let mentionEvents;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;
let subscriptionFilterTimer;
let createSubscriptionModalSeq = 0;
const subscriptionFilters = {
  publisherPlatform: "",
  publisherKind: "",
  publisherId: "",
  subscriberPlatform: "",
  subscriberKind: "",
  subscriberId: "",
};

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
    identityMeta,
    platformTag,
    themeSwatch,
    renderTable,
    notify,
    openModal,
    closeModal,
    confirmDanger,
    loadingRow,
    uniqueValues,
    filterOptions,
    matchesExact,
    matchesAnyContains,
    linkParseModeLabel,
    linkParseModeOptions,
    loadSubscriberTargetCandidates,
    subscriberTargetAddressKey,
    messageTargetChoiceHtml,
    bindTargetSourceToggles,
    selectedTargetPriorityAccount,
    eventTypes,
    blockKinds,
    publisherKey,
    targetKey,
    policyEvents,
    mentionEvents,
  } = ui);
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadSubscriptions(ctx.force);
}

export function unmount() {
  clearTimeout(subscriptionFilterTimer);
  invalidatePageRequests("subscriptions");
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "create-subscription") {
    await openCreateSubscription();
    return true;
  }
  if (action === "export-subscriptions") {
    await openSubscriptionExportModal();
    return true;
  }
  if (action === "import-subscriptions") {
    openSubscriptionImportModal();
    return true;
  }
  if (action === "subscription-detail") {
    await openSubscriptionDetail(id);
    return true;
  }
  if (action === "delete-subscription") {
    if (!(await confirmDanger("删除订阅", "确定删除这个订阅吗？该订阅下的动态过滤规则也会一并移除。", { confirmText: "删除" }))) return true;
    const result = await api(`/subscriptions/${id}`, { method: "DELETE" });
    invalidate("dashboard", "subscriptions", "publishers", "subscribers");
    await loadSubscriptions(true);
    notify(result.message, false);
    return true;
  }
  if (action === "add-filter") {
    openFilterRuleModal(id);
    return true;
  }
  if (action === "clear-filters") {
    if (!(await confirmDanger("清空过滤规则", "确定清空这个订阅的全部过滤规则吗？", { confirmText: "清空" }))) return true;
    const result = await api(`/subscriptions/${id}/filter-rules`, { method: "DELETE" });
    invalidate("subscriptions", "dashboard");
    const subscriptions = await ensureSubscriptions(true);
    const subscription = subscriptions.find(item => Number(item.id) === Number(id));
    const filterList = $("filterList");
    if (filterList) filterList.innerHTML = renderFilterList(subscription);
    notify(result.message || "过滤规则已清空", false);
    return true;
  }
  if (action === "delete-filter") {
    if (!(await confirmDanger("删除过滤规则", "确定删除这条过滤规则吗？", { confirmText: "删除" }))) return true;
    await api(`/filter-rules/${id}`, { method: "DELETE" });
    invalidate("subscriptions", "dashboard");
    const subscriptions = await ensureSubscriptions(true);
    const subscription = subscriptions.find(item => Number(item.id) === Number(button.dataset.subscription));
    const filterList = $("filterList");
    if (filterList) filterList.innerHTML = renderFilterList(subscription);
    notify("过滤规则已删除", false);
    return true;
  }
  return false;
}

async function ensureSubscriptions(force) {
  if (force || !state.cache.subscriptions) state.cache.subscriptions = await api("/subscriptions");
  return state.cache.subscriptions;
}

async function ensurePublisherPlatforms(force) {
  if (force || !state.cache.publisherPlatforms) {
    state.cache.publisherPlatforms = await api("/publisher-platforms").catch(() => []);
  }
  return state.cache.publisherPlatforms;
}

async function loadSubscriptions(force) {
  const request = beginPageRequest("subscriptions");
  releaseMediaObjectUrls();
  const rows = await ensureSubscriptions(force);
  if (!isCurrentPageRequest(request)) return;
  normalizeSubscriptionFilters(rows);
  const filteredRows = filterSubscriptions(rows);
  pageRoot().innerHTML = `
    <section class="page">
      <section class="panel full">
        <div class="panel-head">
          <h2>订阅关系</h2>
          <div class="row-actions">
            <button class="secondary subscription-export-button" data-action="export-subscriptions">导出</button>
            <button class="secondary subscription-import-button" data-action="import-subscriptions">导入/批量订阅</button>
            <button class="add-button" data-action="create-subscription">添加订阅</button>
          </div>
        </div>
        ${subscriptionFilterBar(rows, filteredRows)}
        <div id="subscriptionTable">${subscriptionTableHtml(filteredRows)}</div>
      </section>
    </section>`;
  bindSubscriptionFilters();
  await hydrateMediaImages($("content"));
}

function subscriptionTableHtml(rows) {
  return renderTable(rows, [
    { title: "发布者", render: s => subscriptionPublisherCell(s.publisher) },
    { title: "消息目标", render: s => subscriptionTargetCell(s.subscriber) },
    { title: "接收事件", render: s => tags(policyEvents(s.policy).map(eventLabel)) },
    { title: "@全体", render: s => tags(mentionEvents(s.policy).map(eventLabel)) },
    { title: "动态过滤", render: s => cell(`${s.filterRuleCount || 0} 条阻止规则`, "仅作用于动态内容") },
    { title: "更新时间", render: s => `<span class="sub-line">${fmtTime(s.updatedAtEpochSeconds)}</span>` },
    { title: "操作", render: s => `<div class="row-actions"><button data-action="subscription-detail" data-id="${s.id}">编辑</button><button class="danger" data-action="delete-subscription" data-id="${s.id}">删除</button></div>` }
  ]);
}

function subscriptionPublisherCell(publisher) {
  if (!publisher) return identity("-", "-", null);
  return subscriptionEntityCell(
    publisher.name,
    publisher.avatarUri,
    publisher.platformId,
    "AVATAR",
    publisher.platformId,
    label(publisher.kind),
    publisher.externalId,
  );
}

function subscriptionTargetCell(target) {
  if (!target) return identity("-", "-", null);
  return subscriptionEntityCell(
    target.name,
    target.avatarUri,
    target.platformId,
    "AVATAR",
    target.platformId,
    label(target.targetKind),
    target.externalId,
  );
}

function subscriptionEntityCell(name, image, mediaPlatform, mediaKind, platformId, typeText, externalId) {
  return identityMeta(name, image, mediaPlatform, mediaKind, platformId, typeText, externalId);
}

function subscriptionFilterBar(rows, filteredRows) {
  const active = subscriptionFilterActive();
  const publisherPlatforms = uniqueValues(rows, row => row.publisher && row.publisher.platformId);
  const publisherKinds = uniqueValues(rows, row => row.publisher && row.publisher.kind);
  const subscriberPlatforms = uniqueValues(rows, row => row.subscriber && row.subscriber.platformId);
  const subscriberKinds = uniqueValues(rows, row => row.subscriber && row.subscriber.targetKind);

  return `<div class="entity-filter-bar subscription-entity-filter">
    <span class="entity-filter-title">筛选</span>
    <div class="entity-filter-controls">
      <select data-subscription-filter="publisherPlatform">${filterOptions("发布者平台", publisherPlatforms, subscriptionFilters.publisherPlatform)}</select>
      <select data-subscription-filter="publisherKind">${filterOptions("发布者类型", publisherKinds, subscriptionFilters.publisherKind, label)}</select>
      <input data-subscription-filter="publisherId" value="${attr(subscriptionFilters.publisherId)}" placeholder="发布者搜索">
      <div class="entity-filter-divider"></div>
      <select data-subscription-filter="subscriberPlatform">${filterOptions("目标平台", subscriberPlatforms, subscriptionFilters.subscriberPlatform)}</select>
      <select data-subscription-filter="subscriberKind">${filterOptions("目标类型", subscriberKinds, subscriptionFilters.subscriberKind, label)}</select>
      <input data-subscription-filter="subscriberId" value="${attr(subscriptionFilters.subscriberId)}" placeholder="目标搜索">
      <button type="button" class="entity-filter-clear" data-subscription-filter-reset${active ? "" : " disabled"}>清除</button>
    </div>
    <span class="entity-filter-summary" data-subscription-filter-summary>${filteredRows.length} / ${rows.length}</span>
  </div>`;
}

function bindSubscriptionFilters() {
  pageRoot().querySelectorAll("[data-subscription-filter]").forEach(control => {
    const apply = () => {
      subscriptionFilters[control.dataset.subscriptionFilter] = control.value.trim();
      refreshSubscriptionTable();
    };
    if (control.tagName === "INPUT") {
      control.oninput = () => {
        clearTimeout(subscriptionFilterTimer);
        subscriptionFilterTimer = setTimeout(apply, 220);
      };
      control.onkeydown = event => {
        if (event.key !== "Enter") return;
        clearTimeout(subscriptionFilterTimer);
        apply();
      };
    } else {
      control.onchange = apply;
    }
  });
  const reset = pageRoot().querySelector("[data-subscription-filter-reset]");
  if (reset) {
    reset.onclick = () => {
      Object.keys(subscriptionFilters).forEach(key => subscriptionFilters[key] = "");
      pageRoot().querySelectorAll("[data-subscription-filter]").forEach(control => control.value = "");
      refreshSubscriptionTable();
    };
  }
}

function refreshSubscriptionTable() {
  const rows = state.cache.subscriptions || [];
  const filteredRows = filterSubscriptions(rows);
  const table = $("subscriptionTable");
  if (table) {
    releaseMediaObjectUrls();
    table.innerHTML = subscriptionTableHtml(filteredRows);
    hydrateMediaImages(table).catch(handleError);
  }
  const summary = pageRoot().querySelector("[data-subscription-filter-summary]");
  if (summary) summary.textContent = `${filteredRows.length} / ${rows.length}`;
  const reset = pageRoot().querySelector("[data-subscription-filter-reset]");
  if (reset) reset.disabled = !subscriptionFilterActive();
}

function filterSubscriptions(rows) {
  return (rows || []).filter(row => {
    const publisher = row.publisher || {};
    const subscriber = row.subscriber || {};
    return matchesExact(publisher.platformId, subscriptionFilters.publisherPlatform) &&
      matchesExact(publisher.kind, subscriptionFilters.publisherKind) &&
      matchesAnyContains([publisher.externalId, publisher.name], subscriptionFilters.publisherId) &&
      matchesExact(subscriber.platformId, subscriptionFilters.subscriberPlatform) &&
      matchesExact(subscriber.targetKind, subscriptionFilters.subscriberKind) &&
      matchesAnyContains([subscriber.externalId, subscriber.name], subscriptionFilters.subscriberId);
  });
}

function normalizeSubscriptionFilters(rows) {
  normalizeSubscriptionFilter("publisherPlatform", uniqueValues(rows, row => row.publisher && row.publisher.platformId));
  normalizeSubscriptionFilter("publisherKind", uniqueValues(rows, row => row.publisher && row.publisher.kind));
  normalizeSubscriptionFilter("subscriberPlatform", uniqueValues(rows, row => row.subscriber && row.subscriber.platformId));
  normalizeSubscriptionFilter("subscriberKind", uniqueValues(rows, row => row.subscriber && row.subscriber.targetKind));
}

function normalizeSubscriptionFilter(key, values) {
  if (subscriptionFilters[key] && !values.includes(subscriptionFilters[key])) subscriptionFilters[key] = "";
}

function subscriptionFilterActive() {
  return Object.values(subscriptionFilters).some(Boolean);
}

async function openSubscriptionExportModal() {
  const rows = await ensureSubscriptions(false);
  const filteredRows = filterSubscriptions(rows);
  const filteredDisabled = filteredRows.length === 0;
  openModal("导出订阅", `
    <div class="subscription-transfer">
      <section class="panel full subscription-transfer-card">
        <div class="panel-head">
          <div>
            <h2>导出范围</h2>
            <p>导出文件只包含订阅关系、订阅规则、@全体规则和动态过滤规则。</p>
          </div>
        </div>
        <div class="subscription-export-options">
          <label class="target-choice">
            <input type="radio" name="subscriptionExportScope" value="all" checked>
            <span class="target-choice-text">全部订阅 · ${rows.length} 条</span>
          </label>
          <label class="target-choice${filteredDisabled ? " disabled" : ""}">
            <input type="radio" name="subscriptionExportScope" value="filtered"${filteredDisabled ? " disabled" : ""}>
            <span class="target-choice-text">当前筛选结果 · ${filteredRows.length} 条</span>
          </label>
        </div>
        <div class="inline-note">导出不会包含名称、头像、头图等展示缓存；再次导入时会由插件重新解析。</div>
      </section>
    </div>
  `, async () => {
    const scope = document.querySelector(`input[name="subscriptionExportScope"]:checked`)?.value || "all";
    const ids = scope === "filtered" ? filteredRows.map(row => row.id) : null;
    const payload = ids ? { subscriptionIds: ids } : {};
    const documentData = await api("/subscriptions/export", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    downloadJson(
      documentData,
      `dynamic-bot-subscriptions-${formatDownloadTimestamp(new Date())}.json`,
    );
    closeModal();
    notify(scope === "filtered" ? "当前筛选结果已导出" : "全部订阅已导出", false);
  }, { size: "small", confirmText: "导出" });
}

function openSubscriptionImportModal() {
  openModal("导入/批量订阅", `
    <div class="subscription-transfer">
      <section class="panel full subscription-transfer-card">
        <div class="panel-head">
          <div>
            <h2>导入订阅</h2>
            <p>支持当前 dynamic-bot JSON，也支持旧项目订阅 YAML 的 dynamic 数据。</p>
          </div>
        </div>
        <div class="form-grid single">
          <div class="field full subscription-import-options">
            <label class="check"><input type="checkbox" id="subscriptionImportFetchProfiles" checked>导入时拉取发布者和目标资料</label>
            <label class="check"><input type="checkbox" id="subscriptionImportAutoFollow" checked>导入后自动关注发布者</label>
          </div>
          <div class="field full">
            <label>导入格式</label>
            <select id="subscriptionImportFormat">
              <option value="JSON">dynamic-bot JSON</option>
              <option value="LEGACY_DYNAMIC_YAML">bilibili-dynamic-mirai-plugin 插件订阅数据 YAML</option>
            </select>
          </div>
          <div class="field full">
            <label>选择文件</label>
            <input type="file" id="subscriptionImportFile" accept="application/json,.json,.yml,.yaml">
          </div>
          <div class="field full">
            <label id="subscriptionImportTextLabel">粘贴 JSON</label>
            <textarea id="subscriptionImportText" class="subscription-import-text" placeholder="可以粘贴导出的 dynamic-bot 订阅 JSON"></textarea>
          </div>
        </div>
        <div id="subscriptionImportSummary" class="batch-result" hidden></div>
        <div id="subscriptionImportNote" class="inline-note">导入不是全局事务：单条失败不会影响其他订阅。</div>
      </section>
      <div id="subscriptionImportResult" class="batch-result" hidden></div>
    </div>
  `, async () => {
    const result = await submitSubscriptionImport();
    invalidate("dashboard", "subscriptions", "publishers", "subscribers");
    await loadSubscriptions(true);
    const warningLines = subscriptionImportWarnings(result);
    if (result.failed > 0 || warningLines.length) {
      renderSubscriptionImportResult(result);
    }
    if (result.failed > 0) {
      throw new Error(`导入完成，但有 ${result.failed} 条失败`);
    }
    if (warningLines.length) {
      notify("导入完成，存在提示信息，请查看结果明细", false);
      return;
    }
    closeModal();
    notify(`导入完成：创建 ${result.created}，更新 ${result.updated}`, false);
  }, { size: "subscription", confirmText: "导入" });

  const fileInput = $("subscriptionImportFile");
  const textInput = $("subscriptionImportText");
  const formatSelect = $("subscriptionImportFormat");
  const refreshSummary = () => renderSubscriptionImportSummary();
  fileInput.onchange = async () => {
    const file = fileInput.files && fileInput.files[0];
    if (!file) return;
    textInput.value = await file.text();
    refreshSummary();
  };
  formatSelect.onchange = () => {
    refreshSubscriptionImportFormatUi();
    refreshSummary();
  };
  textInput.oninput = refreshSummary;
  refreshSubscriptionImportFormatUi();
}

async function submitSubscriptionImport() {
  const format = subscriptionImportFormat();
  if (format === "LEGACY_DYNAMIC_YAML") {
    const content = subscriptionImportRawText();
    return api("/subscriptions/import/legacy-dynamic-yaml", {
      method: "POST",
      body: JSON.stringify({ content, importOptions: subscriptionImportOptions() }),
    });
  }
  const parsedDocument = parseSubscriptionImportText();
  const documentData = withSubscriptionImportOptions(parsedDocument);
  return api("/subscriptions/import", {
    method: "POST",
    body: JSON.stringify(documentData),
  });
}

function subscriptionImportFormat() {
  return $("subscriptionImportFormat")?.value || "JSON";
}

function subscriptionImportRawText() {
  const text = $("subscriptionImportText")?.value.trim();
  if (!text) throw new Error("请先选择文件或粘贴导入内容");
  return text;
}

function refreshSubscriptionImportFormatUi() {
  const legacy = subscriptionImportFormat() === "LEGACY_DYNAMIC_YAML";
  const labelNode = $("subscriptionImportTextLabel");
  const input = $("subscriptionImportText");
  const autoFollow = $("subscriptionImportAutoFollow");
  const note = $("subscriptionImportNote");
  if (labelNode) labelNode.textContent = legacy ? "粘贴旧版 YAML" : "粘贴 JSON";
  if (input) input.placeholder = legacy
    ? "粘贴 bilibili-dynamic-mirai-plugin 的订阅 YAML，只会导入 dynamic 数据"
    : "可以粘贴导出的 dynamic-bot 订阅 JSON";
  if (note) note.textContent = legacy
    ? "旧版 miari YAML 只解析 dynamic 字段，跳过 0；负数 QQ 号导入为群，正数导入为好友。"
    : "导入不是全局事务：单条失败不会影响其他订阅。";
}

function parseSubscriptionImportText() {
  const text = subscriptionImportRawText();
  let documentData;
  try {
    documentData = JSON.parse(text);
  } catch (error) {
    throw new Error("JSON 格式无效，请检查后重试");
  }
  if (!documentData || documentData.schemaVersion !== 1 || !Array.isArray(documentData.subscriptions)) {
    throw new Error("订阅导入文件格式无效");
  }
  return documentData;
}

function withPublisherLookupMode(documentData, mode) {
  return {
    ...documentData,
    subscriptions: (documentData.subscriptions || []).map(item => ({
      ...item,
      publisherLookupMode: item.publisherLookupMode || mode,
    })),
  };
}

function withSubscriptionImportOptions(documentData) {
  const { fetchProfiles, autoFollowPublishers } = subscriptionImportOptions();
  const next = {
    ...documentData,
    importOptions: {
      ...(documentData.importOptions || {}),
      fetchProfiles,
      autoFollowPublishers,
    },
  };
  return fetchProfiles ? next : withPublisherLookupMode(next, "PLACEHOLDER");
}

function subscriptionImportOptions() {
  return {
    fetchProfiles: $("subscriptionImportFetchProfiles")?.checked !== false,
    autoFollowPublishers: $("subscriptionImportAutoFollow")?.checked !== false,
  };
}

function renderSubscriptionImportSummary() {
  const node = $("subscriptionImportSummary");
  if (!node) return;
  if (subscriptionImportFormat() === "LEGACY_DYNAMIC_YAML") {
    const text = $("subscriptionImportText")?.value.trim();
    if (!text) {
      node.hidden = true;
      node.innerHTML = "";
      node.classList.remove("error");
      return;
    }
    const summary = summarizeLegacyDynamicYamlText(text);
    node.hidden = false;
    node.classList.toggle("error", !!summary.error);
    node.innerHTML = summary.error
      ? `<strong>${esc(summary.error)}</strong>`
      : `<strong>旧版 YAML 待导入</strong><ul>
          <li>预计订阅 ${summary.subscriptionCount} 条，发布者 ${summary.publisherCount} 个，QQ 群 ${summary.groupCount} 个，QQ 好友 ${summary.userCount} 个</li>
          <li>导入时会跳过 dynamic.0，并使用发布者 UID 创建占位发布者</li>
        </ul>`;
    return;
  }
  let documentData;
  try {
    documentData = parseSubscriptionImportText();
  } catch (error) {
    node.hidden = false;
    node.classList.add("error");
    node.innerHTML = `<strong>${esc(error.message || String(error))}</strong>`;
    return;
  }
  const summary = summarizeSubscriptionDocument(documentData);
  node.hidden = false;
  node.classList.remove("error");
  node.innerHTML = `<strong>解析成功</strong><ul>
    <li>订阅 ${summary.subscriptionCount} 条，发布者 ${summary.publisherCount} 个，消息目标 ${summary.targetCount} 个</li>
    <li>动态过滤规则 ${summary.filterRuleCount} 条</li>
    <li>${$("subscriptionImportFetchProfiles")?.checked === false ? "不会拉取资料，缺失的发布者和目标会使用 ID 占位" : "会批量拉取发布者和目标资料"}</li>
    <li>导入后已有订阅会更新规则，并替换对应过滤规则</li>
  </ul>`;
}

function summarizeLegacyDynamicYamlText(text) {
  const lines = String(text || "").split(/\r?\n/);
  let inDynamic = false;
  let currentUid = "";
  let inContacts = false;
  const publisherIds = new Set();
  const groupIds = new Set();
  const userIds = new Set();
  let subscriptionCount = 0;
  for (const line of lines) {
    if (/^dynamic\s*:\s*$/.test(line)) {
      inDynamic = true;
      currentUid = "";
      inContacts = false;
      continue;
    }
    if (!inDynamic) continue;
    const topLevel = /^([^\s#][^:]*):\s*$/.exec(line);
    if (topLevel && topLevel[1] !== "dynamic") break;
    const uidMatch = /^\s{2}([0-9]+)\s*:\s*$/.exec(line);
    if (uidMatch) {
      currentUid = uidMatch[1];
      inContacts = false;
      if (currentUid !== "0") publisherIds.add(currentUid);
      continue;
    }
    if (!currentUid || currentUid === "0") continue;
    if (/^\s{4}contacts\s*:\s*$/.test(line)) {
      inContacts = true;
      continue;
    }
    if (inContacts) {
      const contactMatch = /^\s{6}-\s*['"]?(-?\d+)['"]?\s*$/.exec(line);
      if (contactMatch) {
        const contact = contactMatch[1];
        const targetId = contact.replace(/^-/, "");
        if (!targetId) continue;
        subscriptionCount += 1;
        if (contact.startsWith("-")) groupIds.add(targetId); else userIds.add(targetId);
      } else if (/^\s{4}\S/.test(line)) {
        inContacts = false;
      }
    }
  }
  if (!publisherIds.size && !subscriptionCount) {
    return { error: "没有识别到可导入的 dynamic 订阅" };
  }
  return {
    subscriptionCount,
    publisherCount: publisherIds.size,
    groupCount: groupIds.size,
    userCount: userIds.size,
  };
}

function summarizeSubscriptionDocument(documentData) {
  const subscriptions = Array.isArray(documentData.subscriptions) ? documentData.subscriptions : [];
  const publishers = new Set();
  const targets = new Set();
  let filterRuleCount = 0;
  subscriptions.forEach(item => {
    const publisher = item.publisher || {};
    const target = item.target || {};
    publishers.add([publisher.platformId, publisher.kind || "USER", publisher.externalId].join(":"));
    targets.add([target.platformId, target.targetKind, target.externalId, target.scopeId || "", target.threadId || ""].join(":"));
    filterRuleCount += Array.isArray(item.filterRules) ? item.filterRules.length : 0;
  });
  return {
    subscriptionCount: subscriptions.length,
    publisherCount: publishers.size,
    targetCount: targets.size,
    filterRuleCount,
  };
}

function renderSubscriptionImportResult(result) {
  const node = $("subscriptionImportResult");
  if (!node) return;
  const failedItems = (result.items || []).filter(item => item.status === "FAILED");
  const warningLines = subscriptionImportWarnings(result).slice(0, 12);
  node.hidden = false;
  node.classList.toggle("error", failedItems.length > 0);
  node.classList.toggle("success", failedItems.length === 0);
  node.innerHTML = `<strong>导入结果：创建 ${result.created}，更新 ${result.updated}，失败 ${result.failed}</strong>${
    failedItems.length || warningLines.length
      ? `<ul>${warningLines.map(line => `<li>${esc(line)}</li>`).join("")}${failedItems.map(item =>
        `<li>第 ${Number(item.index) + 1} 条：${esc(item.message || "导入失败")}</li>`
      ).join("")}</ul>`
      : ""
  }`;
}

function subscriptionImportWarnings(result) {
  const warnings = [...(result.warnings || [])];
  (result.items || []).forEach(item => {
    (item.warnings || []).forEach(line => {
      warnings.push(`第 ${Number(item.index) + 1} 条：${line}`);
    });
  });
  return warnings;
}

async function importSubscriptionDocument(documentData) {
  const result = await api("/subscriptions/import", {
    method: "POST",
    body: JSON.stringify(documentData),
  });
  invalidate("dashboard", "subscriptions", "publishers", "subscribers");
  await loadSubscriptions(true);
  return result;
}

function downloadJson(data, filename) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function formatDownloadTimestamp(date) {
  const pad = value => String(value).padStart(2, "0");
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`;
}

async function openCreateSubscription() {
  const modalSeq = ++createSubscriptionModalSeq;
  let modalClosed = false;
  const isModalActive = () => !modalClosed && createSubscriptionModalSeq === modalSeq && !!$("subNewTargetCandidateList");
  await ensureSubscriptions(false);
  if (!state.cache.publishers) state.cache.publishers = await api("/publishers");
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const targetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  const fallbackTargets = targetPlatforms.length ? targetPlatforms : [{ platformId: "qq", pluginName: "手动", supportedTypes: ["GROUP", "USER"] }];
  const publisherPlatforms = await ensurePublisherPlatforms(false);
  const existingTargets = state.cache.subscribers || [];
  const existingPublishers = state.cache.publishers || [];
  const targetMode = existingTargets.length ? "existing" : "new";
  const publisherMode = existingPublishers.length ? "existing" : "new";
  const initialPublisherPlatform = publisherMode === "existing" ? "" : publisherPlatforms[0]?.platformId;
  const initialSupportsLive = publisherPlatformSupportsLive(initialPublisherPlatform);
  let targetCandidates = [];
  let publisherCandidates = [];
  let createFilterConditions = [];

  openModal("添加订阅", `
    <div class="subscription-create subscription-create-subscription">
      <section class="panel subscription-card subscription-target-card">
        <div class="panel-head">
          <div>
            <h2>消息目标</h2>
          </div>
        </div>
        <div class="subscription-mode-row">
          ${subscriptionModeSwitch("subTargetMode", [
            { value: "existing", text: "选择已有目标", checked: targetMode === "existing", disabled: existingTargets.length === 0 },
            { value: "new", text: "新增目标", checked: targetMode === "new" },
          ])}
          <button type="button" class="secondary compact choice-tool-button choice-clear-button" id="subExistingTargetClear">清空</button>
        </div>
        <div id="subExistingTargetBlock" class="form-grid single">
          <div class="field full">
            <div id="subExistingTargetList" class="target-choice-list">${existingTargets.length
              ? existingTargets.map((target, index) => existingTargetChoiceHtml(target, index, false)).join("")
              : `<div class="empty">还没有消息目标，请切换到新增目标。</div>`}</div>
          </div>
        </div>
        <div id="subNewTargetBlock" class="form-grid">
          <div class="field"><label>目标平台</label><select id="subNewTargetPlatform">${fallbackTargets.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")}</select></div>
          <div class="field"><label>目标类型</label><select id="subNewTargetKind"></select></div>
          <div class="field full" id="subNewTargetCandidateWrap">
            <div class="field-head">
              <div class="field-title-line">
                <label>可用目标</label>
                <span id="subNewTargetStatus" class="field-inline-status"></span>
              </div>
              <div class="row-actions" id="subNewTargetCandidateActions">
                <button type="button" class="secondary compact choice-tool-button choice-refresh-button" id="subNewTargetRefresh">刷新</button>
                <button type="button" class="secondary compact choice-tool-button" id="subNewTargetSelectAll">全选</button>
                <button type="button" class="secondary compact choice-tool-button choice-clear-button" id="subNewTargetClearAll">清空</button>
              </div>
            </div>
            <div id="subNewTargetCandidateList" class="target-choice-list"></div>
          </div>
          <div class="field full" id="subNewTargetManualWrap"><label>目标 ID</label><input id="subNewTargetManual" placeholder="插件无法枚举目标时手动填写"></div>
          <div class="field full"><label>链接解析</label><select id="subNewTargetLinkParse">${linkParseModeOptions("INHERIT")}</select><span class="inline-note">使用全局回退时，会跟随主配置里的回退触发方式。</span></div>
        </div>
      </section>

      <section class="panel subscription-card subscription-publisher-card">
        <div class="panel-head">
          <div>
            <h2>发布者</h2>
          </div>
        </div>
        <div class="subscription-mode-row">
          ${subscriptionModeSwitch("subPublisherMode", [
            { value: "existing", text: "选择已有发布者", checked: publisherMode === "existing", disabled: existingPublishers.length === 0 },
            { value: "new", text: "新增发布者", checked: publisherMode === "new" },
          ])}
          <button type="button" class="secondary compact choice-tool-button choice-clear-button" id="subExistingPublisherClear">清空</button>
        </div>
        <div id="subExistingPublisherBlock" class="form-grid single">
          <div class="field full">
            <div id="subExistingPublisherList" class="target-choice-list">${existingPublishers.length
              ? existingPublishers.map((publisher, index) => existingPublisherChoiceHtml(publisher, index, false)).join("")
              : `<div class="empty">还没有发布者，请切换到新增发布者。</div>`}</div>
          </div>
        </div>
        <div id="subNewPublisherBlock" class="form-grid single publisher-create-form">
          <div class="field"><label>发布者平台</label><select id="subNewPublisherPlatform">${publisherPlatforms.length
            ? publisherPlatforms.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")
            : `<option value="">无可用发布者平台</option>`}</select></div>
          <div class="field">
            <label>发布者 UID / 用户名<span class="required-mark">*</span></label>
            <div class="publisher-search-row">
              <input id="subNewPublisherId" placeholder="填写 UID 会自动查询资料；填写用户名需搜索选择">
              <button type="button" class="publisher-search-button" id="subNewPublisherSearch">搜索</button>
            </div>
          </div>
          <div class="field full" id="subNewPublisherResultWrap" hidden>
            <div class="field-head">
              <label>搜索结果</label>
              <div class="row-actions"><button type="button" class="secondary compact choice-tool-button choice-clear-button" id="subNewPublisherResultClear">清空</button></div>
            </div>
            <div id="subNewPublisherResultList" class="target-choice-list"></div>
          </div>
          <div class="field full"><span id="subNewPublisherStatus" class="inline-note">填写 UID 时创建前会自动查询资料；用户名搜索成功后请选择结果。</span></div>
        </div>
      </section>

      <section class="panel subscription-card subscription-policy-card">
        <div class="panel-head">
          <div>
            <h2>订阅类型</h2>
            <p>选择接收动态、开播或下播；@全体仅对群目标生效。</p>
          </div>
        </div>
        ${policyForm("subPolicy", null, "GROUP", initialSupportsLive)}
      </section>
      <section class="panel subscription-card subscription-filter-card">
        <div class="panel-head">
          <div>
            <h2>动态内容过滤</h2>
            <p>命中任意规则的动态会被阻止投递；不填写则不添加过滤规则。</p>
          </div>
          <div class="row-actions"><button type="button" class="danger compact" id="subCreateFilterClear">清空</button></div>
        </div>
        <div class="form-grid create-filter-form">
          <div class="field"><label>类型</label><select id="subCreateFilterType"><option value="HAS_BLOCK_KIND">元素</option><option value="TEXT_CONTAINS">关键词</option><option value="TEXT_REGEX">正则</option><option value="HAS_REFERENCE">引用</option></select></div>
          <div class="field" id="subCreateFilterElementWrap"><label>元素</label><select id="subCreateFilterElement">${blockKinds.map(([v,t]) => `<option value="${v}">${t}</option>`).join("")}</select></div>
          <div class="field" id="subCreateFilterReferenceWrap" hidden><label>引用</label><select id="subCreateFilterReference"><option value="">任意引用</option><option value="REPOST">转发</option><option value="QUOTE">引用</option><option value="ORIGIN">原动态</option></select></div>
          <div class="field full" id="subCreateFilterTextWrap" hidden><label>内容</label><input id="subCreateFilterText" placeholder="关键词或正则表达式"></div>
          <div class="field filter-add-field"><button type="button" id="subCreateFilterAdd">添加规则</button></div>
        </div>
        <div id="subCreateFilterList" class="create-filter-list"></div>
      </section>
      <div id="subCreateResult" class="batch-result" hidden></div>
    </div>
  `, async () => {
    setCreateSubscriptionResult("");
    const selectedTargets = collectCreateSubscriptionTargets(targetCandidates);
    const selectedPublishers = collectCreateSubscriptionPublishers(publisherCandidates);
    if (selectedPublishers.length === 0) throw new Error("请选择发布者；如果填写的是用户名，请先搜索并选择结果");
    if (selectedTargets.length === 0) throw new Error("请选择或填写消息目标");
    if (selectedPublishers.length > 1 && selectedTargets.length > 1) {
      throw new Error("消息目标和发布者不能同时多选，请只批量选择其中一侧");
    }
    const documentData = buildCreateSubscriptionImportDocument(
      selectedPublishers,
      selectedTargets,
      collectPolicy("subPolicy"),
      createFilterConditions,
    );
    if (documentData.subscriptions.length === 0) {
      throw new Error("选择的订阅组合都已经存在，无需重复创建");
    }
    const result = await importSubscriptionDocument(documentData);
    const failures = (result.items || [])
      .filter(item => item.status === "FAILED")
      .map(item => `第 ${Number(item.index) + 1} 条订阅：${item.message || "创建失败"}`);
    if (failures.length) {
      setCreateSubscriptionResult(`已创建 ${result.created} 个订阅，更新 ${result.updated} 个订阅，失败 ${failures.length} 个。`, failures, true);
      throw new Error(`批量订阅部分失败：失败 ${failures.length} 个`);
    }
    const warningLines = subscriptionImportWarnings(result);
    if (warningLines.length) {
      setCreateSubscriptionResult(`已创建 ${result.created} 个订阅，更新 ${result.updated} 个订阅，存在 ${warningLines.length} 条提示。`, warningLines.slice(0, 8), false);
      setCreateSubscriptionFooterMessage("订阅已处理，存在提示信息");
      notify("订阅已处理，存在提示信息", false);
      return;
    }
    closeModal();
    notify(`订阅已处理：创建 ${result.created}，更新 ${result.updated}`, false);
  }, {
    size: "subscription subscription-create-modal",
    confirmText: "创建",
    loadingText: "创建中...",
    cleanup: () => {
      modalClosed = true;
    },
  });

  const policyUpdater = wirePolicyForm(
    "subPolicy",
    currentCreateSubscriptionTargetKind,
    currentCreateSubscriptionLiveSupport,
  );
  const syncSelections = (changedSide = "", changedInput = null) => {
    syncCreateSubscriptionSelections(targetCandidates, publisherCandidates, changedSide, changedInput);
    policyUpdater();
  };
  bindCreateSubscriptionMode(syncSelections);
  bindCreateSubscriptionSelectionInputs(syncSelections);
  const refreshTargetKinds = async () => {
    if (!isModalActive()) return;
    const platformId = $("subNewTargetPlatform").value;
    const platform = fallbackTargets.find(item => item.platformId === platformId);
    const kinds = platform && platform.supportedTypes && platform.supportedTypes.length
      ? platform.supportedTypes
      : ["GROUP", "USER", "CHANNEL", "OTHER"];
    if (!isModalActive()) return;
    $("subNewTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
    policyUpdater();
    resetTargetCandidates();
  };
  const resetTargetCandidates = (status = "未获取可用目标，可手动填写目标 ID") => {
    if (!isModalActive()) return;
    targetCandidates = [];
    $("subNewTargetCandidateWrap").hidden = false;
    $("subNewTargetCandidateActions").hidden = false;
    $("subNewTargetManualWrap").hidden = false;
    $("subNewTargetCandidateList").innerHTML = `<div class="empty">未获取可用目标</div>`;
    $("subNewTargetSelectAll").disabled = true;
    $("subNewTargetClearAll").disabled = true;
    setCreateSubscriptionTargetStatus(status);
    syncSelections("target");
  };
  const refreshTargetCandidates = async (force = false) => {
    if (!isModalActive()) return;
    const platform = $("subNewTargetPlatform").value;
    const kind = $("subNewTargetKind").value;
    targetCandidates = [];
    setCreateSubscriptionTargetLoading(force ? "正在刷新可用目标..." : "正在获取可用目标...");
    $("subNewTargetSelectAll").disabled = true;
    $("subNewTargetClearAll").disabled = true;
    let allCandidates = [];
    let source = "后端";
    try {
      const result = await loadSubscriberTargetCandidates(platform, kind, force);
      if (!isModalActive()) return;
      allCandidates = result.items;
      source = result.stale ? "过期缓存" : result.fromCache ? "缓存" : "后端";
      targetCandidates = filterExistingSubscriptionTargets(allCandidates);
    } catch (error) {
      if (!isModalActive()) return;
      $("subNewTargetCandidateWrap").hidden = false;
      $("subNewTargetCandidateActions").hidden = false;
      $("subNewTargetManualWrap").hidden = false;
      $("subNewTargetCandidateList").innerHTML = `<div class="empty">目标列表获取失败</div>`;
      $("subNewTargetSelectAll").disabled = true;
      $("subNewTargetClearAll").disabled = true;
      setCreateSubscriptionTargetStatus("目标列表获取失败，请手动填写目标 ID");
      syncSelections("target");
      return;
    }
    if (targetCandidates.length) {
      $("subNewTargetCandidateWrap").hidden = false;
      $("subNewTargetCandidateActions").hidden = false;
      $("subNewTargetManualWrap").hidden = true;
      $("subNewTargetSelectAll").disabled = false;
      $("subNewTargetClearAll").disabled = false;
      const candidateList = $("subNewTargetCandidateList");
      if (!candidateList) return;
      candidateList.innerHTML = targetCandidates.map((target, index) => subscriptionTargetChoiceHtml(target, index, false)).join("");
      bindTargetSourceToggles(candidateList);
      bindCreateSubscriptionSelectionInputs(syncSelections);
      await hydrateMediaImages(candidateList);
      if (!isModalActive()) return;
      setCreateSubscriptionTargetStatus(`${targetCandidates.length} 个可添加目标，已添加目标不显示，来自${source}`);
      syncSelections("target");
    } else {
      $("subNewTargetCandidateWrap").hidden = false;
      $("subNewTargetCandidateActions").hidden = false;
      $("subNewTargetManualWrap").hidden = false;
      $("subNewTargetCandidateList").innerHTML = `<div class="empty">暂无可添加目标</div>`;
      $("subNewTargetSelectAll").disabled = true;
      $("subNewTargetClearAll").disabled = true;
      setCreateSubscriptionTargetStatus(allCandidates.length ? `已添加过的目标已排除，可手动填写目标 ID，来自${source}` : `未获取到目标，请手动填写目标 ID，来自${source}`);
      syncSelections("target");
    }
  };
  const searchPublisher = async () => {
    if (!isModalActive()) return;
    const platformId = $("subNewPublisherPlatform").value.trim();
    const queryText = $("subNewPublisherId").value.trim();
    if (!platformId) throw new Error("没有可用的发布者平台");
    if (!queryText) throw new Error("请填写发布者 UID 或用户名");
    publisherCandidates = [];
    setCreateSubscriptionPublisherLoading("正在搜索发布者...");
    const result = await api(`/publisher-search?platformId=${encodeURIComponent(platformId)}&q=${encodeURIComponent(queryText)}`);
    if (!isModalActive()) return;
    publisherCandidates = result;
    if (publisherCandidates.length) {
      $("subNewPublisherResultWrap").hidden = false;
      $("subNewPublisherResultList").innerHTML = publisherCandidates.map((publisher, index) => subscriptionPublisherCandidateHtml(publisher, index, false)).join("");
      bindCreateSubscriptionSelectionInputs(syncSelections);
      await hydrateMediaImages($("subNewPublisherResultList"));
      if (!isModalActive()) return;
      $("subNewPublisherStatus").textContent = `已找到 ${publisherCandidates.length} 个发布者，请选择一个结果`;
      syncSelections("publisher");
    } else {
      $("subNewPublisherResultWrap").hidden = false;
      $("subNewPublisherResultList").innerHTML = `<div class="empty">未找到发布者</div>`;
      $("subNewPublisherStatus").textContent = "未找到发布者，请确认 UID 或关键词后重试";
      syncSelections("publisher");
    }
  };
  $("subNewTargetPlatform").onchange = refreshTargetKinds;
  $("subNewTargetKind").onchange = async () => {
    resetTargetCandidates();
  };
  $("subNewTargetRefresh").onclick = () => refreshTargetCandidates(true).catch(handleError);
  $("subExistingTargetClear").onclick = () => {
    document.querySelectorAll(`input[name="subExistingTarget"]`).forEach(input => input.checked = false);
    setCreateSubscriptionTargetChecked(false);
    if ($("subNewTargetManual")) $("subNewTargetManual").value = "";
    syncSelections("target");
  };
  $("subNewTargetSelectAll").onclick = () => {
    setCreateSubscriptionTargetChecked(true);
    syncSelections("target");
  };
  $("subNewTargetClearAll").onclick = () => {
    setCreateSubscriptionTargetChecked(false);
    syncSelections("target");
  };
  $("subExistingPublisherClear").onclick = () => {
    document.querySelectorAll(`input[name="subExistingPublisher"]`).forEach(input => input.checked = false);
    document.querySelectorAll(`input[name="subNewPublisherCandidate"]`).forEach(input => input.checked = false);
    publisherCandidates = [];
    $("subNewPublisherResultWrap").hidden = true;
    $("subNewPublisherResultList").innerHTML = "";
    $("subNewPublisherId").value = "";
    $("subNewPublisherStatus").textContent = "填写 UID 时创建前会自动查询资料；用户名搜索成功后请选择结果。";
    syncSelections("publisher");
  };
  $("subNewPublisherResultClear").onclick = () => {
    document.querySelectorAll(`input[name="subNewPublisherCandidate"]`).forEach(input => input.checked = false);
    syncSelections("publisher");
  };
  $("subNewPublisherSearch").onclick = () => searchPublisher().catch(error => {
    if (!isModalActive()) return;
    $("subNewPublisherResultWrap").hidden = true;
    $("subNewPublisherStatus").textContent = error.message || String(error);
    syncSelections("publisher");
  });
  $("subNewPublisherId").onkeydown = event => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    searchPublisher().catch(error => {
      if (!isModalActive()) return;
      $("subNewPublisherResultWrap").hidden = true;
      $("subNewPublisherStatus").textContent = error.message || String(error);
      syncSelections("publisher");
    });
  };
  $("subNewPublisherId").oninput = () => {
    publisherCandidates = [];
    $("subNewPublisherResultWrap").hidden = true;
    $("subNewPublisherStatus").textContent = "填写 UID 时创建前会自动查询资料；用户名搜索成功后请选择结果。";
    syncSelections("publisher");
  };
  $("subNewPublisherPlatform").onchange = () => {
    if (!isModalActive()) return;
    publisherCandidates = [];
    $("subNewPublisherResultWrap").hidden = true;
    $("subNewPublisherStatus").textContent = "填写 UID 时创建前会自动查询资料；用户名搜索成功后请选择结果。";
    syncSelections("publisher");
  };
  const renderCreateFilters = () => {
    const list = $("subCreateFilterList");
    if (!list) return;
    list.innerHTML = renderCreateSubscriptionFilterList(createFilterConditions);
    list.querySelectorAll("[data-create-filter-remove]").forEach(button => {
      button.onclick = () => {
        createFilterConditions.splice(Number(button.dataset.createFilterRemove), 1);
        renderCreateFilters();
      };
    });
  };
  const refreshCreateFilterForm = () => {
    const type = $("subCreateFilterType").value;
    $("subCreateFilterElementWrap").hidden = type !== "HAS_BLOCK_KIND";
    $("subCreateFilterReferenceWrap").hidden = type !== "HAS_REFERENCE";
    $("subCreateFilterTextWrap").hidden = !["TEXT_CONTAINS", "TEXT_REGEX"].includes(type);
  };
  $("subCreateFilterType").onchange = refreshCreateFilterForm;
  $("subCreateFilterAdd").onclick = () => {
    try {
      const condition = collectCreateSubscriptionFilterCondition();
      if (createFilterConditions.some(item => createFilterConditionKey(item) === createFilterConditionKey(condition))) {
        throw new Error("这条过滤规则已经添加过了");
      }
      createFilterConditions.push(condition);
      $("subCreateFilterText").value = "";
      renderCreateFilters();
      setCreateSubscriptionFooterMessage("");
    } catch (error) {
      setCreateSubscriptionFooterMessage(error.message || String(error), true);
    }
  };
  $("subCreateFilterClear").onclick = () => {
    createFilterConditions = [];
    renderCreateFilters();
  };
  refreshCreateFilterForm();
  renderCreateFilters();
  await hydrateMediaImages($("modalBody"));
  syncSelections();
  await refreshTargetKinds();
}

function subscriptionModeSwitch(name, options) {
  return `<div class="subscription-mode-switch">${options.map(option => `<label class="subscription-mode-option">
    <input type="radio" name="${attr(name)}" value="${attr(option.value)}"${option.checked ? " checked" : ""}${option.disabled ? " disabled" : ""}>
    <span>${esc(option.text)}</span>
  </label>`).join("")}</div>`;
}

function bindCreateSubscriptionMode(selectionUpdater) {
  const refresh = () => {
    const targetMode = selectedSubscriptionMode("subTargetMode");
    const publisherMode = selectedSubscriptionMode("subPublisherMode");
    $("subExistingTargetBlock").hidden = targetMode !== "existing";
    $("subNewTargetBlock").hidden = targetMode !== "new";
    $("subExistingPublisherBlock").hidden = publisherMode !== "existing";
    $("subNewPublisherBlock").hidden = publisherMode !== "new";
    selectionUpdater("mode");
  };
  document.querySelectorAll(`input[name="subTargetMode"], input[name="subPublisherMode"]`).forEach(input => input.onchange = refresh);
  refresh();
}

function selectedSubscriptionMode(name) {
  const input = document.querySelector(`input[name="${name}"]:checked`);
  return input ? input.value : "";
}

function currentCreateSubscriptionTargetKind() {
  if (selectedSubscriptionMode("subTargetMode") === "existing") {
    const targets = Array.from(document.querySelectorAll(`input[name="subExistingTarget"]:checked`))
      .map(input => (state.cache.subscribers || [])[Number(input.dataset.index)])
      .filter(Boolean);
    if (!targets.length) return "GROUP";
    return targets.every(target => target.targetKind === "GROUP") ? "GROUP" : "OTHER";
  }
  const kindSelect = $("subNewTargetKind");
  return kindSelect && kindSelect.value || "OTHER";
}

function currentCreateSubscriptionPublisherPlatform() {
  if (selectedSubscriptionMode("subPublisherMode") === "existing") {
    const input = document.querySelector(`input[name="subExistingPublisher"]:checked`);
    const publisher = input ? (state.cache.publishers || [])[Number(input.dataset.index)] : null;
    return publisher && publisher.platformId || "";
  }
  const platformSelect = $("subNewPublisherPlatform");
  return platformSelect && platformSelect.value || "";
}

function currentCreateSubscriptionLiveSupport() {
  if (selectedSubscriptionMode("subPublisherMode") !== "existing") {
    return publisherPlatformSupportsLive(currentCreateSubscriptionPublisherPlatform());
  }
  const publishers = Array.from(document.querySelectorAll(`input[name="subExistingPublisher"]:checked`))
    .map(input => (state.cache.publishers || [])[Number(input.dataset.index)])
    .filter(Boolean);
  if (!publishers.length) return true;
  const supportValues = publishers.map(publisher => publisherPlatformSupportsLive(publisher.platformId));
  if (supportValues.some(value => value === false)) return false;
  if (supportValues.every(value => value === true)) return true;
  return null;
}

function setCreateSubscriptionTargetLoading(text) {
  if (!$("subNewTargetCandidateList")) return;
  $("subNewTargetCandidateWrap").hidden = false;
  $("subNewTargetCandidateActions").hidden = true;
  $("subNewTargetManualWrap").hidden = true;
  $("subNewTargetCandidateList").innerHTML = loadingRow(text);
  setCreateSubscriptionTargetStatus("");
}

function setCreateSubscriptionTargetStatus(text) {
  if (!$("subNewTargetStatus")) return;
  $("subNewTargetStatus").textContent = text ? `· ${text}` : "";
}

function setCreateSubscriptionResult(summary, details = [], isError = false) {
  const node = $("subCreateResult");
  if (!node) return;
  if (!summary) {
    node.hidden = true;
    node.innerHTML = "";
    node.classList.remove("error");
    return;
  }
  node.hidden = false;
  node.classList.toggle("error", !!isError);
  node.innerHTML = `<strong>${esc(summary)}</strong>${details.length
    ? `<ul>${details.map(detail => `<li>${esc(detail)}</li>`).join("")}</ul>`
    : ""}`;
}

function bindCreateSubscriptionSelectionInputs(selectionUpdater) {
  [
    ["subExistingTarget", "target"],
    ["subNewTargetCandidate", "target"],
    ["subExistingPublisher", "publisher"],
    ["subNewPublisherCandidate", "publisher"],
  ].forEach(([name, side]) => {
    document.querySelectorAll(`input[name="${name}"]`).forEach(input => {
      input.onchange = event => selectionUpdater(side, event.currentTarget);
    });
  });
}

function syncCreateSubscriptionSelections(targetCandidates, publisherCandidates, changedSide = "", changedInput = null) {
  enforceCreateSubscriptionSelectionLimit(targetCandidates, publisherCandidates, changedSide, changedInput);
  let selectedTargets = collectCreateSubscriptionTargets(targetCandidates);
  let selectedPublishers = collectCreateSubscriptionPublishers(publisherCandidates);
  updateExistingSubscriptionChoiceVisibility(selectedTargets, selectedPublishers);
  selectedTargets = collectCreateSubscriptionTargets(targetCandidates);
  selectedPublishers = collectCreateSubscriptionPublishers(publisherCandidates);
  updateCreateSubscriptionChoiceDisabled(selectedTargets, selectedPublishers);
  updateCreateSubscriptionSelectionHint(selectedTargets, selectedPublishers);
}

function enforceCreateSubscriptionSelectionLimit(targetCandidates, publisherCandidates, changedSide, changedInput) {
  const selectedTargets = collectCreateSubscriptionTargets(targetCandidates);
  const selectedPublishers = collectCreateSubscriptionPublishers(publisherCandidates);
  if (selectedTargets.length <= 1 || selectedPublishers.length <= 1) return;
  if (changedSide === "publisher") {
    clampCreateSubscriptionTargetSelection(changedInput);
  } else {
    clampCreateSubscriptionPublisherSelection(changedInput);
  }
}

function clampCreateSubscriptionTargetSelection(changedInput = null) {
  if (selectedSubscriptionMode("subTargetMode") !== "existing") {
    clampCheckedInputs("subNewTargetCandidate", changedInput);
    return;
  }
  clampCheckedInputs("subExistingTarget", changedInput);
}

function clampCreateSubscriptionPublisherSelection(changedInput = null) {
  if (selectedSubscriptionMode("subPublisherMode") === "existing") {
    clampCheckedInputs("subExistingPublisher", changedInput);
  }
}

function clampCheckedInputs(name, changedInput = null) {
  const checked = Array.from(document.querySelectorAll(`input[name="${name}"]:checked`));
  if (checked.length <= 1) return;
  const keep = checked.includes(changedInput) ? changedInput : checked[0];
  checked.forEach(input => {
    if (input !== keep) input.checked = false;
  });
}

function updateExistingSubscriptionChoiceVisibility(selectedTargets, selectedPublishers) {
  const pairs = existingSubscriptionPairSet();
  const publisherIds = selectedPublishers.map(publisher => publisher.publisherId).filter(Boolean);
  updateSubscriptionChoiceVisibilitySide({
    selector: "[data-sub-existing-target-id]",
    inputName: "subExistingTarget",
    ownSelectedCount: selectedTargets.length,
    oppositeIds: publisherIds,
    isFullyDuplicated: (targetId, publisherId) => pairs.has(subscriptionPairKey(targetId, publisherId)),
    idOf: choice => Number(choice.dataset.subExistingTargetId),
  });

  const targetIds = selectedTargets.map(target => target.subscriberId).filter(Boolean);
  updateSubscriptionChoiceVisibilitySide({
    selector: "[data-sub-existing-publisher-id]",
    inputName: "subExistingPublisher",
    ownSelectedCount: selectedPublishers.length,
    oppositeIds: targetIds,
    isFullyDuplicated: (publisherId, targetId) => pairs.has(subscriptionPairKey(targetId, publisherId)),
    idOf: choice => Number(choice.dataset.subExistingPublisherId),
  });
}

function updateSubscriptionChoiceVisibilitySide(options) {
  const hasOwnSelection = options.ownSelectedCount > 0;
  const hasOppositeSelection = options.oppositeIds.length > 0;
  document.querySelectorAll(options.selector).forEach(choice => {
    const input = choice.querySelector(`input[name="${options.inputName}"]`);
    if (input && input.checked) {
      choice.hidden = false;
      return;
    }
    if (!hasOppositeSelection) {
      choice.hidden = false;
      return;
    }
    const ownId = options.idOf(choice);
    const fullyDuplicated = options.oppositeIds.every(oppositeId =>
      options.isFullyDuplicated(ownId, oppositeId)
    );
    choice.hidden = hasOwnSelection
      ? choice.hidden || fullyDuplicated
      : fullyDuplicated;
  });
}

function updateCreateSubscriptionChoiceDisabled(selectedTargets, selectedPublishers) {
  const targetMulti = selectedTargets.length > 1;
  const publisherMulti = selectedPublishers.length > 1;
  const publisherLocked = targetMulti && selectedPublishers.length >= 1;
  const targetLocked = publisherMulti && selectedTargets.length >= 1;
  setChoiceInputsDisabled("subExistingPublisher", publisherLocked);
  setChoiceInputsDisabled("subExistingTarget", targetLocked);
  setChoiceInputsDisabled("subNewTargetCandidate", targetLocked);
}

function setChoiceInputsDisabled(name, lockUnselected) {
  document.querySelectorAll(`input[name="${name}"]`).forEach(input => {
    const disabled = !!lockUnselected && !input.checked;
    input.disabled = disabled;
    const choice = input.closest(".target-choice");
    if (choice) choice.classList.toggle("disabled", disabled);
  });
}

function updateCreateSubscriptionSelectionHint(selectedTargets, selectedPublishers) {
  const node = $("modalMessage");
  if (!node) return;
  node.classList.remove("error");
  if (!selectedTargets.length || !selectedPublishers.length) {
    node.textContent = "请选择消息目标和发布者";
    return;
  }
  const total = selectedTargets.length * selectedPublishers.length;
  const existed = countExistingSubscriptionPairs(selectedTargets, selectedPublishers);
  const creatable = Math.max(0, total - existed);
  node.textContent = `将新增 ${creatable} 个订阅组合${existed ? `，跳过 ${existed} 个已有组合` : ""}`;
}

function setCreateSubscriptionFooterMessage(message, isError = false) {
  const node = $("modalMessage");
  if (!node) return;
  node.textContent = message || "";
  node.classList.toggle("error", !!isError);
}

function existingTargetChoiceHtml(target, index, checked) {
  const title = target.name || target.externalId;
  const parts = [
    title,
    target.platformId,
    label(target.targetKind),
    target.externalId,
    target.accountId ? `优先账号 ${target.accountId}` : "",
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice" data-sub-existing-target-id="${attr(target.id)}">
    <input type="checkbox" name="subExistingTarget" value="${attr(target.id)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(target.avatarUri, "target-choice-avatar", target.platformId, "AVATAR")}
    <span class="target-choice-text target-choice-meta-line" title="${attr(parts)}">
      ${platformTag(target.platformId, target.platformId)}
      <span class="target-choice-name">${esc(title)}</span>
      <span class="target-choice-sub">${esc(label(target.targetKind))}</span>
      <span class="target-choice-sub">${esc(target.externalId || "-")}</span>
      ${target.accountId ? `<span class="target-choice-sub">${esc(`优先账号 ${target.accountId}`)}</span>` : ""}
    </span>
  </label>`;
}

function existingPublisherChoiceHtml(publisher, index, checked) {
  const title = publisher.name || publisher.externalId;
  const parts = [
    title,
    publisher.platformId,
    label(publisher.kind),
    publisher.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice" data-sub-existing-publisher-id="${attr(publisher.id)}">
    <input type="checkbox" name="subExistingPublisher" value="${attr(publisher.id)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(publisher.avatarUri, "target-choice-avatar", publisher.platformId, "AVATAR")}
    <span class="target-choice-text target-choice-meta-line" title="${attr(parts)}">
      ${platformTag(publisher.platformId, publisher.platformId)}
      <span class="target-choice-name">${esc(title)}</span>
      <span class="target-choice-sub">${esc(label(publisher.kind))}</span>
      <span class="target-choice-sub">${esc(publisher.externalId || "-")}</span>
    </span>
  </label>`;
}

function subscriptionTargetChoiceHtml(target, index, checked) {
  return messageTargetChoiceHtml(target, index, {
    inputName: "subNewTargetCandidate",
    prefix: "subNewTarget",
    inputType: "checkbox",
    checked,
  });
}

function setCreateSubscriptionTargetChecked(checked) {
  document.querySelectorAll(`input[name="subNewTargetCandidate"]`).forEach(input => {
    if (!checked || !input.disabled) input.checked = checked;
  });
}

function buildCreateSubscriptionImportDocument(publishers, targets, policy, filterConditions = []) {
  const subscriptions = [];
  const existingPairs = existingSubscriptionPairSet();
  const addedPairs = new Set();
  const filterRules = filterConditions.map(condition => ({ condition }));
  publishers.forEach(publisher => {
    targets.forEach(target => {
      const existingPair = publisher.publisherId && target.subscriberId
        ? subscriptionPairKey(target.subscriberId, publisher.publisherId)
        : "";
      if (existingPair && existingPairs.has(existingPair)) return;
      const dedupeKey = createSubscriptionImportPairKey(publisher, target);
      if (addedPairs.has(dedupeKey)) return;
      addedPairs.add(dedupeKey);
      subscriptions.push({
        publisher: {
          platformId: publisher.platformId,
          kind: publisher.kind || "USER",
          externalId: publisher.externalId,
        },
        target: {
          platformId: target.platformId,
          targetKind: target.targetKind,
          externalId: target.externalId,
          scopeId: target.scopeId || null,
          threadId: target.threadId || null,
          accountId: target.accountId || null,
        },
        policy,
        filterRules,
        linkParseTriggerMode: target.linkParseTriggerMode || null,
        publisherLookupMode: publisher.publisherLookupMode || null,
      });
    });
  });
  return {
    schemaVersion: 1,
    exportedAtEpochSeconds: Math.floor(Date.now() / 1000),
    subscriptions,
  };
}

function collectCreateSubscriptionTargets(candidates) {
  if (selectedSubscriptionMode("subTargetMode") === "existing") {
    return Array.from(document.querySelectorAll(`input[name="subExistingTarget"]:checked`))
      .map(input => (state.cache.subscribers || [])[Number(input.dataset.index)])
      .filter(Boolean)
      .map(target => ({
        subscriberId: target.id,
        platformId: target.platformId,
        targetKind: target.targetKind,
        externalId: target.externalId,
        scopeId: target.scopeId,
        threadId: target.threadId,
        accountId: target.accountId,
        linkParseTriggerMode: target.linkParseTriggerMode,
        label: target.name || target.externalId,
      }));
  }
  const platformId = $("subNewTargetPlatform").value.trim();
  const targetKind = $("subNewTargetKind").value;
  const linkParseTriggerMode = subscriptionTargetLinkParseMode();
  if (candidates.length === 0 || $("subNewTargetCandidateWrap").hidden) {
    const manual = $("subNewTargetManual").value.trim();
    return manual ? [{ platformId, targetKind, externalId: manual, label: manual, linkParseTriggerMode }] : [];
  }
  return Array.from(document.querySelectorAll(`input[name="subNewTargetCandidate"]:checked`))
    .map(input => {
      const index = Number(input.dataset.index);
      const target = candidates[index];
      if (!target) return null;
      const accountId = selectedTargetPriorityAccount("subNewTarget", index);
      return Object.assign({}, target, accountId ? { accountId } : { accountId: undefined });
    })
    .filter(Boolean)
    .map(target => ({
      platformId,
      targetKind,
      externalId: target.externalId,
      scopeId: target.scopeId,
      threadId: target.threadId,
      accountId: target.accountId,
      label: target.name || target.externalId,
      linkParseTriggerMode,
    }));
}

function subscriptionTargetLinkParseMode() {
  const select = $("subNewTargetLinkParse");
  const mode = select && select.value;
  return mode && mode !== "INHERIT" ? mode : undefined;
}

function setCreateSubscriptionPublisherLoading(text) {
  if (!$("subNewPublisherResultList")) return;
  $("subNewPublisherResultWrap").hidden = false;
  $("subNewPublisherResultList").innerHTML = loadingRow(text);
  $("subNewPublisherStatus").textContent = text;
}

function subscriptionPublisherCandidateHtml(publisher, index, checked) {
  const title = publisher.name || publisher.externalId;
  const parts = [
    title,
    label(publisher.kind),
    publisher.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice">
    <input type="radio" name="subNewPublisherCandidate" value="${attr(publisher.externalId)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(publisher.avatarUri, "target-choice-avatar", publisher.platformId, "AVATAR")}
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
  </label>`;
}

function collectCreateSubscriptionPublisher(candidates) {
  const publishers = collectCreateSubscriptionPublishers(candidates);
  return publishers.length ? publishers[0] : null;
}

function collectCreateSubscriptionPublishers(candidates) {
  if (selectedSubscriptionMode("subPublisherMode") === "existing") {
    return Array.from(document.querySelectorAll(`input[name="subExistingPublisher"]:checked`))
      .map(input => (state.cache.publishers || [])[Number(input.dataset.index)])
      .filter(Boolean)
      .map(publisher => ({
        publisherId: publisher.id,
        platformId: publisher.platformId,
        kind: publisher.kind || "USER",
        externalId: publisher.externalId,
        label: publisher.name || publisher.externalId,
        publisherLookupMode: "VERIFY",
      }));
  }
  const selected = !$("subNewPublisherResultWrap").hidden
    ? document.querySelector(`input[name="subNewPublisherCandidate"]:checked`)
    : null;
  const candidate = selected ? candidates[Number(selected.dataset.index)] : null;
  const platformId = $("subNewPublisherPlatform").value.trim();
  const inputExternalId = $("subNewPublisherId").value.trim();
  if (!candidate && inputExternalId && !isLikelyPublisherUid(inputExternalId)) {
    return [];
  }
  const externalId = candidate ? candidate.externalId : inputExternalId;
  if (!platformId || !externalId) return [];
  return [{
    platformId,
    kind: candidate && candidate.kind || "USER",
    externalId,
    label: candidate && candidate.name || externalId,
    publisherLookupMode: "VERIFY",
  }];
}

function subscriptionPairKey(subscriberId, publisherId) {
  return `${subscriberId}\u001F${publisherId}`;
}

function existingSubscriptionPairSet() {
  return new Set((state.cache.subscriptions || [])
    .filter(subscription => subscription && subscription.subscriberId != null && subscription.publisherId != null)
    .map(subscription => subscriptionPairKey(subscription.subscriberId, subscription.publisherId)));
}

function countExistingSubscriptionPairs(targets, publishers) {
  const pairs = existingSubscriptionPairSet();
  return targets.reduce((count, target) => count + publishers.filter(publisher =>
    target.subscriberId && publisher.publisherId && pairs.has(subscriptionPairKey(target.subscriberId, publisher.publisherId))
  ).length, 0);
}

function createSubscriptionImportPairKey(publisher, target) {
  return [
    publisher.platformId,
    publisher.kind || "USER",
    publisher.externalId,
    target.platformId,
    target.targetKind,
    target.externalId,
    target.scopeId || "",
    target.threadId || "",
    target.accountId || "",
  ].join("\u001F");
}

function isLikelyPublisherUid(value) {
  return /^\d+$/.test((value || "").trim());
}

function filterExistingSubscriptionTargets(candidates) {
  const existed = new Set((state.cache.subscribers || []).map(target =>
    subscriberTargetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId)
  ));
  return (candidates || []).filter(target =>
    !existed.has(subscriberTargetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId))
  );
}

function publisherPlatformSupportsLive(platformId) {
  const normalized = (platformId || "").trim().toLowerCase();
  if (!normalized) return null;
  const platform = (state.cache.publisherPlatforms || []).find(item =>
    String(item.platformId || "").toLowerCase() === normalized
  );
  if (!platform) return null;
  return !!(platform && (platform.supportsLive || (platform.capabilities || []).includes("LIVE_SOURCE")));
}

function isLiveSubscriptionEvent(value) {
  return value === "LIVE_STARTED" || value === "LIVE_ENDED";
}

function policyForm(prefix, policy, targetKind, supportsLive = true) {
  const selectedEvents = new Set(policyEvents(policy));
  const selectedMentions = new Set(mentionEvents(policy));
  const groupTarget = targetKind === "GROUP";
  const liveKnownUnsupported = supportsLive === false;
  return `<div class="rule-matrix">
    ${eventTypes.map(([value, text]) => {
      const eventAllowed = !liveKnownUnsupported || !isLiveSubscriptionEvent(value);
      const enabled = eventAllowed && selectedEvents.has(value);
      const mention = eventAllowed && selectedMentions.has(value);
      return `<div class="rule-row${eventAllowed ? "" : " rule-row-disabled"}">
        <strong>${esc(text)}${eventAllowed ? "" : `<span class="rule-note">平台不支持</span>`}</strong>
        <label class="check"><input type="checkbox" name="${prefix}-event" value="${value}"${enabled ? " checked" : ""}${eventAllowed ? "" : " disabled"}>接收</label>
        <label class="check"><input type="checkbox" name="${prefix}-mention" value="${value}"${mention ? " checked" : ""}${groupTarget && enabled && eventAllowed ? "" : " disabled"}>@全体</label>
      </div>`;
    }).join("")}
  </div>
  <div class="inline-note policy-live-hint" data-policy-live-hint="${attr(prefix)}"${liveKnownUnsupported ? "" : " hidden"}>当前发布者平台不支持开播和下播订阅。</div>`;
}

function wirePolicyForm(prefix, targetKindProvider, liveSupportProvider = () => true) {
  const update = () => {
    const groupTarget = targetKindProvider() === "GROUP";
    const liveKnownUnsupported = liveSupportProvider() === false;
    eventTypes.forEach(([value]) => {
      const eventNode = document.querySelector(`input[name="${prefix}-event"][value="${value}"]`);
      const mentionNode = document.querySelector(`input[name="${prefix}-mention"][value="${value}"]`);
      if (!eventNode || !mentionNode) return;
      const eventAllowed = !liveKnownUnsupported || !isLiveSubscriptionEvent(value);
      eventNode.disabled = !eventAllowed;
      if (eventNode.disabled) eventNode.checked = false;
      mentionNode.disabled = !eventAllowed || !groupTarget || !eventNode.checked;
      if (mentionNode.disabled) mentionNode.checked = false;
      const row = eventNode.closest(".rule-row");
      if (row) row.classList.toggle("rule-row-disabled", !eventAllowed);
    });
    const hint = document.querySelector(`[data-policy-live-hint="${prefix}"]`);
    if (hint) hint.hidden = !liveKnownUnsupported;
  };
  document.querySelectorAll(`input[name="${prefix}-event"]`).forEach(node => node.onchange = update);
  update();
  return update;
}

function collectPolicy(prefix) {
  const enabledEvents = Array.from(document.querySelectorAll(`input[name="${prefix}-event"]:checked`))
    .filter(item => !item.disabled)
    .map(item => item.value);
  const mentionAllEvents = Array.from(document.querySelectorAll(`input[name="${prefix}-mention"]:checked`))
    .filter(item => !item.disabled && enabledEvents.includes(item.value))
    .map(item => item.value);
  if (enabledEvents.length === 0) throw new Error("至少选择一种接收事件");
  return { enabledEvents, mentionAllEvents };
}

async function openSubscriptionDetail(id) {
  const subscriptions = await ensureSubscriptions(false);
  await ensurePublisherPlatforms(false);
  const subscription = subscriptions.find(item => Number(item.id) === Number(id));
  if (!subscription) throw new Error("未找到订阅");
  const targetKind = subscription.subscriber && subscription.subscriber.targetKind || "OTHER";
  const supportsLive = publisherPlatformSupportsLive(subscription.publisher && subscription.publisher.platformId);
  openModal("编辑订阅", `
    <div class="grid">
      <section class="panel full">
        <div class="panel-head">
          <div><h2>订阅规则</h2><p>${esc(publisherKey(subscription.publisher))} -> ${esc(targetKey(subscription.subscriber))}</p></div>
        </div>
        ${policyForm("editPolicy", subscription.policy, targetKind, supportsLive)}
      </section>
      <section class="panel full">
        <div class="panel-head"><h2>动态内容过滤</h2><div class="row-actions"><button data-action="add-filter" data-id="${subscription.id}">添加规则</button><button class="danger" data-action="clear-filters" data-id="${subscription.id}">清空规则</button></div></div>
        <div id="filterList">${renderFilterList(subscription)}</div>
      </section>
    </div>
  `, async () => {
    await api(`/subscriptions/${subscription.id}`, { method: "PATCH", body: JSON.stringify({ policy: collectPolicy("editPolicy") }) });
    closeModal();
    invalidate("dashboard", "subscriptions");
    await loadSubscriptions(true);
    notify("订阅规则已保存", false);
  }, { size: "subscription" });
  wirePolicyForm("editPolicy", () => targetKind, () => supportsLive);
}

function renderFilterList(subscription) {
  const rules = subscription.filterRules || [];
  if (!rules.length) return `<div class="empty">暂无过滤规则</div>`;
  return `<div class="filter-rule-table-wrap">
    <table class="filter-rule-table">
      <thead><tr><th>类型</th><th>条件</th><th>创建时间</th><th>操作</th></tr></thead>
      <tbody>${rules.map(rule => `<tr>
        <td><span class="primary-line">${esc(filterConditionTypeText(rule.condition))}</span></td>
        <td>${cell(filterConditionText(rule.condition), "命中即阻止")}</td>
        <td><span class="sub-line">${fmtTime(rule.createdAtEpochSeconds)}</span></td>
        <td><button class="danger compact" data-action="delete-filter" data-id="${rule.id}" data-subscription="${subscription.id}">删除</button></td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
}

function filterConditionTypeText(condition) {
  if (!condition) return "-";
  if (condition.type === "HAS_BLOCK_KIND") return "元素";
  if (condition.type === "TEXT_CONTAINS") return "关键词";
  if (condition.type === "TEXT_REGEX") return "正则";
  if (condition.type === "HAS_REFERENCE") return "引用";
  return condition.type || "-";
}

function filterConditionText(condition) {
  if (!condition) return "-";
  if (condition.type === "HAS_BLOCK_KIND") return label(condition.kind);
  if (condition.type === "TEXT_CONTAINS") return condition.value;
  if (condition.type === "TEXT_REGEX") return condition.pattern;
  if (condition.type === "HAS_REFERENCE") return condition.kind ? label(condition.kind) : "任意引用";
  return condition.type || JSON.stringify(condition);
}

function renderCreateSubscriptionFilterList(conditions) {
  if (!conditions.length) {
    return `<div class="empty create-filter-empty">暂无过滤规则</div>`;
  }
  return `<div class="create-filter-items">${conditions.map((condition, index) => `
    <div class="create-filter-item">
      <div>
        <strong>${esc(filterConditionTypeText(condition))}</strong>
        <span>${esc(filterConditionText(condition))}</span>
      </div>
      <button type="button" class="danger compact" data-create-filter-remove="${attr(index)}">删除</button>
    </div>
  `).join("")}</div>`;
}

function collectCreateSubscriptionFilterCondition() {
  const type = $("subCreateFilterType").value;
  if (type === "HAS_BLOCK_KIND") return { type, kind: $("subCreateFilterElement").value };
  if (type === "HAS_REFERENCE") return { type, kind: $("subCreateFilterReference").value || null };
  const text = $("subCreateFilterText").value.trim();
  if (!text) throw new Error("请填写过滤内容");
  if (type === "TEXT_REGEX") return { type, pattern: text };
  return { type, value: text, ignoreCase: true };
}

function createFilterConditionKey(condition) {
  return JSON.stringify(condition || {});
}

function openFilterRuleModal(subscriptionId) {
  openModal("添加过滤规则", `
    <div class="form-grid">
      <div class="field"><label>类型</label><select id="filterType"><option value="HAS_BLOCK_KIND">元素</option><option value="TEXT_CONTAINS">关键词</option><option value="TEXT_REGEX">正则</option><option value="HAS_REFERENCE">引用</option></select></div>
      <div class="field" id="filterElementWrap"><label>元素</label><select id="filterElement">${blockKinds.map(([v,t]) => `<option value="${v}">${t}</option>`).join("")}</select></div>
      <div class="field" id="filterReferenceWrap" hidden><label>引用</label><select id="filterReference"><option value="">任意引用</option><option value="REPOST">转发</option><option value="QUOTE">引用</option><option value="ORIGIN">原动态</option></select></div>
      <div class="field full" id="filterTextWrap" hidden><label>内容</label><input id="filterText"></div>
    </div>
  `, async () => {
    const condition = collectFilterCondition();
    await api(`/subscriptions/${subscriptionId}/filter-rules`, { method: "POST", body: JSON.stringify({ condition }) });
    closeModal();
    invalidate("subscriptions", "dashboard");
    await loadSubscriptions(true);
    await openSubscriptionDetail(subscriptionId);
    notify("过滤规则已添加", false);
  }, { size: "small" });
  const refresh = () => {
    const type = $("filterType").value;
    $("filterElementWrap").hidden = type !== "HAS_BLOCK_KIND";
    $("filterReferenceWrap").hidden = type !== "HAS_REFERENCE";
    $("filterTextWrap").hidden = !["TEXT_CONTAINS", "TEXT_REGEX"].includes(type);
  };
  $("filterType").onchange = refresh;
  refresh();
}

function collectFilterCondition() {
  const type = $("filterType").value;
  if (type === "HAS_BLOCK_KIND") return { type, kind: $("filterElement").value };
  if (type === "HAS_REFERENCE") return { type, kind: $("filterReference").value || null };
  const text = $("filterText").value.trim();
  if (!text) throw new Error("请填写过滤内容");
  if (type === "TEXT_REGEX") return { type, pattern: text };
  return { type, value: text, ignoreCase: true };
}
