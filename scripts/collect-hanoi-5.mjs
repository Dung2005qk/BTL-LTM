#!/usr/bin/env node
/**
 * Thu thập 5 địa điểm Hà Nội mới từ KartaView (kartaview.org, ảnh giấy phép CC BY-SA 4.0)
 * cho game GeoDuel. Thay thế cho tools.KartaViewCollector (Java) vì API KartaView
 * hiện chỉ trả 1 ảnh đại diện mỗi sequence → chọn target + manh mối từ các sequence
 * lân cận khác nhau trong bán kính 200 m quanh đích (vẫn đúng đặc tả).
 *
 * Chạy: node scripts/collect-hanoi-5.mjs
 * Kết quả: assets/raw/<slug>/img1..4.jpg + cập nhật assets/raw/manifest.json.
 * Chạy lại an toàn: slug đã có trong manifest sẽ bị bỏ qua.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import path from 'node:path';

const API = 'https://api.kartaview.org/2.0/photo/';
const UA = 'GeoDuelLTM/1.0 (university course project)';
const THROTTLE_MS = 600;
const RADIUS = 500;               // API giới hạn radius <= 500 m
const CELL_OFFSETS = [
  [0, 0], [0.008, 0], [-0.008, 0], [0, 0.008], [0, -0.008],
  [0.008, 0.008], [0.008, -0.008], [-0.008, 0.008], [-0.008, -0.008],
];
const MIN_TARGET_SEPARATION_M = 500;
const MAX_CLUE_RADIUS_M = 200;
const MIN_FRAME_SPACING_M = 30;
const MIN_IMAGES = 3;             // AssetSeeder yêu cầu >= 3 ảnh
const WANT_IMAGES = 4;
const MIN_BYTES = 25_000;
const MIN_WIDTH = 1600, MIN_WIDTH_PANO = 2048;

const ROOT = path.resolve(import.meta.dirname, '..');
const RAW = path.join(ROOT, 'assets', 'raw');

const SEEDS = [
  { slug: 'hanoi-hoangmai-1',   name: 'Hà Nội · điểm 1', lat: 20.9760, lng: 105.8480 },
  { slug: 'hanoi-bactuliem-1',  name: 'Hà Nội · điểm 1', lat: 21.0480, lng: 105.7820 },
  { slug: 'hanoi-haibatrung-1', name: 'Hà Nội · điểm 1', lat: 21.0059, lng: 105.8466 },
  { slug: 'hanoi-donganh-1',    name: 'Hà Nội · điểm 1', lat: 21.1403, lng: 105.7986 },
  { slug: 'hanoi-gialam-1',     name: 'Hà Nội · điểm 1', lat: 21.0280, lng: 105.9250 },
];

const manifestPath = path.join(RAW, 'manifest.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const existingTargets = manifest.locations.map(o => [o.lat, o.lng]);
const haveSlug = new Set(manifest.locations.map(o => o.slug));
const usedTargetHashes = new Set();
for (const o of manifest.locations) {
  const img1 = path.join(RAW, o.slug, 'img1.jpg');
  if (existsSync(img1)) usedTargetHashes.add(sha1(readFileSync(img1)));
}

let lastRequestAt = 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));

function sha1(buf) { return createHash('sha1').update(buf).digest('hex'); }

function haversineM(aLat, aLng, bLat, bLng) {
  const R = 6371, toRad = d => d * Math.PI / 180;
  const dLat = toRad(bLat - aLat), dLng = toRad(bLng - aLng);
  const h = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(aLat)) * Math.cos(toRad(bLat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h)) * 1000;
}

async function throttle() {
  const wait = lastRequestAt + THROTTLE_MS - Date.now();
  if (wait > 0) await sleep(wait);
  lastRequestAt = Date.now();
}

async function fetchRetry(url, asBytes) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    await throttle();
    try {
      const resp = await fetch(url, {
        headers: { 'User-Agent': UA },
        signal: AbortSignal.timeout(45_000),
      });
      if (resp.ok) return asBytes ? Buffer.from(await resp.arrayBuffer()) : await resp.json();
      if (resp.status === 429 || resp.status >= 500) {
        console.log(`  HTTP ${resp.status}, thu lai sau ${2000 * attempt} ms (${attempt}/4)`);
        await sleep(2000 * attempt);
        continue;
      }
      console.log(`  HTTP ${resp.status}: ${url}`);
    } catch (e) {
      console.log(`  Loi mang (${attempt}/4): ${e.message || e}`);
    }
    await sleep(1000);
  }
  return null;
}

/** Đọc kích thước ảnh JPEG bằng cách quét marker SOF (không phụ thuộc thư viện ngoài). */
function jpegSize(buf) {
  if (buf.length < 4 || buf[0] !== 0xFF || buf[1] !== 0xD8) return null;
  let i = 2;
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xFF) { i++; continue; }
    const marker = buf[i + 1];
    if (marker >= 0xC0 && marker <= 0xCF && marker !== 0xC4 && marker !== 0xC8 && marker !== 0xCC) {
      return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
    }
    const len = buf.readUInt16BE(i + 2);
    if (len < 2) return null;
    i += 2 + len;
  }
  return null;
}

function pickUrl(photo, pano) {
  if (pano) return photo.fileurlProc || photo.fileurlLTh;
  return photo.fileurlLTh || photo.fileurlProc;
}

async function downloadValid(photo) {
  const pano = String(photo.fieldOfView) === '360';
  const url = pickUrl(photo, pano);
  if (!url) return null;
  const buf = await fetchRetry(url, true);
  if (!buf || buf.length < MIN_BYTES || buf[0] !== 0xFF || buf[1] !== 0xD8) return null;
  const dim = jpegSize(buf);
  if (!dim) return null;
  const minW = pano ? MIN_WIDTH_PANO : MIN_WIDTH;
  if (dim.width < minW) return null;
  return { buf, pano, dim };
}

