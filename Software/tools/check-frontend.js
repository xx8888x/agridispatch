#!/usr/bin/env node
/**
 * 智農派工 (AgriDispatch) - 前端靜態檢查腳本
 *
 * 對指定資料夾內所有 .html 檔案：
 *   1. 抽出所有內嵌 <script>...</script>（不含 src=... 的外部引用）用 vm.Script 檢查語法錯誤。
 *   2. 檢查頁面中本地的 href / src（相對路徑，非 http(s):// 、非 # 、非 mailto: / tel: / javascript:）
 *      對應的檔案是否存在。
 * 任一項有錯即印出詳細訊息並以 exit code 1 結束；全部通過則 exit 0。
 *
 * 用法：
 *   node tools/check-frontend.js demo
 *   node tools/check-frontend.js web
 *   node tools/check-frontend.js android/app/src/main/assets/www
 */

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const target = process.argv[2];
if (!target) {
  console.error('用法: node tools/check-frontend.js <資料夾路徑>');
  process.exit(1);
}

const ROOT = path.resolve(__dirname, '..');
const BASE_DIR = path.isAbsolute(target) ? target : path.resolve(ROOT, target);

if (!fs.existsSync(BASE_DIR)) {
  console.error(`[check-frontend] 找不到資料夾: ${BASE_DIR}`);
  process.exit(1);
}

function walkHtml(dir) {
  let out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out = out.concat(walkHtml(full));
    } else if (entry.isFile() && entry.name.toLowerCase().endsWith('.html')) {
      out.push(full);
    }
  }
  return out;
}

// 抽出所有 <script ...>...</script>，跳過帶 src 屬性（外部檔案）的
function extractInlineScripts(html) {
  const scripts = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const attrs = m[1] || '';
    const body = m[2] || '';
    if (/\bsrc\s*=/i.test(attrs)) continue; // 外部腳本略過
    if (/type\s*=\s*["'](?!text\/javascript|application\/javascript)[^"']*["']/i.test(attrs)) continue; // 非 JS type（如 application/json）略過
    if (!body.trim()) continue;
    // 計算行號（方便定位）
    const line = html.slice(0, m.index).split('\n').length;
    scripts.push({ body, line });
  }
  return scripts;
}

// 抽出本地 href / src 連結（略過 Vue 動態綁定 :href= / :src= 與模板運算式）
function extractLocalLinks(html) {
  const links = [];
  const re = /([:\w-]*)\b(href|src)\s*=\s*["']([^"']+)["']/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const prefixChar = m[1] ? m[1].slice(-1) : '';
    if (prefixChar === ':') continue; // Vue :href / :src 動態綁定，非靜態路徑
    const val = m[3].trim();
    if (!val) continue;
    if (/^(https?:)?\/\//i.test(val)) continue; // 外部網址
    if (/^(#|mailto:|tel:|javascript:|data:)/i.test(val)) continue;
    if (/[{}$]|\s/.test(val)) continue; // 含模板運算式 / 空白，非靜態路徑
    links.push(val);
  }
  return links;
}

let errorCount = 0;
const htmlFiles = walkHtml(BASE_DIR);

for (const file of htmlFiles) {
  const html = fs.readFileSync(file, 'utf-8');
  const relFile = path.relative(ROOT, file);

  // 1. 語法檢查
  const scripts = extractInlineScripts(html);
  for (const s of scripts) {
    try {
      new vm.Script(s.body, { filename: `${relFile}:L${s.line}` });
    } catch (e) {
      errorCount++;
      console.error(`[SYNTAX] ${relFile} (行 ${s.line}附近): ${e.message}`);
    }
  }

  // 2. 本地連結存在性檢查（inline <script> 內容先清空，避免誤判 JS 字串中的 src=/href=）
  const htmlNoScript = html.replace(/(<script\b[^>]*>)([\s\S]*?)(<\/script>)/gi, '$1$3');
  const links = extractLocalLinks(htmlNoScript);
  const fileDir = path.dirname(file);
  for (const link of links) {
    const clean = link.split('#')[0].split('?')[0];
    if (!clean) continue; // 純 #anchor 或 ?query
    const resolved = path.resolve(fileDir, clean);
    if (!fs.existsSync(resolved)) {
      errorCount++;
      console.error(`[LINK] ${relFile}: 找不到本地連結目標 "${link}" (解析為 ${path.relative(ROOT, resolved)})`);
    }
  }
}

console.log(`[check-frontend] 已檢查 ${htmlFiles.length} 個 HTML 檔案（${BASE_DIR}）`);

if (errorCount > 0) {
  console.error(`[check-frontend] 發現 ${errorCount} 項錯誤`);
  process.exit(1);
}

console.log('[check-frontend] 全部通過，無錯誤。');
