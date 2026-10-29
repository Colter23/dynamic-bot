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
let bootstrapCountdownTimer = null;
let bootstrapDeadlineMs = 0;

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

export async function unmount() {
  stopBootstrapCountdown();
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
  if (action === "rotate-bootstrap") {
    await rotateCommandBootstrap();
    return true;
  }
  return false;
}

async function loadSystem(force) {
  const request = beginPageRequest("system");
  const [status, platforms, bootstrap] = await Promise.all([
    loadSystemStatus(force),
    loadForwardTargetPlatforms(force),
    loadCommandBootstrap(),
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

        ${bootstrapPanelHtml(bootstrap)}

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

  startBootstrapCountdown(bootstrap);
}

async function loadCommandBootstrap() {
  try {
    return await api("/command-bootstrap");
  } catch (_) {
    return null;
  }
}

async function rotateCommandBootstrap() {
  try {
    const next = await api("/command-bootstrap/rotate", { method: "POST" });
    notify(next && next.code ? "验证码已重新生成" : "命令系统已初始化", false);
    await loadSystem(true);
  } catch (error) {
    handleError(error);
  }
}

function bootstrapPanelHtml(info) {
  if (!info) return "";
  if (info.status === "CLAIMED") {
    const admins = (info.admins || [])
      .map(item => `<li><code>${esc(item.platformId)}</code> · <code>${esc(item.senderId)}</code></li>`)
      .join("");
    return `<section class="panel full system-bootstrap-panel">
      <div class="panel-head">
        <div>
          <h2>命令系统</h2>
          <p>已初始化，验证码不再可用</p>
        </div>
      </div>
      <p class="system-bootstrap-hint">当前系统管理员：</p>
      <ul class="system-bootstrap-admins">${admins || "<li>-</li>"}</ul>
      <p class="system-bootstrap-hint">如需增删管理员，请到「配置 · 命令 · 权限规则」中调整。</p>
    </section>`;
  }

  const expired = info.status !== "PENDING";
  const attempts = Number(info.recentAttempts || 0);
  const codeHtml = expired
    ? `<div class="system-bootstrap-code expired">验证码已过期</div>`
    : `<div class="system-bootstrap-code">${esc(info.code || "")}</div>`;
  const hintHtml = expired
    ? `<p class="system-bootstrap-hint">点击下方按钮生成新的验证码，或重启主程序。</p>`
    : `<p class="system-bootstrap-hint">用 QQ <strong>私聊</strong>机器人发送上面的验证码即可完成认领，剩余 <strong id="bootstrapCountdown">--:--</strong>。</p>`;
  const attemptsHtml = attempts > 0
    ? `<p class="system-bootstrap-hint">最近 10 分钟内有 ${attempts} 次验证码尝试。</p>`
    : "";

  return `<section class="panel full system-bootstrap-panel">
    <div class="panel-head">
      <div>
        <h2>命令系统</h2>
        <p>${expired ? "验证码已过期，尚未初始化" : "还没有系统管理员，请先完成初始化"}</p>
      </div>
    </div>
    ${codeHtml}
    ${hintHtml}
    <div class="system-bootstrap-actions">
      <button type="button" class="secondary compact" data-action="rotate-bootstrap">${expired ? "生成验证码" : "重新生成"}</button>
    </div>
    ${attemptsHtml}
    <p class="system-bootstrap-hint">验证码仅 10 分钟有效；认领后立即失效，如需添加更多管理员请使用命令授权或权限规则。</p>
  </section>`;
}

function startBootstrapCountdown(info) {
  stopBootstrapCountdown();
  if (!info || info.status !== "PENDING" || !info.code) return;
  bootstrapDeadlineMs = Date.now() + Number(info.remainingSeconds || 0) * 1000;
  const tick = () => {
    const el = $("bootstrapCountdown");
    if (!el) {
      stopBootstrapCountdown();
      return;
    }
    const remaining = Math.max(0, Math.round((bootstrapDeadlineMs - Date.now()) / 1000));
    el.textContent = formatCountdown(remaining);
    if (remaining <= 0) {
      stopBootstrapCountdown();
      el.textContent = "已过期";
    }
  };
  tick();
  bootstrapCountdownTimer = setInterval(tick, 1000);
}

function stopBootstrapCountdown() {
  if (bootstrapCountdownTimer) {
    clearInterval(bootstrapCountdownTimer);
    bootstrapCountdownTimer = null;
  }
}

function formatCountdown(seconds) {
  const total = Math.max(0, Math.round(Number(seconds || 0)));
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  return `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
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
