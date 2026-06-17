import { createMessageTemplateEditor } from "../assets/message-template-editor.js";

let ctx;
let root;
let api;
let esc;
let attr;
let mediaImage;
let messageTemplateEditor;
let hydrateMediaImages;
let releaseMediaObjectUrls;
let notify;
let openModal;
let closeModal;
let handleError;

const TEST_VIEWS = ["preview", "media", "data"];

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  ({
    esc,
    attr,
    mediaImage,
    notify,
    openModal,
    closeModal,
  } = ctx.ui);
  messageTemplateEditor = createMessageTemplateEditor({ esc, attr, document });
  hydrateMediaImages = ctx.hydrateMediaImages;
  releaseMediaObjectUrls = ctx.releaseMediaObjectUrls;
  handleError = ctx.handleError;
}

function pageState() {
  if (!ctx.state.adminTesting) {
    ctx.state.adminTesting = {
      mode: "MOCK",
      presetId: "",
      presets: [],
      presetsLoaded: false,
      link: "",
      template: "",
      textVariant: "",
      imageCount: "",
      imageRatio: "",
      includeVideoCard: null,
      includeArticleCard: null,
      includeAdditionalCard: null,
      includeRepost: null,
      themeColors: "",
      useCustomJson: false,
      customJson: "",
      result: null,
      loading: false,
      activeView: "preview",
      lastRunAt: "",
      mediaVersion: "",
    };
  }
  return ctx.state.adminTesting;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadPresets();
  applySelectedPresetDefaults(false);
  renderLayout();
  bindControls();
  updateModeVisibility();
  renderResult();
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  releaseMediaObjectUrls();
}

export async function handleAction(nextCtx, { action, id }) {
  bindContext(nextCtx);
  if (action === "set-admin-test-view") {
    const state = pageState();
    if (TEST_VIEWS.includes(id)) {
      state.activeView = id;
      renderResult();
    }
    return true;
  }
  if (action === "reset-admin-test-template") {
    const state = pageState();
    state.template = "";
    const input = root.querySelector("#testingTemplate");
    if (input) input.value = "";
    syncTemplateControls();
    notify("已清空模板覆盖", false);
    return true;
  }
  if (action === "edit-admin-test-template") {
    openTestingTemplateModal();
    return true;
  }
  if (action === "adopt-admin-test-result-template") {
    adoptCurrentResultTemplate();
    return true;
  }
  if (action === "clear-admin-test") {
    const state = pageState();
    state.result = null;
    state.lastRunAt = "";
    state.activeView = "preview";
    renderResult();
    return true;
  }
  if (action === "apply-preset-defaults") {
    readControls();
    applySelectedPresetDefaults(true);
    syncPresetControls();
    syncTemplateControls();
    return true;
  }
  if (action === "fill-admin-test-json") {
    await fillJsonFromCurrentPreset();
    return true;
  }
  if (action === "format-admin-test-json") {
    formatCustomJson();
    return true;
  }
  if (action === "use-result-json") {
    useResultJson();
    return true;
  }
  return false;
}

async function loadPresets() {
  const state = pageState();
  if (state.presetsLoaded) return;
  const response = await api("/test/presets");
  state.presets = response?.presets || [];
  state.presetId = state.presetId || response?.defaultPresetId || state.presets[0]?.id || "";
  state.presetsLoaded = true;
}

