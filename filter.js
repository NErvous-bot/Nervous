// legado 书源自动维护流水线
// 流程：上游更新合并 → 域名探测 → 域名变体修复 → 阈值保护(失败保留旧版) → 写回提交
// 无任何依赖，GitHub Actions 的 Node 20 可直接运行
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const fs = require('fs');
const UA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36';
// 上游有效源合集（大灰狼仓库自动效验后的有效书源，3373 个）：作者修复了某源规则时，这里会自动拉新版进来
const UPSTREAM = 'https://raw.githubusercontent.com/shidahuilang/shuyuan-bak/main/good.json';

function norm(u) {
    try { const x = new URL(String(u).split('#')[0]); return x.origin + x.pathname.replace(/\/$/, ''); }
    catch (e) { return null; }
}
function originOf(u) {
    try { return new URL(String(u).split('#')[0]).origin; } catch (e) { return null; }
}

async function get(u, ms) {
    const c = new AbortController();
    const t = setTimeout(() => c.abort(), ms || 12000);
    try {
        const r = await fetch(u, { headers: { 'User-Agent': UA }, signal: c.signal, redirect: 'follow' });
        clearTimeout(t); return r;
    } catch (e) { clearTimeout(t); return null; }
}

async function alive(o) {
    const r = await get(o, 15000);
    if (!r) return false;
    if (r.status >= 500) return false;
    if (r.status === 404 || r.status === 410) return false;
    return true;
}

async function pool(items, n, fn) {
    const ret = []; let i = 0;
    async function w() { while (i < items.length) { const k = i++; ret[k] = await fn(items[k], k); } }
    await Promise.all(Array.from({ length: n }, w));
    return ret;
}

async function upstreamMap() {
    try {
        console.log('拉取上游书源全集...');
        const r = await get(UPSTREAM, 60000);
        if (!r || !r.ok) throw new Error('HTTP ' + (r ? r.status : 'fail'));
        const arr = JSON.parse(await r.text());
        const m = new Map();
        for (const s of arr) { const k = norm(s.bookSourceUrl); if (k && !m.has(k)) m.set(k, s); }
        console.log('上游共 ' + arr.length + ' 个源');
        return m;
    } catch (e) {
        console.log('上游获取失败（不影响本次保活）: ' + e.message);
        return null;
    }
}

// 死域名的变体：换协议(http/https)、加/去 www —— 盗版站最常见的"搬家"方式
function variants(o) {
    const u = new URL(o);
    const h = u.hostname.replace(/^www\./, '');
    const protos = u.protocol === 'https:' ? ['https:', 'http:'] : ['http:', 'https:'];
    const out = [];
    for (const p of protos) for (const n of [h, 'www.' + h]) {
        const v = p + '//' + n;
        if (v !== o) out.push(v);
    }
    return out;
}

(async () => {
    const file = 'legado.json';
    const list = JSON.parse(fs.readFileSync(file, 'utf8'));
    let merged = 0, repaired = 0;

    // 1) 上游合并：同名源取 lastUpdateTime 更新的版本（作者修复的规则自动进来）
    const up = await upstreamMap();
    if (up) {
        for (const s of list) {
            const u = up.get(norm(s.bookSourceUrl));
            if (u && (u.lastUpdateTime || 0) > (s.lastUpdateTime || 0)) { Object.assign(s, u); merged++; }
        }
        console.log('上游合并更新 ' + merged + ' 个源');
    }

    // 2) 域名探测
    const origins = [...new Set(list.map(s => originOf(s.bookSourceUrl)).filter(Boolean))];
    console.log('探测 ' + origins.length + ' 个域名...');
    const dead = new Set(
        (await pool(origins, 12, o => alive(o).then(a => [o, a]))).filter(x => !x[1]).map(x => x[0])
    );

    // 3) 修复尝试：死域名逐个试变体，任何一个活着就整体迁移过去
    for (const d of [...dead]) {
        for (const v of variants(d)) {
            if (await alive(v)) {
                for (const s of list) {
                    if (originOf(s.bookSourceUrl) === d) {
                        s.bookSourceUrl = String(s.bookSourceUrl).split(d).join(v);
                        if (s.searchUrl) s.searchUrl = String(s.searchUrl).split(d).join(v);
                        s.lastUpdateTime = Date.now();
                    }
                }
                dead.delete(d); repaired++;
                console.log('修复: ' + d + ' → ' + v);
                break;
            }
        }
    }

    const out = list.filter(s => !dead.has(originOf(s.bookSourceUrl)));
    const removed = list.length - out.length;
    console.log('存活 ' + out.length + ' / ' + list.length + '，剔除 ' + removed + ' 个');
    if (dead.size) console.log('最终死域名:\n' + [...dead].join('\n'));

    // 4) 阈值保护：单次剔除超 30% 视为 Actions 网络抖动，放弃写入（旧版本保留）
    if (list.length > 0 && removed > list.length * 0.3) {
        console.log('⚠️ 本次剔除超过 30%，疑似运行环境网络抖动，放弃写入，旧版本保留。');
        return;
    }
    if (merged === 0 && repaired === 0 && removed === 0) {
        console.log('无变化，不提交');
        return;
    }
    fs.writeFileSync(file, JSON.stringify(out));
    console.log('legado.json 已更新（合并 ' + merged + ' / 修复 ' + repaired + ' / 剔除 ' + removed + '）');
})();
