const $ = id => document.getElementById(id);

let ctx;
let root;
let api;
let state;
let invalidate;
let handleError;
let hydrateMediaImages;
let fmtTime;
let fmtBytes;
let fmtDuration;
let cell;
let esc;
let attr;
let label;
let notify;
let openModal;
let confirmDanger;
let loadingRow;
let loadSubscriberTargetPlatforms;
let messageTargetChoiceHtml;
let messageTargetSearchHtml;
let messageTargetToolbarHtml;
let createMessageTargetCandidateController;
let bindTargetSourceToggles;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;

let forwardTargetPlatforms = [];
let forwardTargetController;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  invalidate = ctx.invalidate;
  handleError = ctx.handleError;
  hydrateMediaImages = ctx.hydrateMediaImages;
  ({
    esc,
    attr,
    fmtTime,
    fmtBytes,
    fmtDuration,
    cell,
    label,
    notify,
    openModal,
    confirmDanger,
    loadingRow,
    loadSubscriberTargetPlatforms,
    messageTargetChoiceHtml,
    messageTargetSearchHtml,
    messageTargetToolbarHtml,
    createMessageTargetCandidateController,
    bindTargetSourceToggles,
  } = ctx.ui);
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadSystem(ctx.force);
}

export function unmount() {
  invalidatePageRequests("system");
}

export async function handleAction(nextCtx, { action }) {
  bindContext(nextCtx);
  if (action === "open-message-forward") {
    await openForwardModal();
    return true;
  }
  if (action === "stop-application") {
    await stopApplication();
    return true;
  }
  return false;
}

async function loadSystem(force) {
  const request = beginPageRequest("system");
  const [status, platforms] = await Promise.all([
    loadSystemStatus(force),
    loadForwardTargetPlatforms(force),
  ]);
  if (!isCurrentPageRequest(request)) return;
  forwardTargetPlatforms = platforms;

  const memoryPercent = Math.max(0, Math.min(100, Math.round((Number(status.usedMemoryBytes || 0) / Math.max(Number(status.maxMemoryBytes || 1), 1)) * 100)));
  const memoryMax = fmtBytes(status.maxMemoryBytes);
  const memoryBarClass = memoryPercent >= 90 ? "danger" : memoryPercent >= 80 ? "warning" : "normal";
  pageRoot().innerHTML = `
    <section class="page system-page">
      <div class="grid system-grid">
        <section class="panel half system-overview">
          <div class="panel-head">
            <h2>运行概览</h2>
          </div>
          <div class="stats system-stats">
            ${stat("运行时间", fmtDuration(status.uptimeMs), fmtTime(status.startedAtEpochMillis, true))}
            ${stat("堆内存", fmtBytes(status.usedMemoryBytes), `${memoryPercent}% / ${memoryMax}`)}
            ${stat("总内存占用", fmtBytes(status.totalMemoryBytes), "可用 " + fmtBytes(status.freeMemoryBytes))}
            ${stat("后台", status.webAdminPort, status.webAdminHost)}
          </div>
          <div class="system-meter">
            <div class="system-meter-head">
              <span>堆内存占用</span>
              <strong>${memoryPercent}%</strong>
            </div>
            <div class="system-meter-track system-meter-${memoryBarClass}" aria-hidden="true">
              <span style="width:${memoryPercent}%"></span>
            </div>
            <div class="system-meter-foot">
              <span>已用 ${esc(fmtBytes(status.usedMemoryBytes))}</span>
              <span>上限 ${esc(memoryMax)}</span>
            </div>
          </div>
        </section>

        <section class="panel half system-path-panel">
          <div class="panel-head">
            <h2>环境与路径</h2>
          </div>
          ${pathList([
            {
              key: "database",
              name: "数据库路径",
              value: status.databasePath || "-",
              hint: status.databasePath ? "当前进程使用的持久化存储" : "未显示数据库路径",
            },
            {
              key: "config",
              name: "主配置路径",
              value: status.mainConfigPath,
              hint: "主项目配置文件",
            },
            {
              key: "java",
              name: "Java / OS",
              value: `${status.javaVersion} / ${status.osName}`,
              hint: "运行环境",
            },
            {
              key: "web",
              name: "后台地址",
              value: `${status.webAdminHost}:${status.webAdminPort}`,
              hint: status.webAdminEnabled ? "后台服务正在监听" : "后台服务未启用",
            },
          ])}
        </section>

        <section class="panel full system-maintenance-panel">
          <div class="panel-head">
            <div>
              <h2>维护操作</h2>
              <p>系统级管理操作，请谨慎执行</p>
            </div>
          </div>
          ${maintenanceOperationsHtml()}
        </section>
      </div>
    </section>`;
}