function renderLayout() {
  const state = pageState();
  root.innerHTML = `
    <section class="page testing-page">
      <section class="testing-workbench">
        <aside class="panel testing-input-panel">
          <form id="testingForm" class="testing-form">
            <div class="testing-mode-switch" role="radiogroup" aria-label="测试模式">
              ${modeOption("MOCK", "Mock 数据", "预设或自定义 JSON", state.mode)}
              ${modeOption("REAL_LINK", "真实链接", "调用插件解析请求", state.mode)}
            </div>

            <div class="testing-control-group" data-testing-mock-field>
              <div class="testing-section-head">
                <div>
                  <strong>预设场景</strong>
                  <span>优先选组合场景，再按需要覆盖参数。</span>
                </div>
                <button type="button" class="secondary compact" data-action="apply-preset-defaults">恢复</button>
              </div>
              <label class="field">
                <span>场景</span>
                <select id="testingPreset">
                  ${presetOptionsHtml(state.presets, state.presetId)}
                </select>
              </label>
            </div>

            <div class="testing-control-group" data-testing-real-field>
              <div class="testing-section-head">
                <div>
                  <strong>Bilibili 链接</strong>
                  <span>通过已加载插件执行真实解析。</span>
                </div>
              </div>
              <label class="field">
                <span>链接</span>
                <input id="testingLink" value="${attr(state.link)}" placeholder="https://t.bilibili.com/... 或 https://www.bilibili.com/video/...">
              </label>
            </div>

            <div class="testing-control-group" data-testing-mock-field>
              <div class="testing-section-head">
                <div>
                  <strong>场景覆盖</strong>
                  <span>保持真实组合，也可以手动压测边界。</span>
                </div>
              </div>
              <div class="testing-compact-grid">
                <label class="field">
                  <span>文本长度</span>
                  <select id="testingTextVariant">
                    ${option("", "使用预设", state.textVariant)}
                    ${option("SHORT", "极短", state.textVariant)}
                    ${option("NORMAL", "常规", state.textVariant)}
                    ${option("LONG", "长文本", state.textVariant)}
                    ${option("EXTREME", "极长", state.textVariant)}
                  </select>
                </label>
                <label class="field">
                  <span>图片数量</span>
                  <input id="testingImageCount" type="number" min="0" max="9" value="${attr(state.imageCount)}" placeholder="预设">
                </label>
                <label class="field">
                  <span>图片比例</span>
                  <select id="testingImageRatio">
                    ${option("", "使用预设", state.imageRatio)}
                    ${option("MIXED", "横竖混合", state.imageRatio)}
                    ${option("WIDE", "横图", state.imageRatio)}
                    ${option("SQUARE", "方图", state.imageRatio)}
                    ${option("VERTICAL", "竖图", state.imageRatio)}
                  </select>
                </label>
                <label class="field">
                  <span>主题色</span>
                  <input id="testingThemeColors" value="${attr(state.themeColors)}" placeholder="#FE65A6;#4F8FE8">
                </label>
              </div>
              <div class="testing-switch-grid">
                ${switchOption("testingIncludeVideoCard", "视频主卡片", "与图片默认互斥，手动可混入", state.includeVideoCard)}
                ${switchOption("testingIncludeArticleCard", "文章主卡片", "检查小卡片摘要和封面", state.includeArticleCard)}
                ${switchOption("testingIncludeAdditionalCard", "附加卡片", "活动、预约、链接等附加块", state.includeAdditionalCard)}
                ${switchOption("testingIncludeRepost", "转发块", "包含原动态嵌套预览", state.includeRepost)}
              </div>
            </div>

            <details class="testing-fold"${state.template ? " open" : ""}>
              <summary>
                <span>模板覆盖</span>
                <small>${state.template ? "使用页面模板" : "使用当前配置模板"}</small>
              </summary>
              <div class="testing-template-card">
                <textarea id="testingTemplate" hidden>${esc(state.template)}</textarea>
                ${testingTemplateCompactHtml()}
                <div class="testing-template-actions">
                  <button type="button" class="compact message-template-edit-button" data-action="edit-admin-test-template">编辑模板</button>
                  <button id="testingAdoptTemplateButton" type="button" class="secondary compact" data-action="adopt-admin-test-result-template"${resultTemplateAvailable() && !state.template ? "" : " hidden"}>载入当前模板</button>
                  <button id="testingResetTemplateButton" type="button" class="secondary compact" data-action="reset-admin-test-template"${state.template ? "" : " disabled"}>清空覆盖</button>
                </div>
              </div>
            </details>

            <details class="testing-fold" data-testing-mock-field${state.useCustomJson ? " open" : ""}>
              <summary>
                <span>SourceUpdate JSON</span>
                <small>${state.useCustomJson ? "自定义数据已启用" : "可从预设生成后编辑"}</small>
              </summary>
              <label class="testing-json-toggle">
                <input id="testingUseCustomJson" type="checkbox"${state.useCustomJson ? " checked" : ""}>
                <span class="testing-check-mark" aria-hidden="true"></span>
                <span>使用自定义 JSON</span>
              </label>
              <textarea id="testingCustomJson" class="testing-json-editor" ${state.useCustomJson ? "" : "hidden"} placeholder="从当前预设生成 JSON 后可编辑">${esc(state.customJson)}</textarea>
              <div class="testing-json-actions" ${state.useCustomJson ? "" : "hidden"}>
                <button type="button" class="secondary compact" data-action="fill-admin-test-json">从预设生成</button>
                <button type="button" class="secondary compact" data-action="format-admin-test-json">格式化</button>
                <button type="button" class="secondary compact" data-action="use-result-json">使用当前结果</button>
              </div>
            </details>

            <div class="testing-runbar">
              <button id="testingRunButton" type="submit">${state.mode === "REAL_LINK" ? "请求并预览" : "运行预览"}</button>
              <button type="button" class="secondary" data-action="clear-admin-test">清空结果</button>
              <div id="testingStatus" class="testing-status"></div>
            </div>
          </form>
        </aside>

        <section class="testing-output">
          <section class="panel testing-summary-panel">
            <div id="testingRunSummary" class="testing-run-summary"></div>
            <div id="testingOutputTabs" class="testing-view-tabs" role="tablist"></div>
          </section>
          <section class="panel testing-output-panel">
            <div id="testingOutputContent" class="testing-output-content"></div>
          </section>
        </section>
      </section>
    </section>`;
}

