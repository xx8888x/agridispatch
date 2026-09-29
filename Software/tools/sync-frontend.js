#!/usr/bin/env node
/**
 * ?箄噙瘣曉極 (AgriDispatch) - ?垢?郊?單
 *
 * demo/ ?箏銝??蝣潘??祈?祆?摰?甇亙嚗? *   - android/app/src/main/assets/www/ 嚗?冽?獢?
 *   - web/                              嚗???js/app-config.js?s/demo-accounts.js嚗? *
 * 閬?嚗? *   - ??斤璅??冗銝准emo/ 撌脖?摮??瑼?嚗?靽??格??芸楛??js/app-config.js
 *     嚗pp-config.js 瘥遢?舀?銝隞踝?瘞賊?銝????芷嚗? *   - js/demo-accounts.js ??demo 撠蝷箇?撣唾?皜嚗??郊??web/?? *   - --check嚗?勗?撌桃嚗?撖怠隞颱?瑼?嚗?撌桃??process.exit(1)?? *
 * ?冽?嚗? *   node tools/sync-frontend.js           # ?瑁??郊
 *   node tools/sync-frontend.js --check   # ?炎?亙榆?堆?CI ?剁?
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
    exclude: new Set() // ?券?郊
  },
  {
    name: 'web',
    dir: path.join(ROOT, 'web'),
    exclude: new Set(['科技後台/js/app-config.js', '科技後台/js/demo-accounts.js', 'linebot/js/app-config.js', 'linebot/js/demo-accounts.js', '農業派遣主網站/js/app-config.js', '農業派遣主網站/js/demo-accounts.js'])
  }
];

// ?格?鞈?憭曆葉?喃蝙 demo/ 瘝?銋偶???芷??獢?瘥遢?舀?銝隞賜?閮剖?瑼?
const NEVER_DELETE = new Set(['科技後台/js/app-config.js', 'linebot/js/app-config.js', '農業派遣主網站/js/app-config.js']);

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

  // 1. ?啣? / ?湔
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

  // 2. ?芷?格?銝?demo/ 撌脖?摮??獢?? exclude ???NEVER_DELETE ?嚗?  const dstFiles = walk(target.dir);
  for (const rel of dstFiles) {
    if (target.exclude.has(rel)) continue; // web/ ??app-config.js / demo-accounts.js嚗撠曹?瘥?
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
    console.error(`[sync-frontend] ?曆??啣?憪Ⅳ鞈?憭? ${SRC_DIR}`);
    process.exit(1);
  }

  let totalDiffs = 0;

  for (const target of TARGETS) {
    ensureDir(target.dir);
    const diffs = syncTarget(target);
    totalDiffs += diffs.length;

    if (diffs.length === 0) {
      console.log(`[sync-frontend] ${target.name}: ?∪榆?躬);
      continue;
    }

    console.log(`[sync-frontend] ${target.name}: ${diffs.length} ?榆??{isCheck ? '嚗?瑼Ｘ嚗撖怠嚗? : '嚗歇?郊嚗?}`);
    for (const d of diffs) {
      const tag = d.type === 'add' ? '+ ?啣?' : d.type === 'update' ? '~ ?湔' : '- ?芷';
      console.log(`    ${tag}  ${d.file}`);
    }
  }

  if (isCheck && totalDiffs > 0) {
    console.log(`\n[sync-frontend] --check ?潛 ${totalDiffs} ?榆?堆?隢銵?"node tools/sync-frontend.js" ?郊?);
    process.exit(1);
  }

  console.log(isCheck ? '\n[sync-frontend] --check ??嚗emo/ ??android www/?eb/ 銝?氬? : '\n[sync-frontend] ?郊摰???);
}

main();


