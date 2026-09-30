# 冬日云朵皮肤包示例

数据型 `.bpskin` 示例，提供首页顶部氛围、底栏饰面和导航图标，不执行代码。

| Manifest 字段 | 范围 |
|---|---|
| `bottomBarTrim` | 底栏背景饰面 |
| `topAtmosphere` | 首页顶部氛围背景 |
| `searchCapsuleBackground` | 可导入并保存，当前未接入首页搜索框图片渲染 |
| `bottomBarIcons` | 常态导航图标；本例未提供选中态图片 |
| `colors` | 底栏与顶部颜色数据 |

字段、图标键名、回退行为和校验限制见 [BPSkin 开发规范](../../../docs/BPSKIN_DEVELOPMENT.md)。

## 构建

本示例不需要 Android SDK，也没有独立 wrapper。请从示例目录使用仓库根 wrapper：

```bash
cd plugins/samples/winter-cloud-skin
../../../gradlew -p . packageBpSkin
```

输出文件：

```text
build/distributions/winter-cloud.bpskin
```

确认包结构：

```bash
unzip -l build/distributions/winter-cloud.bpskin
```

根目录应直接包含：

- `skin-manifest.json`
- `assets/`

在插件中心选择生成的包，预览后点击“保存并启用”。