function bindControls() {
  const form = root.querySelector("#testingForm");
  if (form) {
    form.onsubmit = event => {
      event.preventDefault();
      runPreview().catch(handleError);
    };
  }
  root.querySelectorAll("input[name='testingMode']").forEach(input => {
    input.onchange = () => {
      readControls();
      updateModeVisibility();
      syncTemplateControls();
      const button = root.querySelector("#testingRunButton");
      if (button) button.textContent = pageState().mode === "REAL_LINK" ? "请求并预览" : "运行预览";
    };
  });
  const presetSelect = root.querySelector("#testingPreset");
  if (presetSelect) {
    presetSelect.onchange = () => {
      readControls();
      applySelectedPresetDefaults(true);
      syncPresetControls();
      syncTemplateControls();
    };
  }
  [
    "#testingTextVariant",
    "#testingImageCount",
    "#testingImageRatio",
    "#testingThemeColors",
    "#testingLink",
    "#testingTemplate",
    "#testingCustomJson",
  ].forEach(selector => {
    const node = root.querySelector(selector);
    if (node) node.oninput = readControls;
  });
  [
    "#testingIncludeVideoCard",
    "#testingIncludeArticleCard",
    "#testingIncludeAdditionalCard",
    "#testingIncludeRepost",
  ].forEach(selector => {
    const node = root.querySelector(selector);
    if (node) node.onchange = readControls;
  });
  const customToggle = root.querySelector("#testingUseCustomJson");
  if (customToggle) {
    customToggle.onchange = () => {
      readControls();
      updateCustomJsonVisibility();
    };
  }
}

function readControls() {
  const state = pageState();
  state.mode = root.querySelector("input[name='testingMode']:checked")?.value || state.mode || "MOCK";
  state.presetId = root.querySelector("#testingPreset")?.value || state.presetId;
  state.link = root.querySelector("#testingLink")?.value.trim() || "";
  state.template = root.querySelector("#testingTemplate")?.value || "";
  state.textVariant = root.querySelector("#testingTextVariant")?.value || "";
  state.imageCount = root.querySelector("#testingImageCount")?.value.trim() || "";
  state.imageRatio = root.querySelector("#testingImageRatio")?.value || "";
  state.themeColors = root.querySelector("#testingThemeColors")?.value.trim() || "";
  state.includeVideoCard = !!root.querySelector("#testingIncludeVideoCard")?.checked;
  state.includeArticleCard = !!root.querySelector("#testingIncludeArticleCard")?.checked;
  state.includeAdditionalCard = !!root.querySelector("#testingIncludeAdditionalCard")?.checked;
  state.includeRepost = !!root.querySelector("#testingIncludeRepost")?.checked;
  state.useCustomJson = !!root.querySelector("#testingUseCustomJson")?.checked;
  const customJsonEditor = root.querySelector("#testingCustomJson");
  state.customJson = customJsonEditor ? customJsonEditor.value : (state.customJson || "");
}

function syncPresetControls() {
  const state = pageState();
  setControlValue("#testingPreset", state.presetId);
  setControlValue("#testingTextVariant", state.textVariant);
  setControlValue("#testingImageCount", state.imageCount);
  setControlValue("#testingImageRatio", state.imageRatio);
  setControlValue("#testingThemeColors", state.themeColors);
  setControlChecked("#testingIncludeVideoCard", state.includeVideoCard);
  setControlChecked("#testingIncludeArticleCard", state.includeArticleCard);
  setControlChecked("#testingIncludeAdditionalCard", state.includeAdditionalCard);
  setControlChecked("#testingIncludeRepost", state.includeRepost);
}

function syncTemplateControls() {
  const state = pageState();
  setControlValue("#testingTemplate", state.template);
  const stats = root.querySelector("#testingTemplateStats");
  if (stats) stats.textContent = testingTemplateStatsText();
  const inline = root.querySelector("#testingTemplateInline");
  if (inline) {
    const inlineText = testingTemplateInlineTextForCard();
    inline.textContent = inlineText;
    inline.title = inlineText;
  }
  const source = root.querySelector("#testingTemplateSource");
  if (source) source.textContent = testingTemplateSourceText();
  const adopt = root.querySelector("#testingAdoptTemplateButton");
  if (adopt) adopt.hidden = !(resultTemplateAvailable() && !state.template);
  const reset = root.querySelector("#testingResetTemplateButton");
  if (reset) reset.disabled = !state.template;
}

function setControlValue(selector, value) {
  const node = root.querySelector(selector);
  if (node) node.value = value ?? "";
}

function setControlChecked(selector, checked) {
  const node = root.querySelector(selector);
  if (node) node.checked = checked === true;
}

function updateModeVisibility() {
  const state = pageState();
  const real = state.mode === "REAL_LINK";
  root.querySelectorAll("[data-testing-mock-field]").forEach(node => node.hidden = real);
  root.querySelectorAll("[data-testing-real-field]").forEach(node => node.hidden = !real);
  updateCustomJsonVisibility();
}

function updateCustomJsonVisibility() {
  const state = pageState();
  const editor = root.querySelector("#testingCustomJson");
  const actions = root.querySelector(".testing-json-actions");
  if (editor) editor.hidden = !state.useCustomJson;
  if (actions) actions.hidden = !state.useCustomJson;
}

