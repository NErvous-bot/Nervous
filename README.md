# Nervous

个人自用的「阅读」(legado) 书源自动维护仓库。

## 仓库组件

- `legado.json`：精选书源合集（番茄不限量镜像源置顶）
- `filter.js` + GitHub Actions：每 3 天自动运行——探测域名存活、剔除死源、跟进域名搬家、从上游合并同名源的规则修复，并自动筛选上游新源扩容（每轮限量，须通过探活）；失败保留旧版本
- `SourceAutoSync/`：安卓「书源自动同步」App（自研）——每天自动检查本仓库更新并通过阅读官方 ContentProvider 接口写入书源
- `LegadoProviderTest/`：同步 App 的接口连通性测试工具（自研）

## 来源与致谢

书源数据整理自 [shidahuilang/shuyuan-bak](https://github.com/shidahuilang/shuyuan-bak) （大灰狼订阅源），感谢原作者的持续维护。

## 许可与免责声明

本仓库基于上游 GPL-3.0 许可证发布：自由使用、修改、分发，保留原署名。自研的同步 App 与测试工具代码同样按 GPL-3.0 发布。

本仓库仅包含书源规则配置与维护脚本，不含任何小说文本内容；仅供个人学习与自用，未作商业用途。书源指向的第三方网站内容与本人无关；如有侵权请联系删除。
