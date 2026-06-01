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
            <button data-action="create-subscription">新建订阅</button>
            <button data-action="refresh-current" class="secondary">刷新</button>
          </div>
        </div>
        ${renderTable(rows, [
          { title: "发布者", render: s => identity(s.publisher && s.publisher.name, publisherKey(s.publisher), s.publisher && s.publisher.avatarUri, s.publisher && s.publisher.platformId, "AVATAR") },
          { title: "消息目标", render: s => cell(s.subscriber && s.subscriber.name, targetKey(s.subscriber)) },
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
  if (!state.cache.platformLogins) state.cache.platformLogins = await api("/platform-logins");
  if (!state.cache.publishers) state.cache.publishers = await api("/publishers");
  const targetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  const fallbackTargets = targetPlatforms.length ? targetPlatforms : [{ platformId: "onebot", pluginName: "手动", supportedTypes: ["GROUP", "USER"] }];
  const publisherPlatforms = Array.from(new Set(
    (state.cache.platformLogins || []).map(item => item.platformId)
      .concat((state.cache.publishers || []).map(item => item.platformId))
  )).filter(Boolean);
  let targetCandidates = [];

  openModal("新建订阅", `
    <div class="subscription-create">
      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>消息目标</h2>
            <p>选择要推送到的即时通讯平台目标，可一次选择多个。</p>
          </div>
        </div>
        <div class="form-grid">
          <div class="field"><label>目标平台</label><select id="subTargetPlatform">${fallbackTargets.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")}</select></div>
          <div class="field"><label>目标类型</label><select id="subTargetKind"></select></div>
          <div class="field full" id="subTargetCandidateWrap">
            <div class="field-head">
              <label>可用目标</label>
              <div class="row-actions">
                <button type="button" class="secondary compact" id="subTargetSelectAll">全选</button>
                <button type="button" class="secondary compact" id="subTargetClearAll">清空</button>
              </div>
            </div>
            <div id="subTargetCandidateList" class="target-choice-list"></div>
          </div>
          <div class="field full" id="subTargetManualWrap"><label>目标 ID</label><input id="subTargetManual" placeholder="插件无法枚举目标时手动填写"></div>
          <div class="field full"><span id="subTargetStatus" class="inline-note"></span></div>
        </div>
      </section>

      <section class="panel subscription-card">
        <div class="panel-head">
          <div>
            <h2>发布者</h2>
            <p>只需要填写平台和发布者 ID，名称会由后端自动获取。</p>
          </div>
        </div>
        <div class="form-grid">
          <div class="field"><label>发布者平台</label><input id="subPublisherPlatform" list="publisherPlatforms" value="${attr(publisherPlatforms[0] || "bilibili")}"><datalist id="publisherPlatforms">${publisherPlatforms.map(p => `<option value="${attr(p)}"></option>`).join("")}</datalist></div>
          <div class="field"><label>发布者 ID<span style="color: red"> *</span></label><input id="subPublisherId"></div>
          <div class="field full"><label class="check"><input id="subAutoFollow" type="checkbox" checked>自动关注发布者</label></div>
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
    const basePayload = {
      subscriberPlatform: $("subTargetPlatform").value.trim(),
      targetKind: $("subTargetKind").value,
      publisherPlatform: $("subPublisherPlatform").value.trim(),
      publisherExternalId: $("subPublisherId").value.trim(),
      autoFollow: $("subAutoFollow").checked,
      policy: collectPolicy("subPolicy")
    };
    if (!basePayload.subscriberPlatform || !basePayload.publisherPlatform || !basePayload.publisherExternalId) throw new Error("请填写必要字段");
    if (selectedTargets.length === 0) throw new Error("请选择至少一个消息目标");

    let successCount = 0;
    const failures = [];
    for (const target of selectedTargets) {
      try {
        await api("/subscriptions", {
          method: "POST",
          body: JSON.stringify(Object.assign({}, basePayload, { subscriberTargetId: target.id }))
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
  });

  const policyUpdater = wirePolicyForm("subPolicy", () => $("subTargetKind").value);
  const refreshTargetKinds = async () => {
    const platformId = $("subTargetPlatform").value;
    const platform = fallbackTargets.find(item => item.platformId === platformId);
    const kinds = platform && platform.supportedTypes && platform.supportedTypes.length
      ? platform.supportedTypes
      : ["GROUP", "USER", "CHANNEL", "OTHER"];
    $("subTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
    policyUpdater();
    await refreshTargetCandidates();
  };
  const refreshTargetCandidates = async () => {
    const platform = $("subTargetPlatform").value;
    const kind = $("subTargetKind").value;
    targetCandidates = [];
    setCreateSubscriptionTargetLoading("正在获取可用目标...");
    try {
      targetCandidates = await api(`/subscriber-targets?platformId=${encodeURIComponent(platform)}&type=${encodeURIComponent(kind)}`);
    } catch (error) {
      $("subTargetCandidateWrap").hidden = true;
      $("subTargetManualWrap").hidden = false;
      $("subTargetCandidateList").innerHTML = "";
      $("subTargetStatus").textContent = "目标列表获取失败，请手动填写目标 ID";
      return;
    }
    if (targetCandidates.length) {
      $("subTargetCandidateWrap").hidden = false;
      $("subTargetManualWrap").hidden = true;
      $("subTargetCandidateList").innerHTML = targetCandidates.map((target, index) => targetChoiceHtml(target, index === 0)).join("");
      $("subTargetStatus").textContent = `已获取 ${targetCandidates.length} 个目标，已默认选择第一个`;
    } else {
      $("subTargetCandidateWrap").hidden = true;
      $("subTargetManualWrap").hidden = false;
      $("subTargetCandidateList").innerHTML = "";
      $("subTargetStatus").textContent = "未获取到目标，请手动填写目标 ID";
    }
  };
  $("subTargetPlatform").onchange = refreshTargetKinds;
  $("subTargetKind").onchange = async () => {
    policyUpdater();
    await refreshTargetCandidates();
  };
  $("subTargetSelectAll").onclick = () => setCreateSubscriptionTargetChecked(true);
  $("subTargetClearAll").onclick = () => setCreateSubscriptionTargetChecked(false);
  await refreshTargetKinds();
}

function setCreateSubscriptionTargetLoading(text) {
  $("subTargetCandidateWrap").hidden = false;
  $("subTargetManualWrap").hidden = true;
  $("subTargetCandidateList").innerHTML = `<div class="target-loading"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
  $("subTargetStatus").textContent = text;
}

function targetChoiceHtml(target, checked) {
  const title = target.name || target.externalId;
  const parts = [
    title,
    target.platformId,
    label(target.targetKind),
    target.externalId,
  ].filter(Boolean).join(" · ");
  return `<label class="target-choice">
    <input type="checkbox" name="subTargetCandidate" value="${attr(target.externalId)}" data-label="${attr(title)}"${checked ? " checked" : ""}>
    <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
  </label>`;
}

function setCreateSubscriptionTargetChecked(checked) {
  document.querySelectorAll(`input[name="subTargetCandidate"]`).forEach(input => input.checked = checked);
}

function collectCreateSubscriptionTargets(candidates) {
  if (candidates.length === 0) {
    const manual = $("subTargetManual").value.trim();
    return manual ? [{ id: manual, label: manual }] : [];
  }
  return Array.from(document.querySelectorAll(`input[name="subTargetCandidate"]:checked`))
    .map(input => ({ id: input.value, label: input.dataset.label || input.value }));
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