function applySelectedPresetDefaults(force) {
  const state = pageState();
  const preset = selectedPreset();
  if (!preset) return;
  const defaults = preset.defaultOptions || {};
  if (force || state.textVariant === "") state.textVariant = defaults.textVariant || "";
  if (force || state.imageCount === "") state.imageCount = defaults.imageCount == null ? "" : String(defaults.imageCount);
  if (force || state.imageRatio === "") state.imageRatio = defaults.imageRatio || "";
  if (force || !state.themeColors) state.themeColors = defaults.themeColors || "";
  if (force || state.includeVideoCard == null) state.includeVideoCard = defaults.includeVideoCard === true;
  if (force || state.includeArticleCard == null) state.includeArticleCard = defaults.includeArticleCard === true;
  if (force || state.includeAdditionalCard == null) state.includeAdditionalCard = defaults.includeAdditionalCard === true;
  if (force || state.includeRepost == null) state.includeRepost = defaults.includeRepost === true;
}

async function runPreview() {
  const state = pageState();
  readControls();
  state.loading = true;
  renderStatus();
  renderRunSummary();
  const button = root.querySelector("#testingRunButton");
  if (button) {
    button.disabled = true;
    button.textContent = state.mode === "REAL_LINK" ? "请求中..." : "生成中...";
  }
  try {
    const result = await api("/test/preview", {
      method: "POST",
      body: JSON.stringify(buildPreviewRequest()),
    });
    state.result = result;
    state.activeView = "preview";
    state.lastRunAt = new Date().toLocaleString("zh-CN", { hour12: false });
    state.mediaVersion = String(Date.now());
    renderResult();
    notify("测试预览已生成", false);
  } finally {
    state.loading = false;
    renderStatus();
    renderRunSummary();
    if (button?.isConnected) {
      button.disabled = false;
      button.textContent = state.mode === "REAL_LINK" ? "请求并预览" : "运行预览";
    }
  }
}

function buildPreviewRequest() {
  const state = pageState();
  const request = {
    mode: state.mode,
    presetId: state.presetId,
    presetOptions: presetOptionsRequest(),
    link: state.link,
    template: state.template,
  };
  if (state.mode === "MOCK" && state.useCustomJson) {
    request.customUpdate = parseCustomJson();
  }
  return request;
}

function presetOptionsRequest() {
  const state = pageState();
  const imageCount = state.imageCount === "" ? null : Number(state.imageCount);
  return {
    textVariant: state.textVariant || null,
    imageCount: Number.isFinite(imageCount) ? Math.max(0, Math.min(9, imageCount)) : null,
    imageRatio: state.imageRatio || null,
    includeVideoCard: state.includeVideoCard,
    includeArticleCard: state.includeArticleCard,
    includeAdditionalCard: state.includeAdditionalCard,
    includeRepost: state.includeRepost,
    themeColors: state.themeColors || null,
  };
}

function parseCustomJson() {
  const text = pageState().customJson.trim();
  if (!text) throw new Error("请先填写 SourceUpdate JSON");
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`SourceUpdate JSON 格式无效：${error.message}`);
  }
}

async function fillJsonFromCurrentPreset() {
  const state = pageState();
  readControls();
  const result = await api("/test/preview", {
    method: "POST",
    body: JSON.stringify({
      mode: "MOCK",
      presetId: state.presetId,
      presetOptions: presetOptionsRequest(),
      template: state.template,
    }),
  });
  if (!result.update) throw new Error("当前预设没有生成 SourceUpdate");
  state.result = result;
  state.activeView = "data";
  state.lastRunAt = new Date().toLocaleString("zh-CN", { hour12: false });
  state.customJson = JSON.stringify(result.update, null, 2);
  state.useCustomJson = true;
  renderLayout();
  bindControls();
  updateModeVisibility();
  renderResult();
  notify("已从预设生成可编辑 JSON", false);
}

function formatCustomJson() {
  const state = pageState();
  readControls();
  const parsed = parseCustomJson();
  state.customJson = JSON.stringify(parsed, null, 2);
  const editor = root.querySelector("#testingCustomJson");
  if (editor) editor.value = state.customJson;
  notify("JSON 已格式化", false);
}

function useResultJson() {
  const state = pageState();
  const update = state.result?.update;
  if (!update) throw new Error("当前结果没有 SourceUpdate，可先运行 Mock 预设或真实动态链接");
  state.mode = "MOCK";
  state.useCustomJson = true;
  state.customJson = JSON.stringify(update, null, 2);
  renderLayout();
  bindControls();
  updateModeVisibility();
  renderResult();
  notify("已填入当前结果 JSON", false);
}

function adoptCurrentResultTemplate() {
  const state = pageState();
  const template = state.result?.template || "";
  if (!template.trim()) throw new Error("当前结果没有可载入的模板");
  state.template = template;
  syncTemplateControls();
  notify("已载入当前配置模板，可继续编辑后重新运行", false);
}

