export function createMessageTemplateEditor(deps) {
  const esc = deps.esc;
  const attr = deps.attr;
  const doc = deps.document || document;
  const dropRangeRects = new WeakMap();
  let activeContent = null;

  function editorHtml(options) {
    const kind = normalizeKind(options.kind);
    const value = String(options.value || "");
    return `
      <div class="message-template-editor-modal${options.extraClass ? ` ${attr(options.extraClass)}` : ""}" data-message-template-editor>
        <textarea id="messageTemplateEditorInput" class="message-template-editor-input message-template-source-input" hidden>${esc(value)}</textarea>
        <div class="message-template-editor-pane">
          <div class="message-template-builder-head">
            <div>
              <span class="primary-line">模板编排</span>
              <span class="sub-line">${esc(templateKindLabel(kind))}</span>
            </div>
            <div class="message-template-builder-actions">
              ${supportsMessageSplit(kind) ? `<button type="button" class="compact message-template-add-message-button" data-template-add-block="message">添加消息</button>` : ""}
              <button type="button" class="compact message-template-add-forward-button" data-template-add-block="forward">添加合并转发</button>
            </div>
          </div>
          <div id="messageTemplateBlockList" class="message-template-block-list">
            ${renderEditorBlocks(parseTemplateBlocks(value, kind), kind)}
          </div>
          <div class="message-template-builder-hint">Enter 换行，拖出内容框可删除字段，添加消息会拆成下一条发送。</div>
          <div class="message-template-token-section">
            <div class="message-template-section-head">
              <span class="sub-line">字段</span>
              <span class="sub-line">点击或拖入内容框</span>
            </div>
            ${tokenPaletteHtml(kind)}
          </div>
          <details class="message-template-source-panel">
            <summary><span>模板源码</span><small>高级编辑</small></summary>
            <textarea id="messageTemplateSourceInput" class="message-template-editor-input" spellcheck="false" wrap="off">${esc(sourceDisplayValue(value))}</textarea>
          </details>
        </div>
        <div class="message-template-preview-pane">
          <div class="message-template-preview-head">
            <span class="primary-line">消息预览</span>
            <span id="messageTemplateEditorStats" class="sub-line">${esc(messageTemplateStats(value, kind))}</span>
          </div>
          <div id="messageTemplateEditorPreview" class="message-template-preview">${renderMessageTemplatePreview(value, kind)}</div>
        </div>
      </div>`;
  }

  function bindEditor(options = {}) {
    const kind = normalizeKind(options.kind);
    const root = doc.querySelector("[data-message-template-editor]");
    const hiddenInput = doc.getElementById("messageTemplateEditorInput");
    const blockList = doc.getElementById("messageTemplateBlockList");
    const sourceInput = doc.getElementById("messageTemplateSourceInput");
    const preview = doc.getElementById("messageTemplateEditorPreview");
    const stats = doc.getElementById("messageTemplateEditorStats");
    if (!root || !hiddenInput || !blockList || !preview) return;

    activeContent = blockList.querySelector("[data-template-content]");
    let draggingChip = null;
    let dropHandled = false;
    let deleteArmed = false;

    const refreshEditorState = () => updateEditorState(blockList);
    const refreshOutputs = value => {
      hiddenInput.value = value;
      const displayValue = sourceDisplayValue(value);
      if (sourceInput && sourceInput.value !== displayValue) sourceInput.value = displayValue;
      preview.innerHTML = renderMessageTemplatePreview(value, kind);
      if (stats) stats.textContent = messageTemplateStats(value, kind);
      if (typeof options.onInput === "function") options.onInput(value);
    };
    const commitBuilder = () => {
      refreshEditorState();
      refreshOutputs(serializeBuilder(blockList, kind));
    };
    const applySource = value => {
      hiddenInput.value = value;
      const displayValue = sourceDisplayValue(value);
      if (sourceInput && sourceInput.value !== displayValue) sourceInput.value = displayValue;
      blockList.innerHTML = renderEditorBlocks(parseTemplateBlocks(value, kind), kind);
      activeContent = blockList.querySelector("[data-template-content]");
      refreshEditorState();
      refreshOutputs(value);
    };

    root.querySelectorAll("button[data-template-token]").forEach(button => {
      button.addEventListener("click", () => {
        if (doc.activeElement === sourceInput) {
          insertIntoTextarea(sourceInput, button.dataset.templateToken || "");
          applySource(sourceTemplateValue(sourceInput.value));
          return;
        }
        insertTokenIntoContent(activeContent || ensureFirstContent(blockList, kind), button.dataset.templateToken || "", kind);
        commitBuilder();
      });
      button.addEventListener("dragstart", event => {
        draggingChip = null;
        dropHandled = false;
        deleteArmed = false;
        event.dataTransfer?.setData("application/x-message-template-token", button.dataset.templateToken || "");
        event.dataTransfer?.setData("text/plain", button.dataset.templateToken || "");
        if (event.dataTransfer) event.dataTransfer.effectAllowed = "copy";
        event.dataTransfer?.setDragImage?.(transparentDragImage(), 0, 0);
      });
    });

    root.querySelectorAll("button[data-template-add-block]").forEach(button => {
      button.addEventListener("click", () => {
        if (button.dataset.templateAddBlock === "message" && !supportsMessageSplit(kind)) return;
        insertBlockAfterActive(blockList, button.dataset.templateAddBlock, kind);
        commitBuilder();
      });
    });

    blockList.addEventListener("focusin", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (content) activeContent = content;
    });
    blockList.addEventListener("input", event => {
      if (event.target.closest?.("[data-template-content]")) commitBuilder();
    });
    blockList.addEventListener("beforeinput", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (!content || !["insertParagraph", "insertLineBreak"].includes(event.inputType)) return;
      event.preventDefault();
      activeContent = content;
      insertSourceIntoContent(content, "\\n", kind);
      commitBuilder();
    });
    blockList.addEventListener("keydown", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (!content || event.isComposing || event.key !== "Enter") return;
      event.preventDefault();
      activeContent = content;
      insertSourceIntoContent(content, "\\n", kind);
      commitBuilder();
    });
    blockList.addEventListener("paste", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (!content) return;
      const text = event.clipboardData?.getData("text/plain") || "";
      if (!text) return;
      event.preventDefault();
      activeContent = content;
      insertSourceIntoContent(content, encodeLineBreaks(text), kind);
      commitBuilder();
    });
    blockList.addEventListener("click", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (content) activeContent = content;

      const blockAction = event.target.closest?.("button[data-template-block-action]");
      if (blockAction) {
        handleBlockAction(blockList, blockAction.dataset.templateBlockAction, blockAction.closest("[data-template-block]"), kind);
        commitBuilder();
        return;
      }

      const nodeAction = event.target.closest?.("button[data-template-node-action]");
      if (nodeAction) {
        handleNodeAction(nodeAction.dataset.templateNodeAction, nodeAction.closest("[data-template-node]"), kind);
        commitBuilder();
        return;
      }

      const addNode = event.target.closest?.("button[data-template-add-node]");
      if (addNode) {
        addForwardNode(addNode.closest("[data-template-block]"), "", kind);
        commitBuilder();
      }
    });
    blockList.addEventListener("dragstart", event => {
      const chip = event.target.closest?.(".message-template-field-chip");
      if (!chip) return;
      draggingChip = chip;
      dropHandled = false;
      deleteArmed = false;
      chip.classList.add("is-moving");
      event.dataTransfer?.setData("application/x-message-template-token", chip.dataset.templateToken || "");
      event.dataTransfer?.setData("text/plain", chip.dataset.templateToken || "");
      if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
      event.dataTransfer?.setDragImage?.(transparentDragImage(), 0, 0);
    });
    blockList.addEventListener("dragend", () => {
      draggingChip = null;
      dropHandled = false;
      deleteArmed = false;
      clearDragState(root, blockList);
    });
    blockList.addEventListener("dragover", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (!content) return;
      event.preventDefault();
      event.stopPropagation();
      if (event.dataTransfer) event.dataTransfer.dropEffect = draggingChip ? "move" : "copy";
      deleteArmed = false;
      root.classList.remove("is-chip-delete-ready");
      blockList.querySelectorAll(".message-template-content-editor.is-dragging").forEach(item => {
        if (item !== content) item.classList.remove("is-dragging");
      });
      content.classList.add("is-dragging");
      showDropCaret(blockList, content, event);
    });
    blockList.addEventListener("dragleave", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (content && !content.contains(event.relatedTarget)) content.classList.remove("is-dragging");
    });
    blockList.addEventListener("drop", event => {
      const content = event.target.closest?.("[data-template-content]");
      if (!content) return;
      event.preventDefault();
      event.stopPropagation();
      content.classList.remove("is-dragging");
      activeContent = content;
      const token = event.dataTransfer?.getData("application/x-message-template-token") || event.dataTransfer?.getData("text/plain") || draggingChip?.dataset?.templateToken || "";
      placeCaretAtDropPosition(blockList, content, event);
      insertTokenIntoContent(content, token, kind);
      if (draggingChip && draggingChip.isConnected) draggingChip.remove();
      dropHandled = true;
      deleteArmed = false;
      draggingChip = null;
      clearDragState(root, blockList);
      commitBuilder();
    });
    root.addEventListener("dragover", event => {
      if (!draggingChip) return;
      if (event.target.closest?.("[data-template-content]")) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
      deleteArmed = true;
      root.classList.add("is-chip-delete-ready");
      blockList.querySelectorAll(".message-template-content-editor.is-dragging").forEach(item => item.classList.remove("is-dragging"));
      removeDropCaret(blockList);
    });
    root.addEventListener("drop", event => {
      if (!draggingChip || event.target.closest?.("[data-template-content]")) return;
      event.preventDefault();
      if (draggingChip.isConnected) draggingChip.remove();
      dropHandled = true;
      deleteArmed = false;
      draggingChip = null;
      clearDragState(root, blockList);
      commitBuilder();
    });

    if (sourceInput) {
      sourceInput.addEventListener("input", () => applySource(sourceTemplateValue(sourceInput.value)));
    }

    applySource(hiddenInput.value);
  }

  function renderEditorBlocks(blocks, kind) {
    const list = blocks.length ? blocks : [{ type: "message", text: "" }];
    return list.map((block, index) => block.type === "forward" ? renderForwardBlock(block, kind, index) : renderMessageBlock(block, kind, index)).join("");
  }

  function renderMessageBlock(block, kind, index = 0) {
    return `<section class="message-template-builder-block" data-template-block data-template-block-type="message">
      <div class="message-template-builder-block-head">
        <span class="message-template-builder-block-title">消息 ${index + 1}</span>
        <div class="message-template-builder-block-actions">
          ${blockControlButtonsHtml()}
        </div>
      </div>
      ${contentEditorHtml(block.text, kind, "消息内容")}
    </section>`;
  }

  function renderForwardBlock(block, kind, index = 0) {
    const nodes = block.nodes && block.nodes.length ? block.nodes : [{ text: "" }];
    return `<section class="message-template-builder-block forward" data-template-block data-template-block-type="forward">
      <div class="message-template-builder-block-head">
        <span class="message-template-builder-block-title">合并转发 ${index + 1}</span>
        <div class="message-template-builder-block-actions">
          <button type="button" class="compact message-template-add-node-button" data-template-add-node>添加节点</button>
          ${blockControlButtonsHtml()}
        </div>
      </div>
      <div class="message-template-forward-editor-nodes">
        ${nodes.map((node, nodeIndex) => renderForwardNode(node, kind, nodeIndex)).join("")}
      </div>
    </section>`;
  }

  function renderForwardNode(node, kind, index = 0) {
    return `<div class="message-template-forward-editor-node" data-template-node>
      <div class="message-template-forward-editor-node-head">
        <span>节点 ${index + 1}</span>
        <div class="message-template-forward-editor-node-actions">
          <button type="button" class="compact message-template-move-button" data-template-node-action="up">上移</button>
          <button type="button" class="compact message-template-move-button" data-template-node-action="down">下移</button>
          <button type="button" class="compact message-template-delete-button" data-template-node-action="delete">删除</button>
        </div>
      </div>
      ${contentEditorHtml(node.text, kind, "节点内容")}
    </div>`;
  }

  function contentEditorHtml(source, kind, placeholder) {
    return `<div class="message-template-content-editor" contenteditable="true" spellcheck="false" data-template-content data-placeholder="${attr(placeholder)}">${renderEditableContent(source, kind)}</div>`;
  }

  function blockControlButtonsHtml() {
    return `
      <button type="button" class="compact message-template-move-button" data-template-block-action="up">上移</button>
      <button type="button" class="compact message-template-move-button" data-template-block-action="down">下移</button>
      <button type="button" class="compact message-template-delete-button" data-template-block-action="delete">删除</button>`;
  }

  function renderEditableContent(source, kind) {
    const tokenRegex = /(?:\\n|\{[a-zA-Z0-9_]+\})/g;
    const value = String(source || "");
    let html = "";
    let index = 0;
    value.replace(tokenRegex, (match, offset) => {
      html += renderEditableText(value.slice(index, offset));
      html += match === "\\n" ? "<br>" : fieldChipHtml(match, kind);
      index = offset + match.length;
      return match;
    });
    html += renderEditableText(value.slice(index));
    return html;
  }

  function sourceDisplayValue(value) {
    return String(value || "").replace(/\\n/g, "\n");
  }

  function sourceTemplateValue(value) {
    return encodeLineBreaks(value);
  }

  function clearDragState(root, blockList) {
    root?.classList?.remove("is-chip-delete-ready");
    blockList?.querySelectorAll(".message-template-field-chip.is-moving").forEach(item => item.classList.remove("is-moving"));
    blockList?.querySelectorAll(".message-template-content-editor.is-dragging").forEach(item => item.classList.remove("is-dragging"));
    removeDropCaret(blockList);
  }

  function transparentDragImage() {
    if (!transparentDragImage.canvas) {
      const canvas = doc.createElement("canvas");
      canvas.width = 1;
      canvas.height = 1;
      transparentDragImage.canvas = canvas;
    }
    return transparentDragImage.canvas;
  }

  function showDropCaret(blockList, content, event) {
    const range = rangeFromPoint(content, event);
    const rect = dropCaretRect(blockList, content, range);
    const caret = ensureDropCaret(blockList);
    caret.style.left = `${Math.max(rect.left, 0)}px`;
    caret.style.top = `${Math.max(rect.top, 0)}px`;
    caret.style.height = `${Math.max(rect.height, 16)}px`;
    caret.hidden = false;
  }

  function placeCaretAtDropPosition(blockList, content, event) {
    removeDropCaret(blockList);
    placeCaretFromRange(content, rangeFromPoint(content, event));
  }

  function ensureDropCaret(blockList) {
    let caret = blockList.querySelector(":scope > .message-template-drop-caret");
    if (!caret) {
      caret = doc.createElement("span");
      caret.className = "message-template-drop-caret";
      caret.hidden = true;
      blockList.appendChild(caret);
    }
    return caret;
  }

  function removeDropCaret(blockList) {
    blockList?.querySelector(":scope > .message-template-drop-caret")?.remove();
  }

  function rangeFromPoint(content, event) {
    const nativeRange = nativeRangeFromPoint(event);
    if (nativeRange && shouldUseNativeRange(content, event, nativeRange)) {
      return nativeRange;
    }
    return manualRangeFromPoint(content, event) || nativeRange || endRange(content);
  }

  function dropCaretRect(blockList, content, range) {
    const listRect = blockList.getBoundingClientRect();
    const contentRect = content.getBoundingClientRect();
    const rect = dropRangeRects.get(range) || range.getClientRects?.()[0] || range.getBoundingClientRect?.() || null;
    const fallbackTop = contentRect.top + 8;
    const fallbackLeft = contentRect.left + 8;
    return {
      left: (rect?.left || fallbackLeft) - listRect.left,
      top: (rect?.top || fallbackTop) - listRect.top,
      height: rect?.height || 18,
    };
  }

  function nativeRangeFromPoint(event) {
    let range = null;
    if (doc.caretRangeFromPoint) {
      range = doc.caretRangeFromPoint(event.clientX, event.clientY);
    } else if (doc.caretPositionFromPoint) {
      const position = doc.caretPositionFromPoint(event.clientX, event.clientY);
      if (position) {
        range = doc.createRange();
        range.setStart(position.offsetNode, position.offset);
      }
    }
    if (!range) return null;
    const chip = closestElement(range.commonAncestorContainer, ".message-template-field-chip");
    if (chip) {
      const rect = chip.getBoundingClientRect();
      const chipRange = doc.createRange();
      if (event.clientX < rect.left + rect.width / 2) chipRange.setStartBefore(chip);
      else chipRange.setStartAfter(chip);
      chipRange.collapse(true);
      dropRangeRects.set(chipRange, {
        left: event.clientX < rect.left + rect.width / 2 ? rect.left : rect.right,
        top: rect.top,
        height: rect.height,
      });
      return chipRange;
    }
    range.collapse(true);
    setRangeDropRect(range, rangeRect(range));
    return range;
  }

  function shouldUseNativeRange(content, event, range) {
    const rect = rangeRect(range);
    if (!rect) return false;
    const distance = distanceToRect(event.clientX, event.clientY, rect);
    if (distance <= 18) return true;
    const contentRect = content.getBoundingClientRect();
    if (distance <= 28) {
      const nearEdge = rect.left <= contentRect.left + 8 || rect.right >= contentRect.right - 8 || rect.top <= contentRect.top + 8 || rect.bottom >= contentRect.bottom - 8;
      if (nearEdge) return true;
    }
    return false;
  }

  function manualRangeFromPoint(content, event) {
    const anchors = collectCaretAnchors(content);
    if (!anchors.length) return endRange(content);
    let best = null;
    for (const anchor of anchors) {
      const score = anchorScore(anchor.rect, event.clientX, event.clientY);
      if (!best || score < best.score) best = { anchor, score };
    }
    if (!best) return endRange(content);
    return anchorToRange(best.anchor, content, event);
  }

  function collectCaretAnchors(content) {
    const anchors = [];
    const nodeFilter = doc.defaultView?.NodeFilter || NodeFilter;
    const walker = doc.createTreeWalker(content, nodeFilter.SHOW_TEXT | nodeFilter.SHOW_ELEMENT, {
      acceptNode(node) {
        if (node.nodeType === Node.TEXT_NODE) {
          return String(node.nodeValue || "").length ? nodeFilter.FILTER_ACCEPT : nodeFilter.FILTER_SKIP;
        }
        if (node.matches?.(".message-template-field-chip")) return nodeFilter.FILTER_ACCEPT;
        if (node.tagName === "BR") return nodeFilter.FILTER_ACCEPT;
        return nodeFilter.FILTER_SKIP;
      },
    });
    let node;
    while ((node = walker.nextNode())) {
      if (node.nodeType === Node.TEXT_NODE) {
        const rect = nodeRect(node);
        if (rect) anchors.push({ type: "text", node, rect });
        continue;
      }
      if (node.matches?.(".message-template-field-chip")) {
        anchors.push({ type: "chip", node, rect: node.getBoundingClientRect() });
        continue;
      }
      if (node.tagName === "BR") {
        const rect = brRect(node);
        if (rect) anchors.push({ type: "break", node, rect });
      }
    }
    const contentRect = content.getBoundingClientRect();
    anchors.unshift({ type: "start", node: content, rect: { left: contentRect.left, top: contentRect.top, right: contentRect.left, bottom: contentRect.top, width: 0, height: 0 } });
    anchors.push({ type: "end", node: content, rect: { left: contentRect.right, top: contentRect.bottom, right: contentRect.right, bottom: contentRect.bottom, width: 0, height: 0 } });
    return anchors;
  }

  function anchorScore(rect, x, y) {
    return distanceToRect(x, y, rect) * 1000 + Math.abs((rect.left + rect.right) / 2 - x);
  }

  function anchorToRange(anchor, content, event) {
    if (anchor.type === "chip") {
      const rect = anchor.rect;
      const range = doc.createRange();
      if (event.clientX < rect.left + rect.width / 2) range.setStartBefore(anchor.node);
      else range.setStartAfter(anchor.node);
      range.collapse(true);
      dropRangeRects.set(range, {
        left: event.clientX < rect.left + rect.width / 2 ? rect.left : rect.right,
        top: rect.top,
        height: rect.height,
      });
      return range;
    }
    if (anchor.type === "text") {
      const offset = offsetForTextNodeAtX(anchor.node, event.clientX);
      const range = doc.createRange();
      range.setStart(anchor.node, offset);
      range.collapse(true);
      setRangeDropRect(range, caretRectForTextNodeOffset(anchor.node, offset, anchor.rect));
      return range;
    }
    if (anchor.type === "break") {
      const range = doc.createRange();
      range.setStartBefore(anchor.node);
      range.collapse(true);
      dropRangeRects.set(range, {
        left: anchor.rect.left,
        top: anchor.rect.top,
        height: Math.max(anchor.rect.height, 18),
      });
      return range;
    }
    return endRange(content);
  }

  function offsetForTextNodeAtX(textNode, x) {
    const text = String(textNode.nodeValue || "");
    const length = text.length;
    if (!length) return 0;
    let low = 0;
    let high = length;
    while (low < high) {
      const mid = Math.floor((low + high) / 2);
      const rect = caretRectForTextNodeOffset(textNode, mid);
      if (!rect || x > rect.left) low = mid + 1;
      else high = mid;
    }
    return Math.max(0, Math.min(length, low));
  }

  function caretRectForTextNodeOffset(textNode, offset, fallbackRect) {
    const range = doc.createRange();
    const safeOffset = Math.max(0, Math.min(offset, String(textNode.nodeValue || "").length));
    range.setStart(textNode, safeOffset);
    range.collapse(true);
    return rangeRect(range) || fallbackRect || null;
  }

  function nodeRect(node) {
    const range = doc.createRange();
    range.selectNodeContents(node);
    return rangeRect(range);
  }

  function brRect(node) {
    const range = doc.createRange();
    range.setStartBefore(node);
    range.collapse(true);
    return rangeRect(range);
  }

  function rangeRect(range) {
    const rect = range?.getClientRects?.()[0] || range?.getBoundingClientRect?.() || null;
    if (!rect || (rect.width === 0 && rect.height === 0 && rect.left === 0 && rect.top === 0)) return null;
    return rect;
  }

  function setRangeDropRect(range, rect) {
    if (range && rect) dropRangeRects.set(range, rect);
  }

  function distanceToRect(x, y, rect) {
    const dx = x < rect.left ? rect.left - x : x > rect.right ? x - rect.right : 0;
    const dy = y < rect.top ? rect.top - y : y > rect.bottom ? y - rect.bottom : 0;
    return Math.hypot(dx, dy);
  }

  function endRange(content) {
    const range = doc.createRange();
    range.selectNodeContents(content);
    range.collapse(false);
    const contentRect = content.getBoundingClientRect();
    dropRangeRects.set(range, {
      left: contentRect.right - 8,
      top: contentRect.bottom - 8,
      height: 18,
    });
    return range;
  }

  function renderEditableText(value) {
    return esc(String(value || "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").replace(/\\n/g, "\n")).replace(/\n/g, "<br>");
  }

  function fieldChipHtml(token, kind) {
    return `<span class="message-template-field-chip" contenteditable="false" draggable="true" data-template-token="${attr(token)}" title="${attr(token)}">${esc(templateTokenTitle(token, kind))}</span>`;
  }

  function updateEditorState(blockList) {
    const blocks = Array.from(blockList.querySelectorAll(":scope > [data-template-block]"));
    blocks.forEach((block, index) => {
      const title = block.querySelector(":scope > .message-template-builder-block-head .message-template-builder-block-title");
      if (title) title.textContent = block.dataset.templateBlockType === "forward" ? `合并转发 ${index + 1}` : `消息 ${index + 1}`;
      const head = block.querySelector(":scope > .message-template-builder-block-head");
      setActionDisabled(head, "data-template-block-action", "up", index === 0);
      setActionDisabled(head, "data-template-block-action", "down", index === blocks.length - 1);
      setActionDisabled(head, "data-template-block-action", "delete", false);

      const nodes = Array.from(block.querySelectorAll(":scope .message-template-forward-editor-nodes > [data-template-node]"));
      nodes.forEach((node, nodeIndex) => {
        const nodeTitle = node.querySelector(":scope > .message-template-forward-editor-node-head > span");
        if (nodeTitle) nodeTitle.textContent = `节点 ${nodeIndex + 1}`;
        const nodeHead = node.querySelector(":scope > .message-template-forward-editor-node-head");
        setActionDisabled(nodeHead, "data-template-node-action", "up", nodeIndex === 0);
        setActionDisabled(nodeHead, "data-template-node-action", "down", nodeIndex === nodes.length - 1);
        setActionDisabled(nodeHead, "data-template-node-action", "delete", nodes.length <= 1);
      });
    });
  }

  function setActionDisabled(scope, attribute, action, disabled) {
    const button = scope?.querySelector(`button[${attribute}="${action}"]`);
    if (!button) return;
    button.disabled = disabled;
  }

  function insertBlockAfterActive(blockList, type, kind) {
    const activeBlock = activeBlockFor(blockList);
    const wrapper = doc.createElement("div");
    const html = type === "forward"
      ? renderForwardBlock(defaultForwardBlock(kind), kind)
      : renderMessageBlock({ type: "message", text: "" }, kind);
    wrapper.innerHTML = html;
    const nextBlock = wrapper.firstElementChild;
    if (!nextBlock) return;
    if (activeBlock?.nextSibling) blockList.insertBefore(nextBlock, activeBlock.nextSibling);
    else blockList.appendChild(nextBlock);
    const content = nextBlock.querySelector("[data-template-content]");
    if (content) {
      activeContent = content;
      content.focus();
      content.scrollIntoView({ block: "nearest" });
    }
  }

  function activeBlockFor(blockList) {
    return activeContent?.closest?.("[data-template-block]") || blockList.querySelector("[data-template-block]:last-child");
  }

  function handleBlockAction(blockList, action, block, kind) {
    if (!block) return;
    if (action === "delete") {
      block.remove();
      if (!blockList.querySelector("[data-template-block]")) {
        blockList.innerHTML = renderEditorBlocks([{ type: "message", text: "" }], kind);
      }
      activeContent = blockList.querySelector("[data-template-content]");
      activeContent?.focus?.();
      return;
    }
    if (action === "up" && block.previousElementSibling) {
      blockList.insertBefore(block, block.previousElementSibling);
      return;
    }
    if (action === "down" && block.nextElementSibling) {
      blockList.insertBefore(block.nextElementSibling, block);
    }
  }

  function handleNodeAction(action, node, kind) {
    if (!node) return;
    const list = node.parentElement;
    if (action === "delete") {
      node.remove();
      if (list && !list.querySelector("[data-template-node]")) addForwardNode(list.closest("[data-template-block]"), "", kind);
      return;
    }
    if (action === "up" && node.previousElementSibling) {
      list.insertBefore(node, node.previousElementSibling);
      return;
    }
    if (action === "down" && node.nextElementSibling) {
      list.insertBefore(node.nextElementSibling, node);
    }
  }

  function addForwardNode(block, text, kind) {
    const list = block?.querySelector(".message-template-forward-editor-nodes");
    if (!list) return;
    const wrapper = doc.createElement("div");
    wrapper.innerHTML = renderForwardNode({ text }, kind);
    const node = wrapper.firstElementChild;
    if (!node) return;
    list.appendChild(node);
    const content = node.querySelector("[data-template-content]");
    if (content) {
      activeContent = content;
      content.focus();
      content.scrollIntoView({ block: "nearest" });
    }
  }

  function ensureFirstContent(blockList, kind) {
    let content = blockList.querySelector("[data-template-content]");
    if (!content) {
      blockList.innerHTML = renderEditorBlocks([{ type: "message", text: "" }], kind);
      content = blockList.querySelector("[data-template-content]");
    }
    activeContent = content;
    content?.scrollIntoView?.({ block: "nearest" });
    return content;
  }

  function insertIntoTextarea(input, text) {
    if (!input || !text) return;
    const start = input.selectionStart ?? input.value.length;
    const end = input.selectionEnd ?? input.value.length;
    input.setRangeText(text, start, end, "end");
    input.focus();
  }

  function insertTokenIntoContent(content, token, kind) {
    if (!content || !token) return;
    insertSourceIntoContent(content, token, kind);
  }

  function insertSourceIntoContent(content, source, kind) {
    if (!content || !source) return;
    content.focus();
    const selection = doc.getSelection();
    let range = selection && selection.rangeCount ? selection.getRangeAt(0) : null;
    if (!range || !content.contains(range.commonAncestorContainer)) {
      range = doc.createRange();
      range.selectNodeContents(content);
      range.collapse(false);
    }
    range.deleteContents();
    const fragment = fragmentFromSource(source, kind);
    const lastNode = fragment.lastChild;
    range.insertNode(fragment);
    if (lastNode) {
      range.setStartAfter(lastNode);
      range.collapse(true);
      selection.removeAllRanges();
      selection.addRange(range);
    }
  }

  function fragmentFromSource(source, kind) {
    const template = doc.createElement("template");
    template.innerHTML = renderEditableContent(source, kind);
    return template.content;
  }

  function placeCaretFromRange(content, range) {
    try {
      content.focus?.({ preventScroll: true });
    } catch {
      content.focus?.();
    }
    const selection = doc.getSelection();
    range.collapse(true);
    selection.removeAllRanges();
    selection.addRange(range);
  }

  function closestElement(node, selector) {
    const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
    return element?.closest?.(selector) || null;
  }

  function serializeBuilder(blockList, kind) {
    const blocks = Array.from(blockList.querySelectorAll(":scope > [data-template-block]"));
    let source = "";
    let previousMessage = false;
    blocks.forEach(block => {
      if (block.dataset.templateBlockType === "forward") {
        const nodes = Array.from(block.querySelectorAll("[data-template-node]"))
          .map(node => contentSource(node.querySelector("[data-template-content]")))
          .filter(hasTemplateContent);
        if (!nodes.length) return;
        source += `{>>}${nodes.join("\\r")}{<<}`;
        previousMessage = false;
        return;
      }
      const text = contentSource(block.querySelector("[data-template-content]"));
      if (!hasTemplateContent(text)) return;
      if (previousMessage) source += supportsMessageSplit(kind) ? "\\r" : "\\n";
      source += text;
      previousMessage = true;
    });
    return source;
  }

  function contentSource(content) {
    return encodeLineBreaks(readContentText(content).replace(/\n+$/g, "")).replace(/\r/g, "");
  }

  function hasTemplateContent(source) {
    return String(source || "").replace(/\\n/g, "").trim().length > 0;
  }

  function readContentText(node, isRoot = true) {
    if (!node) return "";
    if (node.nodeType === Node.TEXT_NODE) return String(node.nodeValue || "").replace(/\u00a0/g, " ");
    if (node.nodeType !== Node.ELEMENT_NODE) return "";
    if (node.dataset?.templateToken) return node.dataset.templateToken;
    if (node.tagName === "BR") return "\n";
    const text = Array.from(node.childNodes).map(child => readContentText(child, false)).join("");
    if (!isRoot && ["DIV", "P"].includes(node.tagName) && text && !text.endsWith("\n")) return `${text}\n`;
    return text;
  }

  function encodeLineBreaks(value) {
    return String(value || "").replace(/\r\n/g, "\n").replace(/\r/g, "\n").replace(/\n/g, "\\n");
  }

  function parseTemplateBlocks(template, kind) {
    const source = String(template || "");
    const segments = parseMessageTemplatePreviewSegments(source);
    if (!segments) return [{ type: "message", text: source }];
    const blocks = [];
    segments.forEach(segment => {
      if (segment.type === "forward") {
        blocks.push({
          type: "forward",
          nodes: segment.value.split("\\r").map(text => ({ text })),
        });
        return;
      }
      const parts = kind === "LIVE_ENDED" ? [segment.value] : segment.value.split("\\r");
      parts.forEach(text => {
        if (text.length) blocks.push({ type: "message", text });
      });
    });
    return blocks.length ? blocks : [{ type: "message", text: "" }];
  }

  function defaultForwardBlock(kind) {
    const parsed = parseTemplateBlocks(messageTemplateForwardToken(kind).value, kind);
    return parsed.find(block => block.type === "forward") || { type: "forward", nodes: [{ text: "" }] };
  }

  function tokenPaletteHtml(kind) {
    return placeholderGroups(kind).map(group => `
      <div class="message-template-token-group">
        <span class="message-template-token-group-title">${esc(group.title)}</span>
        <div class="message-template-token-bar">
          ${group.items.map(item => tokenButtonHtml(item)).join("")}
        </div>
      </div>`).join("");
  }

  function tokenButtonHtml(item) {
    return `<button type="button" class="message-template-token-button" draggable="true" data-template-token="${attr(item.value)}" title="${attr(item.label || item.value)}">
      <span class="message-template-token-title">${esc(item.title || item.label)}</span>
    </button>`;
  }

  function placeholderGroups(kind) {
    const items = messageTemplatePlaceholders(kind);
    const groups = [
      ["media", "媒体", item => ["{draw}", "{images}", "{cover}", "{video}"].includes(item.value)],
      ["identity", "对象", item => ["{name}", "{uid}", "{did}", "{rid}", "{id}", "{kind}"].includes(item.value)],
      ["content", "内容", item => ["{title}", "{content}", "{time}", "{area}", "{startTime}", "{endTime}", "{duration}", "{stats}", "{link}", "{links}", "{size}"].includes(item.value)],
    ];
    return groups.map(([, title, match]) => ({
      title,
      items: items.filter(match),
    })).filter(group => group.items.length);
  }

  function messageTemplatePlaceholders(kind) {
    const common = [
      { value: "{name}", label: "{name}", title: "发布者名称" },
      { value: "{uid}", label: "{uid}", title: "发布者 ID" },
      { value: "{link}", label: "{link}", title: "主链接" },
    ];
    if (kind && kind.startsWith("LINK_")) {
      return [
        { value: "{draw}", label: "{draw}", title: "预览绘图" },
        { value: "{cover}", label: "{cover}", title: "封面图" },
        { value: "{video}", label: "{video}", title: "下载后的视频" },
        { value: "{name}", label: "{name}", title: "发布者 / 账号名称" },
        { value: "{uid}", label: "{uid}", title: "发布者 / 账号 ID" },
        { value: "{id}", label: "{id}", title: "链接对象 ID" },
        { value: "{kind}", label: "{kind}", title: "链接类型" },
        { value: "{title}", label: "{title}", title: "标题" },
        { value: "{content}", label: "{content}", title: "描述 / 正文" },
        { value: "{link}", label: "{link}", title: "链接" },
        { value: "{stats}", label: "{stats}", title: "数据指标" },
        { value: "{duration}", label: "{duration}", title: "时长" },
        { value: "{size}", label: "{size}", title: "视频大小" },
      ];
    }
    if (kind === "LIVE_STARTED") {
      return [
        { value: "{draw}", label: "{draw}", title: "直播绘图" },
        ...common,
        { value: "{rid}", label: "{rid}", title: "直播间 ID" },
        { value: "{time}", label: "{time}", title: "开播时间" },
        { value: "{title}", label: "{title}", title: "直播标题" },
        { value: "{area}", label: "{area}", title: "直播分区" },
        { value: "{cover}", label: "{cover}", title: "封面图" },
      ];
    }
    if (kind === "LIVE_ENDED") {
      return [
        ...common,
        { value: "{rid}", label: "{rid}", title: "直播间 ID" },
        { value: "{title}", label: "{title}", title: "直播标题" },
        { value: "{area}", label: "{area}", title: "直播分区" },
        { value: "{startTime}", label: "{startTime}", title: "开始时间" },
        { value: "{endTime}", label: "{endTime}", title: "结束时间" },
        { value: "{duration}", label: "{duration}", title: "直播时长" },
      ];
    }
    return [
      { value: "{draw}", label: "{draw}", title: "动态绘图" },
      ...common,
      { value: "{did}", label: "{did}", title: "动态 ID" },
      { value: "{time}", label: "{time}", title: "发布时间" },
      { value: "{content}", label: "{content}", title: "动态正文" },
      { value: "{images}", label: "{images}", title: "动态图片" },
      { value: "{links}", label: "{links}", title: "附加链接" },
    ];
  }

  function messageTemplateForwardToken(kind) {
    if (kind && kind.startsWith("LINK_")) {
      return {
        value: "{>>}{title}\\n{content}\\r{cover}\\r{link}{<<}",
        label: "{>>}...{<<}",
        title: "合并转发块",
      };
    }
    if (kind === "LIVE_STARTED") {
      return {
        value: "{>>}直播标题：{title}\\n分区：{area}\\r封面：\\n{cover}\\r直播间：{link}{<<}",
        label: "{>>}...{<<}",
        title: "合并转发块",
      };
    }
    if (kind === "LIVE_ENDED") {
      return {
        value: "{>>}直播标题：{title}\\n直播时长：{duration}\\r直播间：{link}{<<}",
        label: "{>>}...{<<}",
        title: "合并转发块",
      };
    }
    return {
      value: "{>>}{name}@{uid}\\n{time}\\n\\n{content}\\n\\n{links}\\r{images}{<<}",
      label: "{>>}...{<<}",
      title: "合并转发块",
    };
  }

  function templateTokenTitle(token, kind) {
    const found = messageTemplatePlaceholders(kind).find(item => item.value === token);
    if (found) return found.title || found.label;
    const key = String(token || "").replace(/[{}]/g, "");
    return key ? `字段：${key}` : String(token || "");
  }

  function messageTemplateStats(template, kind) {
    const batches = messageTemplatePreviewBatches(template, kind);
    const normalized = String(template || "").replace(/\\r/g, "\n").replace(/\\n/g, "\n");
    const lines = normalized ? normalized.split("\n").length : 0;
    return `${batches.length} 条消息 / ${lines} 行`;
  }

  function renderMessageTemplatePreview(template, kind) {
    const batches = messageTemplatePreviewBatches(template, kind);
    if (!batches.length) return `<div class="empty">预览为空</div>`;
    return batches.map((item, index) => renderMessageTemplatePreviewItem(item, index)).join("");
  }

  function compactLine(value) {
    const text = String(value || "").replace(/\s+/g, " ").trim();
    return text.length > 48 ? `${text.slice(0, 48)}...` : (text || "-");
  }

  function messageTemplatePreviewBatches(template, kind) {
    const source = String(template || "");
    const segments = parseMessageTemplatePreviewSegments(source);
    if (!segments) {
      return messageTemplatePlainPreviewBatches(source, kind);
    }

    const batches = [];
    let current = "";
    const flush = () => {
      const text = current.trim();
      if (text) batches.push({ type: "text", text });
      current = "";
    };

    segments.forEach(segment => {
      if (segment.type === "forward") {
        flush();
        const nodes = segment.value.split("\\r")
          .map(fragment => renderMessageTemplateFragment(fragment.replace(/\\n/g, "\n"), kind).trim())
          .filter(Boolean);
        if (nodes.length) batches.push({ type: "forward", nodes });
        return;
      }
      const fragments = kind === "LIVE_ENDED" ? [segment.value] : segment.value.split("\\r");
      fragments.forEach((fragment, index) => {
        if (index > 0) flush();
        current += renderMessageTemplateFragment(fragment.replace(/\\n/g, "\n"), kind);
      });
    });
    flush();
    return batches;
  }

  function renderMessageTemplateFragment(fragment, kind) {
    const sample = messageTemplateSampleValues(kind);
    return String(fragment || "").replace(/\{([a-zA-Z0-9_]+)\}/g, (match, key) => {
      return sample[key] === undefined ? match : sample[key];
    });
  }

  function messageTemplatePlainPreviewBatches(source, kind) {
    const fragments = kind === "LIVE_ENDED" ? [source] : source.split("\\r");
    return fragments
      .map(fragment => renderMessageTemplateFragment(fragment.replace(/\\n/g, "\n"), kind).trim())
      .filter(Boolean)
      .map(text => ({ type: "text", text }));
  }

  function parseMessageTemplatePreviewSegments(source) {
    const segments = [];
    let index = 0;
    while (index < source.length) {
      const start = source.indexOf("{>>}", index);
      const end = source.indexOf("{<<}", index);
      if (end !== -1 && (start === -1 || end < start)) return null;
      if (start === -1) {
        segments.push({ type: "text", value: source.slice(index) });
        break;
      }
      if (start > index) segments.push({ type: "text", value: source.slice(index, start) });
      const contentStart = start + 4;
      const close = source.indexOf("{<<}", contentStart);
      if (close === -1) return null;
      const nested = source.indexOf("{>>}", contentStart);
      if (nested !== -1 && nested < close) return null;
      segments.push({ type: "forward", value: source.slice(contentStart, close) });
      index = close + 4;
    }
    return segments;
  }

  function renderMessageTemplatePreviewItem(item, index) {
    const labelText = item.type === "forward" ? `合并转发 ${index + 1}` : `消息 ${index + 1}`;
    if (item.type !== "forward") {
      return `<div class="message-template-preview-message">
        <span class="message-template-preview-index">${esc(labelText)}</span>
        <div class="message-template-preview-body">${esc(item.text).replace(/\n/g, "<br>")}</div>
      </div>`;
    }
    return `<div class="message-template-preview-message message-template-preview-forward">
      <span class="message-template-preview-index">${esc(labelText)}</span>
      <div class="message-template-forward-card">
        <div class="message-template-forward-summary">合并转发节点：${item.nodes.length} 条</div>
        <div class="message-template-forward-nodes">
          ${item.nodes.map((node, nodeIndex) => `<div class="message-template-forward-node">
            <span class="message-template-forward-node-title">节点 ${nodeIndex + 1}</span>
            <div class="message-template-preview-body">${esc(node).replace(/\n/g, "<br>")}</div>
          </div>`).join("")}
        </div>
      </div>
    </div>`;
  }

  function messageTemplateSampleValues(kind) {
    if (kind && kind.startsWith("LINK_")) {
      return {
        draw: "【链接预览图】",
        cover: "【封面图】",
        video: "【视频文件】",
        name: "示例 UP",
        uid: "000000000",
        id: kind === "LINK_LIVE" ? "230001" : (kind === "LINK_USER" ? "000000000" : "BV1xx411c7mD"),
        kind: kind === "LINK_LIVE" ? "直播" : (kind === "LINK_USER" ? "用户" : "视频"),
        title: kind === "LINK_LIVE" ? "今晚一起写代码" : (kind === "LINK_USER" ? "示例 UP 的主页" : "示例视频标题"),
        content: kind === "LINK_USER" ? "Bilibili 用户 000000000" : "这里是链接解析拿到的简介内容。",
        link: kind === "LINK_LIVE" ? "https://live.bilibili.com/230001" : (kind === "LINK_USER" ? "https://space.bilibili.com/000000000" : "https://www.bilibili.com/video/BV1xx411c7mD"),
        stats: "12.3万播放 / 456弹幕 / 789点赞",
        duration: "3m 21s",
        size: "18.5 MB",
      };
    }
    if (kind === "LIVE_STARTED") {
      return {
        draw: "【直播绘图】",
        name: "示例主播",
        uid: "000000000",
        rid: "230001",
        time: "2026年06月16日 20:30:00",
        title: "今晚一起写代码",
        area: "科技 / 编程",
        cover: "【直播封面】",
        link: "https://live.bilibili.com/230001",
      };
    }
    if (kind === "LIVE_ENDED") {
      return {
        name: "示例主播",
        uid: "000000000",
        rid: "230001",
        title: "今晚一起写代码",
        area: "科技 / 编程",
        startTime: "2026年06月16日 20:30:00",
        endTime: "2026年06月16日 22:04:00",
        duration: "1h 34m",
        link: "https://live.bilibili.com/230001",
      };
    }
    return {
      draw: "【动态绘图】",
      name: "示例发布者",
      uid: "000000000",
      did: "987654321",
      time: "2026年06月16日 20:30:00",
      content: "今天更新了一组开发进度，顺便整理了几张截图。",
      images: "【图片 1】\n【图片 2】",
      link: "https://t.bilibili.com/987654321",
      links: "https://example.com/post\nhttps://example.com/detail",
    };
  }

  function messageTemplateInlineText(value, kind) {
    const batches = messageTemplatePreviewBatches(value, kind);
    const text = batches.map(item => {
      if (item.type === "forward") return `合并转发(${item.nodes.length}节点)`;
      return compactLine(item.text);
    }).filter(Boolean).join(" / ");
    return text || "空模板";
  }

  function templateKindLabel(kind) {
    const map = {
      DYNAMIC: "动态推送模板",
      LIVE_STARTED: "开播推送模板",
      LIVE_ENDED: "下播推送模板",
      LINK_MESSAGE: "链接解析模板",
      LINK_VIDEO: "链接解析模板",
      LINK_LIVE: "直播链接模板",
      LINK_USER: "用户链接模板",
    };
    return map[kind] || "动态推送模板";
  }

  function normalizeKind(kind) {
    const value = String(kind || "DYNAMIC").toUpperCase();
    return value === "LINK_MESSAGE" ? "LINK_VIDEO" : value;
  }

  function supportsMessageSplit(kind) {
    return normalizeKind(kind) !== "LIVE_ENDED";
  }

  return {
    editorHtml,
    bindEditor,
    messageTemplateStats,
    messageTemplateInlineText,
    renderMessageTemplatePreview,
    templateKindLabel,
  };
}
