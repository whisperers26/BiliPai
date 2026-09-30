# BPSkin 开发规范

`.bpskin` 是 BiliPai 的 ZIP 资源包，提供界面图片、动效和颜色，不执行 Kotlin、Dex、Jar 或脚本。包格式与 API 版本均为 `1`。

## 包结构

```text
example.bpskin
├── skin-manifest.json
└── assets/
    ├── top.png
    └── bottom.png
```

`skin-manifest.json` 必须位于根目录。资源路径区分大小写，使用 `/` 分隔，且必须位于 `assets/` 下。不要将整个项目文件夹压入包中。

最小示例：

```json
{
  "formatVersion": 1,
  "apiVersion": 1,
  "skinId": "dev.example.theme",
  "displayName": "示例主题",
  "version": "1.0.0",
  "surfaces": ["HOME_TOP_CHROME", "HOME_BOTTOM_BAR"],
  "assets": {
    "topAtmosphere": "assets/top.png",
    "bottomBarTrim": "assets/bottom.png"
  },
  "colors": {
    "bottomBarTrimTint": "#F6DCEB",
    "bottomBarSelectedTint": "#365C8A"
  }
}
```

## Manifest 字段

| 字段 | 类型 | 要求 |
|---|---|---|
| `formatVersion`、`apiVersion` | 整数 | 必填，均为 `1` |
| `skinId` | 字符串 | 必填、非空；建议使用反向域名并保持稳定 |
| `displayName` | 字符串 | 必填、非空，展示名称 |
| `version` | 字符串 | 必填，建议使用 `1.0.0` 形式 |
| `surfaces` | 字符串数组 | 必填、非空；填写资源适用的界面 |
| `author` | 字符串 | 可选，作者名称 |
| `assets`、`colors`、`motion` | 对象 | 可选；字段见下表 |
| `styleSourceName`、`styleSourceUrl` | 字符串 | 可选，素材来源说明和链接 |
| `licenseNote` | 字符串 | 授权说明；可分享包必须填写 |
| `communityShareable` | 布尔值 | 默认 `false`；设为 `true` 时要求非空 `licenseNote` |
| `containsOfficialAssets` | 布尔值 | 默认 `false`；包含官方装扮素材时应设为 `true` |

未知字段会被忽略；未知 `surfaces` 值会被拒绝。资源可省略，声明的资源必须存在。授权字段是作者声明，宿主不判断版权归属。

## 界面与资源

除集合字段外，`assets` 字段值均为包内资源路径。图片使用 PNG、WebP 或 JPEG；MP4 用于个人页视频背景，Lottie JSON 用于相应动效槽位。格式校验通过不代表任意格式都能用于任意槽位。

| `surfaces` | `assets` 字段 | 用途 |
|---|---|---|
| `HOME_BOTTOM_BAR` | `bottomBarTrim`、`bottomBarIcons` | 底栏背景、导航图标 |
| `HOME_BOTTOM_BAR` | `homeChannelIcon`、`homeChannelSelectedIcon` | 未提供 `channel` 图标时的兼容回退 |
| `HOME_TOP_CHROME` | `topAtmosphere`、`homeTopTabBackground` | 首页顶部、顶部标签区域背景 |
| `HOME_TOP_CHROME` | `searchCapsuleBackground` | 可导入并保存，当前未接入首页搜索框图片渲染 |
| `HOME_DRAWER` | `homeSideBackground`、`drawerBottomTrim` | 抽屉背景和底部饰面 |
| `PROFILE` | `homeProfileBackground`、`homeProfileSquaredBackground` | 个人页背景图 |
| `PROFILE` | `homeProfileVideoBackground` | 个人页 MP4 背景 |
| `PROFILE` | `spaceBackgrounds` | 本人空间背景，支持横竖屏资源 |
| `DYNAMIC_PUBLISH` | `dynamicPublishIcon`、`dynamicPublishSelectedIcon` | 动态发布图标 |
| `LOADING_INDICATOR` | `loadingAnimation`、`loadingFrame` | 加载动图与静态回退图 |
| `LIKE_EFFECT` | `likeEffectAnimation`、`likeEffectPreview` | 点赞动效（Lottie JSON 或图片）与预览图 |
| `PLAYER_PROGRESS` | `playerProgressIcon`、`playerProgressDraggingIcon`、`playerProgressStaticIcon` | 进度条常态、拖动动效与静态图 |
| 无独立枚举值 | `emojiImages` | 已启用皮肤的评论输入表情，点击插入键名文本；不会上传本地图片 |

### 导航图标

`bottomBarIcons` 为键名到资源路径的映射：

| 常态键名 | 选中态键名 | 默认对应入口 |
|---|---|---|
| `home` | `home_selected` | 首页 |
| `following` | `following_selected` | 动态 |
| `member` | `member_selected` | 历史 |
| `channel` | `channel_selected` | 听视频 |
| `profile` | `profile_selected` | 我的 |

选中态可省略，回退为常态图。每个角色需先提供常态图；自定义导航入口由宿主映射到对应角色，缺少角色时回退到已有图标。

手机端同时有底栏背景与图标时可使用插画导航，支持选中态立绘。紧凑导航保持常态图，平板不使用该插画导航布局。皮肤不改变导航功能、触摸区域或宿主组件代码。

### 集合资源

```json
{
  "spaceBackgrounds": [
    {
      "portrait": "assets/space_portrait.jpg",
      "landscape": "assets/space_landscape.jpg"
    }
  ],
  "emojiImages": {
    "[微笑]": "assets/emoji_smile.png"
  }
}
```

