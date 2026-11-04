import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const source = await readFile(new URL("../../main/resources/admin/pages/login.js", import.meta.url), "utf8");
const { cookieHeaderFromJson } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);

test("Weibo raw export contains only live cookies matching the homepage", () => {
  const rows = [
    { name: "SUB", value: "desktop", domain: ".weibo.com", path: "/", expires: 2000 },
    { name: "SUB", value: "mobile", domain: ".weibo.cn", path: "/" },
    { name: "ALC", value: "sso", domain: ".login.sina.com.cn", path: "/" },
    { name: "SRF", value: "passport", domain: ".passport.weibo.com", path: "/" },
    { name: "expired", value: "old", domain: ".weibo.com", expires: 999 },
    { name: "private", value: "path", domain: ".weibo.com", path: "/private" },
    { name: "XSRF-TOKEN", value: "token", domain: "weibo.com", hostOnly: true },
  ];
  assert.equal(cookieHeaderFromJson(JSON.stringify(rows), "weibo", 1000), "SUB=desktop;XSRF-TOKEN=token;");
  assert.equal(cookieHeaderFromJson(JSON.stringify({ cookies: rows }), "weibo", 1000), "SUB=desktop;XSRF-TOKEN=token;");
});

test("Other platform exports retain their existing format", () => {
  assert.equal(cookieHeaderFromJson('[{"name":"SESSDATA","value":"session","domain":".bilibili.com"}]', "bilibili"), "SESSDATA=session;");
  assert.equal(cookieHeaderFromJson("bad json", "weibo"), "");
});
