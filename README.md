# my-book-sources

个人自用的「阅读」(legado) 书源自动维护仓库。

- `legado.json`：精选书源合集（番茄不限量镜像源置顶）
- `filter.js` + GitHub Actions：每 3 天自动探测域名存活、剔除死源、尝试域名搬家修复、失败保留旧版本
- `preflight.js`：升级预演工具，上传新 filter.js 前可本地跑一遍看预期效果

## 升级流程（开发者）

```bash
