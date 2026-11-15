import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("../../main/resources/admin/assets/message-template-editor.js", import.meta.url), "utf8");
const { createMessageTemplateEditor } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
const editor = createMessageTemplateEditor({ document: {}, esc: String, attr: String });

test("Catalog kind names use their own preview sample", () => {
  for (const kind of ["VIDEO", "LIVE", "USER", "LINK"]) {
    assert.equal(editor.messageTemplateInlineText("{draw}", kind), "【链接预览图】");
  }
  assert.equal(editor.messageTemplateInlineText("{kind}", "LINK"), "链接");
  assert.equal(editor.messageTemplateInlineText("{kind}", "LIVE"), "直播");
});

test("Common aliases render samples and forwards suppress mention-all", () => {
  assert.doesNotMatch(editor.messageTemplateInlineText("{id} {url}", "DYNAMIC"), /\{(?:id|url)\}/);
  assert.doesNotMatch(editor.renderMessageTemplatePreview("{>>}{atAll}正文{<<}", "DYNAMIC"), /@全体/);
  assert.match(editor.renderMessageTemplatePreview("第一条\\r第二条", "LIVE_ENDED"), /消息 2/);
});
