const AUTO_REFRESH_MS = 5000;

let ctx;
let root;
let api;
let state;
let query;
let handleError;
let esc;
let attr;
let fmtTime;
let label;
let pill;
let cell;
let renderTable;
let notify;
let openModal;
let confirmDanger;
let beginPageRequest;
let isCurrentPageRequest;
let invalidatePageRequests;
let refreshTimer = null;

function bindContext(nextCtx) {
  ctx = nextCtx;
  root = ctx.root;
  api = ctx.api;
  state = ctx.state;
  query = ctx.query;
  handleError = ctx.handleError;
  ({
    esc,
    attr,
    fmtTime,
    label,
    pill,
    cell,
    renderTable,
    notify,
    openModal,
    confirmDanger,
  } = ctx.ui);
  beginPageRequest = ctx.beginPageRequest;
  isCurrentPageRequest = ctx.isCurrentPageRequest;
  invalidatePageRequests = ctx.invalidatePageRequests;
}

function pageRoot() {
  return root;
}

function pageQuery(selector) {
  return pageRoot().querySelector(selector);
}

function taskFilters() {
  if (!state.taskFilters) {
    state.taskFilters = {
      ownerType: "",
      status: "",
      scheduleType: "",
      q: "",
    };
  }
  return state.taskFilters;
}

export async function mount(nextCtx) {
  bindContext(nextCtx);
  await loadTasksPage(ctx.force);
}

export async function unmount(nextCtx) {
  bindContext(nextCtx);
  stopAutoRefresh();
  invalidatePageRequests("tasks");
  document.getElementById("content")?.classList.remove("content-tasks");
}

export async function handleAction(nextCtx, { action, button, id }) {
  bindContext(nextCtx);
  if (action === "apply-task-filter") {
    readTaskFilterControls();
    renderTasks();
    return true;
  }
  if (action === "refresh-tasks") {
    await refreshTasks(button, true);
    notify("任务状态已刷新", false);
    return true;
  }
  if (action === "task-detail") {
    openTaskDetail(id);
    return true;
  }
  if (action === "task-start" || action === "task-stop" || action === "task-restart") {
    await operateTask(action, id, button);
    return true;
  }
  return false;
}

async function loadTasksPage(force) {
  document.getElementById("content")?.classList.add("content-tasks");
  renderLayout();
  bindTaskControls();
  if (force || !state.cache.tasks) {
    await refreshTasks(null, true);
  } else {
    state.taskRows = state.cache.tasks.tasks || [];
    state.taskResponse = state.cache.tasks;
    renderTasks();
    renderTaskStatus();
  }
  startAutoRefresh();
}

function renderLayout() {
  const filters = taskFilters();
  pageRoot().innerHTML = `
    <section class="page tasks-page">
      <section class="panel full task-hero-panel">
        <div class="panel-head task-panel-head">
          <div>
            <h2>任务查看</h2>
            <p>观察主项目和插件注册的定时任务，必要时停止、恢复或重启已有任务。</p>
          </div>
          <div id="taskStatus" class="task-record-status"></div>
        </div>
        <div id="taskSummary" class="task-summary-grid"></div>
      </section>

      <section class="panel full task-table-panel">
        <div class="entity-filter-bar task-filter-bar">
          <span class="entity-filter-title">筛选</span>
          <div class="entity-filter-controls">
            <select id="taskFilterOwner" data-task-filter="ownerType">
              ${ownerOptions(filters.ownerType)}
            </select>
            <select id="taskFilterStatus" data-task-filter="status">
              ${statusOptions(filters.status)}
            </select>
            <select id="taskFilterSchedule" data-task-filter="scheduleType">
              ${scheduleOptions(filters.scheduleType)}
            </select>
            <input id="taskFilterKeyword" data-task-filter="q" value="${attr(filters.q)}" placeholder="名称 / 描述 / 插件 / 错误">
            <button type="button" class="entity-filter-clear" data-action="apply-task-filter">筛选</button>
            <button type="button" class="choice-refresh-button compact" data-action="refresh-tasks">刷新</button>
          </div>
          <span id="taskFilterSummary" class="entity-filter-summary"></span>
        </div>
        <div id="tasksTable" class="tasks-table-host"></div>
      </section>
    </section>`;
  renderTaskStatus();
}