以上字段置于 `assets` 内。空间背景按屏幕方向选择资源，缺少对应方向时回退到另一方向；每组至少提供一张图。表情键名应使用期望插入评论的文本，不代表服务端已注册该表情。

## 颜色与动效

颜色建议写为 `#RRGGBB` 或 `#AARRGGBB`。省略或无法解析时使用宿主回退值。颜色 token 不会替换全局主题。

| `colors` 字段 | 用途 |
|---|---|
| `bottomBarTrimTint` | 底栏饰面底色 |
| `bottomBarIconTint`、`bottomBarIconDarkTint` | 常态图标浅色／深色模式颜色 |
| `bottomBarSelectedTint`、`bottomBarSelectedDarkTint` | 选中态浅色／深色模式颜色 |
| `topAtmosphereTint` | 顶部氛围色及底栏装饰辅助色 |
| `searchCapsuleTint` | 搜索胶囊颜色数据及合成预览；不保证首页搜索框采用该值 |
| `sideBackgroundTint` | 抽屉背景色 |
| `dynamicPublishIconTint` | 动态发布图标色 |
| `dynamicPublishShadeTop`、`dynamicPublishShadeBottom` | 发布入口渐变色 |
| `playerProgressActiveTint`、`playerProgressBufferedTint`、`playerProgressTrackTint` | 已播放、缓冲和轨道颜色 |
| `colorMode` | 来源主题的 `light`／`dark` 模式提示，不强制切换应用主题 |

底栏深色颜色省略时回退到对应普通颜色。图片不会因声明深色颜色而自动生成深色版本。

| `motion` 字段 | 类型与行为 |
|---|---|
| `bottomBarIconAnimated` | 布尔值，默认 `false`；启用宿主图标动效 |
| `bottomBarIconAnimationMode` | `loop`、`cycle`、`repeat`、`always` 在选中时启用循环缩放；其他值不启用循环 |
| `bottomBarIconMode` | 可保存的来源元数据，当前不直接控制渲染 |
| `profileVideoPlayMode` | `once` 播放一次，其他值或省略时循环播放 |

## 导入与启用

插件中心支持以下输入：

| 输入 | 处理方式 | 网络要求 |
|---|---|---|
| `.bpskin` | 校验 manifest 和资源 | 完整包可离线 |
| 单主题目录 ZIP | 读取主题 JSON 与内层 `_package.zip`，转换为 `.bpskin` | 资源齐全可离线；缺包时可按 JSON 的 `package_url` 下载 |
| `_package.zip` | 按已知素材名称转换，使用通用名称与默认色板 | 可离线 |
| 受支持的主题 JSON | 读取元数据并下载 `package_url` | 需要网络 |
| 单项装扮 JSON | 支持 `part_id` 为 `3`（点赞）、`10`（加载）、`11`（进度条） | 需要下载引用资源 |

JSON 需符合导入器支持的主题或装扮结构；不是任意 JSON。远程资源应使用 HTTPS。转换只保留宿主支持的资源，不保证完整复现来源主题。

选择文件后进入预览，点击“保存并启用”安装到应用私有目录。已安装皮肤可预览、切换、停用或删除。具有个人页背景图或视频的包可选“仅个人背景”；该选项不会导入完整导航皮肤。在线皮肤目录支持下载、预览和导入。

转换出的官方装扮包声明 `containsOfficialAssets=true`、`communityShareable=false`。分发前需确认素材授权。桌面转换工具见 [`bilibili_skin_to_bpskin.py`](../plugins/tools/bilibili_skin_to_bpskin.py)；其支持范围与 App 导入器分别维护。

## 校验限制

- 标准包最多 256 个文件；manifest 最大 64 KiB；解压总量最大 32 MiB。
- 来源主题 ZIP 扫描上限为 256 个文件、128 MiB 解压内容；转换后的标准包仍受 32 MiB 限制。
- 拒绝路径穿越、重复 ZIP 路径、缺失资源、未声明资源、重复声明同一路径和根目录额外文件。
- 资源格式按内容识别，改后缀不会转换格式。JSON 初筛不验证完整 Lottie 兼容性，应使用自包含动效并检查实际播放。
- 不支持用包内代码替换 Compose 组件。宿主决定布局、缩放、裁切、动画与交互。

## 素材与检查

当前没有强制像素尺寸。顶部和底栏背景建议使用横向素材，主体避开边缘裁切区；导航图标建议留透明边距，并保持各状态画布一致。空间背景建议分别提供横竖屏版本。

发布前检查浅色／深色模式、手机／平板、导航选中态、背景裁切和动效回退。预览用于核对合成效果，不能代替全部界面的实际显示检查。

示例：

- [冬日云朵](../plugins/samples/winter-cloud-skin/README.md)：顶部、底栏及导航图标。
- [蓝雪女仆](../plugins/samples/blue-snow-maid-skin/README.md)：顶部、底栏和五组双态导航图标；附可直接导入的包。

实现参考：[数据模型](../app/src/main/java/com/android/purebilibili/core/plugin/skin/UiSkinModels.kt)、[包校验](../app/src/main/java/com/android/purebilibili/core/plugin/skin/UiSkinPackageReader.kt)、[导入转换](../app/src/main/java/com/android/purebilibili/core/plugin/skin/UiSkinImportPackageResolver.kt)。
