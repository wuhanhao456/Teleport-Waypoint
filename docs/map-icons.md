# 数据包地图图标

此 fork 支持按结构注册名、结构标签、锚点 ID、原始名称、维度和锚点类型配置地图图标。
Xaero 世界地图显示 PNG；Xaero 小地图使用规则中的 `symbol` 文字符号，保留原来的激活状态颜色。
客户端和服务端都需要此 fork（网络协议版本 5）。不需要安装 Xaero 也能正常使用锚点。

## 快速使用

将 `examples/datapacks/structure_map_icons` 整个文件夹放进世界的 `datapacks`，执行 `/reload`。
构建还会生成可直接安装的 `build/distributions/structure_map_icons_example.zip`，也可以将该 ZIP 放入 `datapacks`。
该示例将村庄的小地图符号设为 `V`，将试炼密室的世界地图图标设为试炼钥匙、小地图符号设为 `T`。
村庄的两张 PNG 是模组原有水晶图片的副本，用来演示服务器分发；替换它们即可使用自己的建筑图片。
修改或删除规则、替换 PNG 后再次执行 `/reload`。客户端会自动收到更新，不需要另外安装资源包。

## 文件位置与规则

每个 JSON 定义一条规则：`data/<命名空间>/teleportwaypoint/map_icons/<规则名>.json`。

```json
{
  "priority": 100,
  "match": {
    "structures": ["minecraft:village_plains", "othermod:castle"],
    "dimensions": "minecraft:overworld",
    "pocket": false
  },
  "active_icon": {"png": "mypack:teleportwaypoint/icons/castle_active.png"},
  "inactive_icon": {"png": "mypack:teleportwaypoint/icons/castle_inactive.png"},
  "size": 32,
  "symbol": "C"
}
```

对应图片放在 `data/mypack/teleportwaypoint/icons/castle_active.png` 和 `castle_inactive.png`。
`png` 值是相对于 `data/<命名空间>/` 的完整路径，包含 `.png`。
模组读取数据包中的图片并发送给客户端，再注册为动态纹理。因此纯数据包也能补充图片。

| 字段 | 含义 |
| --- | --- |
| `structures` | 结构注册名，例如 `minecraft:village_plains`、`minecraft:fortress`，不是结构模板 NBT 文件名 |
| `structure_tags` | 结构标签，例如 `minecraft:village`；填写标签 ID，不加 `#` |
| `waypoint_ids` | 普通锚点的 `waypoint_id`，例如 `village`、`nether_fortress`；不匹配口袋锚点 |
| `names` | 原始名称精确匹配；普通锚点匹配 ID，口袋锚点匹配文字名称，不使用翻译后的名称 |
| `dimensions` | 维度注册名，例如 `minecraft:the_nether` |
| `pocket` | `true` 匹配口袋锚点，`false` 匹配普通锚点 |

除 `pocket` 外，每个条件接受一个字符串或字符串数组。数组内部满足任意一项即可。
不同条件之间必须全部满足。省略的条件不限制；`"match": {}` 可以定义默认规则。
不支持通配符或正则。空数组和未知条件会报错并跳过该规则，避免拼写错误扩大匹配范围。

`priority` 默认为 0，较大的值优先。相同优先级按规则资源 ID 的字典序选择第一条。
只使用第一条匹配规则，不合并多条规则。同路径规则遵循 Minecraft 数据包的覆盖顺序。

## 图标来源和状态

`icon` 同时用于已激活与未激活状态；`active_icon`、`inactive_icon` 可以分别覆盖它。
未指定的状态保留原水晶图标。缺失客户端纹理或 PNG 解码失败时也回退到原图标。
数据包中缺失或超限的 PNG 会使整条规则被跳过，服务器日志会记录原因。

```json
{
  "match": {"waypoint_ids": "ocean_monument"},
  "icon": {"texture": "minecraft:textures/item/prismarine_shard.png"},
  "symbol": "M"
}
```

`texture` 可引用客户端已有的原版、模组或资源包纹理，路径相对于 `assets/<命名空间>/`。
如果使用自定义 `texture`，必须保证客户端拥有相应资源包；服务器不会发送这种图片。
`png` 和 `texture` 每个图标只能选择一个。

`size` 是世界地图显示尺寸，范围 8–64，默认 32。PNG 应为正方形，否则显示时会拉伸。
支持透明背景。建议使用 32×32 或 64×64。
每张图片最多 128×128、128 KiB；每次最多同步 64 张不同图片、加载 1024 条规则。
`symbol` 最多 8 个字符；省略时小地图保留 `W` / `P`。PNG 不替换小地图及世界内的 Xaero 原生文字标记。

## 建筑识别和手动指定

锚点注册时，服务端查询包含锚点坐标的结构包围盒，并将结构注册名保存在世界锚点索引中。
重叠结构可以记录多个 ID。读取旧存档时兼容没有结构字段的记录；相应区块加载或锚点使用后会补充识别。
结构识别不在地图渲染时执行。这里更换的是已有锚点的图标，不会扫描世界并给所有建筑创建新标记。

模板作者可以在锚点方块实体 NBT 中填写 `structure_id`，覆盖自动识别。
这适用于锚点位于建筑包围盒外、手动放置建筑、或注册名需要精确指定的情况：

```mcfunction
/data merge block 100 64 200 {structure_id:"othermod:castle"}
```

随后右键使用该锚点或重新加载区块，让它重新注册并同步。
清除覆盖可使用 `/data remove block 100 64 200 structure_id`。
`structure_id` 不会改变锚点名称或结构生成方式。
`structure_tags` 匹配服务器实际结构注册表的标签；手写一个没有注册的 `structure_id` 仍可精确匹配 `structures`，但不能匹配标签。

## 验证建议

1. 启动服务器和带 Xaero World Map 的客户端，安装示例数据包。
2. 在试炼密室内放普通锚点，检查钥匙图标；激活与未激活状态均应显示钥匙。
3. 修改村庄 PNG 并 `/reload`，确认已在线玩家看到新图片，新登录玩家也能收到图片。
4. 删除规则并 `/reload`，确认对应锚点回到原图标。
5. 测试 `structure_id` 覆盖、跨维度、口袋锚点权限和断线重连；未激活口袋锚点仍不能显示。
6. 不安装 Xaero 启动客户端和独立服务端，确认可正常加载。
