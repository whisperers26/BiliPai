# 蓝雪女仆皮肤

数据型 `.bpskin` 示例，提供首页顶部背景、底栏饰面、五组常态／选中态导航图标及颜色。

## 导入

在插件中心选择 [`blue-snow-maid.bpskin`](blue-snow-maid.bpskin)，预览后点击“保存并启用”。也可从应用内皮肤目录选择“蓝雪女仆”。

## 资源

| 文件 | Manifest 字段 |
|---|---|
| `assets/top_atmosphere.png` | `assets.topAtmosphere` |
| `assets/bottom_trim.png` | `assets.bottomBarTrim` |
| `assets/icon_*.png` | `assets.bottomBarIcons` |

底栏背景与人物图标分离。手机端使用插画导航，选中入口切换对应图片；平板保留宿主导航布局。

字段及渲染范围见 [BPSkin 开发规范](../../../docs/BPSKIN_DEVELOPMENT.md)。

## 打包

从本目录执行以下命令，仅打包资源，无需 Android SDK：

```bash
../../../gradlew -p . packageBpSkin
```

输出：`build/distributions/blue-snow-maid.bpskin`。打包后检查 ZIP 根目录直接包含 `skin-manifest.json` 和 `assets/`。

## 素材

底栏背景和导航图标使用内置 ImageGen 生成，沿用示例角色风格，并非 B 站官方原始素材。提示词见 [artwork-prompts.json](artwork-prompts.json)。

## 授权

包声明 `containsOfficialAssets=true`、`communityShareable=false`。含官方角色主题素材，仅供应用内个人使用，不可作为社区皮肤包分发。
