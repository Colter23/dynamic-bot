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
    invalidate("platformLogins", "dashboard");
    await loadPlatformLogins(true);
    return true;
  }
  return false;
}

async function loadPlatformLogins(force) {
  if (force || !state.cache.platformLogins) state.cache.platformLogins = await api("/platform-logins");
  const rows = state.cache.platformLogins;
  pageRoot().innerHTML = `
    <section class="page">
      <div class="cards">
        ${rows.length ? rows.map(renderPlatformCard).join("") : `<div class="empty">暂无可登录平台</div>`}
      </div>
    </section>`;
}

function renderPlatformCard(item) {
  const methods = item.supportedLoginMethods || [];
  return `<article class="card">
    <div class="card-head">
      <div>${cell(item.platformId, `${item.pluginName} · ${item.pluginVersion}`)}</div>
      ${pill(item.status)}
    </div>
    <div>${tags(methods.map(label))}</div>
    <div>${cell(item.account && item.account.name || "未显示账号", item.message || "")}</div>
    <div class="row-actions">
      <button data-action="cookie-login" data-platform="${attr(item.platformId)}"${methods.includes("COOKIE") ? "" : " disabled"}>Cookie</button>
      <button class="secondary" data-action="qr-login" data-platform="${attr(item.platformId)}"${methods.includes("QR_CODE") ? "" : " disabled"}>二维码</button>
      <button class="secondary" data-action="refresh-login" data-platform="${attr(item.platformId)}">刷新</button>
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