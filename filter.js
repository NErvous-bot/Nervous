// legado 书源自动保活脚本：探测每个源的域名，剔除已死的源
// 无任何依赖，GitHub Actions 的 Node 20 可直接运行
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const fs = require('fs');
const UA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36';

function originOf(u) {
    try { return new URL(String(u).split('#')[0]).origin; } catch (e) { return null; }
}

async function alive(origin) {
    try {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), 15000);
        const r = await fetch(origin, { headers: { 'User-Agent': UA }, signal: ctrl.signal, redirect: 'follow' });
        clearTimeout(t);
        if (r.status >= 500) return false;   // 服务器挂了
        if (r.status === 404 || r.status === 410) return false; // 页面没了
        return true;                          // 200/301/403 等都算活着
    } catch (e) { return false; }             // DNS失效/连接拒绝/超时/证书错误 = 死
}

// 并发池：15 个并发逐批探测
async function pool(items, n, fn) {
    const ret = []; let i = 0;
    async function worker() {
        while (i < items.length) { const k = i++; ret[k] = await fn(items[k], k); }
    }
    await Promise.all(Array.from({ length: n }, worker));
    return ret;
}

(async () => {
    const file = 'legado.json';
    const list = JSON.parse(fs.readFileSync(file, 'utf8'));
    const origins = [...new Set(list.map(s => originOf(s.bookSourceUrl)).filter(Boolean))];
    console.log('探测 ' + origins.length + ' 个域名（' + list.length + ' 个源）...');
    const results = await pool(origins, 15, o => alive(o).then(a => [o, a]));
    const dead = new Set(results.filter(x => !x[1]).map(x => x[0]));
    const out = list.filter(s => !dead.has(originOf(s.bookSourceUrl)));
    console.log('存活 ' + out.length + ' / ' + list.length + '，剔除 ' + (list.length - out.length) + ' 个');
    if (dead.size) console.log('已死域名:\n' + [...dead].join('\n'));
    if (out.length !== list.length) {
        const now = Date.now();
        out.forEach(s => { s.lastUpdateTime = now; }); // 刷新时间戳，手机重新导入时强制覆盖旧源
        fs.writeFileSync(file, JSON.stringify(out));
        console.log('legado.json 已更新');
    } else {
        console.log('无变化，不提交');
    }
})();
