/**
 * 镜像线路自动探活：
 * 逐条请求 urls.txt 里的线路，能返回有效 legado.json 的保留，
 * 死线剔除后写回 urls.txt（由 update.yml 的 commit 步骤提交）。
 * 防误删规则：存活少于3条时不写回，保持原名单。
 */
const fs = require('fs');
const https = require('https');

const FILE = 'urls.txt';
const MIN_KEEP = 3;

const urls = fs.readFileSync(FILE, 'utf8')
    .split('\n').map(s => s.trim())
    .filter(s => s && !s.startsWith('#'));

function probe(url) {
    return new Promise(resolve => {
        const req = https.get(url, {
            timeout: 15000,
            headers: { 'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) Chrome/142.0 Safari/537.36' }
        }, res => {
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

(async () => {
    const alive = [];
    for (const u of urls) {
        const ok = await probe(u);
        console.log((ok ? '[活] ' : '[死] ') + u);
        if (ok) alive.push(u);
    }
    if (alive.length < MIN_KEEP) {
        console.log(`存活仅 ${alive.length} 条（<${MIN_KEEP}），疑似网络抖动，本次不剔除，保持原名单`);
        return;
    }
    const changed = alive.length !== urls.length || alive.some((u, i) => u !== urls[i]);
    if (!changed) {
        console.log(`名单无变化（${alive.length}/${urls.length} 全部存活）`);
        return;
    }
    const comments = fs.readFileSync(FILE, 'utf8').split('\n').filter(s => s.trim().startsWith('#'));
    fs.writeFileSync(FILE, comments.join('\n') + '\n' + alive.join('\n') + '\n');
    console.log(`urls.txt 已更新：保留 ${alive.length}/${urls.length} 条`);
})();
