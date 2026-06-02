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
let platformTag;
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
const entityFilters = {
  publisherPlatform: "",
  publisherState: "",
  subscriberPlatform: "",
  subscriberState: ""
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
    platformTag,
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
  await loadEntities(ctx.force);
}

export async function handleAction(nextCtx, { action, id }) {
  bindContext(nextCtx);
  if (action === "edit-publisher") {
    await openEditPublisher(id);
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
  if (action === "delete-publisher") {
    if (!confirm("确定删除这个发布者及关联订阅吗？")) return true;
    const result = await api(`/publishers/${id}`, { method: "DELETE" });
    invalidate("dashboard", "publishers", "subscriptions");
    await loadEntities(true);
    notify(result.message, false);
    return true;
  }
  if (action === "delete-subscriber") {
    if (!confirm("确定删除这个消息目标及关联订阅吗？")) return true;
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

function linkParseModeLabel(value) {
  const map = {
    INHERIT: "使用全局回退",
    DISABLED: "不解析",
    MENTION_ONLY: "必须 @bot",
    ALWAYS: "匹配链接即解析"
  };
  return map[value] || value || "-";
}

function linkParseModeOptions(selected) {
  return ["INHERIT", "DISABLED", "MENTION_ONLY", "ALWAYS"].map(mode =>
    `<option value="${mode}"${mode === selected ? " selected" : ""}>${esc(linkParseModeLabel(mode))}</option>`
  ).join("");
}

function linkParseCell(target) {
  const source = target.linkParseConfigSource === "CUSTOM" ? "当前目标配置" : "全局回退";
  return cell(linkParseModeLabel(target.effectiveLinkParseTriggerMode), source);
}

function uniqueValues(rows, key, extraValues = []) {
  const values = new Set(extraValues.filter(Boolean));
  (rows || []).forEach(row => {
    if (row && row[key]) values.add(row[key]);
  });
  return Array.from(values).sort((a, b) => String(a).localeCompare(String(b), "zh-CN"));
}

function filterOptions(allText, values, selected, labeler = value => value) {
  return `<option value="">${esc(allText)}</option>` + values.map(value =>
    `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(labeler(value))}</option>`
  ).join("");
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
  releaseMediaObjectUrls();
  if (force || !state.cache.publishers) state.cache.publishers = await api("/publishers");
  if (force || !state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
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
          { title: "发布者", render: p => identity(p.name, p ? `${label(p.kind)}:${p.externalId}` : "-", p.avatarUri, p.platformId, "AVATAR") },
          { title: "主题色", render: p => themeSwatch(p.drawTheme) },
          { title: "头图", render: p => p.bannerUri ? mediaImage(p.bannerUri, "header-image", p.platformId, "COVER") : `<span class="sub-line">-</span>` },
          { title: "订阅", render: p => `<span class="primary-line">${p.subscriptionCount || 0}</span>` },
          { title: "状态", render: p => entityStatePill(p.state) },
          { title: "创建时间", render: p => `<span class="sub-line">${fmtTime(p.createTime)}</span>` },
          { title: "操作", render: p => `<div class="row-actions"><button data-action="edit-publisher" data-id="${p.id}">编辑</button><button class="danger" data-action="delete-publisher" data-id="${p.id}">删除</button></div>` }
        ])}
      </section>
      <section class="panel full">
        <div class="panel-head"><h2>消息目标</h2><button class="add-button" data-action="create-subscriber">添加</button></div>
        ${entityFilterBar("subscriber", subscribers, filteredSubscribers)}
        ${renderTable(filteredSubscribers, [
          { title: "平台", render: s => platformTag(s.platformId, s.platformId) },
          { title: "目标", render: s => identity(s.name, s ? `${label(s.targetKind)}:${s.externalId}` : "-", s.avatarUri, s.platformId, "AVATAR") },
          { title: "订阅", render: s => `<span class="primary-line">${s.subscriptionCount || 0}</span>` },
          { title: "链接解析", render: s => linkParseCell(s) },
          { title: "状态", render: s => entityStatePill(s.state) },
          { title: "创建时间", render: s => `<span class="sub-line">${fmtTime(s.createTime)}</span>` },
          { title: "操作", render: s => `<div class="row-actions"><button data-action="edit-subscriber" data-id="${s.id}">编辑</button><button class="danger" data-action="delete-subscriber" data-id="${s.id}">删除</button></div>` }
        ])}
      </section>
    </section>`;
  bindEntityFilters();
  await hydrateMediaImages($("content"));
}

function liveSummary(publisher) {
  const lives = publisher.liveStatuses || [];
  if (!lives.length) return "无直播状态";
  return lives.map(live => `${label(live.status)} ${live.title || live.roomId}`).join(" / ");
}

function cursorSummary(publisher) {
  const cursors = publisher.cursors || [];
  if (!cursors.length) return "无游标";
  const latest = cursors.slice().sort((a, b) => b.lastSeenAtEpochSeconds - a.lastSeenAtEpochSeconds)[0];
  return `${latest.sourceKey} · ${eventLabel(latest.eventType)} · ${fmtTime(latest.lastSeenAtEpochSeconds)}`;
}

async function openCreatePublisher() {
  const platforms = await api("/publisher-platforms").catch(() => []);
  let publisherCandidates = [];
  openModal("添加发布者", `
    <div class="subscription-create">
      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>发布者</h2>
            <p>先按平台和 ID 搜索发布者，再从结果中选择添加。</p>
          </div>
        </div>
        <div class="form-grid single publisher-create-form">
          <div class="field"><label>发布者平台</label><select id="newPublisherPlatform">${platforms.length
            ? platforms.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")
            : `<option value="">无可用发布者平台</option>`}</select></div>
          <div class="field">
            <label>发布者 ID<span class="required-mark">*</span></label>
            <div class="publisher-search-row">
              <input id="newPublisherId" placeholder="当前支持 UID 搜索">
              <button type="button" class="publisher-search-button" id="newPublisherSearch">搜索</button>
            </div>
          </div>
          <div class="field full" id="newPublisherResultWrap" hidden>
            <div class="field-head"><label>搜索结果</label></div>
            <div id="newPublisherResultList" class="target-choice-list"></div>
          </div>
          <div class="field full"><span id="newPublisherStatus" class="inline-note">请输入发布者 ID 后搜索。</span></div>
        </div>
      </section>
    </div>
  `, async () => {
    const selected = collectCreatePublisherCandidate(publisherCandidates);
    const platformId = $("newPublisherPlatform").value.trim();
    const externalId = selected ? selected.externalId : $("newPublisherId").value.trim();
    if (!platformId || !externalId) throw new Error("请选择平台并填写发布者 ID");
    await api("/publishers", { method: "POST", body: JSON.stringify({ platformId, externalId }) });
    closeModal();
    invalidate("dashboard", "publishers", "subscriptions");
    await loadEntities(true);
    notify("发布者已添加", false);
  }, { size: "narrow", confirmText: "添加" });

  const search = async () => {
    const platformId = $("newPublisherPlatform").value.trim();
    const queryText = $("newPublisherId").value.trim();
    if (!platformId) throw new Error("没有可用的发布者平台");
    if (!queryText) throw new Error("请填写发布者 ID");
    publisherCandidates = [];
    setCreatePublisherLoading("正在搜索发布者...");
    publisherCandidates = await api(`/publisher-search?platformId=${encodeURIComponent(platformId)}&q=${encodeURIComponent(queryText)}`);
    if (publisherCandidates.length) {
      $("newPublisherResultWrap").hidden = false;
      $("newPublisherResultList").innerHTML = publisherCandidates.map((publisher, index) => publisherCandidateHtml(publisher, index, index === 0)).join("");
      await hydrateMediaImages($("newPublisherResultList"));
      $("newPublisherStatus").textContent = `已找到 ${publisherCandidates.length} 个发布者，已默认选择第一个`;
    } else {
      $("newPublisherResultWrap").hidden = false;
      $("newPublisherResultList").innerHTML = `<div class="empty">未找到发布者</div>`;
      $("newPublisherStatus").textContent = "未找到发布者，请确认平台和 UID";
    }
  };
  $("newPublisherSearch").onclick = () => search().catch(error => {
    $("newPublisherResultWrap").hidden = true;
    $("newPublisherStatus").textContent = error.message || String(error);
  });
  $("newPublisherId").onkeydown = event => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    search().catch(error => {
      $("newPublisherResultWrap").hidden = true;
      $("newPublisherStatus").textContent = error.message || String(error);
    });
  };
}

function setCreatePublisherLoading(text) {
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

async function openCreateSubscriber() {
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const targetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  const fallbackTargets = targetPlatforms.length ? targetPlatforms : [{ platformId: "onebot", pluginName: "手动", supportedTypes: ["GROUP", "USER"] }];
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
  }, { size: "medium", confirmText: "添加" });

  const refreshTargetKinds = async () => {
    const platformId = $("newTargetPlatform").value;
    const platform = fallbackTargets.find(item => item.platformId === platformId);
    const kinds = platform && platform.supportedTypes && platform.supportedTypes.length
      ? platform.supportedTypes
      : ["GROUP", "USER", "CHANNEL", "OTHER"];
    $("newTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
    await refreshTargetCandidates();
  };
  const refreshTargetCandidates = async () => {
    const platform = $("newTargetPlatform").value;
    const kind = $("newTargetKind").value;
    targetCandidates = [];
    setCreateSubscriberTargetLoading("正在获取可用目标...");
    let allCandidates = [];
    try {
      allCandidates = await api(`/subscriber-targets?platformId=${encodeURIComponent(platform)}&type=${encodeURIComponent(kind)}`);
      targetCandidates = filterExistingSubscriberTargets(allCandidates);
    } catch (error) {
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
      $("newTargetCandidateList").innerHTML = targetCandidates.map((target, index) => subscriberTargetChoiceHtml(target, index, false)).join("");
      await hydrateMediaImages($("newTargetCandidateList"));
      setTargetInlineStatus(`${targetCandidates.length} 个可添加目标，已添加目标不显示`);
    } else {
      $("newTargetCandidateWrap").hidden = false;
      $("newTargetCandidateActions").hidden = true;
      $("newTargetManualWrap").hidden = false;
      $("newTargetCandidateList").innerHTML = `<div class="empty">暂无可添加目标</div>`;
      setTargetInlineStatus(allCandidates.length ? "已添加过的目标已排除，可手动填写目标 ID" : "未获取到目标，请手动填写目标 ID");
    }
  };
  $("newTargetPlatform").onchange = refreshTargetKinds;
  $("newTargetKind").onchange = refreshTargetCandidates;
  $("newTargetSelectAll").onclick = () => setCreateSubscriberTargetChecked(true);
  $("newTargetClearAll").onclick = () => setCreateSubscriberTargetChecked(false);
  await refreshTargetKinds();
}

function setCreateSubscriberTargetLoading(text) {
  $("newTargetCandidateWrap").hidden = false;
  $("newTargetCandidateActions").hidden = true;
  $("newTargetManualWrap").hidden = true;
  $("newTargetCandidateList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
  setTargetInlineStatus("");
}

function setTargetInlineStatus(text) {
  $("newTargetStatus").textContent = text ? `· ${text}` : "";
}

function subscriberTargetChoiceHtml(target, index, checked) {
  const title = target.name || target.externalId;
  const parts = [
    title,
    label(target.targetKind),
    target.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice">
    <input type="checkbox" name="newTargetCandidate" value="${attr(target.externalId)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(target.avatarUri, "target-choice-avatar", target.platformId, "AVATAR")}
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
  </label>`;
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
    .map(input => candidates[Number(input.dataset.index)])
    .filter(Boolean);
}

function filterExistingSubscriberTargets(candidates) {
  const existed = new Set((state.cache.subscribers || []).map(target =>
    targetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId, target.accountId)
  ));
  return (candidates || []).filter(target =>
    !existed.has(targetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId, target.accountId))
  );
}

function targetAddressKey(platformId, kind, externalId, scopeId, threadId, accountId) {
  return [platformId, kind, externalId, scopeId || "", threadId || "", accountId || ""].join("\u001F");
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
