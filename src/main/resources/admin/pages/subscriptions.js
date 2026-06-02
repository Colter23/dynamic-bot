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
  await loadSubscriptions(ctx.force);
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "create-subscription") {
    await openCreateSubscription();
    return true;
  }
  if (action === "subscription-detail") {
    await openSubscriptionDetail(id);
    return true;
  }
  if (action === "delete-subscription") {
    if (!confirm("确定删除这个订阅吗？")) return true;
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
    if (!confirm("确定清空这个订阅的过滤规则吗？")) return true;
    const result = await api(`/subscriptions/${id}/filter-rules`, { method: "DELETE" });
    invalidate("subscriptions", "dashboard");
    const subscriptions = await ensureSubscriptions(true);
    const subscription = subscriptions.find(item => Number(item.id) === Number(id));
    $("filterList").innerHTML = renderFilterList(subscription);
    notify(result.message || "过滤规则已清空", false);
    return true;
  }
  if (action === "delete-filter") {
    if (!confirm("确定删除这条过滤规则吗？")) return true;
    await api(`/filter-rules/${id}`, { method: "DELETE" });
    invalidate("subscriptions", "dashboard");
    const subscriptions = await ensureSubscriptions(true);
    const subscription = subscriptions.find(item => Number(item.id) === Number(button.dataset.subscription));
    $("filterList").innerHTML = renderFilterList(subscription);
    notify("过滤规则已删除", false);
    return true;
  }
  return false;
}

async function ensureSubscriptions(force) {
  if (force || !state.cache.subscriptions) state.cache.subscriptions = await api("/subscriptions");
  return state.cache.subscriptions;
}

async function loadSubscriptions(force) {
  releaseMediaObjectUrls();
  const rows = await ensureSubscriptions(force);
  pageRoot().innerHTML = `
    <section class="page">
      <section class="panel full">
        <div class="toolbar">
          <h2>订阅关系</h2>
          <div class="toolbar-actions">
            <button class="add-button" data-action="create-subscription">添加订阅</button>
          </div>
        </div>
        ${renderTable(rows, [
          { title: "发布者", render: s => identity(s.publisher && s.publisher.name, publisherKey(s.publisher), s.publisher && s.publisher.avatarUri, s.publisher && s.publisher.platformId, "AVATAR") },
          { title: "消息目标", render: s => identity(s.subscriber && s.subscriber.name, targetKey(s.subscriber), s.subscriber && s.subscriber.avatarUri, s.subscriber && s.subscriber.platformId, "AVATAR") },
          { title: "接收事件", render: s => tags(policyEvents(s.policy).map(eventLabel)) },
          { title: "@全体", render: s => tags(mentionEvents(s.policy).map(eventLabel)) },
          { title: "动态过滤", render: s => cell(`${s.filterRuleCount || 0} 条阻止规则`, "仅作用于动态内容") },
          { title: "更新时间", render: s => `<span class="sub-line">${fmtTime(s.updatedAtEpochSeconds)}</span>` },
          { title: "操作", render: s => `<div class="row-actions"><button data-action="subscription-detail" data-id="${s.id}">详情</button><button class="danger" data-action="delete-subscription" data-id="${s.id}">删除</button></div>` }
        ])}
      </section>
    </section>`;
  await hydrateMediaImages($("content"));
}

