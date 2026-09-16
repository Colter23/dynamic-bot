let ctx;
let root;
let api;
let state;
let esc;
let notify;
let withButtonLoading;

const PROJECT_NAME = "dynamic-bot";
const PROJECT_DESC = "面向动态订阅、直播提醒、链接解析和动态绘图推送的可扩展 Bot 主程序。";
const PROJECT_URL = "https://github.com/Colter23/dynamic-bot";
const PROJECT_REPO = "Colter23/dynamic-bot";
const LICENSE_NAME = "Apache License 2.0";
const COPYRIGHT = "© 2026 Colter23";
const RELEASES_API = `https://api.github.com/repos/${PROJECT_REPO}/releases/latest`;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  ({ esc, notify, withButtonLoading } = ctx.ui);
}

function pageRoot() {
  return root;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadAbout(ctx.force);
}

export async function handleAction(nextCtx, { action, button }) {
  bindContext(nextCtx);
  if (action === "about-check-update") {
    await checkUpdate(button);
    return true;
  }
  return false;
}

async function loadAbout(force) {
  const status = await loadSystemStatus(force).catch(() => null);
  pageRoot().innerHTML = renderAbout(status);
}

async function loadSystemStatus(force) {
  if (force || !state.cache.system) state.cache.system = await api("/system/status");
  return state.cache.system;
}

/** 当前版本；开发环境（直接跑 class 文件）后端返回 dev，此时视为未知。 */
function currentVersion() {
  const version = String(state.cache.system?.version || "").trim();
  return version && version !== "dev" ? version : "";
}

function renderAbout(status) {
  const version = String(status?.version || "").trim();
  const versionText = !version || version === "dev" ? "开发版" : `v${version}`;
  return `
    <section class="page about-page">
      <article class="panel about-card">
        <div class="about-brand">
          <img class="about-logo" src="/admin/assets/logo-icon.svg" alt="" aria-hidden="true">
          <h2 class="about-name">${esc(PROJECT_NAME)}</h2>
          <p class="about-desc">${esc(PROJECT_DESC)}</p>
        </div>
        <div class="about-meta">
          ${metaRow("许可证", esc(LICENSE_NAME))}
          ${metaRow("版权", esc(COPYRIGHT))}
          ${metaRow("GitHub", `<a class="about-link" href="${PROJECT_URL}" target="_blank" rel="noreferrer">${esc(PROJECT_REPO)}</a>`)}
          ${metaRow("版本", `<span class="about-version">${esc(versionText)}</span>${updateButton()}`)}
        </div>
      </article>
    </section>`;
}

function metaRow(label, valueHtml) {
  return `<div class="about-meta-row">
    <span class="about-meta-label">${esc(label)}</span>
    <div class="about-meta-value">${valueHtml}</div>
  </div>`;
}

function updateButton() {
  const known = Boolean(currentVersion());
  const disabled = known ? "" : ' disabled title="开发环境下读不到版本号，无法比较"';
  return `<button type="button" class="about-update-btn" data-action="about-check-update"${disabled}>
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path d="M13.2 8a5.2 5.2 0 1 1-1.5-3.7" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"></path>
      <path d="M13.4 2.6v3.1h-3.1" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"></path>
    </svg>
    <span>检查更新</span>
  </button>`;
}

async function checkUpdate(button) {
  const current = currentVersion();
  if (!current) {
    notify("开发环境下读不到版本号，跳过检查", true);
    return;
  }
  try {
    await withButtonLoading(button, "检查中...", async () => {
      const latest = await fetchLatestVersion();
      if (!latest) {
        notify("没有找到已发布的版本", true);
        return;
      }
      if (compareVersion(latest, current) > 0) {
        notify(`发现新版本 v${latest}，当前 v${current}`);
      } else {
        notify(`已是最新版本 v${current}`);
      }
    });
  } catch (error) {
    notify(`检查更新失败：${error?.message || "网络不可用"}`, true);
  }
}

async function fetchLatestVersion() {
  const response = await fetch(RELEASES_API, { headers: { Accept: "application/vnd.github+json" } });
  if (!response.ok) throw new Error(`GitHub 返回 HTTP ${response.status}`);
  const data = await response.json();
  return String(data?.tag_name || "").trim().replace(/^v/i, "");
}

/** 逐段数字比较：a > b 返回正数。段数不同时缺位按 0 处理。 */
function compareVersion(a, b) {
  const left = String(a).split(".");
  const right = String(b).split(".");
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const diff = (Number(left[index]) || 0) - (Number(right[index]) || 0);
    if (diff !== 0) return diff;
  }
  return 0;
}
