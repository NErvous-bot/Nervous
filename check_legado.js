const https = require('https');
function get(u) {
  return new Promise((ok, no) => {
    https.get(u, { headers: { 'User-Agent': 'v' } }, r => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location)
        return get(r.headers.location).then(ok, no);
      let b = '';
      r.on('data', c => b += c);
      r.on('end', () => ok({ code: r.statusCode, body: b }));
    }).on('error', no);
  });
}
(async () => {
  const tag = '3.26.082823';
  const c = await get('https://raw.githubusercontent.com/gedoor/legado/' + tag + '/app/src/main/AndroidManifest.xml');
  console.log('manifest status:', c.code);
  if (c.code !== 200) { console.log(c.body.slice(0, 200)); return; }
  // 用 indexOf 简化匹配
  const blocks = c.body.split('<provider');
  for (let i = 1; i < blocks.length; i++) {
    const end = blocks[i].indexOf('</provider>');
    if (end < 0) continue;
    console.log('PROVIDER:', ('<provider' + blocks[i].slice(0, end + 11)).replace(/\s+/g, ' ').slice(0, 300));
  }
  // 也读 reader provider 的源码，确认路径
  const src = await get('https://raw.githubusercontent.com/gedoor/legado/' + tag + '/app/src/main/java/io/legado/app/help/ContentProviderReadFiles.kt');
  console.log('\nReaderProvider src status:', src.code);
  if (src.code === 200) console.log(src.body.slice(0, 1500));
})();
