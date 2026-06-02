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
  await loadPlatformLogins(ctx.force);
}

export async function handleAction(nextCtx, { action, button }) {
  bindContext(nextCtx);
  if (action === "cookie-login") {
    await openCookieLogin(button.dataset.platform);
    return true;
  }
  if (action === "qr-login") {
    await openQrLogin(button.dataset.platform);
    return true;
  }
  if (action === "refresh-login") {
    const platform = button.dataset.platform;
    button.disabled = true;
    try {
      invalidate("platformLogins", "dashboard");
      await loadPlatformLogins(true);
      notify(`${platform || "平台"} 登录状态已刷新`, false);
    } finally {
      if (button.isConnected) button.disabled = false;
    }
    return true;
  }
  if (action === "export-cookie") {
    await openCookieExport(button.dataset.platform);
    return true;
  }
  return false;
}

async function loadPlatformLogins(force) {
  if (force || !state.cache.platformLogins) state.cache.platformLogins = await api("/platform-logins");
  const rows = state.cache.platformLogins;
  pageRoot().innerHTML = `
    <section class="page platform-login-page">
      <div class="platform-login-grid">
        ${rows.length ? rows.map(renderPlatformCard).join("") : `<div class="empty">暂无可登录平台</div>`}
      </div>
    </section>`;
  await hydrateMediaImages(pageRoot());
}

function renderPlatformCard(item) {
  const methods = item.supportedLoginMethods || [];
  const accountName = item.account && item.account.name || "未显示账号";
  const accountId = item.account && item.account.userId || "";
  const accountAvatar = item.account && item.account.avatarUri;
  const exportDisabled = methods.includes("COOKIE") ? "" : " disabled title=\"当前平台不支持 Cookie 导出\"";
  return `<article class="platform-login-card">
    <div class="platform-login-head">
      <div class="platform-login-title">
        ${platformTag(item.platformId, item.platformId)}
        <span>${esc(item.pluginName || item.pluginId || "未知插件")}${item.pluginVersion ? ` · ${esc(item.pluginVersion)}` : ""}</span>
      </div>
      ${pill(item.status)}
    </div>
    <div class="platform-login-account">
      ${mediaImage(accountAvatar, "platform-login-avatar avatar", item.platformId, "AVATAR")}
      <div>
        <span>当前账号</span>
        <strong>${esc(accountName)}</strong>
        <small>${esc([accountId, item.message].filter(Boolean).join(" · ") || "暂无账号信息")}</small>
      </div>
    </div>
    <div class="platform-login-methods">${tags(methods.map(label))}</div>
    <div class="platform-login-actions">
      <button class="login-action-qr" data-action="qr-login" data-platform="${attr(item.platformId)}"${methods.includes("QR_CODE") ? "" : " disabled"}>扫码登录</button>
      <button class="login-action-cookie" data-action="cookie-login" data-platform="${attr(item.platformId)}"${methods.includes("COOKIE") ? "" : " disabled"}>Cookie登录</button>
      <button class="login-action-export" data-action="export-cookie" data-platform="${attr(item.platformId)}"${exportDisabled}>导出Cookie</button>
      <button class="login-action-refresh" data-action="refresh-login" data-platform="${attr(item.platformId)}">刷新</button>
    </div>
  </article>`;
}

async function openCookieLogin(platform) {
  openModal(`${platform} Cookie 登录`, `<div class="field"><label>Cookie</label><textarea id="cookieValue" placeholder="SESSDATA=..."></textarea></div>`, async () => {
    const result = await api(`/platforms/${encodeURIComponent(platform)}/login/cookie`, { method: "POST", body: JSON.stringify({ cookie: $("cookieValue").value }) });
    closeModal();
    invalidate("platformLogins", "dashboard");
    await loadPlatformLogins(true);
    notify(result.message || "登录请求已提交", result.status !== "SUCCESS");
  }, { size: "small" });
}

async function openCookieExport(platform) {
  const result = await api(`/platforms/${encodeURIComponent(platform)}/login/cookie/export`);
  openModal(`${platform} Cookie`, `
    <div class="field">
      <label>Cookie</label>
      <textarea readonly>${esc(result.cookie || "")}</textarea>
      <span class="inline-note">${esc(result.message || "Cookie 已导出")}</span>
    </div>
  `, null, { size: "small", cancelText: "关闭" });
}

async function openQrLogin(platform) {
  const start = await api(`/platforms/${encodeURIComponent(platform)}/login/qr`, { method: "POST" });
  const blob = await apiBlob(start.imageUrl.replace("/api", ""));
  const imageUrl = URL.createObjectURL(blob);
  let timer = null;
  let qrDone = false;
  openModal(`${platform} 二维码登录`, `
    <div class="form-grid">
      <div class="field full"><img src="${attr(imageUrl)}" alt="二维码" style="width:220px;height:220px;border:1px solid var(--line);border-radius:8px;background:#fff"></div>
      <div class="field full"><div id="qrStatus" class="message">${esc(start.message || "等待扫码")}</div></div>
    </div>
  `, null, {
    confirmHidden: true,
    cancelText: "关闭",
    size: "small",
    cleanup: () => {
      URL.revokeObjectURL(imageUrl);
      if (timer) clearInterval(timer);
      if (!qrDone) api(`/login/qr/${encodeURIComponent(start.loginId)}`, { method: "DELETE" }).catch(() => {});
    }
  });
  timer = setInterval(async () => {
    const status = await api(`/login/qr/${encodeURIComponent(start.loginId)}`);
    $("qrStatus").textContent = status.message;
    if (!["PENDING"].includes(status.status)) {
      qrDone = true;
      clearInterval(timer);
      timer = null;
      invalidate("platformLogins", "dashboard");
      await loadPlatformLogins(true).catch(() => {});
    }
  }, 2500);
}