function openTestingTemplateModal() {
  readControls();
  const state = pageState();
  const kind = currentTemplateKind();
  const value = testingTemplateEditorInitialValue();
  openModal("编辑测试模板", messageTemplateEditor.editorHtml({
    value,
    kind,
    extraClass: "testing-template-editor-modal",
  }), async () => {
    const input = document.getElementById("messageTemplateEditorInput");
    state.template = input?.value || "";
    syncTemplateControls();
    closeModal();
    notify("模板覆盖已更新，重新运行后生效", false);
  }, { size: "wide", confirmText: "应用到测试" });
  messageTemplateEditor.bindEditor({ kind });
}

function testingTemplateCompactHtml() {
  const inlineText = testingTemplateInlineTextForCard();
  return `
    <div class="testing-template-compact">
      <span id="testingTemplateStats" class="message-template-stats">${esc(testingTemplateStatsText())}</span>
      <div id="testingTemplateInline" class="message-template-inline" title="${attr(inlineText)}">${esc(inlineText)}</div>
      <span id="testingTemplateSource" class="testing-template-source">${esc(testingTemplateSourceText())}</span>
    </div>`;
}

function testingTemplateStatsText() {
  const state = pageState();
  const value = testingTemplateDisplayValue();
  if (!value.trim()) return state.template ? "空模板" : "未覆盖";
  return messageTemplateStats(value, currentTemplateKind());
}

function testingTemplateInlineTextForCard() {
  const state = pageState();
  const value = testingTemplateDisplayValue();
  if (!value.trim()) {
    return state.template ? "空模板" : "运行时使用当前配置模板";
  }
  const prefix = state.template ? "覆盖：" : "当前：";
  return `${prefix}${messageTemplateInlineText(value, currentTemplateKind())}`;
}

function testingTemplateSourceText() {
  const state = pageState();
  if (state.template) return `页面覆盖 · ${templateKindLabel(currentTemplateKind())}`;
  if (resultTemplateAvailable()) return `当前结果 · ${templateSourceLabel(state.result.templateSource)}`;
  return `自动选择 · ${templateKindLabel(currentTemplateKind())}`;
}

function testingTemplateDisplayValue() {
  const state = pageState();
  return state.template || state.result?.template || "";
}

function testingTemplateEditorInitialValue() {
  return testingTemplateDisplayValue();
}

function resultTemplateAvailable() {
  const template = pageState().result?.template || "";
  return template.trim().length > 0;
}

function currentTemplateKind() {
  const state = pageState();
  const result = state.result;
  if (result?.templateSource === "PUSH_TEMPLATE_LIVE_STARTED") return "LIVE_STARTED";
  if (result?.templateSource === "PUSH_TEMPLATE_LIVE_ENDED") return "LIVE_ENDED";
  if (result?.templateSource === "LINK_PARSING_TEMPLATE" || result?.resolutionType === "PREVIEW") {
    return linkTemplateKind(result?.parsedLink);
  }
  if (state.mode === "REAL_LINK") return linkTemplateKind(result?.parsedLink);
  const eventType = String(selectedPreset()?.eventType || "").toUpperCase();
  if (eventType === "LIVE_STARTED") return "LIVE_STARTED";
  if (eventType === "LIVE_ENDED") return "LIVE_ENDED";
  return "DYNAMIC";
}

function linkTemplateKind(parsedLink) {
  const kind = String(parsedLink?.kind || "").toUpperCase();
  if (kind.includes("LIVE")) return "LINK_LIVE";
  if (kind.includes("USER") || kind.includes("AUTHOR") || kind.includes("PUBLISHER")) return "LINK_USER";
  return "LINK_VIDEO";
}

function templateKindLabel(kind) {
  return messageTemplateEditor.templateKindLabel(kind);
}

function messageTemplateStats(template, kind) {
  return messageTemplateEditor.messageTemplateStats(template, kind);
}

function messageTemplateInlineText(value, kind) {
  return messageTemplateEditor.messageTemplateInlineText(value, kind);
}

function renderStatus() {
  const target = root.querySelector("#testingStatus");
  if (!target) return;
  const state = pageState();
  if (state.loading) {
    target.innerHTML = `<span class="loading-spinner" aria-hidden="true"></span><span class="pill warn">运行中</span>`;
    return;
  }
  const result = state.result;
  if (!result) {
    target.innerHTML = `<span class="pill info">未运行</span>`;
    return;
  }
  target.innerHTML = `${statusPill(result.status)}<span>${esc(result.message || "")}</span>`;
}

function renderResult() {
  releaseMediaObjectUrls();
  syncTemplateControls();
  renderStatus();
  renderRunSummary();
  renderTabs();
  renderOutputContent();
  hydrateMediaImages(root).catch(handleError);
}

