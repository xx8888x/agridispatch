#!/usr/bin/env node
/**
 * 智農派工 (AgriDispatch) - 前端同步腳本
 *
 * demo/ 為唯一原始碼，本腳本把它同步到：
 *   - android/app/src/main/assets/www/ （全部檔案）
 *   - web/                              （排除 js/app-config.js、js/demo-accounts.js）
 *
 * 規則：
 *   - 會刪除目標資料夾中「demo/ 已不存在」的檔案，但保留目標自己的 js/app-config.js
 *     （app-config.js 每份副本各自一份，永遠不覆蓋、不刪除）。
 *   - js/demo-accounts.js 為 demo 專用示範帳號清單，不同步到 web/。
 *   - --check：只報告差異，不寫入任何檔案；有差異時 process.exit(1)。
 *
 * 用法：
 *   node tools/sync-frontend.js           # 執行同步
 *   node tools/sync-frontend.js --check   # 僅檢查差異（CI 用）
 */

'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const SRC_DIR = path.join(ROOT, 'demo');
const TARGETS = [
  {
    name: 'android www',
    dir: path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'www'),
    exclude: new Set() // 全部同步
  },
  {
    name: 'web',
    dir: path.join(ROOT, 'web'),
    exclude: new Set(['js/app-config.js', 'js/demo-accounts.js'])
  }
];

// 目標資料夾中即使 demo/ 沒有也永遠不刪除的檔案（每份副本各自一份的設定檔）
const NEVER_DELETE = new Set(['js/app-config.js']);

const isCheck = process.argv.includes('--check');

function walk(dir, base) {
  base = base || dir;
  let out = [];
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out = out.concat(walk(full, base));
    } else if (entry.isFile()) {
      out.push(path.relative(base, full).split(path.sep).join('/'));
    }
  }
  return out;
}

function hashFile(p) {
  return crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}

function ensureDir(p) {
  fs.mkdirSync(p, { recursive: true });
}

function syncTarget(target) {
  const diffs = []; // { type: 'add'|'update'|'delete', file }
  const srcFiles = walk(SRC_DIR).filter((f) => !target.exclude.has(f));
  const srcSet = new Set(srcFiles);

  // 1. 新增 / 更新
  for (const rel of srcFiles) {
    const srcPath = path.join(SRC_DIR, rel);
    const dstPath = path.join(target.dir, rel);

    if (!fs.existsSync(dstPath)) {
      diffs.push({ type: 'add', file: rel });
      if (!isCheck) {
        ensureDir(path.dirname(dstPath));
        fs.copyFileSync(srcPath, dstPath);
      }
      continue;
    }

    const same = hashFile(srcPath) === hashFile(dstPath);
    if (!same) {
      diffs.push({ type: 'update', file: rel });
      if (!isCheck) {
        ensureDir(path.dirname(dstPath));
        fs.copyFileSync(srcPath, dstPath);
      }
    }
  }

  // 2. 刪除目標中 demo/ 已不存在的檔案（排除 exclude 名單與 NEVER_DELETE 名單）
  const dstFiles = walk(target.dir);
  for (const rel of dstFiles) {
    if (target.exclude.has(rel)) continue; // web/ 的 app-config.js / demo-accounts.js：本就不比對
    if (NEVER_DELETE.has(rel)) continue;
    if (!srcSet.has(rel)) {
      diffs.push({ type: 'delete', file: rel });
      if (!isCheck) {
        fs.unlinkSync(path.join(target.dir, rel));
      }
    }
  }

  return diffs;
}

function main() {
  if (!fs.existsSync(SRC_DIR)) {
    console.error(`[sync-frontend] 找不到原始碼資料夾: ${SRC_DIR}`);
    process.exit(1);
  }

  let totalDiffs = 0;

  for (const target of TARGETS) {
    ensureDir(target.dir);
    const diffs = syncTarget(target);
    totalDiffs += diffs.length;

    if (diffs.length === 0) {
      console.log(`[sync-frontend] ${target.name}: 無差異`);
      continue;
    }

    console.log(`[sync-frontend] ${target.name}: ${diffs.length} 項差異${isCheck ? '（僅檢查，未寫入）' : '（已同步）'}`);
    for (const d of diffs) {
      const tag = d.type === 'add' ? '+ 新增' : d.type === 'update' ? '~ 更新' : '- 刪除';
      console.log(`    ${tag}  ${d.file}`);
    }
  }

  if (isCheck && totalDiffs > 0) {
    console.log(`\n[sync-frontend] --check 發現 ${totalDiffs} 項差異，請執行 "node tools/sync-frontend.js" 同步。`);
    process.exit(1);
  }

  console.log(isCheck ? '\n[sync-frontend] --check 通過：demo/ 與 android www/、web/ 一致。' : '\n[sync-frontend] 同步完成。');
}

main();
