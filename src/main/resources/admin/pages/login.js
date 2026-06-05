const $ = id => document.getElementById(id);

let ctx;
let root;
let api;
let apiBlob;
let state;
let ui;
let invalidate;
let handleError;
let hydrateMediaImages;
let esc;
let attr;
let fmtTime;
let pill;
let mediaImage;
let platformTag;
let notify;
let openModal;
let closeModal;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  apiBlob = ctx.apiBlob;
  state = ctx.state;
  ui = ctx.ui;
  invalidate = ctx.invalidate;
  handleError = ctx.handleError;
  hydrateMediaImages = ctx.hydrateMediaImages;
  ({
    esc,
    attr,
    fmtTime,
    pill,
    mediaImage,
    platformTag,
    notify,
    openModal,
    closeModal,
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
  await loadPlatformLogins(ctx.force);
}

export function unmount() {
  invalidatePageRequests("login");
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
  const request = beginPageRequest("login");
  const sourceAccountsPromise = force || !state.cache.platformLogins
    ? api(force ? "/platform-logins?force=true" : "/platform-logins")
    : Promise.resolve(state.cache.platformLogins);
  const targetAccountsPromise = force || !state.cache.targetPlatformAccounts
    ? api("/target-platform-accounts")
    : Promise.resolve(state.cache.targetPlatformAccounts);
  const [sourceAccounts, targetAccounts] = await Promise.all([sourceAccountsPromise, targetAccountsPromise]);
  if (!isCurrentPageRequest(request)) return;
  state.cache.platformLogins = sourceAccounts;
  state.cache.targetPlatformAccounts = targetAccounts;
  pageRoot().innerHTML = `
    <section class="page account-connection-page">
      ${renderAccountConnectionSection(
        "消息源账号",
        "用于读取 Bilibili、X 等动态源的登录账号。",
        renderAccountGrid(sourceAccounts, renderPlatformCard, "暂无可登录的消息源账号"),
      )}
      ${renderAccountConnectionSection(
        "目标平台账号",
        "用于向 QQ、Discord 等真实目标平台发送消息的 Bot 连接。",
        renderAccountGrid(targetAccounts, renderTargetPlatformAccountCard, "暂无目标平台账号连接"),
      )}
    </section>`;
  await hydrateMediaImages(pageRoot());
}

function renderAccountConnectionSection(title, description, body) {
  return `<section class="panel account-connection-section">
    <div class="panel-head">
      <div>
        <h2>${esc(title)}</h2>
        <p>${esc(description)}</p>
      </div>
    </div>
    ${body}
  </section>`;
}

function renderAccountGrid(rows, renderer, emptyText) {
  return rows && rows.length
    ? `<div class="platform-login-grid">${rows.map(renderer).join("")}</div>`
    : `<div class="empty">${esc(emptyText)}</div>`;
}

function renderPlatformCard(item) {
  const accountName = item.account && item.account.name || "未显示账号";
  const accountId = item.account && item.account.userId || "";
  const accountAvatar = item.account && item.account.avatarUri;
  const checkedText = item.checkedAtEpochMillis ? `检查：${fmtTime(item.checkedAtEpochMillis, true)}` : "";
  const accountMeta = [accountId, item.message, checkedText].filter(Boolean).join(" · ") || "暂无账号信息";
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
        <small>${esc(accountMeta)}</small>
      </div>
    </div>
    <div class="platform-login-actions">${renderLoginActions(item)}</div>
  </article>`;
}

function renderTargetPlatformAccountCard(item) {
  const accountName = item.accountName || "未显示账号";
  const accountId = item.accountId || "";
  const checkedText = item.checkedAtEpochMillis ? `检查：${fmtTime(item.checkedAtEpochMillis, true)}` : "";
  const transportText = item.transportName || item.transportId || "未知通道";
  const pluginText = item.pluginName || item.pluginId || "未知插件";
  const accountMeta = [accountId, transportText, pluginText, checkedText].filter(Boolean).join(" · ") || "暂无连接信息";
  const status = item.enabled === false ? "DISABLED" : item.state;
  return `<article class="platform-login-card target-platform-account-card">
    <div class="platform-login-head">
      <div class="platform-login-title">
        ${platformTag(item.platformId, item.platformId)}
        <span>${esc(transportText)}${item.pluginVersion ? ` · ${esc(item.pluginVersion)}` : ""}</span>
      </div>
      ${pill(status)}
    </div>
    <div class="platform-login-account">
      ${mediaImage(item.avatarUri, "platform-login-avatar avatar", item.platformId, "AVATAR")}
      <div>
        <span>Bot 账号</span>
        <strong>${esc(accountName)}</strong>
        <small>${esc(accountMeta)}</small>
      </div>
    </div>
  </article>`;
}

function renderLoginActions(item) {
  const actions = item.actions || [];
  if (!actions.length) return `<span class="sub-line">暂无可用操作</span>`;
  return actions.map(action => renderLoginActionButton(item.platformId, action)).join("");
}

function renderLoginActionButton(platform, action) {
  const map = {
    QR_LOGIN: { domAction: "qr-login", className: "login-action-qr" },
    COOKIE_LOGIN: { domAction: "cookie-login", className: "login-action-cookie" },
    COOKIE_EXPORT: { domAction: "export-cookie", className: "login-action-export" },
    REFRESH_STATUS: { domAction: "refresh-login", className: "login-action-refresh" },
  };
  const config = map[action.key] || { domAction: "", className: "login-action-unknown" };
  const enabled = !!action.enabled && !!config.domAction;
  const title = action.reason || action.description || action.label || "";
  return `<button class="${attr(config.className)}" data-action="${attr(config.domAction)}" data-platform="${attr(platform)}"${enabled ? "" : " disabled"}${title ? ` title="${attr(title)}"` : ""}>${esc(action.label || action.key || "操作")}</button>`;
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

function cookieHeaderFromJson(cookieJson) {
  try {
    const rows = JSON.parse(cookieJson || "[]");
    if (!Array.isArray(rows)) return "";
    const pairs = rows.map(item => {
      const name = String(item && item.name || "").trim();
      const value = item && item.value != null ? String(item.value) : "";
      return name ? `${name}=${value}` : "";
    }).filter(Boolean);
    return pairs.length ? pairs.join(";") + ";" : "";
  } catch (_) {
    return "";
  }
}

async function openCookieExport(platform) {
  const result = await api(`/platforms/${encodeURIComponent(platform)}/login/cookie/export`);
  const cookieJson = result.cookie || "";
  const cookieHeader = cookieHeaderFromJson(cookieJson);
  const formats = [
    {
      key: "json",
      title: "JSON 格式",
      description: "后端原样返回的浏览器 Cookie JSON，适合保存到 Bilibili 插件配置或再次导入后台。",
      value: cookieJson,
    },
    {
      key: "raw",
      title: "原始 Cookie 格式",
      description: "形如 SESSDATA=xxxx;buvid4=xxxx;，适合复制到请求头或手动 Cookie 登录。",
      value: cookieHeader,
    },
  ];
  openModal(`${platform} Cookie`, `
    <div class="cookie-export">
      <div class="cookie-export-tabs">
        ${formats.map((item, index) =>
          `<button type="button" class="cookie-export-tab${index === 0 ? " active" : ""}" data-cookie-format="${attr(item.key)}">${esc(item.title)}</button>`
        ).join("")}
      </div>
      <div id="cookieExportDescription" class="cookie-export-description">${esc(formats[0].description)}</div>
      <textarea id="cookieExportValue" readonly>${esc(formats[0].value)}</textarea>
      <div class="cookie-export-footer">
        <span class="inline-note">${esc(result.message || "Cookie 已导出")}</span>
        <button type="button" id="cookieExportCopy" class="secondary compact">复制当前格式</button>
      </div>
    </div>
  `, null, { size: "small", cancelText: "关闭" });

  function selectFormat(key) {
    const selected = formats.find(item => item.key === key) || formats[0];
    $("cookieExportValue").value = selected.value || "";
    $("cookieExportDescription").textContent = selected.value
      ? selected.description
      : `${selected.description} 当前 Cookie JSON 无法转换出该格式。`;
    document.querySelectorAll("[data-cookie-format]").forEach(button => {
      button.classList.toggle("active", button.dataset.cookieFormat === selected.key);
    });
  }

  document.querySelectorAll("[data-cookie-format]").forEach(button => {
    button.onclick = () => selectFormat(button.dataset.cookieFormat);
  });
  $("cookieExportCopy").onclick = async () => {
    const value = $("cookieExportValue").value;
    if (!value) {
      notify("当前格式没有可复制的内容", true);
      return;
    }
    try {
      await navigator.clipboard.writeText(value);
    } catch (_) {
      $("cookieExportValue").select();
      document.execCommand("copy");
    }
    notify("Cookie 已复制", false);
  };
}

async function openQrLogin(platform) {
  let loginId = "";
  let imageUrl = "";
  let timer = null;
  let countdownTimer = null;
  let autoCloseTimer = null;
  let expiresAtMs = 0;
  let statusPollIntervalMs = 2500;
  let closed = false;
  let sessionFinished = false;
  let polling = false;
  let refreshing = false;

  function clearTimers() {
    if (timer) clearInterval(timer);
    if (countdownTimer) clearInterval(countdownTimer);
    if (autoCloseTimer) clearTimeout(autoCloseTimer);
    timer = null;
    countdownTimer = null;
    autoCloseTimer = null;
  }

  function releaseQrImage() {
    if (imageUrl) URL.revokeObjectURL(imageUrl);
    imageUrl = "";
  }

  function setQrStatus(message, stateName = "pending") {
    const node = $("qrStatus");
    if (!node) return;
    node.textContent = message || "-";
    const card = node.closest(".qr-status-card");
    if (card) {
      card.classList.remove("pending", "success", "expired", "error");
      card.classList.add(stateName);
    }
  }

  function setQrBoxState(stateName) {
    const box = $("qrCodeBox");
    if (!box) return;
    box.classList.remove("loading", "ready", "expired", "success", "error");
    box.classList.add(stateName);
  }

  function setRefreshVisible(visible) {
    const overlay = $("qrExpiredOverlay");
    if (overlay) overlay.hidden = !visible;
  }

  function setLoadingVisible(visible) {
    const loading = $("qrLoading");
    if (loading) loading.hidden = !visible;
  }

  function setCountdownText(text) {
    const node = $("qrCountdown");
    if (node) node.textContent = text;
  }

  function setInstruction(text) {
    const node = $("qrInstruction");
    if (node) node.textContent = text || "请扫码并确认登录";
  }

  function setValidityHint(text) {
    const node = $("qrValidity");
    if (node) node.textContent = text || "请在二维码有效期内完成操作";
  }

  function updateCountdown() {
    if (sessionFinished) return;
    if (!expiresAtMs) {
      setCountdownText("有效期未知");
      return;
    }
    const remainingMs = Math.max(0, expiresAtMs - Date.now());
    const totalSeconds = Math.ceil(remainingMs / 1000);
    const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, "0");
    const seconds = String(totalSeconds % 60).padStart(2, "0");
    setCountdownText(remainingMs > 0 ? `剩余 ${minutes}:${seconds}` : "已失效");
    if (remainingMs <= 0 && loginId) {
      markExpired("二维码已失效，请刷新后重新扫码", true);
    }
  }

  function resetQrView() {
    const image = $("qrImage");
    if (image) {
      image.hidden = true;
      image.removeAttribute("src");
    }
    setQrBoxState("loading");
    setRefreshVisible(false);
    setLoadingVisible(true);
    setInstruction("正在准备扫码登录");
    setValidityHint("请在二维码有效期内完成操作");
    setCountdownText("等待二维码");
    setQrStatus("正在生成二维码...", "pending");
  }

  function markExpired(message, localOnly = false) {
    if (localOnly) {
      if (countdownTimer) clearInterval(countdownTimer);
      countdownTimer = null;
    } else {
      sessionFinished = true;
      clearTimers();
    }
    setQrBoxState("expired");
    setRefreshVisible(true);
    setLoadingVisible(false);
    setQrStatus(message || "二维码已失效，请刷新后重新扫码", "expired");
    setCountdownText("已失效");
  }

  function markFailed(message, localOnly = false) {
    if (!localOnly) sessionFinished = true;
    clearTimers();
    setQrBoxState("error");
    setRefreshVisible(true);
    setLoadingVisible(false);
    setQrStatus(message || "二维码登录失败，请刷新后重试", "error");
  }

  async function finishSuccess(message) {
    sessionFinished = true;
    clearTimers();
    setQrBoxState("success");
    setRefreshVisible(false);
    setLoadingVisible(false);
    setQrStatus(message || "登录成功，3 秒后自动关闭", "success");
    setCountdownText("已登录");
    autoCloseTimer = setTimeout(async () => {
      if (closed) return;
      closeModal();
      invalidate("platformLogins", "dashboard");
      await loadPlatformLogins(true).catch(handleError);
      notify("登录成功", false);
    }, 3000);
  }

  async function pollQrStatus(currentLoginId) {
    if (closed || polling || !currentLoginId || currentLoginId !== loginId || sessionFinished) return;
    polling = true;
    try {
      const status = await api(`/login/qr/${encodeURIComponent(currentLoginId)}`);
      if (closed || currentLoginId !== loginId) return;
      const statusValue = String(status.status || "PENDING");
      if (statusValue === "PENDING") {
        setQrStatus(status.message || "等待扫码确认", "pending");
        return;
      }
      if (statusValue === "SUCCESS") {
        await finishSuccess(status.message || "登录成功，3 秒后自动关闭");
        return;
      }
      if (statusValue === "EXPIRED") {
        markExpired(status.message || "二维码已失效，请刷新后重新扫码");
        return;
      }
      if (statusValue === "CANCELED") {
        markFailed(status.message || "二维码登录已取消");
        return;
      }
      markFailed(status.message || "二维码登录失败，请刷新后重试");
    } catch (error) {
      if (!closed) setQrStatus(error.message || "扫码状态查询失败", "error");
    } finally {
      polling = false;
    }
  }

  function startPolling() {
    const currentLoginId = loginId;
    updateCountdown();
    countdownTimer = setInterval(updateCountdown, 1000);
    timer = setInterval(() => {
      pollQrStatus(currentLoginId).catch(handleError);
    }, statusPollIntervalMs);
    pollQrStatus(currentLoginId).catch(handleError);
  }

  async function refreshQr() {
    if (refreshing) return;
    refreshing = true;
    const previousLoginId = loginId;
    const shouldCancelPrevious = previousLoginId && !sessionFinished;
    loginId = "";
    sessionFinished = false;
    clearTimers();
    releaseQrImage();
    resetQrView();
    const refreshButton = $("qrRefresh");
    if (refreshButton) refreshButton.disabled = true;
    try {
      if (shouldCancelPrevious) {
        await api(`/login/qr/${encodeURIComponent(previousLoginId)}`, { method: "DELETE" }).catch(() => {});
      }
      const start = await api(`/platforms/${encodeURIComponent(platform)}/login/qr`, { method: "POST" });
      if (closed) return;
      loginId = start.loginId;
      expiresAtMs = start.expiresAtEpochSeconds ? Number(start.expiresAtEpochSeconds) * 1000 : 0;
      const nextPollInterval = Number(start.statusPollIntervalMillis || 2500);
      statusPollIntervalMs = Number.isFinite(nextPollInterval) && nextPollInterval > 0 ? Math.max(1000, nextPollInterval) : 2500;
      setInstruction(start.instruction || start.message || "请扫码并确认登录");
      setValidityHint(start.validityHint || "请在二维码有效期内完成操作");
      setQrStatus(start.message || "等待扫码确认", "pending");
      updateCountdown();
      const blob = await apiBlob(start.imageUrl.replace(/^\/api/, ""));
      if (closed || loginId !== start.loginId) return;
      imageUrl = URL.createObjectURL(blob);
      const image = $("qrImage");
      if (image) {
        image.src = imageUrl;
        image.hidden = false;
      }
      setQrBoxState("ready");
      setRefreshVisible(false);
      setLoadingVisible(false);
      startPolling();
    } catch (error) {
      if (!closed) markFailed(error.message || "二维码生成失败，请稍后重试", !!loginId);
    } finally {
      refreshing = false;
      if (refreshButton && refreshButton.isConnected) refreshButton.disabled = false;
    }
  }

  openModal(`${platform} 二维码登录`, `
    <div class="qr-login-panel">
      <div class="qr-login-copy">
        <strong id="qrInstruction">正在准备扫码登录</strong>
        <span><span id="qrValidity">请在二维码有效期内完成操作</span> · <b id="qrCountdown">等待二维码</b></span>
      </div>
      <div id="qrCodeBox" class="qr-code-box loading">
        <div id="qrLoading" class="qr-code-loading"><span class="loading-spinner"></span><span>正在生成二维码</span></div>
        <img id="qrImage" alt="二维码" hidden>
        <div id="qrExpiredOverlay" class="qr-expired-overlay" hidden>
          <button type="button" id="qrRefresh" class="qr-refresh-button" title="刷新二维码">↻</button>
        </div>
      </div>
      <div class="qr-status-card pending">
        <span>当前扫码状态</span>
        <strong id="qrStatus">正在生成二维码...</strong>
      </div>
    </div>
  `, null, {
    confirmHidden: true,
    cancelText: "关闭",
    size: "qr",
    cleanup: () => {
      closed = true;
      clearTimers();
      releaseQrImage();
      if (!sessionFinished && loginId) {
        api(`/login/qr/${encodeURIComponent(loginId)}`, { method: "DELETE" }).catch(() => {});
      }
    }
  });
  $("qrRefresh").onclick = () => refreshQr().catch(handleError);
  await refreshQr();
}
