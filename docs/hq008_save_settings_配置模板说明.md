# HQ008 CMP `SAVE_SETTINGS` 配置模板说明

## 1. 文档目的

这份文档用于说明，当自有后台希望控制 CMP 执行 `SAVE_SETTINGS` 时，客户端需要接收什么样的配置，以及每个字段怎么改、改了以后代表什么。

要先明确一点：

- `ACCEPT_ALL` 是固定动作，不需要额外配置明细。
- `REJECT` 也是固定动作，不需要额外配置明细。
- `SAVE_SETTINGS` 不是固定动作，它代表“按一组自定义开关结果提交设置”。

也就是说，后台如果以后要支持 `SAVE_SETTINGS`，不能只返回：

```json
{
  "consent_action": "SAVE_SETTINGS"
}
```

还必须同时返回一份 `consent_payload`，用来告诉客户端，这次具体要勾选哪些 Purpose、Vendor、Special Feature。

## 2. 默认模板

### 2.1 最基础模板

这是最基础的结构模板，字段必须完整带上：

```json
{
  "consent_action": "SAVE_SETTINGS",
  "consent_payload": {
    "purpose_consent_ids": [],
    "purpose_li_ids": [],
    "custom_purpose_consent_ids": [],
    "custom_purpose_li_ids": [],
    "special_feature_ids": [],
    "vendor_consent_ids": [],
    "vendor_li_ids": []
  }
}
```

说明：

- 这里所有数组默认都是空数组。
- 空数组不代表报错，只代表“这一类没有放行任何 ID”。
- 真正是否有效，要看这些 ID 是否和当前 CMP campaign / GVL 中下发的数据对应得上。

### 2.2 示例模板

下面是一份可直接拿来理解的示例：

```json
{
  "consent_action": "SAVE_SETTINGS",
  "consent_payload": {
    "purpose_consent_ids": [1, 3, 4],
    "purpose_li_ids": [2, 7],
    "custom_purpose_consent_ids": [101, 102],
    "custom_purpose_li_ids": [103],
    "special_feature_ids": [1, 2],
    "vendor_consent_ids": [12, 18, 25],
    "vendor_li_ids": [30, 31]
  }
}
```

这份示例的意思是：

- 标准 Purpose 中，明确同意了 `1、3、4`
- 标准 Purpose 中，按 Legitimate Interest 放行了 `2、7`
- 自定义 Purpose 中，明确同意了 `101、102`
- 自定义 Purpose 中，按 Legitimate Interest 放行了 `103`
- Special Feature 中，放行了 `1、2`
- Vendor 中，明确同意了 `12、18、25`
- Vendor 中，按 Legitimate Interest 放行了 `30、31`

## 3. 字段说明

| 字段名 | 类型 | 默认值 | 代表含义 |
| --- | --- | --- | --- |
| `consent_action` | String | `SAVE_SETTINGS` | 表示这次不是一键同意、也不是一键拒绝，而是按自定义配置提交 |
| `purpose_consent_ids` | Int[] | `[]` | 标准 Purpose 中，明确同意的 ID 列表 |
| `purpose_li_ids` | Int[] | `[]` | 标准 Purpose 中，按 Legitimate Interest 放行的 ID 列表 |
| `custom_purpose_consent_ids` | Int[] | `[]` | 自定义 Purpose 中，明确同意的 ID 列表 |
| `custom_purpose_li_ids` | Int[] | `[]` | 自定义 Purpose 中，按 Legitimate Interest 放行的 ID 列表 |
| `special_feature_ids` | Int[] | `[]` | Special Feature 中允许开启的 ID 列表 |
| `vendor_consent_ids` | Int[] | `[]` | Vendor 中，明确同意的 ID 列表 |
| `vendor_li_ids` | Int[] | `[]` | Vendor 中，按 Legitimate Interest 放行的 ID 列表 |

## 4. 每个字段怎么改，改了代表什么

这一节重点说“怎么调值”和“调完之后是什么意思”。

### 4.1 `purpose_consent_ids`

默认模板：

```json
"purpose_consent_ids": []
```

如果改成：

```json
"purpose_consent_ids": [1, 3, 4]
```

代表：

- 标准 Purpose 里的 `1、3、4` 被明确同意
- 没有出现在数组里的其他标准 Purpose，不会被当成“明确同意”

如果把某个 ID 删掉，比如从 `[1, 3, 4]` 改成 `[1, 4]`，代表：

- `3` 不再被明确同意

### 4.2 `purpose_li_ids`

默认模板：

```json
"purpose_li_ids": []
```

如果改成：

```json
"purpose_li_ids": [2, 7]
```

代表：

- 标准 Purpose 里的 `2、7` 按 Legitimate Interest 方式放行

如果清空，代表：

- 没有任何标准 Purpose 通过 Legitimate Interest 放行

### 4.3 `custom_purpose_consent_ids`

默认模板：

```json
"custom_purpose_consent_ids": []
```

如果改成：

```json
"custom_purpose_consent_ids": [101, 102]
```

代表：

- 自定义 Purpose 中的 `101、102` 被明确同意

如果删掉其中一个值，代表：