async function searchCells(seed) {
  const byId = new Map();
  for (const [dLat, dLng] of CELL_OFFSETS) {
    const url = `${API}?lat=${seed.lat + dLat}&lng=${seed.lng + dLng}&radius=${RADIUS}&itemsPerPage=100`;
    const body = await fetchRetry(url, false);
    const data = body?.result?.data ?? [];
    for (const p of data) {
      if (p.id != null && !byId.has(p.id) && Number.isFinite(+p.lat) && Number.isFinite(+p.lng)) {
        byId.set(p.id, p);
      }
    }
  }
  return [...byId.values()];
}

function farFromExistingTargets(lat, lng) {
  return existingTargets.every(([tLat, tLng]) => haversineM(tLat, tLng, lat, lng) >= MIN_TARGET_SEPARATION_M);
}

async function collectOne(seed) {
  if (haveSlug.has(seed.slug)) {
    console.log(`${seed.slug}: da co trong manifest, bo qua`);
    return true;
  }
  console.log(`${seed.slug}: dang quet quanh ${seed.lat},${seed.lng} ...`);
  const photos = await searchCells(seed);
  // KHÔNG lọc autoImgProcessingResult=BLURRED — cờ này nghĩa là KartaView đã tự làm
  // mờ biển số/khuôn mặt (GDPR), gần như 100% ảnh đều mang cờ và vẫn dùng được.
  // Sắp xếp: ưu tiên ứng viên có nhiều ảnh lân cận nhất trong 200 m (cụm dày,
  // đủ manh mối), rồi phân giải cao, rồi gần tâm quét.
  const density = p => photos.filter(q => haversineM(+p.lat, +p.lng, +q.lat, +q.lng) <= MAX_CLUE_RADIUS_M).length;
  const cands = photos
    .map(p => ({ p, n: density(p) }))
    .sort((a, b) => b.n - a.n
      || ((+b.p.width || 0) >= 2000 ? 0 : 1) - ((+a.p.width || 0) >= 2000 ? 0 : 1)
      || (+a.p.distance || 99999) - (+b.p.distance || 99999))
    .map(x => x.p);
  if (cands.length === 0) { console.log(`${seed.slug}: khong co ung vien`); return false; }

  const outDir = path.join(RAW, seed.slug);
  const files = [];
  try {
    // Ảnh đích: đầu tiên thỏa khoảng cách tới các điểm đã có + tải được
    let target = null;
    for (const p of cands) {
      if (!farFromExistingTargets(+p.lat, +p.lng)) continue;
      const got = await downloadValid(p);
      if (!got) continue;
      const hash = sha1(got.buf);
      if (usedTargetHashes.has(hash)) continue;
      usedTargetHashes.add(hash);
      target = { photo: p, ...got, lat: +p.lat, lng: +p.lng };
      break;
    }
    if (!target) { console.log(`${seed.slug}: khong tim duoc anh dich hop le`); return false; }
    mkdirSync(outDir, { recursive: true });

    // Manh mối: ảnh khác trong bán kính 200 m quanh đích, cách nhau >= 30 m
    const picked = [{ lat: target.lat, lng: target.lng }];
    const cluePool = cands
      .filter(p => p.id !== target.photo.id)
      .map(p => ({ p, d: haversineM(target.lat, target.lng, +p.lat, +p.lng) }))
      .filter(x => x.d <= MAX_CLUE_RADIUS_M)
      .sort((a, b) => a.d - b.d);
    for (const { p } of cluePool) {
      if (picked.length >= WANT_IMAGES) break;
      if (picked.some(q => haversineM(q.lat, q.lng, +p.lat, +p.lng) < MIN_FRAME_SPACING_M)) continue;
      const got = await downloadValid(p);
      if (!got) continue;
      picked.push({ lat: +p.lat, lng: +p.lng });
      const n = files.length + 2; // img1 ghi sau cùng để lỗi giữa chừng không để lại target sai
      writeFileSync(path.join(outDir, `img${n}.jpg`), got.buf);
      files.push({ f: `img${n}.jpg`, pano: got.pano });
    }
    if (files.length + 1 < MIN_IMAGES) {
      console.log(`${seed.slug}: chi du ${files.length + 1} anh, bo qua`);
      rmSync(outDir, { recursive: true, force: true });
      return false;
    }
    writeFileSync(path.join(outDir, 'img1.jpg'), target.buf);
    files.unshift({ f: 'img1.jpg', pano: target.pano });

    manifest.locations.push({
      slug: seed.slug, name: seed.name, country: 'Việt Nam',
      lat: target.lat, lng: target.lng, files,
    });
    existingTargets.push([target.lat, target.lng]);
    haveSlug.add(seed.slug);
    writeFileSync(manifestPath, JSON.stringify(manifest));
    console.log(`${seed.slug}: OK ${files.length} anh (dich ${target.lat.toFixed(5)},${target.lng.toFixed(5)}${target.pano ? ', 360' : ''})`);
    return true;
  } catch (e) {
    console.log(`${seed.slug}: loi - ${e.message || e}`);
    rmSync(outDir, { recursive: true, force: true });
    return false;
  }
}

let ok = 0;
for (const seed of SEEDS) {
  if (await collectOne(seed)) ok++;
}
console.log(`HOAN TAT: ${ok}/${SEEDS.length} dia diem moi, manifest co ${manifest.locations.length} dia diem`);
if (ok < SEEDS.length) process.exitCode = 1;