async function loadSystemStatus(force) {
  if (force || !state.cache.system) state.cache.system = await api("/system/status");
  return state.cache.system;
}

async function loadForwardTargetPlatforms(force) {
  return loadSubscriberTargetPlatforms(force);
}

function maintenanceOperationsHtml() {
  return `<div class="system-operation-grid">
    <button type="button" class="system-operation-button" data-action="open-message-forward">
      <span class="system-operation-icon">💬</span>
      <span class="system-operation-title">消息转发</span>
      <span class="system-operation-desc">批量发送管理员消息到指定目标</span>
    </button>
    <button type="button" class="system-operation-button system-operation-danger" data-action="stop-application">
      <span class="system-operation-icon">⏹️</span>
      <span class="system-operation-title">停止项目</span>
      <span class="system-operation-desc">停止主项目进程并关闭后台</span>
    </button>
  </div>`;
}

async function openForwardModal() {
  forwardTargetPlatforms = await loadForwardTargetPlatforms(false);
  forwardTargetController = null;
  openModal("消息转发", forwardFormHtml(forwardTargetPlatforms), async () => {
    await submitForwardMessage();
  }, {
    size: "wide",
    confirmText: "发送消息",
    cancelText: "关闭",
  });
  bindForwardForm();
  await refreshForwardTargetKinds();
}

function forwardFormHtml(platforms) {
  const options = forwardPlatformOptions(platforms);
  return `<form id="forwardForm" class="system-forward-form">
    <div class="form-grid">
      <div class="field">
        <label>目标平台</label>
        <select id="forwardPlatform">${options.map(platform => `<option value="${attr(platform.platformId)}">${esc(forwardPlatformOptionText(platform))}</option>`).join("")}</select>
      </div>
      <div class="field" id="forwardPlatformManualWrap" hidden>
        <label>平台 ID<span class="required-mark">*</span></label>
        <input id="forwardPlatformManual" placeholder="例如 qq、discord">
      </div>
      <div class="field">
        <label>目标类型</label>
        <select id="forwardTargetKind"></select>
      </div>
      <div class="field full" id="forwardTargetCandidateWrap">
        <div class="field-head">
          <div class="field-title-line">
            <label>可用目标</label>
            <span id="forwardTargetStatus" class="field-inline-status"></span>
          </div>
          ${messageTargetSearchHtml("forwardTargetSearch", "", "搜索目标名称或 ID", { compact: true })}
          <div class="row-actions" id="forwardTargetCandidateActions">
            <button type="button" class="secondary compact choice-tool-button choice-refresh-button" id="forwardTargetRefresh" hidden>获取</button>
            <button type="button" class="secondary compact choice-tool-button" id="forwardTargetSelectAll" hidden>全选</button>
            <button type="button" class="secondary compact choice-tool-button choice-clear-button" id="forwardTargetClearAll" hidden>清空</button>
            <button type="button" class="secondary compact choice-tool-button" id="forwardManualToggle">手动填写</button>
          </div>
        </div>
        <div id="forwardTargetCandidateList" class="target-choice-list system-forward-target-list"></div>
      </div>
      <div class="field full">
        <label>消息内容<span class="required-mark">*</span></label>
        <textarea id="forwardText" class="system-forward-message" placeholder="输入要批量发送的文本"></textarea>
      </div>
    </div>
    <div class="system-forward-actions">
      <span class="inline-note">目标未选择优先来源时，会使用主配置中的全局路由策略。</span>
    </div>
    <div id="forwardResult" class="system-forward-result" hidden></div>
  </form>`;
}