function renderRunSummary() {
  const target = root.querySelector("#testingRunSummary");
  if (!target) return;
  const state = pageState();
  const result = state.result;
  if (!result) {
    target.innerHTML = `
      <div class="testing-summary-empty">
        <strong>${state.loading ? "正在生成预览" : "还没有测试结果"}</strong>
        <span>${state.mode === "REAL_LINK" ? "填写真实链接后运行，会展示插件解析和模板渲染结果。" : "选择预设后运行，会展示真实模板消息和绘图产物。"}</span>
      </div>`;
    return;
  }
  const batches = result.batches || [];
  const mediaItems = allMediaItems(result);
  const meta = [
    ["状态", statusLabel(result.status)],
    ["模式", modeLabel(result.mode)],
    ["类型", result.resolutionType || "-"],
    ["模板", templateSourceLabel(result.templateSource)],
    ["耗时", `${Number(result.elapsedMillis || 0)} ms`],
    ["消息", `${batches.length} 批 / ${contentCount(batches)} 块`],
    ["媒体", `${mediaItems.length} 项`],
    ["时间", state.lastRunAt || "-"],
  ];
  target.innerHTML = `
    <div class="testing-summary-grid">
      ${meta.map(([key, value]) => `
        <div class="testing-summary-item">
          <span>${esc(key)}</span>
          <strong>${esc(value)}</strong>
        </div>
      `).join("")}
    </div>
    ${result.warnings && result.warnings.length ? `
      <div class="testing-warning-list">
        ${result.warnings.map(item => `<span>${esc(item)}</span>`).join("")}
      </div>` : ""}`;
}

function renderTabs() {
  const target = root.querySelector("#testingOutputTabs");
  if (!target) return;
  const state = pageState();
  const result = state.result;
  if (!result) {
    target.innerHTML = "";
    return;
  }
  const labels = {
    preview: "预览",
    media: `媒体 · ${allMediaItems(result).length}`,
    data: "数据",
  };
  target.innerHTML = TEST_VIEWS.map(view => `
    <button type="button" class="testing-view-tab${state.activeView === view ? " active" : ""}" data-action="set-admin-test-view" data-id="${attr(view)}" aria-pressed="${state.activeView === view ? "true" : "false"}">
      ${esc(labels[view])}
    </button>
  `).join("");
}

function renderOutputContent() {
  const target = root.querySelector("#testingOutputContent");
  if (!target) return;
  const state = pageState();
  const result = state.result;
  if (!result) {
    target.innerHTML = renderEmptyPreview();
    return;
  }
  if (state.activeView === "media") {
    target.innerHTML = renderMediaView(result);
    return;
  }
  if (state.activeView === "data") {
    target.innerHTML = renderDataView(result);
    return;
  }
  target.innerHTML = renderPreviewView(result);
}

function renderEmptyPreview() {
  return `
    <div class="testing-start-screen">
      <div>
        <strong>预览结果会出现在这里</strong>
        <span>消息以聊天流展示，绘图产物单独放大查看，媒体和 JSON 可在上方切换。</span>
      </div>
    </div>`;
}

function renderPreviewView(result) {
  return `
    <div class="testing-preview-stage">
      <div class="testing-phone-shell">
        <div class="testing-phone-head">
          <div>
            <strong>推送消息</strong>
            <span>${esc(modeLabel(result.mode))} · ${esc(templateSourceLabel(result.templateSource))}</span>
          </div>
          ${statusPill(result.status)}
        </div>
        <div class="testing-chat">
          ${renderMessagePreview(result)}
        </div>
      </div>
      <div class="testing-draw-viewer">
        <div class="testing-output-head">
          <div>
            <strong>绘图预览</strong>
            <span>${result.drawImage ? "模板中的 {draw} 产物" : "当前结果没有绘图图片"}</span>
          </div>
        </div>
        ${renderDrawPreview(result)}
      </div>
    </div>`;
}

function renderMessagePreview(result) {
  const batches = result.batches || [];
  if (!batches.length) {
    return `<div class="testing-chat-empty">模板没有生成可预览消息</div>`;
  }
  return batches.map((batch, index) => `
    <div class="testing-chat-batch">
      ${batches.length > 1 ? `<div class="testing-chat-batch-label">消息 ${index + 1}</div>` : ""}
      ${renderMessageBatch(batch.content || [], result, `batch[${index}]`)}
    </div>
  `).join("");
}

function renderMessageBatch(contents, result, source, nested = false) {
  const body = (contents || [])
    .map(content => renderMessagePart(content, result, source))
    .filter(Boolean)
    .join("");
  if (!body) {
    return `<div class="testing-message${nested ? " nested" : ""}"><span class="testing-message-muted">空消息</span></div>`;
  }
  return `<div class="testing-message${nested ? " nested" : ""}">${body}</div>`;
}

