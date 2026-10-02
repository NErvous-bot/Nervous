/**
 * 镜像线路自动维护（探活剔除 + 自动扩容）：
 * 1) 探活 urls.txt，剔除死线（存活<3条时不动名单，防网络抖动误删；
 *    raw.githubusercontent.com 官方源永不剔除）
 * 2) 名单不足 MAX 条时，从 candidates.txt 候选池自动探测新镜像补充
 * 全程无需人工干预；候选池枯竭时才需要人工补充新候选站。
 */
const fs = require('fs');
const https = require('https');

const FILE = 'urls.txt';
const POOL = 'candidates.txt';
const MIN_KEEP = 3;      // 存活少于3条时不写回，防误删
const MAX_LINES = 10;    // 名单上限（App按顺序试，太长拖慢同步）
const TIMEOUT = 15000;

const UA = 'Mozilla/5.0 (X11; Linux x86_64) Chrome/142.0 Safari/537.36';

function probe(url) {
    return new Promise(resolve => {
        const req = https.get(url, { timeout: TIMEOUT, headers: { 'User-Agent': UA } }, res => {
            if (res.statusCode !== 200) { res.resume(); return resolve(false); }
            let n = 0;
            res.on('data', d => {
                n += d.length;
                if (n > 50000) { req.destroy(); resolve(true); }  // 收到50KB即认为有效
            });
            res.on('end', () => resolve(n > 10000));              // legado.json 约几MB
        });
        req.on('timeout', () => { req.destroy(); resolve(false); });
        req.on('error', () => resolve(false));
    });
}

function readUrls(file) {
    if (!fs.existsSync(file)) return [];
    return fs.readFileSync(file, 'utf8').split('\n').map(s => s.trim())
        .filter(s => s && !s.startsWith('#'));
}

(async () => {
    const active = readUrls(FILE);
    const pool = readUrls(POOL);

    // 1) 探活现有名单
    const alive = [];
    for (const u of active) {
        const official = u.includes('raw.githubusercontent.com/');
        const ok = await probe(u);
        console.log((ok ? '[活] ' : '[死] ') + u);
        if (ok || official) alive.push(u);   // 官方源永不剔除（runner连不上≠手机连不上）
    }
    if (alive.length < MIN_KEEP) {
        console.log(`存活仅 ${alive.length} 条（<${MIN_KEEP}），疑似网络抖动，本次不动名单`);
        return;
    }

    // 2) 名单不满时从候选池自动扩容
    let added = 0;
    if (alive.length < MAX_LINES && pool.length > 0) {
        for (const c of pool) {
            if (alive.length >= MAX_LINES) break;
            if (alive.includes(c)) continue;
            const ok = await probe(c);
            console.log((ok ? '[候选可用] ' : '[候选失效] ') + c);
            if (ok) { alive.push(c); added++; }
        }
    }

    // 3) 有变化才写回（注释头保留）
    const changed = alive.length !== active.length || alive.some((u, i) => u !== active[i]);
    if (!changed) {
        console.log(`名单无变化（${alive.length} 条全部有效）`);
        return;
    }
    const comments = fs.readFileSync(FILE, 'utf8').split('\n').filter(s => s.trim().startsWith('#'));
    fs.writeFileSync(FILE, comments.join('\n') + '\n' + alive.join('\n') + '\n');
    console.log(`urls.txt 已更新：有效 ${alive.length} 条（候选新增 ${added} 条）`);
})();
