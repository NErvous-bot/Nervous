const https = require('https');
const fs = require('fs');
const T = fs.readFileSync('C:/Users/ASUS/.cache/gh_token.txt', 'utf8').trim();
function api(p) {
  return new Promise((ok, no) => {
    https.get(p, { headers: { 'User-Agent': 'v', 'Authorization': 'Bearer ' + T } }, r => {
      let b = '';
      r.on('data', c => b += c);
      r.on('end', () => {
        if (!b) return ok({ code: r.statusCode, body: '' });
        try { ok({ code: r.statusCode, body: JSON.parse(b) }); } catch (e) { ok({ code: r.statusCode, body: b }); }
      });
    }).on('error', no);
  });
}
(async () => {
  const j = await api('https://api.github.com/repos/NErvous-bot/x2yefuobr-15whlj/actions/runs?per_page=1');
  const run = j.body.workflow_runs[0];
  const logs = await api('https://api.github.com/repos/NErvous-bot/x2yefuobr-15whlj/actions/runs/' + run.id + '/logs');
  const text = typeof logs.body === 'string' ? logs.body : JSON.stringify(logs.body);
  const lines = text.split('\n');
  const section = lines.filter(x =>
    x.includes('发布APK到仓库') ||
    x.toLowerCase().includes('conflict') ||
    x.includes('fatal:') ||
    x.toLowerCase().includes('error:') ||
    x.includes('git push')
  );
  console.log(section.slice(0, 50).join('\n'));
})();
