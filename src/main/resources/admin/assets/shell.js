const $ = id => document.getElementById(id);
    const tokenKey = "dynamicBotAdminToken";
    const pages = {
      dashboard: ["仪表盘", "运行状态", "/admin/pages/dashboard.html", "/admin/pages/dashboard.js"],
      plugins: ["插件管理", "生命周期与能力", "/admin/pages/plugins.html", "/admin/pages/plugins.js"],
      login: ["平台登录", "动态源账号状态", "/admin/pages/login.html", "/admin/pages/login.js"],
      subscriptions: ["订阅管理", "订阅策略与过滤规则", "/admin/pages/subscriptions.html", "/admin/pages/subscriptions.js"],
      entities: ["发布者与目标", "来源与消息出口", "/admin/pages/entities.html", "/admin/pages/entities.js"],
      logs: ["日志查看", "进程内实时日志", "/admin/pages/logs.html", "/admin/pages/logs.js"],
      configs: ["配置", "主项目与插件配置", "/admin/pages/configs.html", "/admin/pages/configs.js"],
      system: ["系统设置", "运行信息与操作", "/admin/pages/system.html", "/admin/pages/system.js"]
    };
    const state = {
      token: localStorage.getItem(tokenKey) || "",
      page: "dashboard",
      cache: {},
      modalSubmit: null,
      modalCleanup: null,
      logsPaused: false,
      logTimer: null,
      logSince: 0,
      logRows: [],
      mediaObjectUrls: [],
      mediaGeneration: 0,
      selectedConfigId: "",
      currentConfigDetail: null,
      pendingConfigRestarts: {},
      activePageModule: null,
      pageLoadSeq: 0
    };
    const pageModuleCache = {};

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
        PENDING: "等待", SENDING: "发送中", SENT: "已发送",
        SUCCESS: "已登录", CANCELED: "已取消", EXPIRED: "已过期", UNSUPPORTED: "不支持",
        COOKIE: "Cookie", QR_CODE: "二维码",
        GROUP: "群组", USER: "用户", CHANNEL: "频道", OTHER: "其他",
        ADMIN: "管理员",
        DYNAMIC: "动态", LIVE_STARTED: "开播", LIVE_ENDED: "下播",
        BLOCK: "阻止", ALLOW: "允许", MENTION_ALL: "@全体", NONE: "无",
        IMAGE: "图片", VIDEO: "视频", CARD: "卡片", POLL: "投票", ORIGIN: "转发",
        OPEN: "直播中", CLOSE: "未开播", ROUND: "轮播"
      };
      return map[value] || value || "-";
    }
    function eventLabel(value) {
      return Object.fromEntries(eventTypes)[value] || value || "-";
    }
    function pill(value) {
      const text = label(value);
      const cls = ["ACTIVE", "SUCCESS", "SENT", "OPEN"].includes(value) ? "ok"
        : ["FAILED", "EXPIRED", "ERROR"].includes(value) ? "bad"
        : ["PENDING", "SENDING", "LOADED", "CANCELED", "WARN"].includes(value) ? "warn" : "info";
      return `<span class="pill ${cls}">${esc(text)}</span>`;
    }
    function tags(items) {
      const values = (items || []).filter(Boolean);
      return values.length ? `<span class="tag-list">${values.map(item => `<span class="pill">${esc(item)}</span>`).join("")}</span>` : `<span class="sub-line">-</span>`;
    }
    function cell(title, sub) {
      return `<span class="primary-line">${esc(title || "-")}</span><span class="sub-line">${esc(sub || "")}</span>`;
    }
    function previewImageAttrs(className) {
      const tokens = String(className || "").split(/\s+/);
      const title = tokens.includes("header-image") ? "头图预览" : tokens.includes("avatar") ? "头像预览" : "";
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
      const colors = theme.backgroundColors.filter(Boolean);
      const mode = String(theme.mode || "").toUpperCase();
      const modeText = mode === "DARK" ? "暗色" : mode === "LIGHT" ? "亮色" : "主题";
      const modeClass = mode === "DARK" ? "dark" : "light";
      const gradient = colors.length === 1 ? colors[0] : `linear-gradient(90deg, ${colors.map(attr).join(", ")})`;
      return `<div class="theme-cell">
        <span class="theme-swatch ${modeClass}" style="background:${gradient}" title="${attr(`${modeText} ${colors.join("; ")}`)}"></span>
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
            kind: image.dataset.mediaKind || "OTHER"
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
      if (keys.length === 0) state.cache = {};
      keys.forEach(key => delete state.cache[key]);
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
      setPage(location.hash.replace("#", "") || "dashboard");
    }
    function logout(message) {
      state.token = "";
      localStorage.removeItem(tokenKey);
      closeModal();
      showLogin(message || "", !!message);
    }

    function setPage(page) {
      state.page = pages[page] ? page : "dashboard";
      location.hash = state.page;
      document.body.classList.remove("sidebar-open");
      document.querySelectorAll("[data-nav]").forEach(button => {
        button.classList.toggle("active", button.dataset.nav === state.page);
      });
      $("pageTitle").textContent = pages[state.page][0];
      $("pageSubtitle").textContent = pages[state.page][1];
      loadPage(state.page, false).catch(handleError);
    }
    async function unmountActivePage() {
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
        force,
        api,
        apiBlob,
        state,
        ui,
        invalidate,
        setPage,
        loadPage,
        handleError,
        hydrateMediaImages,
        releaseMediaObjectUrls,
        query
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
      mentionEvents
    };




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





    function openModal(title, body, onSubmit, options = {}) {
      if (state.modalCleanup) state.modalCleanup();
      state.modalCleanup = options.cleanup || null;
      state.modalSubmit = onSubmit || null;
      $("modalTitle").textContent = title;
      $("modalBody").innerHTML = body;
      $("modalBox").className = "modal " + (options.size || "");
      $("modalConfirm").hidden = options.confirmHidden || !onSubmit;
      $("modalConfirm").textContent = options.confirmText || "保存";
      $("modalCancel").textContent = options.cancelText || "取消";
      setModalMessage("", false);
      $("modalBackdrop").hidden = false;
    }
    function closeModal() {
      if (state.modalCleanup) state.modalCleanup();
      state.modalCleanup = null;
      state.modalSubmit = null;
      $("modalBackdrop").hidden = true;
      $("modalBody").innerHTML = "";
      setModalMessage("", false);
    }
    async function submitModal() {
      if (!state.modalSubmit) return closeModal();
      $("modalConfirm").disabled = true;
      try {
        await state.modalSubmit();
      } catch (error) {
        setModalMessage(error.message || String(error), true);
      } finally {
        $("modalConfirm").disabled = false;
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
          setPage(button.dataset.page);
          return;
        }
        if (action === "refresh-current") {
          const keysByPage = {
            dashboard: ["dashboard"],
            plugins: ["plugins"],
            login: ["platformLogins"],
            subscriptions: ["subscriptions"],
            entities: ["publishers", "subscribers"],
            logs: [],
            configs: ["configs"],
            system: ["system", "deliveries"]
          };
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
    $("logout").onclick = () => logout();
    $("refreshPage").onclick = async () => {
      invalidate();
      await loadPage(state.page, true).catch(handleError);
    };
    $("mobileMenu").onclick = () => document.body.classList.toggle("sidebar-open");
    $("modalClose").onclick = closeModal;
    $("modalCancel").onclick = closeModal;
    $("modalConfirm").onclick = submitModal;
    $("modalBackdrop").addEventListener("click", event => {
      if (event.target === $("modalBackdrop")) closeModal();
    });
    $("stopApplication").onclick = async () => {
      if (!confirm("确定停止主项目吗？")) return;
      const result = await api("/system/stop", { method: "POST" });
      notify(result.message, false);
    };

    if (state.token) {
      api("/dashboard").then(showApp).catch(() => showLogin("已保存的 token 无效", true));
    } else {
      showLogin("", false);
    }