- 对应的自定义 Purpose 不再属于“明确同意”

### 4.4 `custom_purpose_li_ids`

默认模板：

```json
"custom_purpose_li_ids": []
```

如果改成：

```json
"custom_purpose_li_ids": [103]
```

代表：

- 自定义 Purpose 中的 `103` 按 Legitimate Interest 放行

### 4.5 `special_feature_ids`

默认模板：

```json
"special_feature_ids": []
```

如果改成：

```json
"special_feature_ids": [1, 2]
```

代表：

- Special Feature 中的 `1、2` 被允许开启

如果清空，代表：

- 不开启任何 Special Feature

### 4.6 `vendor_consent_ids`

默认模板：

```json
"vendor_consent_ids": []
```

如果改成：

```json
"vendor_consent_ids": [12, 18, 25]
```

代表：

- Vendor `12、18、25` 被明确同意

如果去掉某个 Vendor ID，代表：

- 对应 Vendor 不再属于“明确同意”

### 4.7 `vendor_li_ids`

默认模板：

```json
"vendor_li_ids": []
```

如果改成：

```json
"vendor_li_ids": [30, 31]
```

代表：

- Vendor `30、31` 按 Legitimate Interest 放行

如果清空，代表：

- 没有 Vendor 通过 Legitimate Interest 放行

## 5. 一些常见组合怎么理解

### 5.1 全部数组都为空

```json
{
  "consent_action": "SAVE_SETTINGS",
  "consent_payload": {
    "purpose_consent_ids": [],
    "purpose_li_ids": [],
    "custom_purpose_consent_ids": [],
    "custom_purpose_li_ids": [],
    "special_feature_ids": [],
    "vendor_consent_ids": [],
    "vendor_li_ids": []
  }
}
```

这更接近“尽可能保守”的设置。

但要注意：

- 它不等于 SDK 内部固定的 `REJECT` 常量动作
- 它只是 `SAVE_SETTINGS` 模式下，一组什么都没放行的自定义配置
- 最终和 SDK 内部固定 `REJECT` 生成的结果是否完全等价，仍要以 SDK 实际生成的 consent string 为准

### 5.2 所有可配置项都尽量放满

这更接近“接近全同意”的自定义配置。

但要注意：

- 它也不等于 SDK 内部固定的 `ACCEPT_ALL`
- 因为 `ACCEPT_ALL` 走的是 SDK 的固定同意路径
- `SAVE_SETTINGS` 只是手动把各类项目尽量勾满

### 5.3 部分放行

这是 `SAVE_SETTINGS` 最有价值的场景。

例如只允许部分 Purpose、部分 Vendor：

```json
{
  "consent_action": "SAVE_SETTINGS",
  "consent_payload": {
    "purpose_consent_ids": [1, 3],
    "purpose_li_ids": [],
    "custom_purpose_consent_ids": [],
    "custom_purpose_li_ids": [],
    "special_feature_ids": [],
    "vendor_consent_ids": [12, 18],
    "vendor_li_ids": []
  }
}
```

这代表：

- 不是一键同意
- 不是一键拒绝
- 而是只放行一部分业务用途和一部分 Vendor

## 6. 后台配置时的建议规则

### 6.1 必须保证 ID 来自当前 campaign / GVL

后台下发的这些 ID，必须是当前 CMP 下发数据里真实存在的 ID。

如果后台自己随便写一个不存在的值，比如：

```json
"vendor_consent_ids": [999999]
```

那客户端即使收到，也无法保证 SDK 最终会按预期生成有效结果。

### 6.2 不要只传 `SAVE_SETTINGS`，不传明细

错误示例：

```json
{
  "consent_action": "SAVE_SETTINGS"
}
```

原因是：

- 客户端无法靠猜测去补齐用户设置
- 每台设备最终要提交的自定义开关，必须由后台明确给出

### 6.3 推荐把这套能力当成“精细化控制”

建议这样理解：

- `ACCEPT_ALL`：后台要的是一键全部同意
- `REJECT`：后台要的是一键拒绝非必要项
- `SAVE_SETTINGS`：后台要的是一组精细化自定义开关

也就是说，`SAVE_SETTINGS` 不是拿来替代前两者的，而是补充前两者做不到的“半开半关”场景。

## 7. 给后台同学的最简结论

如果后面要支持 `SAVE_SETTINGS`，后台返回建议至少长这样：

```json
{
  "consent_action": "SAVE_SETTINGS",
  "consent_payload": {
    "purpose_consent_ids": [],
    "purpose_li_ids": [],
    "custom_purpose_consent_ids": [],
    "custom_purpose_li_ids": [],
    "special_feature_ids": [],
    "vendor_consent_ids": [],
    "vendor_li_ids": []
  }
}
```

后台只需要记住下面这几个规则：

1. `SAVE_SETTINGS` 一定要带 `consent_payload`
2. 每个数组里放的是对应分类下允许的 ID
3. 数组新增一个 ID，代表多放行一个项目
4. 数组删掉一个 ID，代表少放行一个项目
5. 所有 ID 必须和当前 CMP campaign / GVL 对得上

