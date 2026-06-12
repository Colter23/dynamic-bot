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
let uniqueValues;
let filterOptions;
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
const entityFilters = {
  publisherPlatform: "",
  publisherState: "",
  subscriberPlatform: "",
  subscriberState: ""
};
let createPublisherModalSeq = 0;
let createSubscriberModalSeq = 0;

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
    uniqueValues,
    filterOptions,
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
  await loadEntities(ctx.force);
}

export function unmount() {
  invalidatePageRequests("entities");
}

export async function handleAction(nextCtx, { action, id }) {
  bindContext(nextCtx);
  if (action === "edit-publisher") {
    await openEditPublisher(id);
    return true;
  }
  if (action === "publisher-detail") {
    await openPublisherDetail(id);
    return true;
  }
  if (action === "publisher-subscriptions") {
    await openPublisherSubscriptions(id);
    return true;
  }
  if (action === "publisher-live") {
    await openPublisherLive(id);
    return true;
  }
  if (action === "create-publisher") {
    await openCreatePublisher();
    return true;
  }
  if (action === "create-subscriber") {
    await openCreateSubscriber();
    return true;
  }
  if (action === "edit-subscriber") {
    await openEditSubscriber(id);
    return true;
  }
  if (action === "subscriber-detail") {
    await openSubscriberDetail(id);
    return true;
  }
  if (action === "subscriber-subscriptions") {
    await openSubscriberSubscriptions(id);
    return true;
  }
  if (action === "delete-publisher") {
    if (!(await confirmDanger("删除发布者", "确定删除这个发布者及关联订阅吗？相关订阅也会一并移除。", { confirmText: "删除" }))) return true;
    const result = await api(`/publishers/${id}`, { method: "DELETE" });
    invalidate("dashboard", "publishers", "subscriptions");
    await loadEntities(true);
    notify(result.message, false);
    return true;
  }
  if (action === "delete-subscriber") {
    if (!(await confirmDanger("删除消息目标", "确定删除这个消息目标及关联订阅吗？相关订阅也会一并移除。", { confirmText: "删除" }))) return true;
    const result = await api(`/subscribers/${id}`, { method: "DELETE" });
    invalidate("dashboard", "subscribers", "subscriptions");
    await loadEntities(true);
    notify(result.message, false);
    return true;
  }
  return false;
}

function entityStateText(value) {
  const map = { ACTIVE: "启用", DISABLED: "停用" };
  return map[value] || value || "-";
}

function entityStateOptions(selected) {
  return ["ACTIVE", "DISABLED"].map(value =>
    `<option value="${value}"${value === selected ? " selected" : ""}>${esc(entityStateText(value))}</option>`
  ).join("");
}

function entityStatePill(value) {
  return `<span class="pill ${value === "ACTIVE" ? "ok" : "bad"}">${esc(entityStateText(value))}</span>`;
}

function linkParseCell(target) {
  const source = target.linkParseConfigSource === "CUSTOM" ? "当前目标配置" : "全局回退";
  return cell(linkParseModeLabel(target.effectiveLinkParseTriggerMode), source);
}

function filterEntityRows(rows, platform, stateValue) {
  return (rows || []).filter(row =>
    (!platform || row.platformId === platform) &&
    (!stateValue || row.state === stateValue)
  );
}

function normalizeEntityFilters(scope, rows) {
  const platformKey = `${scope}Platform`;
  if (entityFilters[platformKey] && !uniqueValues(rows, "platformId").includes(entityFilters[platformKey])) {
    entityFilters[platformKey] = "";
  }
}

function entityFilterBar(scope, rows, filteredRows) {
  const platformKey = `${scope}Platform`;
  const stateKey = `${scope}State`;
  const disabled = entityFilters[platformKey] || entityFilters[stateKey] ? "" : " disabled";
  return `<div class="entity-filter-bar">
    <span class="entity-filter-title">筛选</span>
    <div class="entity-filter-controls">
      <select data-entity-filter="${attr(platformKey)}">${filterOptions("全部平台", uniqueValues(rows, "platformId"), entityFilters[platformKey])}</select>
      <select data-entity-filter="${attr(stateKey)}">${filterOptions("全部状态", uniqueValues(rows, "state", ["ACTIVE", "DISABLED"]), entityFilters[stateKey], entityStateText)}</select>
      <button type="button" class="entity-filter-clear" data-entity-filter-reset="${attr(scope)}"${disabled}>清除</button>
    </div>
    <span class="entity-filter-summary">显示 ${filteredRows.length} / ${rows.length}</span>
  </div>`;
}

function bindEntityFilters() {
  pageRoot().querySelectorAll("[data-entity-filter]").forEach(select => {
    select.onchange = () => {
      entityFilters[select.dataset.entityFilter] = select.value;
      loadEntities(false).catch(handleError);
    };
  });
  pageRoot().querySelectorAll("[data-entity-filter-reset]").forEach(button => {
    button.onclick = () => {
      const scope = button.dataset.entityFilterReset;
      entityFilters[`${scope}Platform`] = "";
      entityFilters[`${scope}State`] = "";
      loadEntities(false).catch(handleError);
    };
  });
}