function bindForwardForm() {
  forwardTargetController = createMessageTargetCandidateController({
    wrapId: "forwardTargetCandidateWrap",
    actionsId: "forwardTargetCandidateActions",
    listId: "forwardTargetCandidateList",
    refreshId: "forwardTargetRefresh",
    selectAllId: "forwardTargetSelectAll",
    clearAllId: "forwardTargetClearAll",
    manualToggleId: "forwardManualToggle",
    searchId: "forwardTargetSearch",
    fetchPromptId: "forwardTargetFetchPrompt",
    manualInputId: "forwardManualTargets",
    inputName: "forwardTargetCandidate",
    prefix: "forwardTarget",
    useSelectionState: true,
    platformId: currentForwardPlatformId,
    targetKind: () => $("forwardTargetKind").value,
    canFetch: () => $("forwardPlatform").value !== "__manual__" && !!currentForwardPlatformId(),
    fetchBlockedStatus: () => currentForwardPlatformId() ? "手动填写目标 ID" : "请填写目标平台 ID",
    emptyText: "暂无可用目标",
    manualOptions: {
      label: "目标 ID",
      placeholder: "多个目标 ID 可用逗号、空格或换行分隔",
      textarea: true,
      note: "手动目标不指定优先账号，发送时使用全局路由。",
    },
    setStatus: setForwardTargetStatus,
    renderCandidate: (target, index, checked) => messageTargetChoiceHtml(target, index, {
      inputName: "forwardTargetCandidate",
      prefix: "forwardTarget",
      checked,
    }),
    handleError,
  });
  $("forwardPlatform").onchange = () => refreshForwardTargetKinds().catch(handleError);
  $("forwardPlatformManual").oninput = () => {
    if (forwardTargetController.mode() !== "manual") {
      forwardTargetController.showManual("手动填写目标 ID");
    } else {
      setForwardTargetStatus("手动填写目标 ID");
    }
  };
  $("forwardTargetKind").onchange = () => forwardTargetController.showCachedOrPrompt().catch(handleError);
  $("forwardTargetRefresh").onclick = () => forwardTargetController.refresh(forwardTargetController.mode() !== "prompt").catch(handleError);
  $("forwardManualToggle").onclick = () => {
    if (forwardTargetController.mode() === "manual") {
      if (forwardTargetController.candidates().length) forwardTargetController.renderCandidates().catch(handleError);
      else forwardTargetController.showCachedOrPrompt().catch(handleError);
    }
    else forwardTargetController.showManual();
  };
  $("forwardTargetSearch").oninput = () => forwardTargetController.search();
  $("forwardTargetSelectAll").onclick = () => forwardTargetController.setChecked(true);
  $("forwardTargetClearAll").onclick = () => forwardTargetController.setChecked(false);
  $("forwardForm").onsubmit = event => {
    event.preventDefault();
    submitForwardMessage().catch(handleError);
  };
}

async function refreshForwardTargetKinds() {
  const rawPlatformId = $("forwardPlatform").value;
  $("forwardPlatformManualWrap").hidden = rawPlatformId !== "__manual__";
  const platform = forwardPlatformOptions(forwardTargetPlatforms).find(item => item.platformId === rawPlatformId);
  const kinds = (platform && platform.supportedTypes && platform.supportedTypes.length)
    ? platform.supportedTypes
    : ["GROUP", "USER", "CHANNEL", "OTHER"];
  $("forwardTargetKind").innerHTML = kinds.map(kind => `<option value="${attr(kind)}">${esc(label(kind))}</option>`).join("");
  if (rawPlatformId === "__manual__") forwardTargetController.showManual("手动填写目标 ID");
  else forwardTargetController.showPrompt();
}

function setForwardTargetStatus(text) {
  const node = $("forwardTargetStatus");
  if (node) node.textContent = text ? `· ${text}` : "";
}

async function submitForwardMessage() {
  const text = $("forwardText").value.trim();
  if (!text) throw new Error("请填写消息内容");
  const targets = collectForwardTargets();
  if (targets.length === 0) throw new Error("请选择可用目标，或手动填写目标 ID");

  const result = await api("/message-forwards", {
    method: "POST",
    body: JSON.stringify({ targets, text }),
  });
  invalidate("deliveries", "dashboard");
  renderForwardResult(result);
  notify(`已创建转发消息：${result.targetCount} 个目标`, false);
}