function ownerOptions(selected) {
  return [
    ["", "全部来源"],
    ["MAIN", "主项目"],
    ["PLUGIN", "插件"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function statusOptions(selected) {
  return [
    ["", "全部状态"],
    ["RUNNING", "运行中"],
    ["COMPLETED", "已完成"],
    ["CANCELLED", "已取消"],
    ["FAILED", "失败"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function scheduleOptions(selected) {
  return [
    ["", "全部调度"],
    ["FIXED_DELAY", "固定间隔"],
    ["CRON", "Cron"],
    ["ONCE", "一次性"],
  ].map(([value, text]) => `<option value="${attr(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("");
}

function bindTaskControls() {
  pageRoot().querySelectorAll("[data-task-filter]").forEach(control => {
    control.onkeydown = event => {
      if (event.key === "Enter") {
        event.preventDefault();
        readTaskFilterControls();
        renderTasks();
      }
    };
    if (control.tagName === "SELECT") {
      control.onchange = () => {
        readTaskFilterControls();
        renderTasks();
      };
    }
  });
}

function readTaskFilterControls() {
  const filters = taskFilters();
  filters.ownerType = pageQuery("#taskFilterOwner")?.value.trim() || "";
  filters.status = pageQuery("#taskFilterStatus")?.value.trim() || "";
  filters.scheduleType = pageQuery("#taskFilterSchedule")?.value.trim() || "";
  filters.q = pageQuery("#taskFilterKeyword")?.value.trim() || "";
}

async function refreshTasks(button, force) {
  const request = beginPageRequest("tasks");
  const originalText = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = "加载中...";
  }
  state.tasksLoading = true;
  renderTaskStatus();
  try {
    const response = await api("/tasks");
    if (!isCurrentPageRequest(request)) return;
    state.cache.tasks = response;
    state.taskResponse = response;
    state.taskRows = response.tasks || [];
    renderTasks();
  } finally {
    if (isCurrentPageRequest(request)) {
      state.tasksLoading = false;
      renderTaskStatus();
    }
    if (button?.isConnected) {
      button.disabled = false;
      if (originalText) button.textContent = originalText;
    }
  }
}

function renderTasks() {
  const target = pageQuery("#tasksTable");
  if (!target) return;
  const rows = filteredTasks();
  renderTaskSummary();
  renderTaskFilterSummary(rows);
  if (rows.length === 0) {
    target.innerHTML = `<div class="empty task-empty">暂无任务</div>`;
    return;
  }
  target.innerHTML = renderTable(rows, [
    { title: "任务", render: row => cell(row.name || row.id, taskSubLine(row)) },
    { title: "来源", render: row => ownerCell(row) },
    { title: "调度", render: row => `<div class="task-schedule-cell"><span class="primary-line">${esc(scheduleLabel(row.scheduleType))}</span><span class="sub-line">${esc(row.scheduleText || "-")}</span></div>` },
    { title: "状态", render: row => `<div class="task-status-cell">${pill(row.status)}<span class="sub-line">${esc(statusHint(row))}</span></div>` },
    { title: "下次运行", render: row => timeCell(row.nextRunAtMillis, true) },
    { title: "最近成功", render: row => timeCell(row.lastSuccessAtMillis, true) },
    { title: "次数", render: row => `<span class="task-run-count">${Number(row.runCount || 0)}</span>` },
    { title: "错误", render: row => `<span class="task-error-line ${row.lastErrorSummary ? "bad" : ""}">${esc(row.lastErrorSummary || "-")}</span>` },
    { title: "操作", render: row => taskActions(row) },
  ])
    .replace('class="table-wrap"', 'class="table-wrap tasks-table-wrap"')
    .replace("<table>", '<table class="tasks-table">');
}

function filteredTasks() {
  const filters = taskFilters();
  return (state.taskRows || []).filter(row => {
    if (filters.ownerType && row.ownerType !== filters.ownerType) return false;
    if (filters.status && row.status !== filters.status) return false;
    if (filters.scheduleType && row.scheduleType !== filters.scheduleType) return false;
    if (filters.q) {
      const haystack = [
        row.id,
        row.name,
        row.description,
        row.ownerId,
        row.ownerName,
        row.pluginState,
        row.scheduleText,
        row.lastErrorSummary,
      ].filter(Boolean).join(" ").toLowerCase();
      if (!haystack.includes(filters.q.toLowerCase())) return false;
    }
    return true;
  });
}

function taskSubLine(row) {
  const parts = [];
  parts.push(row.id);
  if (row.description) parts.push(row.description);
  if (row.retryBackoffMillis) parts.push(`失败退避 ${formatMillis(row.retryBackoffMillis)}`);
  if (row.lastRunAtMillis) parts.push(`最近运行 ${fmtTime(row.lastRunAtMillis, true)}`);
  return parts.join(" · ");
}

function ownerCell(row) {
  const ownerType = row.ownerType === "MAIN" ? "主项目" : "插件";
  const sub = row.ownerType === "PLUGIN"
    ? `${row.ownerId}${row.pluginVersion ? ` · ${row.pluginVersion}` : ""}`
    : row.ownerId;
  return `<div class="task-owner-cell">
    <span class="pill ${row.ownerType === "MAIN" ? "info" : "ok"}">${esc(ownerType)}</span>
    <div>${cell(row.ownerName, sub)}</div>
    ${row.pluginState ? pill(row.pluginState) : ""}
  </div>`;
}

function statusHint(row) {
  if (row.status === "RUNNING") return row.nextRunAtMillis ? `等待至 ${fmtTime(row.nextRunAtMillis, true)}` : "正在执行或等待下一轮";
  if (row.status === "COMPLETED") return "已完成，可重新恢复执行";
  if (row.status === "CANCELLED") return "已停止，可恢复";
  if (row.status === "FAILED") return row.lastErrorSummary ? "有失败详情" : "异常结束";
  return "-";
}

function timeCell(value, millis) {
  return `<span class="sub-line task-time">${value ? fmtTime(value, millis) : "-"}</span>`;
}

function taskActions(row) {
  const buttons = [];
  if (row.canStart) {
    buttons.push(`<button type="button" class="task-action-button start" data-action="task-start" data-id="${attr(row.key)}">恢复</button>`);
  }
  if (row.canStop) {
    buttons.push(`<button type="button" class="task-action-button stop" data-action="task-stop" data-id="${attr(row.key)}">停止</button>`);
  }
  if (row.canRestart) {
    buttons.push(`<button type="button" class="task-action-button restart" data-action="task-restart" data-id="${attr(row.key)}">重启</button>`);
  }
  buttons.push(`<button type="button" class="message-action-button" data-action="task-detail" data-id="${attr(row.key)}">详情</button>`);
  return `<div class="task-actions">${buttons.join("")}</div>`;
}

async function operateTask(action, key, button) {
  const row = (state.taskRows || []).find(item => item.key === key);
  if (!row) throw new Error("任务不存在");
  const operation = action.replace("task-", "");
  const taskTitle = row.name || row.id;
  if (operation === "stop") {
    const confirmed = await confirmDanger("停止任务", `确定停止任务 ${row.ownerName} / ${taskTitle} 吗？停止后可以在此页面恢复。`, {
      confirmText: "停止任务",
      confirmClass: "danger",
      size: "small",
    });
    if (!confirmed) return;
  }
  if (operation === "restart") {
    const confirmed = await confirmDanger("重启任务", `确定重启任务 ${row.ownerName} / ${taskTitle} 吗？当前等待中的轮次会被取消后重新开始。`, {
      confirmText: "重启任务",
      size: "small",
    });
    if (!confirmed) return;
  }

  const originalText = button?.textContent;
  if (button) {
    button.disabled = true;
    button.textContent = "处理中...";
  }
  try {
    const result = await api(`/tasks/${operation}` + query({
      ownerType: row.ownerType,
      ownerId: row.ownerId,
      taskId: row.id,
    }), { method: "POST" });
    notify(result.message, false);
    await refreshTasks(null, true);
  } finally {
    if (button?.isConnected) {
      button.disabled = false;
      if (originalText) button.textContent = originalText;
    }
  }
}

function renderTaskSummary() {
  const target = pageQuery("#taskSummary");
  if (!target) return;
  const rows = state.taskRows || [];
  const counts = rows.reduce((acc, row) => {
    acc[row.status] = (acc[row.status] || 0) + 1;
    return acc;
  }, {});
  target.innerHTML = `
    ${summaryCard("任务总数", rows.length, "主项目与插件任务", "info")}
    ${summaryCard("运行中", counts.RUNNING || 0, "正在调度或执行", "ok")}
    ${summaryCard("已停止", counts.CANCELLED || 0, "可在页面恢复", "warn")}
    ${summaryCard("失败", counts.FAILED || 0, "需要查看错误详情", counts.FAILED ? "bad" : "info")}`;
}

function summaryCard(title, value, sub, tone) {
  return `<div class="task-summary-card ${attr(tone)}">
    <span>${esc(title)}</span>
    <b>${esc(value)}</b>
    <small>${esc(sub)}</small>
  </div>`;
}

function renderTaskFilterSummary(rows) {
  const target = pageQuery("#taskFilterSummary");
  if (!target) return;
  const all = state.taskRows || [];
  target.textContent = `显示 ${rows.length} / ${all.length} 个任务`;
}

function renderTaskStatus() {
  const target = pageQuery("#taskStatus");
  if (!target) return;
  const rows = state.taskRows || [];
  const generatedAt = state.taskResponse?.generatedAtEpochMillis;
  target.innerHTML = `
    ${state.tasksLoading ? '<span class="loading-spinner" aria-hidden="true"></span>' : ""}
    <span class="pill ${state.tasksLoading ? "warn" : "info"}">${state.tasksLoading ? "正在读取" : `${rows.length} 个任务`}</span>
    <span>${generatedAt ? `更新时间 ${fmtTime(generatedAt, true)}` : "尚未读取"}</span>`;
}

function openTaskDetail(key) {
  const row = (state.taskRows || []).find(item => item.key === key);
  if (!row) throw new Error("任务不存在");
  openModal("任务详情", `
    <div class="task-detail">
      <div class="plugin-detail-grid">
        ${detailItem("任务名称", row.name || row.id)}
        ${detailItem("任务 ID", row.id, true)}
        ${detailItem("任务描述", row.description || "-")}
        ${detailItem("来源", row.ownerType === "MAIN" ? "主项目" : "插件")}
        ${detailItem("来源 ID", row.ownerId, true)}
        ${detailItem("来源名称", row.ownerName)}
        ${detailItem("插件状态", row.pluginState ? label(row.pluginState) : "-")}
        ${detailItem("调度类型", scheduleLabel(row.scheduleType))}
        ${detailItem("调度说明", row.scheduleText)}
        ${detailItem("状态", label(row.status))}
        ${detailItem("失败退避", row.retryBackoffMillis ? formatMillis(row.retryBackoffMillis) : "-")}
        ${detailItem("运行次数", row.runCount)}
        ${detailItem("下次运行", row.nextRunAtMillis ? fmtTime(row.nextRunAtMillis, true) : "-")}
        ${detailItem("最近运行", row.lastRunAtMillis ? fmtTime(row.lastRunAtMillis, true) : "-")}
        ${detailItem("最近成功", row.lastSuccessAtMillis ? fmtTime(row.lastSuccessAtMillis, true) : "-")}
      </div>
      <div class="plugin-detail-section">
        <h3>错误信息</h3>
        <pre class="delivery-error-text">${esc(row.lastErrorSummary || "无错误信息")}</pre>
      </div>
    </div>`, null, {
    size: "medium",
    cancelText: "关闭",
  });
}

function detailItem(title, value, mono = false) {
  return `<div class="plugin-detail-item">
    <span>${esc(title)}</span>
    <strong class="${mono ? "mono" : ""}">${esc(value ?? "-")}</strong>
  </div>`;
}

function scheduleLabel(value) {
  const map = {
    ONCE: "一次性",
    FIXED_DELAY: "固定间隔",
    CRON: "Cron",
    UNKNOWN: "未知",
  };
  return map[value] || value || "-";
}

function formatMillis(value) {
  const millis = Number(value || 0);
  if (millis < 1000) return `${millis}ms`;
  const seconds = millis / 1000;
  if (seconds < 60) return `${trimNumber(seconds)} 秒`;
  const minutes = seconds / 60;
  if (minutes < 60) return `${trimNumber(minutes)} 分钟`;
  return `${trimNumber(minutes / 60)} 小时`;
}

function trimNumber(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

function startAutoRefresh() {
  stopAutoRefresh();
  refreshTimer = window.setInterval(() => {
    refreshTasks(null, true).catch(handleError);
  }, AUTO_REFRESH_MS);
}

function stopAutoRefresh() {
  if (refreshTimer) window.clearInterval(refreshTimer);
  refreshTimer = null;
}