async function loadEntities(force) {
  const request = beginPageRequest("entities");
  releaseMediaObjectUrls();
  if (force || !state.cache.publishers) state.cache.publishers = await api("/publishers");
  if (force || !state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  if (!isCurrentPageRequest(request)) return;
  const publishers = state.cache.publishers;
  const subscribers = state.cache.subscribers;
  normalizeEntityFilters("publisher", publishers);
  normalizeEntityFilters("subscriber", subscribers);
  const filteredPublishers = filterEntityRows(publishers, entityFilters.publisherPlatform, entityFilters.publisherState);
  const filteredSubscribers = filterEntityRows(subscribers, entityFilters.subscriberPlatform, entityFilters.subscriberState);
  pageRoot().innerHTML = `
    <section class="page">
      <section class="panel full">
        <div class="panel-head"><h2>发布者</h2><button class="add-button" data-action="create-publisher">添加</button></div>
        ${entityFilterBar("publisher", publishers, filteredPublishers)}
        ${renderTable(filteredPublishers, [
          { title: "平台", render: p => platformTag(p.platformId, p.platformId) },
          { title: "发布者", render: p => entityPublisherCell(p) },
          { title: "订阅", render: p => subscriptionCountButton("publisher-subscriptions", p.id, p.subscriptionCount, "查看订阅用户") },
          { title: "直播状态", render: p => publisherLiveStatusCell(p) },
          { title: "状态", render: p => entityStatePill(p.state) },
          { title: "主题", render: p => themeSwatch(p.drawTheme) },
          { title: "头图", render: p => p.bannerUri ? mediaImage(p.bannerUri, "header-image", p.platformId, "COVER") : `<span class="sub-line">-</span>` },
          { title: "创建时间", render: p => `<span class="sub-line">${fmtTime(p.createTime)}</span>` },
          { title: "操作", render: p => `<div class="row-actions"><button data-action="edit-publisher" data-id="${p.id}">编辑</button><button class="entity-detail-button" data-action="publisher-detail" data-id="${p.id}">详情</button><button class="danger" data-action="delete-publisher" data-id="${p.id}">删除</button></div>` }
        ])}
      </section>
      <section class="panel full">
        <div class="panel-head"><h2>消息目标</h2><button class="add-button" data-action="create-subscriber">添加</button></div>
        ${entityFilterBar("subscriber", subscribers, filteredSubscribers)}
        ${renderTable(filteredSubscribers, [
          { title: "平台", render: s => platformTag(s.platformId, s.platformId) },
          { title: "目标", render: s => entitySubscriberCell(s) },
          { title: "订阅", render: s => subscriptionCountButton("subscriber-subscriptions", s.id, s.subscriptionCount, "查看订阅发布者") },
          { title: "链接解析", render: s => linkParseCell(s) },
          { title: "状态", render: s => entityStatePill(s.state) },
          { title: "创建时间", render: s => `<span class="sub-line">${fmtTime(s.createTime)}</span>` },
          { title: "操作", render: s => `<div class="row-actions"><button data-action="edit-subscriber" data-id="${s.id}">编辑</button><button class="entity-detail-button" data-action="subscriber-detail" data-id="${s.id}">详情</button><button class="danger" data-action="delete-subscriber" data-id="${s.id}">删除</button></div>` }
        ])}
      </section>
    </section>`;
  bindEntityFilters();
  await hydrateMediaImages($("content"));
}

function entityPublisherCell(publisher) {
  if (!publisher) return identity("-", "-", null);
  return identityMeta(
    publisher.name,
    publisher.avatarUri,
    publisher.platformId,
    "AVATAR",
    publisher.platformId,
    label(publisher.kind),
    publisher.externalId,
    { showPlatform: false },
  );
}

function subscriptionCountButton(action, id, count, title) {
  const value = Number(count || 0);
  return `<button type="button" class="entity-subscription-button" data-action="${attr(action)}" data-id="${attr(id)}" title="${attr(title)}">
    <strong>${value}</strong><span>条</span>
  </button>`;
}

function publisherLiveStatusCell(publisher) {
  const liveSubscriptionCount = Number(publisher.liveSubscriptionCount || 0);
  if (liveSubscriptionCount <= 0) {
    return cell("未订阅直播", "启用开播/下播订阅后显示状态");
  }
  const status = (publisher.liveStatuses || [])[0];
  if (!status) {
    return liveStatusButton(publisher.id, "等待检测", `${liveSubscriptionCount} 个直播订阅`);
  }
  const observed = status.lastObservedAtEpochSeconds ? `最后观察 ${fmtTime(status.lastObservedAtEpochSeconds)}` : `${liveSubscriptionCount} 个直播订阅`;
  return liveStatusButton(publisher.id, pill(status.status), observed);
}

function liveStatusButton(id, title, sub) {
  return `<button type="button" class="entity-live-button" data-action="publisher-live" data-id="${attr(id)}" title="查看直播状态与记录">
    <span class="primary-line">${typeof title === "string" && title.includes("<") ? title : esc(title)}</span>
    <span class="sub-line">${esc(sub || "")}</span>
  </button>`;
}

function entitySubscriberCell(target) {
  if (!target) return identity("-", "-", null);
  return identityMeta(
    target.name,
    target.avatarUri,
    target.platformId,
    "AVATAR",
    target.platformId,
    label(target.targetKind),
    target.externalId,
    { showPlatform: false },
  );
}

async function openPublisherDetail(id) {
  state.cache.publishers = await api("/publishers");
  const item = state.cache.publishers.find(row => Number(row.id) === Number(id));
  if (!item) throw new Error("发布者不存在");
  const cursors = item.cursors || [];
  openModal("发布者详情", `
    <div class="entity-detail">
      <div class="plugin-detail-grid">
        ${detailItem("数据库 ID", item.id)}
        ${detailItem("平台", item.platformId)}
        ${detailItem("类型", label(item.kind))}
        ${detailItem("发布者 ID", item.externalId, true)}
        ${detailItem("状态", entityStateText(item.state))}
        ${detailItem("订阅数量", item.subscriptionCount || 0)}
        ${detailItem("创建用户", item.createUser)}
        ${detailItem("创建时间", fmtTime(item.createTime))}
        ${detailItem("头像角标", item.avatarBadgeKey || "-", true)}
        ${detailItem("挂件 URI", item.pendantUri || "-", true)}
      </div>
      <section class="plugin-detail-section">
        <div class="entity-detail-head">
          <h3>源游标</h3>
          <span class="entity-detail-count">${cursors.length} 条</span>
        </div>
        ${renderPublisherCursors(cursors)}
      </section>
    </div>
  `, null, {
    size: "wide",
    cancelText: "关闭",
  });
  await hydrateMediaImages($("modalBody"));
}

async function openPublisherLive(id) {
  state.cache.publishers = await api("/publishers");
  const item = state.cache.publishers.find(row => Number(row.id) === Number(id));
  if (!item) throw new Error("发布者不存在");
  const liveStatuses = item.liveStatuses || [];
  const liveRecords = item.liveRecords || [];
  openModal("直播状态与记录", `
    <div class="entity-live-modal">
      ${liveModalSummary(item, liveStatuses.length, liveRecords.length)}
      <section class="plugin-detail-section">
        <div class="entity-detail-head">
          <h3>直播状态</h3>
          <span class="entity-detail-count">${liveStatuses.length} 条</span>
        </div>
        ${renderPublisherLiveStatuses(liveStatuses, item.platformId)}
      </section>
      <section class="plugin-detail-section">
        <div class="entity-detail-head">
          <h3>直播记录</h3>
          <span class="entity-detail-count">${liveRecords.length} 条</span>
        </div>
        ${renderPublisherLiveRecords(liveRecords, item.platformId)}
      </section>
    </div>
  `, null, {
    size: "wide",
    cancelText: "关闭",
  });
  await hydrateMediaImages($("modalBody"));
}

async function openPublisherSubscriptions(id) {
  if (!state.cache.publishers) state.cache.publishers = await api("/publishers");
  const item = state.cache.publishers.find(row => Number(row.id) === Number(id));
  if (!item) throw new Error("发布者不存在");
  const relatedSubscriptions = (await loadEntitySubscriptions())
    .filter(row => Number(row.publisherId) === Number(id))
    .sort((a, b) => Number(b.updatedAtEpochSeconds || 0) - Number(a.updatedAtEpochSeconds || 0));
  openModal("订阅用户", `
    <div class="entity-relation-modal">
      ${relationModalSummary(entityPublisherCell(item), relatedSubscriptions.length, "正在接收这个发布者动态或直播事件的消息目标")}
      ${renderPublisherSubscriberRelations(relatedSubscriptions)}
    </div>
  `, null, {
    size: "wide",
    cancelText: "关闭",
  });
  await hydrateMediaImages($("modalBody"));
}

async function openSubscriberSubscriptions(id) {
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const item = state.cache.subscribers.find(row => Number(row.id) === Number(id));
  if (!item) throw new Error("消息目标不存在");
  const relatedSubscriptions = (await loadEntitySubscriptions())
    .filter(row => Number(row.subscriberId) === Number(id))
    .sort((a, b) => Number(b.updatedAtEpochSeconds || 0) - Number(a.updatedAtEpochSeconds || 0));
  openModal("订阅发布者", `
    <div class="entity-relation-modal">
      ${relationModalSummary(entitySubscriberCell(item), relatedSubscriptions.length, "这个消息目标当前订阅的发布者")}
      ${renderSubscriberPublisherRelations(relatedSubscriptions)}
    </div>
  `, null, {
    size: "wide",
    cancelText: "关闭",
  });
  await hydrateMediaImages($("modalBody"));
}

async function loadEntitySubscriptions() {
  if (!state.cache.subscriptions) {
    state.cache.subscriptions = await api("/subscriptions");
  }
  return state.cache.subscriptions || [];
}

function relationModalSummary(entityHtml, count, description) {
  return `<div class="entity-relation-summary">
    <div class="entity-relation-subject">${entityHtml}</div>
    <div class="entity-relation-count">
      <strong>${Number(count || 0)}</strong>
      <span>${esc(description)}</span>
    </div>
  </div>`;
}

function liveModalSummary(publisher, statusCount, recordCount) {
  return `<div class="entity-live-summary">
    <div class="entity-relation-subject">${entityPublisherCell(publisher)}</div>
    <div class="entity-live-counts">
      <span><strong>${Number(statusCount || 0)}</strong> 状态</span>
      <span><strong>${Number(recordCount || 0)}</strong> 记录</span>
      <small>${Number(publisher.liveSubscriptionCount || 0)} 个直播订阅</small>
    </div>
  </div>`;
}

function renderPublisherSubscriberRelations(rows) {
  if (!rows.length) return entityDetailEmpty(
    "暂无订阅用户",
    "这个发布者还没有绑定任何消息目标，可以在订阅管理中添加订阅关系。"
  );
  return `<div class="entity-detail-table-wrap">
    <table class="entity-detail-table subscription-relations">
      <thead><tr><th>消息目标</th><th>接收事件</th><th>@全体</th><th>动态过滤</th><th>状态</th><th>更新时间</th></tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td>${entitySubscriberCell(row.subscriber)}</td>
        <td>${subscriptionEventTags(row.policy)}</td>
        <td>${subscriptionMentionTags(row.policy)}</td>
        <td>${subscriptionFilterCell(row)}</td>
        <td>${entityStatePill(row.state)}</td>
        <td><span class="sub-line">${fmtTime(row.updatedAtEpochSeconds)}</span></td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
}

function renderSubscriberPublisherRelations(rows) {
  if (!rows.length) return entityDetailEmpty(
    "暂无订阅发布者",
    "这个消息目标还没有绑定任何发布者，可以在订阅管理中添加订阅关系。"
  );
  return `<div class="entity-detail-table-wrap">
    <table class="entity-detail-table subscription-relations">
      <thead><tr><th>发布者</th><th>接收事件</th><th>@全体</th><th>动态过滤</th><th>状态</th><th>更新时间</th></tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td>${entityPublisherCell(row.publisher)}</td>
        <td>${subscriptionEventTags(row.policy)}</td>
        <td>${subscriptionMentionTags(row.policy)}</td>
        <td>${subscriptionFilterCell(row)}</td>
        <td>${entityStatePill(row.state)}</td>
        <td><span class="sub-line">${fmtTime(row.updatedAtEpochSeconds)}</span></td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
}

function subscriptionEventTags(policy) {
  return tags(policyEvents(policy).map(eventLabel));
}

function subscriptionMentionTags(policy) {
  const events = mentionEvents(policy);
  return events.length ? tags(events.map(event => `@全体 ${eventLabel(event)}`)) : `<span class="sub-line">未启用</span>`;
}

function subscriptionFilterCell(subscription) {
  const count = Number(subscription.filterRuleCount || 0);
  return cell(count ? `${count} 条规则` : "无过滤", count ? "命中任一规则会阻止动态" : "动态直接投递");
}

function renderPublisherLiveStatuses(rows, platformId) {
  if (!rows.length) return entityDetailEmpty(
    "暂无直播状态记录",
    "可能尚未启用开播/下播订阅，或插件还未完成直播检测。"
  );
  return `<div class="entity-detail-table-wrap">
    <table class="entity-detail-table">
      <thead><tr><th>房间</th><th>状态</th><th>标题</th><th>分区</th><th>开播时间</th><th>最后观察</th><th>封面</th></tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td><span class="mono">${esc(row.roomId || "-")}</span></td>
        <td>${pill(row.status)}</td>
        <td><span class="primary-line">${esc(row.title || "-")}</span></td>
        <td><span class="sub-line">${esc(row.area || "-")}</span></td>
        <td><span class="sub-line">${fmtTime(row.startedAtEpochSeconds)}</span></td>
        <td><span class="sub-line">${fmtTime(row.lastObservedAtEpochSeconds)}</span></td>
        <td>${row.coverUri ? mediaImage(row.coverUri, "entity-detail-cover", platformId, "COVER") : `<span class="sub-line">-</span>`}</td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
}

function renderPublisherLiveRecords(rows, platformId) {
  if (!rows.length) return entityDetailEmpty(
    "暂无直播历史记录",
    "从新增记录表生效后，开播和下播事件会在这里沉淀为直播场次。"
  );
  return `<div class="entity-detail-table-wrap">
    <table class="entity-detail-table">
      <thead><tr><th>房间</th><th>标题</th><th>分区</th><th>开播时间</th><th>下播时间</th><th>时长</th><th>封面</th></tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td><span class="mono">${esc(row.roomId || "-")}</span></td>
        <td><span class="primary-line">${esc(row.title || "-")}</span></td>
        <td><span class="sub-line">${esc(row.area || "-")}</span></td>
        <td><span class="sub-line">${fmtTime(row.startedAtEpochSeconds)}</span></td>
        <td><span class="sub-line">${fmtTime(row.endedAtEpochSeconds)}</span></td>
        <td><span class="sub-line">${row.durationSeconds == null ? "直播中" : esc(fmtDuration(row.durationSeconds * 1000))}</span></td>
        <td>${row.coverUri ? mediaImage(row.coverUri, "entity-detail-cover", platformId, "COVER") : `<span class="sub-line">-</span>`}</td>
      </tr>`).join("")}</tbody>
    </table>
  </div>`;
}

function renderPublisherCursors(rows) {
  if (!rows.length) return entityDetailEmpty(
    "暂无源游标记录",
    "可能尚未启用动态订阅，或动态检测还没有建立基线。"
  );
  return `<div class="entity-detail-table-wrap">
    <table class="entity-detail-table cursor">
      <thead><tr><th>来源</th><th>事件</th><th>最后动态</th><th>最近去重</th><th>最后观察</th></tr></thead>
      <tbody>${rows.map(row => {
        const recent = row.recentUpdateKeys || [];
        return `<tr>
          <td><span class="mono">${esc(row.sourceKey || "-")}</span></td>
          <td>${pill(row.eventType)}</td>
          <td><span class="mono detail-long-text">${esc(row.lastSeenUpdateKey || "-")}</span></td>
          <td><span class="sub-line" title="${attr(recent.join("\n"))}">${recent.length} 条</span></td>
          <td><span class="sub-line">${fmtTime(row.lastSeenAtEpochSeconds)}</span></td>
        </tr>`;
      }).join("")}</tbody>
    </table>
  </div>`;
}

async function openSubscriberDetail(id) {
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const item = state.cache.subscribers.find(row => Number(row.id) === Number(id));
  if (!item) throw new Error("消息目标不存在");
  const linkParseSource = item.linkParseConfigSource === "CUSTOM" ? "当前目标配置" : "全局回退";
  openModal("消息目标详情", `
    <div class="entity-detail">
      <div class="plugin-detail-grid">
        ${detailItem("数据库 ID", item.id)}
        ${detailItem("平台", item.platformId)}
        ${detailItem("类型", label(item.targetKind))}
        ${detailItem("目标 ID", item.externalId, true)}
        ${detailItem("状态", entityStateText(item.state))}
        ${detailItem("订阅数量", item.subscriptionCount || 0)}
        ${detailItem("显示名称", item.name)}
        ${detailItem("创建用户", item.createUser)}
        ${detailItem("创建时间", fmtTime(item.createTime))}
        ${detailItem("目标作用域", item.scopeId || "未指定", true)}
        ${detailItem("线程 ID", item.threadId || "未指定", true)}
        ${detailItem("优先账号", item.accountId || "未指定", true)}
        ${detailItem("链接解析", linkParseModeLabel(item.effectiveLinkParseTriggerMode))}
        ${detailItem("配置来源", linkParseSource)}
      </div>
    </div>
  `, null, {
    size: "medium",
    cancelText: "关闭",
  });
}

function entityDetailEmpty(title, description) {
  return `<div class="empty entity-detail-empty">
    <strong>${esc(title)}</strong>
    <span>${esc(description)}</span>
  </div>`;
}

function detailItem(title, value, mono = false) {
  return `<div class="plugin-detail-item">
    <span>${esc(title)}</span>
    <strong class="${mono ? "mono" : ""}">${esc(value ?? "-")}</strong>
  </div>`;
}

async function openCreatePublisher() {
  const modalSeq = ++createPublisherModalSeq;
  let modalClosed = false;
  const isModalActive = () => !modalClosed && createPublisherModalSeq === modalSeq && !!$("newPublisherResultList");
  const platforms = await api("/publisher-platforms").catch(() => []);
  let publisherCandidates = [];
  openModal("添加发布者", `
    <div class="subscription-create">
      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>发布者</h2>
            <p>先按平台和 UID / 用户名搜索发布者，再从结果中选择添加。</p>
          </div>
        </div>
        <div class="form-grid single publisher-create-form">
          <div class="field"><label>发布者平台</label><select id="newPublisherPlatform">${platforms.length
            ? platforms.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")
            : `<option value="">无可用发布者平台</option>`}</select></div>
          <div class="field">
            <label>发布者 UID / 用户名<span class="required-mark">*</span></label>
            <div class="publisher-search-row">
              <input id="newPublisherId" placeholder="填写 UID 可直接添加；填写用户名需搜索选择">
              <button type="button" class="publisher-search-button" id="newPublisherSearch">搜索</button>
            </div>
          </div>
          <div class="field full" id="newPublisherResultWrap" hidden>
            <div class="field-head"><label>搜索结果</label></div>
            <div id="newPublisherResultList" class="target-choice-list"></div>
          </div>
          <div class="field full"><span id="newPublisherStatus" class="inline-note">可填写 UID 手动添加；用户名搜索成功后请选择结果。</span></div>
        </div>
      </section>
    </div>
  `, async () => {
    const selected = collectCreatePublisherCandidate(publisherCandidates);
    const platformId = $("newPublisherPlatform").value.trim();
    const inputExternalId = $("newPublisherId").value.trim();
    if (!selected && inputExternalId && !isLikelyPublisherUid(inputExternalId)) {
      throw new Error("请选择发布者；如果填写的是用户名，请先搜索并选择结果");
    }
    const externalId = selected ? selected.externalId : inputExternalId;
    if (!platformId || !externalId) throw new Error("请选择平台并填写发布者 UID 或用户名");
    await api("/publishers", { method: "POST", body: JSON.stringify({ platformId, externalId }) });
    closeModal();
    invalidate("dashboard", "publishers", "subscriptions");
    await loadEntities(true);
    notify("发布者已添加", false);
  }, {
    size: "narrow",
    confirmText: "添加",
    cleanup: () => {
      modalClosed = true;
    },
  });

  const search = async () => {
    if (!isModalActive()) return;
    const platformId = $("newPublisherPlatform").value.trim();
    const queryText = $("newPublisherId").value.trim();
    if (!platformId) throw new Error("没有可用的发布者平台");
    if (!queryText) throw new Error("请填写发布者 UID 或用户名");
    publisherCandidates = [];
    setCreatePublisherLoading("正在搜索发布者...");
    const result = await api(`/publisher-search?platformId=${encodeURIComponent(platformId)}&q=${encodeURIComponent(queryText)}`);
    if (!isModalActive()) return;
    publisherCandidates = result;
    if (publisherCandidates.length) {
      $("newPublisherResultWrap").hidden = false;
      $("newPublisherResultList").innerHTML = publisherCandidates.map((publisher, index) => publisherCandidateHtml(publisher, index, index === 0)).join("");
      await hydrateMediaImages($("newPublisherResultList"));
      if (!isModalActive()) return;
      $("newPublisherStatus").textContent = `已找到 ${publisherCandidates.length} 个发布者，已默认选择第一个`;
    } else {
      $("newPublisherResultWrap").hidden = false;
      $("newPublisherResultList").innerHTML = `<div class="empty">未找到发布者</div>`;
      $("newPublisherStatus").textContent = "未找到发布者；如果填写的是 UID，可以直接添加";
    }
  };
  $("newPublisherSearch").onclick = () => search().catch(error => {
    if (!isModalActive()) return;
    $("newPublisherResultWrap").hidden = true;
    $("newPublisherStatus").textContent = error.message || String(error);
  });
  $("newPublisherId").onkeydown = event => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    search().catch(error => {
      if (!isModalActive()) return;
      $("newPublisherResultWrap").hidden = true;
      $("newPublisherStatus").textContent = error.message || String(error);
    });
  };
  $("newPublisherId").oninput = () => {
    publisherCandidates = [];
    $("newPublisherResultWrap").hidden = true;
    $("newPublisherStatus").textContent = "可填写 UID 手动添加；用户名搜索成功后请选择结果。";
  };
  $("newPublisherPlatform").onchange = () => {
    publisherCandidates = [];
    $("newPublisherResultWrap").hidden = true;
    $("newPublisherStatus").textContent = "可填写 UID 手动添加；用户名搜索成功后请选择结果。";
  };
}

