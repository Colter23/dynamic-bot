import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

async function loadPage(name, setup) {
  const source = await readFile(new URL(`../../main/resources/admin/pages/${name}.js`, import.meta.url), "utf8");
  const context = vm.createContext({});
  // Exercise the page's own functions without mounting unrelated DOM controls.
  vm.runInContext(source.replace(/^import .+;\r?$/gm, "").replace(/^export /gm, "") + "\n" + setup, context);
  return context;
}

test("Template platform matching reuses normalized entries at every inheritance level", async () => {
  const page = await loadPage("configs", `
    globalThis.controls = {
      "#templateSetPlatform": { value: "bilibili" },
      "#templateSetPurpose": { value: "linkParsing" },
    };
    root = { querySelector: selector => controls[selector] };
    globalThis.templates = {
      dynamic: "global-base",
      linkParsing: { dynamic: "global-purpose" },
      platforms: [{ platformId: " BILIBILI ", base: { dynamic: "platform-base" }, linkParsing: { dynamic: "platform-purpose" } }],
    };
  `);
  assert.equal(vm.runInContext('effectiveTemplate(templates, "dynamic")', page), "platform-purpose");
  vm.runInContext('templateSetScope(templates, true).dynamic = "edited"', page);
  assert.equal(page.templates.platforms.length, 1);
  assert.equal(page.templates.platforms[0].linkParsing.dynamic, "edited");
  assert.equal(page.templates.platforms[0].platformId, " BILIBILI ");
  vm.runInContext('templateSetScope(templates, true).dynamic = " "', page);
  assert.equal(vm.runInContext('effectiveTemplate(templates, "dynamic")', page), "global-purpose");
  vm.runInContext('controls["#templateSetPurpose"].value = "base"', page);
  assert.equal(vm.runInContext('effectiveTemplate(templates, "dynamic")', page), "platform-base");
  vm.runInContext('controls["#templateSetPlatform"].value = " WEIBO "', page);
  vm.runInContext('templateSetScope(templates, true).dynamic = "weibo-base"', page);
  assert.equal(page.templates.platforms[1].platformId, "weibo");
});

test("Template platform options deduplicate catalog and configured IDs", async () => {
  const page = await loadPage("configs", `
    globalThis.select = { addEventListener() {} };
    root = { querySelector: selector => {
      if (selector === "#templateSetPlatform") return select;
      if (selector === "#templateSetPurpose") return { addEventListener() {} };
      if (selector === "#cfg-template-set") return { value: JSON.stringify({ platforms: [
        { platformId: " BILIBILI " }, { platformId: " WEIBO " }, { platformId: " " },
      ] }) };
      return null;
    } };
    templateCatalog = { platforms: ["bilibili", "weibo", "Custom", "CUSTOM"] };
    esc = attr = String;
  `);
  await vm.runInContext("bindTemplateSet()", page);
  assert.equal((page.select.innerHTML.match(/<option /g) || []).length, 4);
  assert.match(page.select.innerHTML, /value="bilibili"/);
  assert.match(page.select.innerHTML, /value="weibo"/);
  assert.match(page.select.innerHTML, /value="custom"/);
});

test("Template editor follows the returned event instead of the selected preset or source label", async () => {
  const page = await loadPage("testing", `
    ctx = { state: { adminTesting: {
      mode: "MOCK", presetId: "dynamic", presets: [{ id: "dynamic", eventType: "DYNAMIC" }],
      result: null,
    } } };
  `);
  for (const [eventType, expected] of [["live.started", "LIVE_STARTED"], ["live.ended", "LIVE_ENDED"], ["dynamic.created", "DYNAMIC"]]) {
    page.eventType = eventType;
    vm.runInContext(`pageState().result = {
      resolutionType: "CUSTOM_SOURCE_UPDATE", templateSource: "全局基础模板",
      update: { key: { eventType } },
    }`, page);
    assert.equal(vm.runInContext("currentTemplateKind()", page), expected);
    vm.runInContext('pageState().result.templateSource = "OVERRIDE"', page);
    assert.equal(vm.runInContext("currentTemplateKind()", page), expected);
  }
  vm.runInContext(`pageState().presets[0].eventType = "LIVE_ENDED"`, page);
  assert.equal(vm.runInContext("currentTemplateKind()", page), "DYNAMIC");
  vm.runInContext("pageState().result = null", page);
  assert.equal(vm.runInContext("currentTemplateKind()", page), "LIVE_ENDED");
});

test("Link previews and real dynamic results retain their own template kinds", async () => {
  const page = await loadPage("testing", `
    ctx = { state: { adminTesting: { mode: "REAL_LINK", presets: [], result: null } } };
  `);
  for (const [kind, expected] of [["VIDEO", "LINK_VIDEO"], ["LIVE", "LINK_LIVE"], ["USER", "LINK_USER"]]) {
    page.linkKind = kind;
    vm.runInContext('pageState().result = { resolutionType: "PREVIEW", parsedLink: { kind: linkKind } }', page);
    assert.equal(vm.runInContext("currentTemplateKind()", page), expected);
  }
  vm.runInContext(`pageState().result = {
    resolutionType: "DYNAMIC", update: { key: { eventType: "live.ended" } },
  }`, page);
  assert.equal(vm.runInContext("currentTemplateKind()", page), "LIVE_ENDED");
});
