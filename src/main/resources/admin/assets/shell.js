const $ = id => document.getElementById(id);
    const tokenKey = "dynamicBotAdminToken";
    const pages = {
      dashboard: ["仪表盘", "运行状态", "/admin/pages/dashboard.html", "/admin/pages/dashboard.js"],
      plugins: ["插件管理", "生命周期与能力", "/admin/pages/plugins.html", "/admin/pages/plugins.js"],
      login: ["账号连接", "动态源与消息出口状态", "/admin/pages/login.html", "/admin/pages/login.js"],
      subscriptions: ["订阅管理", "订阅策略与过滤规则", "/admin/pages/subscriptions.html", "/admin/pages/subscriptions.js"],
      entities: ["发布者与目标", "来源与消息出口", "/admin/pages/entities.html", "/admin/pages/entities.js"],
      messages: ["消息记录", "投递结果与失败详情", "/admin/pages/messages.html", "/admin/pages/messages.js"],
      testing: ["测试台", "绘图、模板与链接解析预览", "/admin/pages/testing.html", "/admin/pages/testing.js"],
      tasks: ["任务查看", "调度状态与操作", "/admin/pages/tasks.html", "/admin/pages/tasks.js"],
      logs: ["日志查看", "进程内实时日志", "/admin/pages/logs.html", "/admin/pages/logs.js"],
      configs: ["配置", "主项目与插件配置", "/admin/pages/configs.html", "/admin/pages/configs.js"],
      system: ["系统维护", "运行信息与维护", "/admin/pages/system.html", "/admin/pages/system.js"],
      about: ["关于", "项目信息与作者", "/admin/pages/about.html", "/admin/pages/about.js"]
    };
    const themeKey = "dynamicBotAdminTheme";
    const state = {
      token: localStorage.getItem(tokenKey) || "",
      theme: localStorage.getItem(themeKey) || "light",
      page: "dashboard",
      cache: {},
      modalSubmit: null,
      modalCleanup: null,
      modalBusy: false,
      modalConfirmHtml: null,
      modalLoadingText: "处理中...",
      logsPaused: false,
      logsAutoTop: true,
      logTimer: null,
      logSince: 0,
      logRows: [],
      mediaObjectUrls: [],
      mediaGeneration: 0,
      selectedConfigId: "",
      currentConfigDetail: null,
      pendingConfigRestarts: {},
      activePageModule: null,
      pageLoadSeq: 0,
      pageRequestSeq: {},
      restoringHash: false,
      pluginAdminPagesLoaded: false
    };

    function applyTheme(theme) {
      const root = document.documentElement;
      if (theme === "dark") {
        root.setAttribute("data-theme", "dark");
      } else {
        root.removeAttribute("data-theme");
      }
      state.theme = theme;
      localStorage.setItem(themeKey, theme);
    }

    function toggleTheme() {
      const newTheme = state.theme === "dark" ? "light" : "dark";
      applyTheme(newTheme);
      notify(newTheme === "dark" ? "已切换到暗色模式" : "已切换到亮色模式", false);
    }
    const pageModuleCache = {};
    const targetCandidateCacheTtlMillis = 180 * 1000;

    const eventTypes = [
      ["DYNAMIC", "动态"],
      ["LIVE_STARTED", "开播"],
      ["LIVE_ENDED", "下播"]
    ];
    const blockKinds = [
      ["TEXT", "文字"],
      ["IMAGE", "图片"],
      ["VIDEO", "视频"],
      ["CARD", "卡片"],
      ["POLL", "投票"],
      ["REPOST", "转发"]
    ];

    function esc(value) {
      return String(value ?? "").replace(/[&<>"']/g, char => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
      }[char]));
    }
    function attr(value) { return esc(value).replace(/`/g, "&#96;"); }
    function fmtTime(value, millis) {
      if (!value) return "-";
      const date = new Date(millis ? Number(value) : Number(value) * 1000);
      return Number.isNaN(date.getTime()) ? "-" : date.toLocaleString("zh-CN", { hour12: false });
    }
    function fmtBytes(bytes) {
      const n = Number(bytes || 0);
      if (n < 1024) return n + " B";
      if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
      if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
      return (n / 1024 / 1024 / 1024).toFixed(2) + " GB";
    }
    function fmtDuration(ms) {
      let seconds = Math.floor(Number(ms || 0) / 1000);
      const days = Math.floor(seconds / 86400); seconds %= 86400;
      const hours = Math.floor(seconds / 3600); seconds %= 3600;
      const minutes = Math.floor(seconds / 60);
      if (days) return `${days}天 ${hours}小时`;
      if (hours) return `${hours}小时 ${minutes}分钟`;
      return `${minutes}分钟`;
    }
    function label(value) {
      const map = {
        ACTIVE: "运行中", LOADED: "已加载", FAILED: "失败", DISABLED: "停用",
        RUNNING: "运行中", COMPLETED: "已完成", CANCELLED: "已取消",
        PENDING: "等待", SENDING: "发送中", SENT: "已发送",
        SUCCESS: "已登录", CANCELED: "已取消", EXPIRED: "已过期", UNSUPPORTED: "不支持",
        COOKIE: "Cookie", QR_CODE: "二维码",
        GROUP: "群组", USER: "用户", CHANNEL: "频道", OTHER: "其他",
        ADMIN: "管理员",
        DYNAMIC: "动态", LIVE_STARTED: "开播", LIVE_ENDED: "下播",
        OPEN: "直播中", CLOSE: "未开播", ROUND: "轮播中",
        BLOCK: "阻止", ALLOW: "允许", MENTION_ALL: "@全体", NONE: "无",
        IMAGE: "图片", VIDEO: "视频", CARD: "卡片", POLL: "投票", ORIGIN: "转发",
        READY: "可用", UNAVAILABLE: "不可用",
        ROUND_ROBIN: "轮询", PRIMARY_BACKUP: "主备"
      };
      return map[value] || value || "-";
    }
    function eventLabel(value) {
      return Object.fromEntries(eventTypes)[value] || value || "-";
    }
    function pill(value) {
      const text = label(value);
      const cls = ["ACTIVE", "SUCCESS", "SENT", "OPEN", "RUNNING"].includes(value) ? "ok"
        : ["FAILED", "EXPIRED", "ERROR"].includes(value) ? "bad"
        : ["PENDING", "SENDING", "LOADED", "CANCELED", "CANCELLED", "WARN"].includes(value) ? "warn" : "info";
      return `<span class="pill ${cls}">${esc(text)}</span>`;
    }
    function tags(items) {
      const values = (items || []).filter(Boolean);
      return values.length ? `<span class="tag-list">${values.map(item => `<span class="pill">${esc(item)}</span>`).join("")}</span>` : `<span class="sub-line">-</span>`;
    }
    function cell(title, sub) {
      return `<span class="primary-line">${esc(title || "-")}</span><span class="sub-line">${esc(sub || "")}</span>`;
    }
    function option(value, text, selected) {
      return `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`;
    }
    function detailItem(title, value, mono = false) {
      return `<div class="plugin-detail-item">
        <span>${esc(title)}</span>
        <strong class="${mono ? "mono" : ""}">${esc(value === null || value === undefined || value === "" ? "-" : value)}</strong>
      </div>`;
    }
    function prettyJson(value, fallback = "") {
      if (value == null) return fallback;
      try {
        return JSON.stringify(value, null, 2);
      } catch (error) {
        return String(value);
      }
    }
    function dataBlock(title, value, open = false, className = "testing-data-block") {
      return `<details class="${attr(className)}"${open ? " open" : ""}>
        <summary>${esc(title)}</summary>
        <pre>${esc(value || "")}</pre>
      </details>`;
    }
    function loadingRow(text, className = "target-loading") {
      return `<div class="${attr(className)}"><span class="loading-spinner" aria-hidden="true"></span>${esc(text)}</div>`;
    }
    function jsonBlock(title, value, open = false, className = "testing-data-block") {
      if (!value) return "";
      return dataBlock(title, prettyJson(value), open, className);
    }
    async function withButtonLoading(button, loadingText, task) {
      const originalHtml = button?.innerHTML;
      if (button) {
        button.disabled = true;
        button.textContent = loadingText;
      }
      try {
        return await task();
      } finally {
        if (button?.isConnected) {
          button.disabled = false;
          if (originalHtml !== undefined) button.innerHTML = originalHtml;
        }
      }
    }
    function setControlValue(selector, value, container = document) {
      const node = container.querySelector(selector);
      if (node) node.value = value ?? "";
    }
    function setControlChecked(selector, checked, container = document) {
      const node = container.querySelector(selector);
      if (node) node.checked = checked === true;
    }
    function previewImageAttrs(className) {
      const tokens = String(className || "").split(/\s+/);
      const title = tokens.includes("header-image") ? "头图预览"
        : tokens.includes("entity-detail-cover") ? "直播封面预览"
        : tokens.includes("avatar") ? "头像预览"
        : "";
      return title ? {
        className: `${className} previewable-media`,
        attrs: ` data-preview-image="true" data-preview-title="${attr(title)}" title="点击预览" tabindex="0"`
      } : { className, attrs: "" };
    }
    function mediaImage(uri, className = "avatar", platformId = "admin", kind = "OTHER") {
      if (!uri) return `<span class="${attr(className)} media-placeholder" aria-hidden="true"></span>`;
      const preview = previewImageAttrs(className);
      if (/^(data|blob):/i.test(uri)) {
        return `<img class="${attr(preview.className)}" src="${attr(uri)}" alt=""${preview.attrs} onerror="this.style.visibility='hidden'">`;
      }
      return `<img class="${attr(preview.className)}" alt="" data-media-uri="${attr(uri)}" data-media-platform="${attr(platformId || "admin")}" data-media-kind="${attr(kind || "OTHER")}"${preview.attrs} onerror="this.style.visibility='hidden'">`;
    }
    function identity(name, sub, image, platformId = "admin", kind = "AVATAR") {
      return `<div class="identity-cell">${mediaImage(image, "avatar", platformId, kind)}<div>${cell(name, sub)}</div></div>`;
    }
    function identityMeta(name, image, mediaPlatform, mediaKind, platformId, typeText, externalId, options = {}) {
      const platformPart = options.showPlatform === false ? "" : platformTag(platformId, platformId);
      return `<div class="identity-cell">
        ${mediaImage(image, "avatar", mediaPlatform, mediaKind)}
        <div>
          <span class="primary-line">${esc(name || "-")}</span>
          <span class="sub-line subscription-meta-line">${platformPart}<span>${esc(typeText || "-")}</span><span>${esc(externalId || "-")}</span></span>
        </div>
      </div>`;
    }
    function platformHue(value) {
      let hash = 0;
      String(value || "").split("").forEach(char => {
        hash = ((hash << 5) - hash) + char.charCodeAt(0);
        hash |= 0;
      });
      return Math.abs(hash) % 360;
    }
    function platformTag(platformId, text) {
      const value = String(platformId || "").trim();
      const labelText = text || value || "-";
      const style = `--platform-hue:${platformHue(value || labelText)}`;
      return `<span class="platform-tag" style="${attr(style)}"><span class="platform-tag-text">${esc(labelText)}</span></span>`;
    }
    function themeSwatch(theme) {
      if (!theme || !Array.isArray(theme.backgroundColors) || theme.backgroundColors.length === 0) {
        return `<span class="sub-line">使用全局主题</span>`;
      }
      const cssColor = value => {
        const text = String(value || "").trim();
        if (/^#[0-9a-fA-F]{8}$/.test(text)) {
          return `#${text.slice(3)}${text.slice(1, 3)}`;
        }
        return text;
      };
      const colors = theme.backgroundColors.filter(Boolean).map(cssColor);
      const mode = String(theme.mode || "").toUpperCase();
      const modeText = mode === "DARK" ? "暗色" : mode === "LIGHT" ? "亮色" : "主题";
      const modeClass = mode === "DARK" ? "dark" : "light";
      const gradient = colors.length === 1 ? colors[0] : `linear-gradient(90deg, ${colors.map(attr).join(", ")})`;
      const card = cssColor(theme.cardColor || (mode === "DARK" ? "#13171FAD" : "#C2FFFFFF"));
      const border = cssColor(theme.borderColor || (mode === "DARK" ? "#2EFFFFFF" : "#E0FFFFFF"));
      const text = cssColor(theme.textColor || (mode === "DARK" ? "#FFFFFF" : "#000000"));
      const secondary = cssColor(theme.secondaryTextColor || text);
      const muted = cssColor(theme.mutedTextColor || secondary);
      const primary = cssColor(theme.primaryColor || colors[0]);
      const onPrimary = cssColor(theme.onPrimaryColor || "#FFFFFF");
      const accent = cssColor(theme.readableAccentColor || theme.linkColor || primary);
      const link = cssColor(theme.linkColor || accent);
      const qr = cssColor(theme.qrPointColor || primary);
      const tooltip = [
        `${modeText}主题`,
        `背景 ${theme.backgroundColors.join("; ")}`,
        `卡片 ${theme.cardColor || "-"}`,
        `主色 ${theme.primaryColor || "-"}`,
        `可读强调 ${theme.readableAccentColor || theme.linkColor || "-"}`,
        `二维码点 ${theme.qrPointColor || theme.primaryColor || "-"}`,
      ].join("\n");
      return `<div class="theme-cell">
        <div class="theme-preview ${modeClass}" style="${attr([
          `--theme-bg:${gradient}`,
          `--theme-card:${card}`,
          `--theme-border:${border}`,
          `--theme-text:${text}`,
          `--theme-secondary:${secondary}`,
          `--theme-muted:${muted}`,
          `--theme-primary:${primary}`,
          `--theme-on-primary:${onPrimary}`,
          `--theme-accent:${accent}`,
          `--theme-link:${link}`,
          `--theme-qr:${qr}`,
        ].join(";"))}" title="${attr(tooltip)}" aria-label="${attr(`${modeText}主题预览`)}">
          <div class="theme-preview-card">
            <span class="theme-preview-pill">Aa</span>
            <div class="theme-preview-main">
              <span class="theme-preview-title"></span>
              <span class="theme-preview-line main"></span>
              <span class="theme-preview-line sub"></span>
            </div>
          </div>
        </div>
      </div>`;
    }
    function renderTable(rows, columns) {
      if (!rows || rows.length === 0) return `<div class="empty">暂无数据</div>`;
      return `<div class="table-wrap"><table><thead><tr>${columns.map(col => `<th>${esc(col.title)}</th>`).join("")}</tr></thead><tbody>${
        rows.map(row => `<tr>${columns.map(col => `<td>${col.render(row)}</td>`).join("")}</tr>`).join("")
      }</tbody></table></div>`;
    }
    function notify(message, isError) {
      const node = document.createElement("div");
      node.className = "toast" + (isError ? " error" : "");
      node.innerHTML = `<strong>${isError ? "操作失败" : "操作完成"}</strong><span>${esc(message)}</span>`;
      node.onclick = () => node.remove();
      $("toastStack").appendChild(node);
      setTimeout(() => node.remove(), isError ? 7000 : 3600);
    }
    function setLoginMessage(text, isError) {
      $("loginMessage").textContent = text || "";
      $("loginMessage").classList.toggle("error", !!isError);
    }
    function setModalMessage(text, isError) {
      $("modalMessage").textContent = text || "";
      $("modalMessage").classList.toggle("error", !!isError);
    }
    function query(params) {
      const out = new URLSearchParams();
      Object.entries(params || {}).forEach(([key, value]) => {
        if (value !== undefined && value !== null && String(value).trim() !== "") out.set(key, value);
      });
      const text = out.toString();
      return text ? "?" + text : "";
    }
    function uniqueValues(rows, selector, extraValues = []) {
      const values = new Set((extraValues || []).filter(Boolean));
      (rows || []).forEach(row => {
        const value = typeof selector === "function" ? selector(row) : row && row[selector];
        if (value) values.add(value);
      });
      return Array.from(values).sort((a, b) => String(a).localeCompare(String(b), "zh-CN"));
    }
    function filterOptions(allText, values, selected, labeler = value => value) {
      return `<option value="">${esc(allText)}</option>` + (values || []).map(value =>
        `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(labeler(value))}</option>`
      ).join("");
    }
    function matchesExact(value, filter) {
      return !filter || String(value || "") === filter;
    }
    function matchesContains(value, filter) {
      return !filter || String(value || "").toLowerCase().includes(String(filter).toLowerCase());
    }
    function matchesAnyContains(values, filter) {
      return !filter || (values || []).some(value => matchesContains(value, filter));
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
    function subscriberTargetAddressKey(platformId, kind, externalId, scopeId, threadId) {
      return [platformId, kind, externalId, scopeId || "", threadId || ""].join("\u001F");
    }
    function targetSources(target) {
      return Array.isArray(target && target.sources) ? target.sources : [];
    }
    function targetSourceCount(target) {
      return Number(target && target.sourceCount || targetSources(target).length || 0);
    }
    function sourceAccountLabel(source) {
      const name = source && source.accountName || "";
      const id = source && source.accountId || "";
      if (!name) return id || "-";
      if (!id || id === name) return name;
      return `${name} (${id})`;
    }
    function sourceTransportLabel(source) {
      return source && source.transportName || "-";
    }
    function sourceAccountTitle(target) {
      const names = targetSources(target)
        .map(source => source && (source.accountName || source.accountId))
        .filter(Boolean);
      return names.length ? `来源账号：${names.join("、")}` : "暂无来源账号";
    }
    function targetSourceToggleHtml(target, index, prefix) {
      const count = targetSourceCount(target);
      if (count <= 0) return `<span class="pill warn">无来源</span>`;
      const key = `${prefix}:${index}`;
      const labelText = `${count} 个来源`;
      return `<button type="button" class="secondary compact target-source-toggle" data-target-source-toggle="${attr(key)}" data-target-source-label="${attr(labelText)}" aria-expanded="false" title="${attr(sourceAccountTitle(target))}">${esc(labelText)} ▶</button>`;
    }
    function targetSourcePanelHtml(target, index, prefix, options = {}) {
      const sources = targetSources(target);
      if (!sources.length) return "";
      const key = `${prefix}:${index}`;
      const showPriority = options.showPriority !== false;
      return `<div class="target-source-panel" data-target-source-panel="${attr(key)}" hidden>
        <div class="target-source-help">${showPriority ? "点击优先设定优先推送渠道，不选择就使用全局路由。" : "展开查看这个真实目标来自哪些 Bot 账号。"}</div>
        ${sources.map(source => {
          const selected = target && target.accountId && source.accountId === target.accountId;
          return `
        <div class="target-source-row${showPriority ? "" : " no-priority"}">
          <span class="target-source-account">${esc(sourceAccountLabel(source))}</span>
          <span class="target-source-transport">${esc(sourceTransportLabel(source))}</span>
          ${showPriority ? `<button type="button" class="secondary compact target-priority-button${selected ? " active" : ""}" data-target-priority-choice="${attr(key)}" data-account-id="${attr(source.accountId)}" aria-pressed="${selected ? "true" : "false"}">${selected ? "取消优先" : "优先"}</button>` : ""}
        </div>`;
        }).join("")}</div>`;
    }
    function messageTargetChoiceHtml(target, index, options = {}) {
      const inputName = options.inputName || "targetCandidate";
      const prefix = options.prefix || inputName;
      const inputType = options.inputType || "checkbox";
      const checked = !!options.checked;
      const title = target && (target.name || target.externalId) || "-";
      const parts = [
        title,
        label(target && target.targetKind),
        target && target.externalId,
      ].filter(Boolean).join(" · ");
      return `<div class="target-choice target-choice-rich">
        <label class="target-choice-main">
          <input type="${attr(inputType)}" name="${attr(inputName)}" value="${attr(target && target.externalId || "")}" data-index="${attr(index)}"${checked ? " checked" : ""}>
          ${mediaImage(target && target.avatarUri, "target-choice-avatar", target && target.platformId, "AVATAR")}
          <span class="target-choice-text" title="${attr(parts)}">${esc(parts)}</span>
        </label>
        <div class="target-choice-tools">${targetSourceToggleHtml(target, index, prefix)}</div>
        ${targetSourcePanelHtml(target, index, prefix, options)}
      </div>`;
    }
    function bindTargetSourceToggles(root = document) {
      root.querySelectorAll("[data-target-source-toggle]").forEach(button => {
        button.onclick = event => {
          event.preventDefault();
          event.stopPropagation();
          const key = button.dataset.targetSourceToggle;
          const panel = Array.from(root.querySelectorAll("[data-target-source-panel]"))
            .find(item => item.dataset.targetSourcePanel === key);
          if (!panel) return;
          const open = panel.hidden;
          panel.hidden = !open;
          button.setAttribute("aria-expanded", open ? "true" : "false");
          button.textContent = `${button.dataset.targetSourceLabel || "来源"} ${open ? "▼" : "▶"}`;
        };
      });
      root.querySelectorAll("[data-target-priority-choice]").forEach(button => {
        button.onclick = event => {
          event.preventDefault();
          event.stopPropagation();
          const key = button.dataset.targetPriorityChoice;
          const selected = button.classList.contains("active");
          Array.from(root.querySelectorAll("[data-target-priority-choice]"))
            .filter(item => item.dataset.targetPriorityChoice === key)
            .forEach(item => {
              item.classList.remove("active");
              item.setAttribute("aria-pressed", "false");
              item.textContent = "优先";
            });
          if (!selected) {
            button.classList.add("active");
            button.setAttribute("aria-pressed", "true");
            button.textContent = "取消优先";
          }
        };
      });
    }
    function selectedTargetPriorityAccount(prefix, index, root = document) {
      const key = `${prefix}:${index}`;
      const button = Array.from(root.querySelectorAll("[data-target-priority-choice]"))
        .find(item => item.dataset.targetPriorityChoice === key && item.classList.contains("active"));
      return button ? (button.dataset.accountId || "").trim() : "";
    }
    function subscriberTargetCandidateCacheKey(platform, kind) {
      return `${platform || ""}\u001F${kind || ""}`;
    }
    async function loadSubscriberTargetCandidates(platform, kind, force = false) {
      if (!state.cache.subscriberTargetCandidates) state.cache.subscriberTargetCandidates = {};
      const key = subscriberTargetCandidateCacheKey(platform, kind);
      const entry = state.cache.subscriberTargetCandidates[key];
      const now = Date.now();
      if (!force && entry && now - entry.loadedAt < targetCandidateCacheTtlMillis) {
        return { items: entry.items, fromCache: true, stale: false };
      }
      try {
        const items = await api(`/subscriber-targets?platformId=${encodeURIComponent(platform)}&type=${encodeURIComponent(kind)}`) || [];
        state.cache.subscriberTargetCandidates[key] = { items, loadedAt: Date.now() };
        return { items, fromCache: false, stale: false };
      } catch (error) {
        if (entry && Array.isArray(entry.items)) {
          return { items: entry.items, fromCache: true, stale: true };
        }
        throw error;
      }
    }
    function clearSubscriberTargetCandidateCache() {
      delete state.cache.subscriberTargetCandidates;
    }

    async function api(path, options = {}) {
      const headers = Object.assign({ Authorization: "Bearer " + state.token }, options.headers || {});
      if (options.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
      const response = await fetch("/api" + path, Object.assign({}, options, { headers }));
      if (response.status === 401) {
        logout("认证失败");
        throw new Error("认证失败");
      }
      const text = await response.text();
      const data = text ? JSON.parse(text) : null;
      if (!response.ok) throw new Error((data && (data.message || data.error)) || "请求失败");
      return data;
    }
    async function apiBlob(path) {
      const response = await fetch("/api" + path, { headers: { Authorization: "Bearer " + state.token } });
      if (response.status === 401) {
        logout("认证失败");
        throw new Error("认证失败");
      }
      if (!response.ok) {
        let message = "资源加载失败";
        try {
          const data = await response.clone().json();
          message = data.message || data.error || message;
        } catch (_) {}
        throw new Error(message);
      }
      return response.blob();
    }
    function releaseMediaObjectUrls() {
      state.mediaGeneration += 1;
      state.mediaObjectUrls.forEach(url => URL.revokeObjectURL(url));
      state.mediaObjectUrls = [];
    }
    async function hydrateMediaImages(root = document) {
      const generation = state.mediaGeneration;
      const images = Array.from(root.querySelectorAll("img[data-media-uri]"));
      await Promise.all(images.map(async image => {
        const uri = image.dataset.mediaUri;
        if (!uri) return;
        try {
          const blob = await apiBlob("/media/image" + query({
            uri,
            platformId: image.dataset.mediaPlatform || "admin",
            kind: image.dataset.mediaKind || "OTHER",
            v: image.dataset.mediaVersion || ""
          }));
          const url = URL.createObjectURL(blob);
          if (generation !== state.mediaGeneration || !image.isConnected) {
            URL.revokeObjectURL(url);
            return;
          }
          state.mediaObjectUrls.push(url);
          image.src = url;
          image.style.visibility = "";
        } catch (_) {
          image.style.visibility = "hidden";
          image.title = "图片加载失败";
        }
      }));
    }
    function invalidate(...keys) {
      if (keys.length === 0) {
        state.cache = {};
        return;
      }
      const clearTargetCandidates = keys.some(key => ["plugins", "platformLogins", "targetPlatformAccounts", "subscriberTargetPlatforms"].includes(key));
      if (keys.some(key => ["plugins", "platformLogins"].includes(key))) {
        delete state.cache.targetPlatformAccounts;
        delete state.cache.commands;
      }
      keys.forEach(key => delete state.cache[key]);
      if (clearTargetCandidates) clearSubscriberTargetCandidateCache();
    }

    function showLogin(message, isError) {
      unmountActivePage().catch(handleError);
      releaseMediaObjectUrls();
      $("appView").hidden = true;
      $("loginView").hidden = false;
      setLoginMessage(message || "", isError);
    }
    function showApp() {
      $("loginView").hidden = true;
      $("appView").hidden = false;
      loadPluginAdminPages()
        .catch(handleError)
        .finally(() => setPage(location.hash.replace("#", "") || "dashboard"));
    }
    function logout(message) {
      state.token = "";
      localStorage.removeItem(tokenKey);
      closeModal();
      showLogin(message || "", !!message);
    }
    async function stopApplication() {
      if (!(await canLeaveActivePage())) return false;
      if (!(await confirmDanger("停止主项目", "确定停止主项目吗？当前进程会开始退出，后台也会断开连接。", { confirmText: "停止主项目" }))) return false;
      const result = await api("/system/stop", { method: "POST" });
      notify(result.message, false);
      return true;
    }

    function setPage(page) {
      return setPageAsync(page).catch(handleError);
    }
    async function setPageAsync(page) {
      const nextPage = pages[page] ? page : "dashboard";
      if (nextPage !== state.page && !(await canLeaveActivePage())) {
        restoreCurrentHash();
        return false;
      }
      if (nextPage !== state.page) closeModal();
      state.page = nextPage;
      if (location.hash.replace("#", "") !== state.page) {
        state.restoringHash = true;
        location.hash = state.page;
      }
      document.body.classList.remove("sidebar-open");
      document.querySelectorAll("[data-nav]").forEach(button => {
        button.classList.toggle("active", button.dataset.nav === state.page);
      });
      $("pageTitle").textContent = pages[state.page][0];
      $("pageSubtitle").textContent = pages[state.page][1];
      await loadPage(state.page, false);
      return true;
    }

    async function loadPluginAdminPages(force = false) {
      if (state.pluginAdminPagesLoaded && !force) return;
      const response = await api("/plugin-admin-pages");
      registerPluginAdminPages(response && response.pages || []);
      state.pluginAdminPagesLoaded = true;
    }

    function registerPluginAdminPages(items) {
      document.querySelectorAll("[data-plugin-admin-nav]").forEach(node => node.remove());
      Object.keys(pages)
        .filter(key => key.startsWith("plugin:"))
        .forEach(key => delete pages[key]);

      const grouped = new Map();
      (items || []).forEach(item => {
        const key = `plugin:${item.pluginId}:${item.pageId}`;
        pages[key] = [
          item.title || item.pluginName || item.pluginId,
          item.subtitle || item.pluginName || "",
          item.htmlPath,
          item.scriptPath,
          item
        ];
        const group = item.navGroup || "插件功能";
        if (!grouped.has(group)) grouped.set(group, []);
        grouped.get(group).push(Object.assign({}, item, { key }));
      });

      const nav = document.querySelector(".nav");
      if (!nav) return;
      Array.from(grouped.entries()).forEach(([group, entries]) => {
        const wrapper = document.createElement("div");
        wrapper.className = "nav-group";
        wrapper.dataset.pluginAdminNav = "true";
        wrapper.innerHTML = `<span class="nav-group-title">${esc(group)}</span>` + entries.map(item => `
          <button data-nav="${attr(item.key)}">
            <span class="nav-icon">${esc(item.navIcon || "插件")}</span>
            <span>${esc(item.title || item.pluginName || item.pluginId)}</span>
          </button>
        `).join("");
        nav.appendChild(wrapper);
      });
      document.querySelectorAll("[data-plugin-admin-nav] [data-nav]").forEach(button => {
        button.onclick = () => setPage(button.dataset.nav);
      });
    }

    function restoreCurrentHash() {
      if (location.hash.replace("#", "") === state.page) return;
      state.restoringHash = true;
      location.hash = state.page;
    }
    async function canLeaveActivePage() {
      const pageModule = state.activePageModule;
      if (!pageModule?.canLeave) return true;
      return await Promise.resolve(pageModule.canLeave(pageContext(false)));
    }
    async function unmountActivePage() {
      invalidatePageRequests(state.page);
      const pageModule = state.activePageModule;
      state.activePageModule = null;
      if (pageModule?.unmount) {
        await Promise.resolve(pageModule.unmount(pageContext(false))).catch(handleError);
      }
    }
    async function loadPage(page, force) {
      const loadSeq = ++state.pageLoadSeq;
      await unmountActivePage();
      releaseMediaObjectUrls();
      const pageSpec = pages[page] || pages.dashboard;
      const html = await fetchText(pageSpec[2]);
      if (loadSeq !== state.pageLoadSeq) return;
      $("content").innerHTML = html;
      const pageModule = await loadPageModule(pageSpec[3]);
      if (loadSeq !== state.pageLoadSeq) return;
      state.activePageModule = pageModule;
      if (pageModule?.mount) await pageModule.mount(pageContext(force));
    }
    function handleError(error) {
      notify(error.message || String(error), true);
    }
    async function fetchText(path) {
      const response = await fetch(path, { headers: { Accept: "text/html" } });
      if (!response.ok) throw new Error(`加载页面资源失败：${response.status}`);
      return response.text();
    }
    async function loadPageModule(path) {
      if (!pageModuleCache[path]) pageModuleCache[path] = import(path);
      return pageModuleCache[path];
    }
    function pageRoot() {
      return $("content").querySelector("[data-page-root]") || $("content");
    }
    function pageContext(force) {
      return {
        root: pageRoot(),
        page: state.page,
        force,
        api,
        apiBlob,
        state,
        ui,
        invalidate,
        setPage,
        loadPage,
        loadPluginAdminPages,
        pluginAdminApi,
        handleError,
        hydrateMediaImages,
        releaseMediaObjectUrls,
        query,
        beginPageRequest,
        isCurrentPageRequest,
        invalidatePageRequests
      };
    }
    const ui = {
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
      option,
      detailItem,
      prettyJson,
      dataBlock,
      loadingRow,
      jsonBlock,
      withButtonLoading,
      setControlValue,
      setControlChecked,
      mediaImage,
      identity,
      identityMeta,
      platformTag,
      themeSwatch,
      renderTable,
      notify,
      openModal,
      closeModal,
      confirmDanger,
      uniqueValues,
      filterOptions,
      matchesExact,
      matchesContains,
      matchesAnyContains,
      linkParseModeLabel,
      linkParseModeOptions,
      loadSubscriberTargetCandidates,
      clearSubscriberTargetCandidateCache,
      subscriberTargetAddressKey,
      messageTargetChoiceHtml,
      bindTargetSourceToggles,
      selectedTargetPriorityAccount,
      eventTypes,
      blockKinds,
      publisherKey,
      targetKey,
      policyEvents,
      mentionEvents,
      pluginAdminApi
    };

    async function pluginAdminApi(path, options = {}) {
      const spec = pages[state.page] && pages[state.page][4];
      if (!spec || !spec.pluginId) throw new Error("当前页面不是插件后台页面");
      const normalized = String(path || "").replace(/^\/+/, "");
      return api(`/plugins/${encodeURIComponent(spec.pluginId)}/admin/${normalized}`, options);
    }




    function publisherKey(p) {
      return p ? `${p.platformId}:${p.kind}:${p.externalId}` : "-";
    }
    function targetKey(t) {
      return t ? `${t.platformId}:${t.targetKind}:${t.externalId}` : "-";
    }
    function policyEvents(policy) {
      const events = policy && Array.isArray(policy.enabledEvents) ? policy.enabledEvents : [];
      return events.length ? events : ["DYNAMIC"];
    }
    function mentionEvents(policy) {
      return policy && Array.isArray(policy.mentionAllEvents) ? policy.mentionAllEvents : [];
    }

    function beginPageRequest(page = state.page) {
      const key = page || state.page;
      const seq = (state.pageRequestSeq[key] || 0) + 1;
      state.pageRequestSeq[key] = seq;
      return { page: key, seq };
    }

    function isCurrentPageRequest(token) {
      return !!token &&
        state.page === token.page &&
        state.pageRequestSeq[token.page] === token.seq &&
        !!pageRoot()?.isConnected;
    }

    function invalidatePageRequests(page = state.page) {
      const key = page || state.page;
      state.pageRequestSeq[key] = (state.pageRequestSeq[key] || 0) + 1;
    }





    function openModal(title, body, onSubmit, options = {}) {
      if (state.modalCleanup) state.modalCleanup();
      state.modalCleanup = options.cleanup || null;
      state.modalSubmit = onSubmit || null;
      state.modalBusy = false;
      state.modalConfirmHtml = null;
      state.modalLoadingText = options.loadingText || "处理中...";
      $("modalTitle").textContent = title;
      $("modalBody").innerHTML = body;
      $("modalBox").className = "modal " + (options.size || "");
      $("modalConfirm").hidden = options.confirmHidden || !onSubmit;
      $("modalConfirm").className = options.confirmClass || "";
      $("modalConfirm").textContent = options.confirmText || "保存";
      $("modalCancel").textContent = options.cancelText || "取消";
      setModalMessage("", false);
      $("modalBackdrop").hidden = false;
    }
    function closeModal() {
      if (state.modalCleanup) state.modalCleanup();
      state.modalCleanup = null;
      state.modalSubmit = null;
      state.modalBusy = false;
      state.modalConfirmHtml = null;
      state.modalLoadingText = "处理中...";
      $("modalBackdrop").hidden = true;
      $("modalBody").innerHTML = "";
      setModalMessage("", false);
    }
    function confirmDanger(title, message, options = {}) {
      return new Promise(resolve => {
        let settled = false;
        const finish = value => {
          if (settled) return;
          settled = true;
          resolve(value);
        };
        openModal(title || "确认操作", `
          <div class="confirm-panel">
            <strong>${esc(options.heading || title || "确认操作")}</strong>
            <p>${esc(message || "确定执行这个操作吗？")}</p>
          </div>
        `, async () => {
          settled = true;
          resolve(true);
          closeModal();
        }, {
          size: options.size || "small",
          confirmText: options.confirmText || "确认",
          cancelText: options.cancelText || "取消",
          confirmClass: options.confirmClass || "danger",
          cleanup: () => finish(false)
        });
      });
    }
    async function submitModal() {
      if (!state.modalSubmit) return closeModal();
      try {
        const result = state.modalSubmit();
        if (result && typeof result.then === "function") {
          setModalBusy(true, state.modalLoadingText);
          await result;
        }
      } catch (error) {
        setModalMessage(error.message || String(error), true);
      } finally {
        setModalBusy(false);
      }
    }

    function setModalBusy(busy, text = "处理中...") {
      state.modalBusy = !!busy;
      const modalBox = $("modalBox");
      const modalBody = $("modalBody");
      const confirm = $("modalConfirm");
      modalBox.classList.toggle("modal-busy", !!busy);
      if (modalBody) {
        modalBody.querySelectorAll("input, select, textarea, button").forEach(control => {
          control.disabled = !!busy;
        });
      }
      ["modalCancel", "modalClose"].forEach(id => {
        const control = $(id);
        if (control) control.disabled = !!busy;
      });
      if (!confirm) return;
      confirm.disabled = !!busy;
      if (busy) {
        state.modalConfirmHtml = confirm.innerHTML;
        confirm.innerHTML = `<span class="loading-spinner" aria-hidden="true"></span>${esc(text)}`;
      } else if (state.modalConfirmHtml !== null) {
        confirm.innerHTML = state.modalConfirmHtml;
        state.modalConfirmHtml = null;
      }
    }

    function openImagePreview(image) {
      if (!image || image.hidden || image.style.visibility === "hidden") return;
      const src = image.currentSrc || image.src;
      if (!src) return;
      const title = image.dataset.previewTitle || "图片预览";
      openModal(title, `<div class="image-preview"><img src="${attr(src)}" alt="${attr(title)}"></div>`, null, {
        size: "image",
        cancelText: "关闭"
      });
    }




    document.addEventListener("click", async event => {
      const previewImage = event.target.closest("img[data-preview-image]");
      if (previewImage) {
        event.preventDefault();
        openImagePreview(previewImage);
        return;
      }
      const button = event.target.closest("[data-action]");
      if (!button) return;
      const action = button.dataset.action;
      const id = button.dataset.id;
      try {
        if (action === "goto") {
          await setPage(button.dataset.page);
          return;
        }
        if (action === "stop-application") {
          await stopApplication();
          return;
        }
        if (action === "refresh-current") {
          if (!(await canLeaveActivePage())) return;
          const keysByPage = {
            dashboard: ["dashboard"],
            plugins: ["plugins"],
            login: ["platformLogins", "targetPlatformAccounts"],
            subscriptions: ["subscriptions"],
            entities: ["publishers", "subscribers", "subscriptions"],
            messages: ["deliveries"],
            testing: [],
            tasks: ["tasks"],
            logs: [],
            configs: ["configs"],
            system: ["system"],
            about: ["system", "plugins"]
          };
          if (String(state.page || "").startsWith("plugin:")) {
            await loadPluginAdminPages(true);
          }
          invalidate(...(keysByPage[state.page] || []));
          await loadPage(state.page, true);
          notify("已刷新", false);
          return;
        }
        if (state.activePageModule?.handleAction) {
          await state.activePageModule.handleAction(pageContext(false), { action, button, id, event });
        }
      } catch (error) {
        handleError(error);
      }
    });
    document.addEventListener("keydown", event => {
      if (event.key !== "Enter" && event.key !== " ") return;
      const previewImage = event.target.closest?.("img[data-preview-image]");
      if (!previewImage) return;
      event.preventDefault();
      openImagePreview(previewImage);
    });

    document.querySelectorAll("[data-nav]").forEach(button => button.onclick = () => setPage(button.dataset.nav));
    window.addEventListener("hashchange", () => {
      if (state.restoringHash) {
        state.restoringHash = false;
        return;
      }
      const page = location.hash.replace("#", "") || "dashboard";
      if (page !== state.page) setPage(page);
    });
    $("loginForm").onsubmit = async event => {
      event.preventDefault();
      $("loginButton").disabled = true;
      try {
        state.token = $("loginToken").value.trim();
        await api("/dashboard");
        localStorage.setItem(tokenKey, state.token);
        showApp();
      } catch (error) {
        state.token = "";
        setLoginMessage(error.message, true);
      } finally {
        $("loginButton").disabled = false;
      }
    };
    $("logout").onclick = async () => {
      if (await canLeaveActivePage()) logout();
    };
    $("refreshPage").onclick = async () => {
      if (!(await canLeaveActivePage())) return;
      invalidate();
      await loadPage(state.page, true).catch(handleError);
    };
    $("toggleTheme").onclick = toggleTheme;
    $("mobileMenu").onclick = () => document.body.classList.toggle("sidebar-open");
    $("modalClose").onclick = closeModal;
    $("modalCancel").onclick = closeModal;
    $("modalConfirm").onclick = submitModal;
    $("modalBackdrop").addEventListener("click", event => {
      if (event.target === $("modalBackdrop")) {
        event.preventDefault();
        event.stopPropagation();
      }
    });

    applyTheme(state.theme);

    if (state.token) {
      api("/dashboard").then(showApp).catch(() => showLogin("已保存的 token 无效", true));
    } else {
      showLogin("", false);
    }