function setCreatePublisherLoading(text) {
  if (!$("newPublisherResultList")) return;
  $("newPublisherResultWrap").hidden = false;
  $("newPublisherResultList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
  $("newPublisherStatus").textContent = text;
}

function publisherCandidateHtml(publisher, index, checked) {
  const title = publisher.name || publisher.externalId;
  const parts = [
    title,
    label(publisher.kind),
    publisher.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice">
    <input type="radio" name="newPublisherCandidate" value="${attr(publisher.externalId)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(publisher.avatarUri, "target-choice-avatar", publisher.platformId, "AVATAR")}
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
  </label>`;
}

function collectCreatePublisherCandidate(candidates) {
  if (!candidates.length || $("newPublisherResultWrap").hidden) return null;
  const input = document.querySelector(`input[name="newPublisherCandidate"]:checked`);
  return input ? candidates[Number(input.dataset.index)] : null;
}

function isLikelyPublisherUid(value) {
  return /^\d+$/.test((value || "").trim());
}

async function openCreateSubscriber() {
  const modalSeq = ++createSubscriberModalSeq;
  let modalClosed = false;
  const isModalActive = () => !modalClosed && createSubscriberModalSeq === modalSeq && !!$("newTargetCandidateList");
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const targetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  const fallbackTargets = targetPlatforms.length ? targetPlatforms : [{ platformId: "qq", pluginName: "手动", supportedTypes: ["GROUP", "USER"] }];
  let targetCandidates = [];

  openModal("添加消息目标", `
    <div class="subscription-create">
      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>消息目标</h2>
            <p>选择即时通讯平台目标，可一次添加多个。</p>
          </div>
        </div>
        <div class="form-grid">
          <div class="field"><label>目标平台</label><select id="newTargetPlatform">${fallbackTargets.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")}</select></div>
          <div class="field"><label>目标类型</label><select id="newTargetKind"></select></div>
          <div class="field full" id="newTargetCandidateWrap">
            <div class="field-head">
              <div class="field-title-line">
                <label>可用目标</label>
                <span id="newTargetStatus" class="field-inline-status"></span>
              </div>
              <div class="row-actions" id="newTargetCandidateActions">
                <button type="button" class="secondary compact choice-tool-button choice-refresh-button" id="newTargetRefresh">刷新</button>
                <button type="button" class="secondary compact choice-tool-button" id="newTargetSelectAll">全选</button>
                <button type="button" class="secondary compact choice-tool-button choice-clear-button" id="newTargetClearAll">清空</button>
              </div>
            </div>
            <div id="newTargetCandidateList" class="target-choice-list"></div>
          </div>
          <div class="field full" id="newTargetManualWrap"><label>目标 ID</label><input id="newTargetManual" placeholder="插件无法枚举目标时手动填写"></div>
          <div class="field full"><label>链接解析</label><select id="newTargetLinkParse">${linkParseModeOptions("INHERIT")}</select><span class="inline-note">使用全局回退时，会跟随主配置里的回退触发方式。</span></div>
        </div>
      </section>
    </div>
  `, async () => {
    const selectedTargets = collectCreateSubscriberTargets(targetCandidates);
    const platformId = $("newTargetPlatform").value.trim();
    const targetKind = $("newTargetKind").value;
    const mode = $("newTargetLinkParse").value;
    if (!platformId || !targetKind || selectedTargets.length === 0) throw new Error("请选择或填写消息目标");
    for (const target of selectedTargets) {
      const payload = {
        platformId,
        targetKind,
        externalId: target.externalId
      };
      if (target.scopeId) payload.scopeId = target.scopeId;
      if (target.threadId) payload.threadId = target.threadId;
      if (target.accountId) payload.accountId = target.accountId;
      if (target.name) payload.name = target.name;
      if (mode !== "INHERIT") payload.linkParseTriggerMode = mode;
      await api("/subscribers", { method: "POST", body: JSON.stringify(payload) });
    }
    closeModal();
    invalidate("dashboard", "subscribers", "subscriptions");
    await loadEntities(true);
    notify(selectedTargets.length === 1 ? "消息目标已添加" : `已添加 ${selectedTargets.length} 个消息目标`, false);
  }, {
    size: "medium",
    confirmText: "添加",
    cleanup: () => {
      modalClosed = true;
    },
  });

  const refreshTargetKinds = async () => {
    if (!isModalActive()) return;
    const platformId = $("newTargetPlatform").value;
    const platform = fallbackTargets.find(item => item.platformId === platformId);
    const kinds = platform && platform.supportedTypes && platform.supportedTypes.length
      ? platform.supportedTypes
      : ["GROUP", "USER", "CHANNEL", "OTHER"];
    if (!isModalActive()) return;
    $("newTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
    await refreshTargetCandidates();
  };
  const refreshTargetCandidates = async (force = false) => {
    if (!isModalActive()) return;
    const platform = $("newTargetPlatform").value;
    const kind = $("newTargetKind").value;
    targetCandidates = [];
    setCreateSubscriberTargetLoading(force ? "正在刷新可用目标..." : "正在获取可用目标...");
    let allCandidates = [];
    let source = "后端";
    try {
      const result = await loadSubscriberTargetCandidates(platform, kind, force);
      if (!isModalActive()) return;
      allCandidates = result.items;
      source = result.stale ? "过期缓存" : result.fromCache ? "缓存" : "后端";
      targetCandidates = filterExistingSubscriberTargets(allCandidates);
    } catch (error) {
      if (!isModalActive()) return;
      $("newTargetCandidateWrap").hidden = false;
      $("newTargetCandidateActions").hidden = true;
      $("newTargetManualWrap").hidden = false;
      $("newTargetCandidateList").innerHTML = `<div class="empty">目标列表获取失败</div>`;
      setTargetInlineStatus("目标列表获取失败，请手动填写目标 ID");
      return;
    }
    if (targetCandidates.length) {
      $("newTargetCandidateWrap").hidden = false;
      $("newTargetCandidateActions").hidden = false;
      $("newTargetManualWrap").hidden = true;
      const candidateList = $("newTargetCandidateList");
      if (!candidateList) return;
      candidateList.innerHTML = targetCandidates.map((target, index) => subscriberTargetChoiceHtml(target, index, false)).join("");
      bindTargetSourceToggles(candidateList);
      await hydrateMediaImages(candidateList);
      if (!isModalActive()) return;
      setTargetInlineStatus(`${targetCandidates.length} 个可添加目标，已添加目标不显示，来自${source}`);
    } else {
      $("newTargetCandidateWrap").hidden = false;
      $("newTargetCandidateActions").hidden = true;
      $("newTargetManualWrap").hidden = false;
      $("newTargetCandidateList").innerHTML = `<div class="empty">暂无可添加目标</div>`;
      setTargetInlineStatus(allCandidates.length ? `已添加过的目标已排除，可手动填写目标 ID，来自${source}` : `未获取到目标，请手动填写目标 ID，来自${source}`);
    }
  };
  $("newTargetPlatform").onchange = refreshTargetKinds;
  $("newTargetKind").onchange = () => refreshTargetCandidates();
  $("newTargetRefresh").onclick = () => refreshTargetCandidates(true).catch(handleError);
  $("newTargetSelectAll").onclick = () => setCreateSubscriberTargetChecked(true);
  $("newTargetClearAll").onclick = () => setCreateSubscriberTargetChecked(false);
  await refreshTargetKinds();
}

function setCreateSubscriberTargetLoading(text) {
  if (!$("newTargetCandidateList")) return;
  $("newTargetCandidateWrap").hidden = false;
  $("newTargetCandidateActions").hidden = true;
  $("newTargetManualWrap").hidden = true;
  $("newTargetCandidateList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
  setTargetInlineStatus("");
}

function setTargetInlineStatus(text) {
  if (!$("newTargetStatus")) return;
  $("newTargetStatus").textContent = text ? `· ${text}` : "";
}

function subscriberTargetChoiceHtml(target, index, checked) {
  return messageTargetChoiceHtml(target, index, {
    inputName: "newTargetCandidate",
    prefix: "newTarget",
    inputType: "checkbox",
    checked,
  });
}

function setCreateSubscriberTargetChecked(checked) {
  document.querySelectorAll(`input[name="newTargetCandidate"]`).forEach(input => input.checked = checked);
}

function collectCreateSubscriberTargets(candidates) {
  if (candidates.length === 0 || $("newTargetCandidateWrap").hidden) {
    const manual = $("newTargetManual").value.trim();
    return manual ? [{ externalId: manual, name: manual }] : [];
  }
  return Array.from(document.querySelectorAll(`input[name="newTargetCandidate"]:checked`))
    .map(input => {
      const index = Number(input.dataset.index);
      const target = candidates[index];
      if (!target) return null;
      const accountId = selectedTargetPriorityAccount("newTarget", index);
      return Object.assign({}, target, accountId ? { accountId } : { accountId: undefined });
    })
    .filter(Boolean);
}

function filterExistingSubscriberTargets(candidates) {
  const existed = new Set((state.cache.subscribers || []).map(target =>
    subscriberTargetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId)
  ));
  return (candidates || []).filter(target =>
    !existed.has(subscriberTargetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId))
  );
}

async function openEditPublisher(id) {
  if (!state.cache.publishers) state.cache.publishers = await api("/publishers");
  const item = state.cache.publishers.find(row => Number(row.id) === Number(id));
  openModal("编辑发布者", `
    <div class="form-grid">
      <div class="field"><label>状态</label><select id="entityState">${entityStateOptions(item.state)}</select></div>
      <div class="field full"><label>头图</label><input id="entityHeader" value="${attr(item.bannerUri || "")}"></div>
      <div class="field full"><label>主题色</label><div class="command-permission-toolbar"><input id="entityThemeColors" placeholder="#FE65A6;#BFFAFF"><button type="button" class="secondary" id="entityClearThemeButton"${item.drawTheme ? "" : " disabled"}>清除主题色</button></div><span class="inline-note">多个颜色用英文分号分隔；留空表示不修改。</span></div>
    </div>
  `, async () => {
    const body = { headerUri: $("entityHeader").value, state: $("entityState").value };
    const themeColors = $("entityThemeColors").value.trim();
    if (themeColors) body.themeColors = themeColors;
    await api(`/publishers/${id}`, { method: "PATCH", body: JSON.stringify(body) });
    closeModal();
    invalidate("publishers", "subscriptions", "dashboard");
    await loadEntities(true);
    notify("发布者已保存", false);
  }, { size: "medium" });
  $("entityClearThemeButton").onclick = async () => {
    await api(`/publishers/${id}`, { method: "PATCH", body: JSON.stringify({ clearTheme: true }) });
    closeModal();
    invalidate("publishers", "subscriptions", "dashboard");
    await loadEntities(true);
    notify("发布者主题色已清除", false);
  };
}

async function openEditSubscriber(id) {
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const item = state.cache.subscribers.find(row => Number(row.id) === Number(id));
  const currentLinkParse = item.linkParseConfigSource === "CUSTOM" ? item.linkParseTriggerMode : "INHERIT";
  openModal("编辑消息目标", `
    <div class="form-grid single">
      <div class="field"><label>状态</label><select id="entityState">${entityStateOptions(item.state)}</select></div>
      <div class="field"><label>链接解析</label><select id="subscriberLinkParse">${linkParseModeOptions(currentLinkParse)}</select></div>
      <div class="field full"><span class="inline-note">选择“使用全局回退”会删除当前消息目标的单独链接解析配置。当前生效：${esc(linkParseModeLabel(item.effectiveLinkParseTriggerMode))}</span></div>
    </div>
  `, async () => {
    const body = {
      state: $("entityState").value
    };
    const mode = $("subscriberLinkParse").value;
    if (mode === "INHERIT") body.clearLinkParseTrigger = true;
    else body.linkParseTriggerMode = mode;
    await api(`/subscribers/${id}`, { method: "PATCH", body: JSON.stringify(body) });
    closeModal();
    invalidate("subscribers", "subscriptions", "dashboard");
    await loadEntities(true);
    notify("消息目标已保存", false);
  }, { size: "small" });
}