function renderMessagePart(content, result, source) {
  const type = contentType(content);
  if (type === "TEXT") {
    const text = content.fallbackText || "";
    if (!text.trim()) return "";
    return `<pre class="testing-message-text">${esc(text)}</pre>`;
  }
  if (type === "IMAGE") {
    const image = content.image || {};
    const caption = content.altText || image.alt || "";
    return `<figure class="testing-message-image">
      ${previewMediaImage(image.uri, "testing-chat-image", mediaPlatform(result), image.kind || "IMAGE", content.altText || image.alt || "图片")}
      ${caption ? `<figcaption>${esc(caption)}</figcaption>` : ""}
    </figure>`;
  }
  if (type === "VIDEO") {
    const video = content.video || {};
    return renderAttachment("视频", content.altText || video.uri || "视频", video.uri);
  }
  if (type === "AUDIO") {
    const audio = content.audio || {};
    return renderAttachment("音频", content.altText || audio.uri || "音频", audio.uri);
  }
  if (type === "FORWARD") {
    const nodes = content.nodes || [];
    const summary = content.summary || (nodes.length ? `共 ${nodes.length} 个节点` : "");
    return `<div class="testing-message-forward">
      <div class="testing-forward-head">
        <span class="testing-forward-kicker">合并转发 · ${nodes.length} 条</span>
        <strong>${esc(content.title || "合并转发")}</strong>
        ${summary ? `<span>${esc(summary)}</span>` : ""}
      </div>
      <div class="testing-forward-nodes">
        ${nodes.map((node, index) => renderForwardNode(node, index, result, source, nodes.length)).join("")}
      </div>
    </div>`;
  }
  return renderAttachment(type || "未知", content.fallbackText || "-", "");
}

function renderForwardNode(node, index, result, source, total) {
  return `
    <div class="testing-forward-node">
      <div class="testing-forward-node-head">
        <strong>${esc(node.senderName || `节点 ${index + 1}`)}</strong>
        <span>${index + 1}${total ? ` / ${total}` : ""}</span>
      </div>
      ${(node.batches || []).map(batch => `
        <div class="testing-forward-node-body">
          ${renderMessageBatch(batch.content || [], result, `${source}.forward`, true)}
        </div>
      `).join("")}
    </div>`;
}

function renderAttachment(kind, title, uri) {
  return `<div class="testing-message-attachment">
    <span class="testing-attachment-icon">${esc(kind)}</span>
    <div>
      <strong>${esc(title || kind)}</strong>
      ${uri ? `<small>${esc(uri)}</small>` : ""}
    </div>
  </div>`;
}

function renderDrawPreview(result) {
  const image = result.drawImage;
  if (!image?.uri) {
    return `<div class="testing-draw-empty">
      <strong>没有绘图产物</strong>
      <span>如果模板中包含 {draw}，这里会展示最终生成图片。</span>
    </div>`;
  }
  return `
    <div class="testing-draw-frame">
      ${previewMediaImage(image.uri, "testing-draw-image", mediaPlatform(result), image.kind || "IMAGE", "绘图预览")}
    </div>`;
}

function renderMediaView(result) {
  const items = allMediaItems(result);
  if (!items.length) {
    return `<div class="testing-start-screen"><div><strong>没有媒体项</strong><span>当前模板结果没有图片、封面或头像媒体。</span></div></div>`;
  }
  return `
    <div class="testing-media-board">
      ${items.map((item, index) => `
        <div class="testing-media-tile">
          <div class="testing-media-thumb-wrap">
            ${imageLikeKind(item.kind)
              ? previewMediaImage(item.uri, "testing-media-thumb", mediaPlatform(result), item.kind || "IMAGE", item.alt || `媒体 ${index + 1}`)
              : `<div class="testing-media-file">${statusPill(item.kind || "MEDIA")}</div>`}
          </div>
          <div class="testing-media-info">
            <strong>${esc(item.alt || item.kind || `媒体 ${index + 1}`)}</strong>
            <span>${esc(item.source || "")}</span>
            <small>${esc(item.uri || "")}</small>
          </div>
        </div>
      `).join("")}
    </div>`;
}

function renderDataView(result) {
  const parsedLink = result.parsedLink;
  const preset = selectedPreset();
  const meta = [
    ["模式", modeLabel(result.mode)],
    ["状态", statusLabel(result.status)],
    ["结果类型", result.resolutionType || "-"],
    ["预设", result.mode === "MOCK" ? (preset?.name || "-") : "-"],
    ["模板来源", templateSourceLabel(result.templateSource)],
    ["耗时", `${Number(result.elapsedMillis || 0)} ms`],
  ];
  return `
    <div class="testing-data-view">
      <div class="testing-meta-grid">
        ${meta.map(([key, value]) => detailItem(key, value)).join("")}
        ${parsedLink ? detailItem("解析平台", platformValue(parsedLink.platformId)) : ""}
        ${parsedLink ? detailItem("解析类型", parsedLink.kind || "-") : ""}
        ${parsedLink ? detailItem("目标 ID", parsedLink.targetId || "-", true) : ""}
      </div>
      ${dataBlock("实际模板", result.template || "插件直接返回消息，未使用模板。", true)}
      ${jsonBlock("ParsedLink", result.parsedLink)}
      ${jsonBlock("SourceUpdate", result.update)}
      ${jsonBlock("LinkPreview", result.preview)}
    </div>`;
}

function presetOptionsHtml(presets, selectedId) {
  const groups = new Map();
  (presets || []).forEach(preset => {
    const group = preset.group || "其他";
    if (!groups.has(group)) groups.set(group, []);
    groups.get(group).push(preset);
  });
  return Array.from(groups.entries()).map(([group, items]) => `
    <optgroup label="${attr(group)}">
      ${items.map(preset => `<option value="${attr(preset.id)}"${preset.id === selectedId ? " selected" : ""}>${esc(preset.name)}</option>`).join("")}
    </optgroup>
  `).join("");
}