function collectForwardTargets() {
  const selectedTargets = (forwardTargetController ? forwardTargetController.selectedTargets() : [])
    .map(target => normalizeForwardTarget({
        platformId: target.platformId,
        targetKind: target.targetKind,
        externalId: target.externalId,
        scopeId: target.scopeId,
        threadId: target.threadId,
        accountId: target.accountId,
      }))
    .filter(Boolean);

  const platformId = currentForwardPlatformId();
  const targetKind = $("forwardTargetKind").value;
  const manualInput = $("forwardManualTargets");
  const manualTargets = manualInput ? splitManualTargetIds(manualInput.value).map(externalId => normalizeForwardTarget({
      platformId,
      targetKind,
      externalId,
    })) : [];
  return dedupeForwardTargets(selectedTargets.concat(manualTargets));
}

function normalizeForwardTarget(target) {
  const normalized = {
    platformId: String(target.platformId || "").trim(),
    targetKind: String(target.targetKind || "").trim(),
    externalId: String(target.externalId || "").trim(),
  };
  if (!normalized.platformId || !normalized.targetKind || !normalized.externalId) return null;
  const scopeId = blankToNull(target.scopeId);
  const threadId = blankToNull(target.threadId);
  const accountId = blankToNull(target.accountId);
  if (scopeId) normalized.scopeId = scopeId;
  if (threadId) normalized.threadId = threadId;
  if (accountId) normalized.accountId = accountId;
  return normalized;
}

function dedupeForwardTargets(targets) {
  const result = [];
  const seen = new Set();
  targets.filter(Boolean).forEach(target => {
    const key = [target.platformId, target.targetKind, target.externalId, target.scopeId || "", target.threadId || ""].join("\u001F");
    if (seen.has(key)) return;
    seen.add(key);
    result.push(target);
  });
  return result;
}

function splitManualTargetIds(value) {
  return String(value || "")
    .split(/[,\n\r\t ，、]+/)
    .map(item => item.trim())
    .filter(Boolean);
}

function blankToNull(value) {
  const text = String(value || "").trim();
  return text ? text : null;
}

function renderForwardResult(result) {
  const node = $("forwardResult");
  const deliveries = Array.isArray(result.deliveries) ? result.deliveries : [];
  node.hidden = false;
  node.innerHTML = `<div class="batch-result success">
    <strong>消息已入队</strong>
    <div>消息 ID：${esc(result.messageId)}</div>
    <div>目标 ${esc(result.targetCount)} 个，新建投递 ${esc(result.newDeliveryCount)} 条，已存在 ${esc(result.existingDeliveryCount)} 条。</div>
    ${deliveries.length ? `<ul>${deliveries.slice(0, 8).map(item => `<li>${esc(item.platformId)} / ${esc(label(item.targetKind))} / ${esc(item.targetId)} · ${esc(label(item.status))}${item.accountId ? ` · 优先 ${esc(item.accountId)}` : ""}</li>`).join("")}${deliveries.length > 8 ? `<li>还有 ${esc(deliveries.length - 8)} 条投递未显示</li>` : ""}</ul>` : ""}
  </div>`;
}

function currentForwardPlatformId() {
  return $("forwardPlatform").value === "__manual__"
    ? $("forwardPlatformManual").value.trim()
    : $("forwardPlatform").value;
}

function forwardPlatformOptions(platforms) {
  const rows = Array.isArray(platforms) ? platforms : [];
  return rows.concat([{ platformId: "__manual__", pluginName: "手动输入", supportedTypes: ["GROUP", "USER", "CHANNEL", "OTHER"] }]);
}

function forwardPlatformOptionText(platform) {
  if (platform.platformId === "__manual__") return "手动输入";
  const transports = Number(platform.transportCount || 0);
  const suffix = transports > 1 ? ` · ${transports} 个发送来源` : "";
  return `${platform.platformId}${suffix}`;
}

async function stopApplication() {
  if (!(await confirmDanger("停止主项目", "确定停止主项目吗？当前进程会开始退出，后台也会断开连接。", { confirmText: "停止主项目" }))) return;
  try {
    const result = await api("/system/stop", { method: "POST" });
    notify(result.message || "停止指令已发送", false);
  } catch (error) {
    handleError(error);
  }
}

function stat(title, value, sub) {
  return `<div class="stat"><b>${esc(value)}</b><span>${esc(title)} · ${esc(sub || "")}</span></div>`;
}

function pathList(rows) {
  return `<div class="system-path-list">${
    rows.map(row => `<div class="system-path-item">
      <div>${cell(row.name, row.hint)}</div>
      <span class="system-path-value">${esc(row.value)}</span>
    </div>`).join("")
  }</div>`;
}
