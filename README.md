# my-book-sources

个人自用的「阅读」(legado) 书源自动维护仓库。

- `legado.json`：精选书源合集（番茄不限量镜像源置顶）
- `filter.js` + GitHub Actions：每 3 天自动探测域名存活、剔除死源、尝试域名搬家修复、失败保留旧版本
- `preflight.js`：升级预演工具，上传新 filter.js 前可本地跑一遍看预期效果

## 升级流程（开发者）

```bash
# 1. 改完 filter.js 后，本地预演（不真写 legado.json）
node preflight.js

# 2. 确认预演效果（剔除率 < 30%）后，再上传到 GitHub
# 3. GitHub Actions → "更新书源" → "Run workflow" 手动触发
```

## 来源与致谢

书源数据整理自以下开源仓库，感谢原作者的持续维护：

- [shidahuilang/shuyuan-bak](https://github.com/shidahuilang/shuyuan-bak) （大灰狼订阅源，GPL-3.0）— 体量最大的中文小说书源合集
- [tickmao/Novel](https://github.com/tickmao/Novel) （MIT）— 精而稳的 legado 源，每日验证维护
- [jiwangyihao/source-j-legado](https://github.com/jiwangyihao/source-j-legado) （MIT）— 轻小说/二次元专项源集

本仓库基于上述上游的开放许可证发布：自由使用、修改、分发，保留原署名；仅为个人学习与自用目的做筛选与保活处理，未作商业用途；如有侵权请联系删除。
