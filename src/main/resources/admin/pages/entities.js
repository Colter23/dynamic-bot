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
  await loadEntities(ctx.force);
}

export async function handleAction(nextCtx, { action, id }) {
  bindContext(nextCtx);
  if (action === "edit-publisher") {
    await openEditPublisher(id);
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

async function loadEntities(force) {
  releaseMediaObjectUrls();
  if (force || !state.cache.publishers) state.cache.publishers = await api("/publishers");
  if (force || !state.cache.subscribers) state.cache.subscribers = await api("/subscribers");
  const publishers = state.cache.publishers;
  const subscribers = state.cache.subscribers;
  pageRoot().innerHTML = `
    <section class="page">
      <section class="panel full">
        <div class="panel-head"><h2>发布者</h2><button class="secondary" data-action="refresh-current">刷新</button></div>
        ${renderTable(publishers, [
          { title: "发布者", render: p => identity(p.name, publisherKey(p), p.avatarUri, p.platformId, "AVATAR") },
          { title: "主题色", render: p => themeSwatch(p.drawTheme) },
          { title: "头图", render: p => p.bannerUri ? mediaImage(p.bannerUri, "header-image", p.platformId, "COVER") : `<span class="sub-line">-</span>` },
          { title: "订阅", render: p => `<span class="primary-line">${p.subscriptionCount || 0}</span>` },
          { title: "状态", render: p => entityStatePill(p.state) },
          { title: "创建时间", render: p => `<span class="sub-line">${fmtTime(p.createTime)}</span>` },
          { title: "操作", render: p => `<div class="row-actions"><button data-action="edit-publisher" data-id="${p.id}">编辑</button><button class="danger" data-action="delete-publisher" data-id="${p.id}">删除</button></div>` }
        ])}
      </section>
      <section class="panel full">
        <div class="panel-head"><h2>消息目标</h2><button data-action="create-subscriber">添加</button></div>
        ${renderTable(subscribers, [
          { title: "目标", render: s => cell(s.name, targetKey(s)) },
          { title: "订阅", render: s => `<span class="primary-line">${s.subscriptionCount || 0}</span>` },
          { title: "链接解析", render: s => linkParseCell(s) },
          { title: "状态", render: s => entityStatePill(s.state) },
          { title: "创建时间", render: s => `<span class="sub-line">${fmtTime(s.createTime)}</span>` },
          { title: "操作", render: s => `<div class="row-actions"><button data-action="edit-subscriber" data-id="${s.id}">编辑</button><button class="danger" data-action="delete-subscriber" data-id="${s.id}">删除</button></div>` }
        ])}
      </section>
    </section>`;
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

async function openCreateSubscriber() {
  const targetPlatforms = await api("/subscriber-target-platforms").catch(() => []);
  const fallbackTargets = targetPlatforms.length ? targetPlatforms : [{ platformId: "onebot", pluginName: "手动", supportedTypes: ["GROUP", "USER"] }];
  let targetCandidates = [];

  openModal("添加消息目标", `
    <div class="form-grid">
      <div class="field"><label>目标平台</label><select id="newTargetPlatform">${fallbackTargets.map(p => `<option value="${attr(p.platformId)}">${esc(p.platformId)} · ${esc(p.pluginName || p.pluginId || "")}</option>`).join("")}</select></div>
      <div class="field"><label>目标类型</label><select id="newTargetKind"></select></div>
      <div class="field full" id="newTargetCandidateWrap"><label>可用目标</label><select id="newTargetCandidate"></select></div>
      <div class="field full" id="newTargetManualWrap"><label>目标 ID</label><input id="newTargetManual"></div>
      <div class="field full"><label>链接解析</label><select id="newTargetLinkParse">${linkParseModeOptions("INHERIT")}</select><span class="inline-note">使用全局回退时，会跟随主配置里的回退触发方式。</span></div>
      <div class="field full"><span id="newTargetStatus" class="inline-note"></span></div>
    </div>
  `, async () => {
    const selectedIndex = targetCandidates.length && !$("newTargetCandidateWrap").hidden ? Number($("newTargetCandidate").value) : -1;
    const selectedTarget = Number.isInteger(selectedIndex) && selectedIndex >= 0 ? targetCandidates[selectedIndex] : null;
    const payload = {
      platformId: $("newTargetPlatform").value.trim(),
      targetKind: $("newTargetKind").value,
      externalId: selectedTarget ? selectedTarget.externalId : $("newTargetManual").value.trim()
    };
    if (selectedTarget) {
      payload.scopeId = selectedTarget.scopeId || null;
      payload.threadId = selectedTarget.threadId || null;
      payload.accountId = selectedTarget.accountId || null;
      payload.name = selectedTarget.name || null;
    }
    const mode = $("newTargetLinkParse").value;
    if (mode !== "INHERIT") payload.linkParseTriggerMode = mode;
    if (!payload.platformId || !payload.externalId) throw new Error("请填写必要字段");
    await api("/subscribers", { method: "POST", body: JSON.stringify(payload) });
    closeModal();
    invalidate("dashboard", "subscribers", "subscriptions");
    await loadEntities(true);
    notify("消息目标已添加", false);
  }, { size: "small", confirmText: "添加" });

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
    $("newTargetStatus").textContent = "正在获取目标...";
    targetCandidates = [];
    try {
      targetCandidates = await api(`/subscriber-targets?platformId=${encodeURIComponent(platform)}&type=${encodeURIComponent(kind)}`);
    } catch (error) {
      $("newTargetStatus").textContent = "目标列表获取失败，请手动填写目标 ID";
    }
    if (targetCandidates.length) {
      $("newTargetCandidateWrap").hidden = false;
      $("newTargetManualWrap").hidden = true;
      $("newTargetCandidate").innerHTML = targetCandidates.map((target, index) =>
        `<option value="${index}">${esc(target.name || target.externalId)} · ${esc(target.externalId)}</option>`
      ).join("");
      $("newTargetStatus").textContent = `已获取 ${targetCandidates.length} 个目标`;
    } else {
      $("newTargetCandidateWrap").hidden = true;
      $("newTargetManualWrap").hidden = false;
      $("newTargetStatus").textContent = $("newTargetStatus").textContent || "未获取到目标，请手动填写目标 ID";
    }
  };
  $("newTargetPlatform").onchange = refreshTargetKinds;
  $("newTargetKind").onchange = refreshTargetCandidates;
  await refreshTargetKinds();
}

async function openEditPublisher(id) {
  if (!state.cache.publishers) state.cache.publishers = await api("/publishers");
  const item = state.cache.publishers.find(row => Number(row.id) === Number(id));
  openModal("编辑发布者", `
    <div class="form-grid">
      <div class="field"><label>状态</label><select id="entityState"><option${item.state === "ACTIVE" ? " selected" : ""}>ACTIVE</option><option${item.state === "DISABLED" ? " selected" : ""}>DISABLED</option></select></div>
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
  });
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
    <div class="form-grid">
      <div class="field"><label>状态</label><select id="entityState"><option${item.state === "ACTIVE" ? " selected" : ""}>ACTIVE</option><option${item.state === "DISABLED" ? " selected" : ""}>DISABLED</option></select></div>
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
  });
}