function selectedPreset() {
  const state = pageState();
  return (state.presets || []).find(item => item.id === state.presetId) || state.presets?.[0] || null;
}

function modeOption(value, title, desc, selected) {
  return `<label class="testing-mode-option">
    <input type="radio" name="testingMode" value="${attr(value)}"${value === selected ? " checked" : ""}>
    <span><b>${esc(title)}</b><small>${esc(desc)}</small></span>
  </label>`;
}

function switchOption(id, title, desc, checked) {
  return `<label class="testing-switch">
    <input id="${attr(id)}" type="checkbox"${checked ? " checked" : ""}>
    <span class="testing-check-mark" aria-hidden="true"></span>
    <span class="testing-switch-copy">
      <b>${esc(title)}</b>
      <small>${esc(desc)}</small>
    </span>
  </label>`;
}

function option(value, text, selected) {
  return `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`;
}

function statusPill(value) {
  const normalized = String(value || "").toUpperCase();
  const cls = normalized === "OK" ? "ok"
    : normalized === "WARN" ? "warn"
    : normalized === "FAILED" || normalized === "ERROR" ? "bad"
    : "info";
  return `<span class="pill ${cls}">${esc(statusLabel(normalized))}</span>`;
}

function statusLabel(value) {
  const map = {
    OK: "正常",
    WARN: "警告",
    FAILED: "失败",
    VIDEO: "视频",
    AUDIO: "音频",
    IMAGE: "图片",
    COVER: "封面",
    AVATAR: "头像",
  };
  return map[value] || value || "-";
}

function modeLabel(value) {
  return value === "REAL_LINK" ? "真实链接" : "Mock 数据";
}

function templateSourceLabel(value) {
  const map = {
    PUSH_TEMPLATE_DYNAMIC: "动态推送模板",
    PUSH_TEMPLATE_LIVE_STARTED: "开播推送模板",
    PUSH_TEMPLATE_LIVE_ENDED: "下播推送模板",
    LINK_PARSING_TEMPLATE: "链接解析模板",
    OVERRIDE: "页面覆盖模板",
    PLUGIN_MESSAGE: "插件消息",
    NONE: "未使用模板",
  };
  return map[value] || value || "-";
}

function detailItem(title, value, mono = false) {
  return `<div class="plugin-detail-item">
    <span>${esc(title)}</span>
    <strong class="${mono ? "mono" : ""}">${esc(value ?? "-")}</strong>
  </div>`;
}

function dataBlock(title, value, open = false) {
  return `<details class="testing-data-block"${open ? " open" : ""}>
    <summary>${esc(title)}</summary>
    <pre>${esc(value || "")}</pre>
  </details>`;
}

function jsonBlock(title, value) {
  if (!value) return "";
  return dataBlock(title, prettyJson(value));
}

function prettyJson(value) {
  try {
    return JSON.stringify(value, null, 2);
  } catch (error) {
    return String(value);
  }
}

function contentType(content) {
  return content?.type || (content?.image ? "IMAGE" : content?.video ? "VIDEO" : content?.audio ? "AUDIO" : content?.nodes ? "FORWARD" : "TEXT");
}

function mediaPlatform(result) {
  return platformValue(result?.parsedLink?.platformId)
    || platformValue(result?.update?.key?.publisherKey?.platformId)
    || platformValue(result?.preview?.platformId)
    || "admin";
}

function platformValue(value) {
  if (!value) return "";
  if (typeof value === "string") return value;
  return value.value || "";
}

function imageLikeKind(kind) {
  return ["IMAGE", "COVER", "AVATAR", "EMOJI", "OTHER"].includes(String(kind || "").toUpperCase());
}

function allMediaItems(result) {
  const draw = result?.drawImage ? [{
    kind: result.drawImage.kind || "IMAGE",
    uri: result.drawImage.uri,
    source: "draw",
    alt: "绘图产物",
  }] : [];
  return uniqueMedia(draw.concat(result?.media || []));
}

function uniqueMedia(items) {
  const seen = new Set();
  return (items || []).filter(item => {
    if (!item || !item.uri) return false;
    const key = `${item.kind || ""}\u0000${item.uri}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function contentCount(batches) {
  let count = 0;
  function visit(content) {
    count += 1;
    if (contentType(content) !== "FORWARD") return;
    (content.nodes || []).forEach(node => {
      (node.batches || []).forEach(batch => {
        (batch.content || []).forEach(visit);
      });
    });
  }
  (batches || []).forEach(batch => (batch.content || []).forEach(visit));
  return count;
}

function previewMediaImage(uri, className, platformId, kind, title) {
  const html = mediaImage(uri, className, platformId, kind);
  if (!uri || !html.startsWith("<img ")) return html;
  const versionAttr = pageState().mediaVersion ? ` data-media-version="${attr(pageState().mediaVersion)}"` : "";
  return html.replace("<img ", `<img data-preview-image="true" data-preview-title="${attr(title || "图片预览")}" title="点击预览" tabindex="0"${versionAttr} `);
}