async function openCreateSubscription() {
  await ensureSubscriptions(false);
  if (!state.cache.publishers) state.cache.publishers = await api("/publishers");
  if (!state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const targetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  const fallbackTargets = targetPlatforms.length ? targetPlatforms : [{ platformId: "onebot", pluginName: "手动", supportedTypes: ["GROUP", "USER"] }];
  const publisherPlatforms = await api("/publisher-platforms").catch(() => []);
  const existingTargets = state.cache.subscribers || [];
  const existingPublishers = state.cache.publishers || [];
  const targetMode = existingTargets.length ? "existing" : "new";
  const publisherMode = existingPublishers.length ? "existing" : "new";
  let targetCandidates = [];
  let publisherCandidates = [];

  openModal("添加订阅", `
    <div class="subscription-create subscription-create-subscription">
      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>消息目标</h2>
            <p>可直接使用已有目标，也可以从插件枚举的目标中批量选择。</p>
          </div>
        </div>
        ${subscriptionModeSwitch("subTargetMode", [
          { value: "existing", text: "选择已有目标", checked: targetMode === "existing", disabled: existingTargets.length === 0 },
          { value: "new", text: "新增目标", checked: targetMode === "new" },
        ])}
        <div id="subExistingTargetBlock" class="form-grid single">
          <div class="field full">
            <div class="field-head"><label>已有消息目标</label><span class="inline-note">选择一个已添加的消息目标。</span></div>
            <div id="subExistingTargetList" class="target-choice-list">${existingTargets.length
              ? existingTargets.map((target, index) => existingTargetChoiceHtml(target, index, index === 0 && targetMode === "existing")).join("")
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

      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>发布者</h2>
            <p>可直接选择已有发布者，也可以按平台和 ID 搜索后添加订阅。</p>
          </div>
        </div>
        ${subscriptionModeSwitch("subPublisherMode", [
          { value: "existing", text: "选择已有发布者", checked: publisherMode === "existing", disabled: existingPublishers.length === 0 },
          { value: "new", text: "新增发布者", checked: publisherMode === "new" },
        ])}
        <div id="subExistingPublisherBlock" class="form-grid single">
          <div class="field full">
            <div class="field-head"><label>已有发布者</label><span class="inline-note">选择一个已添加的发布者。</span></div>
            <div id="subExistingPublisherList" class="target-choice-list">${existingPublishers.length
              ? existingPublishers.map((publisher, index) => existingPublisherChoiceHtml(publisher, index, index === 0 && publisherMode === "existing")).join("")
              : `<div class="empty">还没有发布者，请切换到新增发布者。</div>`}</div>
          </div>
        </div>
        <div id="subNewPublisherBlock" class="form-grid single publisher-create-form">
          <div class="field"><label>发布者平台</label><select id="subNewPublisherPlatform">${publisherPlatforms.length
            ? publisherPlatforms.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")
            : `<option value="">无可用发布者平台</option>`}</select></div>
          <div class="field">
            <label>发布者 ID<span class="required-mark">*</span></label>
            <div class="publisher-search-row">
              <input id="subNewPublisherId" placeholder="当前支持 UID 搜索">
              <button type="button" class="publisher-search-button" id="subNewPublisherSearch">搜索</button>
            </div>
          </div>
          <div class="field full" id="subNewPublisherResultWrap" hidden>
            <div class="field-head"><label>搜索结果</label></div>
            <div id="subNewPublisherResultList" class="target-choice-list"></div>
          </div>
          <div class="field full"><span id="subNewPublisherStatus" class="inline-note">请输入发布者 ID 后搜索。</span></div>
        </div>
      </section>

      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>订阅类型</h2>
            <p>选择接收动态、开播或下播；@全体仅对群目标生效。</p>
          </div>
        </div>
        ${policyForm("subPolicy", null, "GROUP")}
      </section>
    </div>
  `, async () => {
    const selectedTargets = collectCreateSubscriptionTargets(targetCandidates);
    const selectedPublisher = collectCreateSubscriptionPublisher(publisherCandidates);
    if (!selectedPublisher) throw new Error("请选择发布者或切换到新增发布者");
    if (selectedTargets.length === 0) throw new Error("请选择或填写消息目标");
    const basePayload = {
      publisherId: selectedPublisher.publisherId,
      publisherPlatform: selectedPublisher.platformId,
      publisherExternalId: selectedPublisher.externalId,
      autoFollow: false,
      policy: collectPolicy("subPolicy")
    };

    let successCount = 0;
    const failures = [];
    for (const target of selectedTargets) {
      try {
        await api("/subscriptions", {
          method: "POST",
          body: JSON.stringify(Object.assign({}, basePayload, {
            subscriberId: target.subscriberId,
            subscriberPlatform: target.platformId,
            targetKind: target.targetKind,
            subscriberTargetId: target.externalId,
            subscriberScopeId: target.scopeId,
            subscriberThreadId: target.threadId,
            subscriberAccountId: target.accountId,
            subscriberLinkParseTriggerMode: target.linkParseTriggerMode,
          }))
        });
        successCount += 1;
      } catch (error) {
        failures.push(`${target.label}: ${error.message || error}`);
      }
    }
    invalidate("dashboard", "subscriptions", "publishers", "subscribers");
    await loadSubscriptions(true);
    if (failures.length) {
      throw new Error(`已创建 ${successCount} 个，失败 ${failures.length} 个：\n${failures.join("\n")}`);
    }
    closeModal();
    notify(successCount > 1 ? `已创建 ${successCount} 个订阅` : "订阅已创建", false);
  }, { size: "subscription", confirmText: "创建" });

  const policyUpdater = wirePolicyForm("subPolicy", currentCreateSubscriptionTargetKind);
  bindCreateSubscriptionMode(policyUpdater);
  document.querySelectorAll(`input[name="subExistingTarget"]`).forEach(input => input.onchange = policyUpdater);
  const refreshTargetKinds = async () => {
    const platformId = $("subNewTargetPlatform").value;
    const platform = fallbackTargets.find(item => item.platformId === platformId);
    const kinds = platform && platform.supportedTypes && platform.supportedTypes.length
      ? platform.supportedTypes
      : ["GROUP", "USER", "CHANNEL", "OTHER"];
    $("subNewTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
    policyUpdater();
    await refreshTargetCandidates();
  };
  const refreshTargetCandidates = async () => {
    const platform = $("subNewTargetPlatform").value;
    const kind = $("subNewTargetKind").value;
    targetCandidates = [];
    setCreateSubscriptionTargetLoading("正在获取可用目标...");
    let allCandidates = [];
    try {
      allCandidates = await api(`/subscriber-targets?platformId=${encodeURIComponent(platform)}&type=${encodeURIComponent(kind)}`);
      targetCandidates = filterExistingSubscriptionTargets(allCandidates);
    } catch (error) {
      $("subNewTargetCandidateWrap").hidden = false;
      $("subNewTargetCandidateActions").hidden = true;
      $("subNewTargetManualWrap").hidden = false;
      $("subNewTargetCandidateList").innerHTML = `<div class="empty">目标列表获取失败</div>`;
      setCreateSubscriptionTargetStatus("目标列表获取失败，请手动填写目标 ID");
      return;
    }
    if (targetCandidates.length) {
      $("subNewTargetCandidateWrap").hidden = false;
      $("subNewTargetCandidateActions").hidden = false;
      $("subNewTargetManualWrap").hidden = true;
      $("subNewTargetCandidateList").innerHTML = targetCandidates.map((target, index) => subscriptionTargetChoiceHtml(target, index, false)).join("");
      await hydrateMediaImages($("subNewTargetCandidateList"));
      setCreateSubscriptionTargetStatus(`${targetCandidates.length} 个可添加目标，已添加目标不显示`);
    } else {
      $("subNewTargetCandidateWrap").hidden = false;
      $("subNewTargetCandidateActions").hidden = true;
      $("subNewTargetManualWrap").hidden = false;
      $("subNewTargetCandidateList").innerHTML = `<div class="empty">暂无可添加目标</div>`;
      setCreateSubscriptionTargetStatus(allCandidates.length ? "已添加过的目标已排除，可手动填写目标 ID" : "未获取到目标，请手动填写目标 ID");
    }
  };
  const searchPublisher = async () => {
    const platformId = $("subNewPublisherPlatform").value.trim();
    const queryText = $("subNewPublisherId").value.trim();
    if (!platformId) throw new Error("没有可用的发布者平台");
    if (!queryText) throw new Error("请填写发布者 ID");
    publisherCandidates = [];
    setCreateSubscriptionPublisherLoading("正在搜索发布者...");
    publisherCandidates = await api(`/publisher-search?platformId=${encodeURIComponent(platformId)}&q=${encodeURIComponent(queryText)}`);
    if (publisherCandidates.length) {
      $("subNewPublisherResultWrap").hidden = false;
      $("subNewPublisherResultList").innerHTML = publisherCandidates.map((publisher, index) => subscriptionPublisherCandidateHtml(publisher, index, index === 0)).join("");
      await hydrateMediaImages($("subNewPublisherResultList"));
      $("subNewPublisherStatus").textContent = `已找到 ${publisherCandidates.length} 个发布者，已默认选择第一个`;
    } else {
      $("subNewPublisherResultWrap").hidden = false;
      $("subNewPublisherResultList").innerHTML = `<div class="empty">未找到发布者</div>`;
      $("subNewPublisherStatus").textContent = "未找到发布者，请确认平台和 UID";
    }
  };
  $("subNewTargetPlatform").onchange = refreshTargetKinds;
  $("subNewTargetKind").onchange = async () => {
    policyUpdater();
    await refreshTargetCandidates();
  };
  $("subNewTargetSelectAll").onclick = () => setCreateSubscriptionTargetChecked(true);
  $("subNewTargetClearAll").onclick = () => setCreateSubscriptionTargetChecked(false);
  $("subNewPublisherSearch").onclick = () => searchPublisher().catch(error => {
    $("subNewPublisherResultWrap").hidden = true;
    $("subNewPublisherStatus").textContent = error.message || String(error);
  });
  $("subNewPublisherId").onkeydown = event => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    searchPublisher().catch(error => {
      $("subNewPublisherResultWrap").hidden = true;
      $("subNewPublisherStatus").textContent = error.message || String(error);
    });
  };
  $("subNewPublisherPlatform").onchange = () => {
    publisherCandidates = [];
    $("subNewPublisherResultWrap").hidden = true;
    $("subNewPublisherStatus").textContent = "请输入发布者 ID 后搜索。";
  };
  await hydrateMediaImages($("modalBody"));
  await refreshTargetKinds();
}

function subscriptionModeSwitch(name, options) {
  return `<div class="subscription-mode-switch">${options.map(option => `<label class="subscription-mode-option">
    <input type="radio" name="${attr(name)}" value="${attr(option.value)}"${option.checked ? " checked" : ""}${option.disabled ? " disabled" : ""}>
    <span>${esc(option.text)}</span>
  </label>`).join("")}</div>`;
}

function bindCreateSubscriptionMode(policyUpdater) {
  const refresh = () => {
    const targetMode = selectedSubscriptionMode("subTargetMode");
    const publisherMode = selectedSubscriptionMode("subPublisherMode");
    $("subExistingTargetBlock").hidden = targetMode !== "existing";
    $("subNewTargetBlock").hidden = targetMode !== "new";
    $("subExistingPublisherBlock").hidden = publisherMode !== "existing";
    $("subNewPublisherBlock").hidden = publisherMode !== "new";
    policyUpdater();
  };
  document.querySelectorAll(`input[name="subTargetMode"], input[name="subPublisherMode"]`).forEach(input => input.onchange = refresh);
  refresh();
}

function selectedSubscriptionMode(name) {
  const input = document.querySelector(`input[name="${name}"]:checked`);
  return input ? input.value : "";
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

function currentCreateSubscriptionTargetKind() {
  if (selectedSubscriptionMode("subTargetMode") === "existing") {
    const input = document.querySelector(`input[name="subExistingTarget"]:checked`);
    const target = input ? (state.cache.subscribers || [])[Number(input.dataset.index)] : null;
    return target && target.targetKind || "OTHER";
  }
  const kindSelect = $("subNewTargetKind");
  return kindSelect && kindSelect.value || "OTHER";
}

function setCreateSubscriptionTargetLoading(text) {
  $("subNewTargetCandidateWrap").hidden = false;
  $("subNewTargetCandidateActions").hidden = true;
  $("subNewTargetManualWrap").hidden = true;
  $("subNewTargetCandidateList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
  setCreateSubscriptionTargetStatus("");
}

function setCreateSubscriptionTargetStatus(text) {
  $("subNewTargetStatus").textContent = text ? `· ${text}` : "";
}

function existingTargetChoiceHtml(target, index, checked) {
  const title = target.name || target.externalId;
  const parts = [
    title,
    target.platformId,
    label(target.targetKind),
    target.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice">
    <input type="radio" name="subExistingTarget" value="${attr(target.id)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(target.avatarUri, "target-choice-avatar", target.platformId, "AVATAR")}
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
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
  return `<label class="target-choice">
    <input type="radio" name="subExistingPublisher" value="${attr(publisher.id)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(publisher.avatarUri, "target-choice-avatar", publisher.platformId, "AVATAR")}
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
  </label>`;
}

function subscriptionTargetChoiceHtml(target, index, checked) {
  const title = target.name || target.externalId;
  const parts = [
    title,
    label(target.targetKind),
    target.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice">
    <input type="checkbox" name="subNewTargetCandidate" value="${attr(target.externalId)}" data-index="${attr(index)}"${checked ? " checked" : ""}>
    ${mediaImage(target.avatarUri, "target-choice-avatar", target.platformId, "AVATAR")}
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
  </label>`;
}

function setCreateSubscriptionTargetChecked(checked) {
  document.querySelectorAll(`input[name="subNewTargetCandidate"]`).forEach(input => input.checked = checked);
}

function collectCreateSubscriptionTargets(candidates) {
  if (selectedSubscriptionMode("subTargetMode") === "existing") {
    const input = document.querySelector(`input[name="subExistingTarget"]:checked`);
    if (!input) return [];
    const target = (state.cache.subscribers || [])[Number(input.dataset.index)];
    return target ? [{
      subscriberId: target.id,
      platformId: target.platformId,
      targetKind: target.targetKind,
      externalId: target.externalId,
      scopeId: target.scopeId,
      threadId: target.threadId,
      accountId: target.accountId,
      label: target.name || target.externalId,
    }] : [];
  }
  const platformId = $("subNewTargetPlatform").value.trim();
  const targetKind = $("subNewTargetKind").value;
  const linkParseTriggerMode = subscriptionTargetLinkParseMode();
  if (candidates.length === 0 || $("subNewTargetCandidateWrap").hidden) {
    const manual = $("subNewTargetManual").value.trim();
    return manual ? [{ platformId, targetKind, externalId: manual, label: manual, linkParseTriggerMode }] : [];
  }
  return Array.from(document.querySelectorAll(`input[name="subNewTargetCandidate"]:checked`))
    .map(input => candidates[Number(input.dataset.index)])
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
  $("subNewPublisherResultWrap").hidden = false;
  $("subNewPublisherResultList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
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
  if (selectedSubscriptionMode("subPublisherMode") === "existing") {
    const input = document.querySelector(`input[name="subExistingPublisher"]:checked`);
    if (!input) return null;
    const publisher = (state.cache.publishers || [])[Number(input.dataset.index)];
    return publisher ? {
      publisherId: publisher.id,
      platformId: publisher.platformId,
      externalId: publisher.externalId,
      label: publisher.name || publisher.externalId,
    } : null;
  }
  const selected = !$("subNewPublisherResultWrap").hidden
    ? document.querySelector(`input[name="subNewPublisherCandidate"]:checked`)
    : null;
  const candidate = selected ? candidates[Number(selected.dataset.index)] : null;
  const platformId = $("subNewPublisherPlatform").value.trim();
  const externalId = candidate ? candidate.externalId : $("subNewPublisherId").value.trim();
  if (!platformId || !externalId) return null;
  return {
    platformId,
    externalId,
    label: candidate && candidate.name || externalId,
  };
}

function filterExistingSubscriptionTargets(candidates) {
  const existed = new Set((state.cache.subscribers || []).map(target =>
    subscriptionTargetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId, target.accountId)
  ));
  return (candidates || []).filter(target =>
    !existed.has(subscriptionTargetAddressKey(target.platformId, target.targetKind, target.externalId, target.scopeId, target.threadId, target.accountId))
  );
}

function subscriptionTargetAddressKey(platformId, kind, externalId, scopeId, threadId, accountId) {
  return [platformId, kind, externalId, scopeId || "", threadId || "", accountId || ""].join("\u001F");
}

function policyForm(prefix, policy, targetKind) {
  const selectedEvents = new Set(policyEvents(policy));
  const selectedMentions = new Set(mentionEvents(policy));
  const groupTarget = targetKind === "GROUP";
  return `<div class="rule-matrix">
    ${eventTypes.map(([value, text]) => {
      const enabled = selectedEvents.has(value);
      const mention = selectedMentions.has(value);
      return `<div class="rule-row">
        <strong>${esc(text)}</strong>
        <label class="check"><input type="checkbox" name="${prefix}-event" value="${value}"${enabled ? " checked" : ""}>接收</label>
        <label class="check"><input type="checkbox" name="${prefix}-mention" value="${value}"${mention ? " checked" : ""}${groupTarget && enabled ? "" : " disabled"}>@全体</label>
      </div>`;
    }).join("")}
  </div>`;
}

function wirePolicyForm(prefix, targetKindProvider) {
  const update = () => {
    const groupTarget = targetKindProvider() === "GROUP";
    eventTypes.forEach(([value]) => {
      const eventNode = document.querySelector(`input[name="${prefix}-event"][value="${value}"]`);
      const mentionNode = document.querySelector(`input[name="${prefix}-mention"][value="${value}"]`);
      if (!eventNode || !mentionNode) return;
      mentionNode.disabled = !groupTarget || !eventNode.checked;
      if (mentionNode.disabled) mentionNode.checked = false;
    });
  };
  document.querySelectorAll(`input[name="${prefix}-event"]`).forEach(node => node.onchange = update);
  update();
  return update;
}

function collectPolicy(prefix) {
  const enabledEvents = Array.from(document.querySelectorAll(`input[name="${prefix}-event"]:checked`)).map(item => item.value);
  const mentionAllEvents = Array.from(document.querySelectorAll(`input[name="${prefix}-mention"]:checked`))
    .filter(item => !item.disabled && enabledEvents.includes(item.value))
    .map(item => item.value);
  if (enabledEvents.length === 0) throw new Error("至少选择一种接收事件");
  return { enabledEvents, mentionAllEvents };
}

async function openSubscriptionDetail(id) {
  const subscriptions = await ensureSubscriptions(false);
  const subscription = subscriptions.find(item => Number(item.id) === Number(id));
  if (!subscription) throw new Error("未找到订阅");
  const targetKind = subscription.subscriber && subscription.subscriber.targetKind || "OTHER";
  openModal("订阅详情", `
    <div class="grid">
      <section class="panel full">
        <div class="panel-head">
          <div><h2>订阅规则</h2><p>${esc(publisherKey(subscription.publisher))} -> ${esc(targetKey(subscription.subscriber))}</p></div>
        </div>
        ${policyForm("editPolicy", subscription.policy, targetKind)}
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
  }, { size: "wide" });
  wirePolicyForm("editPolicy", () => targetKind);
}

function renderFilterList(subscription) {
  const rules = subscription.filterRules || [];
  return renderTable(rules, [
    { title: "类型", render: r => `<span class="primary-line">${esc(filterConditionTypeText(r.condition))}</span>` },
    { title: "条件", render: r => cell(filterConditionText(r.condition), "命中即阻止") },
    { title: "创建时间", render: r => `<span class="sub-line">${fmtTime(r.createdAtEpochSeconds)}</span>` },
    { title: "操作", render: r => `<button class="danger" data-action="delete-filter" data-id="${r.id}" data-subscription="${subscription.id}">删除</button>` }
  ]);
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
