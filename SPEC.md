# 智能文档解析与检核系统 — 设计规格说明书（v5）

## 1. 概述

对 UR/FS 需求文档进行**全量无损提取**，支持多层嵌套文件（word → excel/pdf/word/zip/图片 → 更深层），将内容展开为平级目录结构。在提取产物之上，通过 **MapReduce 架构**执行四类 AI 检核任务，输出结构化《需求管控报告》。

**v5 更新重点**：CSV 数据可见性修复、chunks/index.json 增加 large_tables 索引、MapReduce AI 检核架构设计、通用 prompt 模板、按检查类型的加载策略。

---

## 2. 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 提取层运行环境 |
| Apache POI (ooxml) | 5.3.0 | 解析 .docx / .xlsx |
| Apache POI (scratchpad) | 5.3.0 | HWPF 解析 .doc |
| Apache PDFBox | 3.0.2 | 解析 .pdf |
| Apache Tika | 2.9.2 | MIME 类型检测 |
| JUnit 5 + AssertJ | - | 单元测试 |
| LLM API | - | AI 层 Map/Reduce 调用 |
| 并发框架 | - | Map 阶段并行执行 |

---

## 3. 系统总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Phase 1: 提取层（Java）                    │
│                                                             │
│   UR_FS.docx ──► RecursiveExtractor                        │
│                      │                                      │
│                      ├── DocxHandler / ExcelHandler / ...   │
│                      ├── TypeDetector + OleExtractor        │
│                      └── StoreWriter                        │
│                            │                                │
│                            ▼                                │
│   extracted/{session_id}/                                   │
│   ├── manifest.json          ← 文档索引                     │
│   ├── {doc}/                  ← 每个文档一个目录             │
│   │   ├── {doc}.md           ← 完整 body.md                │
│   │   ├── {doc}.docx          ← 原始二进制副本              │
│   │   ├── chunks/             ← 分块（50元素/chunk）        │
│   │   │   ├── index.json     ← 含 large_tables 引用        │
│   │   │   └── chunk_NNNN.md                                │
│   │   ├── media/              ← 图片原文件                  │
│   │   └── data/               ← 大表 CSV                   │
│   └── ...                                                  │
└─────────────────────────────────────────────────────────────┘
        │
        │ 产物（文件系统）
        ▼
┌─────────────────────────────────────────────────────────────┐
│                  Phase 2: AI 检核层（MapReduce）              │
│                                                             │
│   manifest.json ──► Job Scheduler                           │
│                        │                                    │
│                        ├── 读取 chunks/index.json           │
│                        ├── 生成 Map Task 列表               │
│                        │                                    │
│           ┌────────────┼────────────┐                       │
│           ▼            ▼            ▼                       │
│       Map Task    Map Task    Map Task   ← 并行             │
│       (chunk N)   (chunk M)   (CSV X)                       │
│           │            │            │                       │
│           ▼            ▼            ▼                       │
│       LLM 扫描    LLM 扫描    LLM 扫描                       │
│           │            │            │                       │
│           └────────────┼────────────┘                       │
│                        ▼                                    │
│                   Reduce Phase                              │
│                   ├── 去重合并                              │
│                   ├── 分级排序                              │
│                   └── 生成报告                              │
│                        │                                    │
│                        ▼                                    │
│   输出: 需求管控报告.json + 需求管控报告.md                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 提取层架构（v4 持续，v5 微调）

### 4.1 提取流程

```
RecursiveExtractor（入口）
    │
    ├── TypeDetector        ← Tika 从 byte[] 检测真实 MIME 类型
    │
    ├── selectHandler(bytes, hintFileName)
    │   ├── DocxHandler    (.docx / .docm)
    │   ├── DocHandler     (.doc)
    │   ├── ExcelHandler   (.xlsx / .xls)
    │   ├── PdfHandler     (.pdf)
    │   ├── ZipHandler     (.zip)
    │   └── ImageHandler   (.png/.jpg/...)
    │
    ├── OleExtractor.extract(byte[])  ← OLE 二进制解包
    │       │
    │       └── TypeDetector → 按真实类型路由到对应 handler
    │
    ├── handler.extract(is, fileName) → ExtractionResult
    │
    ├── StoreWriter.write(docDir, result, position)
    │   ├── {name}.md        ← 文本内容（原始完整版）
    │   ├── {name}.{ext}     ← 原始二进制副本（原名）
    │   ├── chunks/           ← 分块目录
    │   │   ├── index.json   ← 含 large_tables 索引
    │   │   └── chunk_0001.md ← 每个 chunk ≤50 个元素
    │   ├── media/            ← 图片文件
    │   └── data/             ← 大表 csv
    │
    ├── manifest.json 更新
    │
    └── 递归处理嵌入文件 + 图片
        ├── 嵌入文件: extract(embedded.data, embedded.fileName, ...)
        └── 图片: ImageHandler 单独处理 → media/
```

### 4.2 Handler 详细规格

#### DocxHandler

| 内容 | 状态 | 说明 |
|------|------|------|
| 段落文字 | 有 | - |
| 表格 | 有 | - |
| 内嵌图片 (drawing) | 有 | 从 XWPFRun 提取 XWPFPicture |
| OLE 嵌入对象 | 有 | /word/embeddings/ → OleExtractor + TypeDetector |
| 页眉/页脚 | 有 | - |
| .docm 支持 | 有 | - |
| 嵌入元素标注 | 有 | 生成 TYPE: embed 元素 |

#### ExcelHandler

| 内容 | 状态 | 说明 |
|------|------|------|
| Sheet 文字/表格 | 有 | 小表(≤50行,≤10列)→内联 Markdown 表格 |
| 大表分流 | 有 | >50行 → body.md 存 data_ref，全量写 data/*.csv |
| data_ref 预览 | **v5修复** | 智能定位表头行，显示 Columns + 前2行数据行 |
| data_ref schema | **v5修复** | 过滤空单元格，用 `\|` 分隔 |
| 内嵌图片 | 有 | `workbook.getAllPictures()` |
| OLE 嵌入对象 | 有 | OleExtractor + TypeDetector |

#### 其余 Handler

| Handler | 职责 | 关键行为 |
|---------|------|---------|
| DocHandler | .doc (HWPF) | 段落/表格提取，OLE 解包 |
| PdfHandler | .pdf | PDFBox 文本/图片/附件提取 |
| ImageHandler | 图片 | 叶子节点，保存到 media/ |
| ZipHandler | .zip | 解压递归，图片路由到 ImageHandler |

---

## 5. 输出格式（v5 最终版）

### 5.1 目录结构

```
extracted/{session_id}/
├── manifest.json
├── {文档名}/
│   ├── {文档名}.md              ← body.md
│   ├── {文档名}.{ext}           ← 原始二进制副本
│   ├── chunks/
│   │   ├── index.json
│   │   └── chunk_NNNN.md
│   ├── media/
│   │   └── img_X_Y.png
│   └── data/
│       ├── 开发排期.csv
│       └── 数据字典.csv
├── {嵌入文档名}/
│   └── ...
└── {同名则加 _2 后缀}/
```

### 5.2 body.md 格式

```
# DOC: {seq}
# SOURCE: {文件名}
# PARENT: {父级目录名}, pos={父级position}
{空行}
# POS: {N} | TYPE: {类型} | {元数据}
{内容}
```

### 5.3 类型定义

| TYPE | 说明 | 内容格式 |
|------|------|---------|
| paragraph | 文字段落 | 原文 |
| table | 小表(≤50行) | Markdown 表格 |
| data_ref | 大表引用 | schema + Columns + Row 预览 + file 路径 |
| embed | 嵌入文件 | file: {文件名} |
| image | 图片 | file: media/xxx.png |
| sheet_header | Excel Sheet 名 | [Sheet: 名称] |
| header | 页眉 | 页眉内容 |
| footer | 页脚 | 页脚内容 |

### 5.4 data_ref 格式（v5 修复后）

```
# POS: 5 | TYPE: data_ref | schema: 模块 | 任务名称 | 负责人 | 优先级 | ... | rows: 37 | file: data/开发排期.csv
Columns: 模块 | 任务名称 | 负责人 | 优先级 | ...
Row 1: 采购管理, 需求分析与原型设计, 张晓明, ...
Row 2: 采购管理, 后端API开发, 刘芳, ...
```

### 5.5 chunks/index.json 格式（v5 修复后）

```json
{
  "source": "Microsoft_Excel_Worksheet/Microsoft_Excel_Worksheet.md",
  "total_elements": 8,
  "total_large_tables": 2,
  "total_images": 0,
  "chunk_size": 50,
  "chunks": [
    {"file": "chunk_0001.md", "pos_range": [0, 7], "element_count": 8, "includes_large_table": true}
  ],
  "large_tables": [
    {"name": "开发排期.csv", "sheet": "开发排期", "pos": 5, "rows": 37, "file": "data/开发排期.csv", "in_chunk": "chunk_0001.md"},
    {"name": "数据字典.csv", "sheet": "数据字典", "pos": 7, "rows": 52, "file": "data/数据字典.csv", "in_chunk": "chunk_0001.md"}
  ]
}
```

**v5 新增字段说明**：
- `large_tables[]`: 每个大表的元信息，AI 层可根据此数组决定是否需要加载 CSV
- `includes_large_table`: 正确反映该 chunk 是否包含 data_ref 元素

### 5.6 manifest.json 格式

```json
{
  "session_id": "extract_20260716_234050",
  "source_file": "原文件名.docx",
  "docs": [
    {
      "seq": 0,
      "dir": "文档目录名",
      "source": "原始文件名.docx",
      "source_copy": "文档目录名/原始文件名.docx",
      "type": "docx",
      "element_count": 223,
      "data_ref_count": 0,
      "image_count": 0,
      "parent": "root"
    }
  ]
}
```

---

## 6. AI 检核层 MapReduce 架构（v5 新增）

### 6.1 设计原则

- **格式无关**：Map task 只消费 markdown 文本，源文档格式差异在提取层已消除
- **Prompt 通用**：同一检查类型的 Map prompt 在所有文档/chunk 上复用，不做文档特化
- **Map 无状态**：每个 Map task 独立运行，不依赖其他 task 的结果
- **Reduce 分层**：先在单文档内聚合，再跨文档全局聚合

### 6.2 MapReduce 执行流程

```
输入: manifest.json + chunks/index.json × N

Step 1: Job Scheduler 解析 manifest，遍历所有 doc
        │
        ├── 对每个 doc，读取 chunks/index.json
        │   ├── 获取 chunk 列表
        │   └── 获取 large_tables 列表
        │
        ├── 生成 Map Task 列表:
        │   ├── ChunkTask(docDir, chunkFile, checkType)
        │   ├── CsvTask(docDir, csvFile, checkType)  ← 仅 PII/知识提取需要
        │   └── ImageTask(docDir, imageFile)         ← 仅知识提取需要(多模态)
        │
Step 2: Map Phase（并行）
        │
        ├── 每个 Map Task:
        │   ├── 加载目标文件内容
        │   ├── 构造 Prompt（检查类型模板 + 文本内容）
        │   ├── 调用 LLM
        │   └── 输出: List<Finding>
        │
Step 3: Reduce Phase
        │
        ├── 去重合并 (dedupKey: type + value + source)
        ├── 位置合并 (同一实体在多处出现 → 汇总 pos 列表)
        ├── 分级排序 (按严重程度/置信度)
        └── 生成报告
```

### 6.3 Map Task 类型

| Task 类型 | 输入 | 适用检查 | 备注 |
|-----------|------|---------|------|
| ChunkTask | chunk_NNNN.md | PII/报送/页面/知识提取 | 主要任务类型 |
| CsvTask | data/*.csv | PII/知识提取 | 大表可能含敏感字段/指标数据 |
| ImageTask | media/*.png | 知识提取(多模态) | 图表中的指标/标签 |

### 6.4 Map Prompt 模板设计

#### PII 检测（全量扫描 → 需要所有 ChunkTask + CsvTask）

```
你是一个个人敏感信息检测器。
扫描以下文档片段，提取所有疑似展示个人信息的字段。

检测目标：
- 应用系统密码、证件号码（身份证/护照/港澳台证件等）
- 银行卡号、家庭地址、手机号码/联系电话
- 姓名、电子邮箱

对于每个发现，输出 JSON：
{
  "type": "PII",
  "field": "字段名",
  "category": "身份证号|银行卡号|手机号|地址|邮箱|姓名|密码",
  "value_example": "原文中的示例值（如有）",
  "evidence": "包含该字段的原文片段（≤100字）",
  "pos": {元素位置},
  "has_masking": true/false,
  "masking_description": "去标识化方案描述（如有）"
}

只输出 JSON 数组，不输出任何解释。
```

#### 监管报送合规检核（条件触发 → 先按关键词过滤 chunk，再 Map）

```
你是一个监管报送需求检核器。
扫描以下文档片段，检查 FS 文档中关于监管报送的数据项定义是否完整。

对于每个报送数据项，检查是否遗漏：
- 数据项中文名、报送口径、取数逻辑、报送范围、dataowner
- 数据质量规则：校验规则、校验频率

对于每个发现，输出 JSON：
{
  "type": "REGULATORY",
  "item_name": "数据项名称",
  "missing_fields": ["缺失字段1", "缺失字段2"],
  "has_quality_rule": true/false,
  "evidence": "原文相关片段（≤200字）",
  "pos": {元素位置}
}

只输出 JSON 数组，不输出任何解释。
```

#### 页面录入数据项检核（全量扫描 → 需要所有 ChunkTask）

```
你是一个页面录入数据项检核器。
扫描以下文档片段，识别所有页面录入/数据导入字段，检查其数据质量规则是否完备。

对于每个录入字段，检查：
- 必填要求：是否明确标注必填/非必填
- 格式要求：如日期格式、数值精度、字符串长度
- 码值要求：枚举值、取值范围
- 精度要求：小数位、四舍五入规则

对于每个发现，输出 JSON：
{
  "type": "PAGE_INPUT",
  "field_name": "字段名",
  "field_location": "所属页面/功能",
  "has_required_flag": true/false,
  "has_format_rule": true/false,
  "has_value_range": true/false,
  "has_precision_rule": true/false,
  "missing_rules": ["缺失的规则类型"],
  "evidence": "原文相关片段（≤200字）",
  "pos": {元素位置}
}

只输出 JSON 数组，不输出任何解释。
```

#### 知识提取（全量扫描 → 需要所有 ChunkTask + CsvTask + ImageTask）

```
你是一个需求文档知识提取器。
扫描以下文档片段，提取所有结构化知识条目。

提取四类内容：
1. 数据项需求：页面录入字段、API参数
2. 数据质量需求：完整性/准确性/一致性/时效性/有效性规则
3. 数据指标需求：以"率""比""度"结尾的KPI指标、含计算公式/口径的定义
4. 数据标签需求：标签/画像/分层/等级

对于每个提取项，输出 JSON：
{
  "type": "KNOWLEDGE",
  "category": "DATA_ITEM|QUALITY_RULE|INDICATOR|TAG",
  "name": "名称",
  "definition": "业务定义（≤200字）",
  "attributes": {"属性名": "属性值"},
  "evidence": "原文相关片段（≤200字）",
  "pos": {元素位置}
}

只输出 JSON 数组，不输出任何解释。
```

### 6.5 按检查类型的加载策略

| 检查类型 | 触发条件 | 加载 Chunk | 加载 CSV | 加载 Image | 关键词过滤 |
|---------|---------|-----------|---------|-----------|-----------|
| PII 检测 | 总是执行 | 全部 | 全部 | 不需要 | 不需要（全文扫） |
| 监管报送 | 前端标记=是 | 关键词命中 | 不需要 | 不需要 | "报送/监管/合规/上报/披露" |
| 页面录入 | 总是执行 | 全部 | 不需要 | 不需要 | 不需要（全文扫） |
| 知识提取 | 总是执行 | 全部 | 全部 | 全部 | 不需要（全文扫） |

**关键词过滤优化**：监管报送检查时，先通过 chunks/index.json 了解元素分布，对每个 chunk 做标题/首行的关键词匹配。命中的 chunk 才进入 Map 阶段，通常可减少 60-80% 的 LLM 调用量。

### 6.6 Reduce 策略

#### PII Reduce
```
去重键: category + field + value_example
合并: 同一条 PII 出现在多个 chunk → 合并 pos 列表
去标识化验证:
  - 遍历所有 finding，逐一对比去标识化规则表
  - has_masking=false 且未在上下文中找到方案 → HIGH 风险
  - has_masking=true 但规则不匹配 → MEDIUM 风险
分组输出: 按 category 分组 → 风险等级排序
```

#### 监管报送 Reduce
```
去重键: item_name
合并: 跨 chunk 汇总同一数据项的缺失字段
输出: 按缺失严重度排序（缺 dataowner > 缺取数逻辑 > 缺校验规则）
```

#### 页面录入 Reduce
```
去重键: field_name + field_location
合并: 跨 chunk 汇总同一字段的缺失规则
输出: 按缺失数量排序
```

#### 知识提取 Reduce
```
去重键: category + name
合并: 同一实体在多处出现 → 汇总 evidence + pos
分类输出:
  - indicators: 指标列表（name + formula + frequency）
  - tags: 标签列表（name + definition + rules）
  - data_items: 数据项列表
  - quality_rules: 质量规则列表
```

### 6.7 Token 预算

| 阶段 | 单次输入 | 预估输出 | 说明 |
|------|---------|---------|------|
| Map (ChunkTask) | ~2000 tokens | ~500 tokens | 50元素/chunk 约 2000 tokens |
| Map (CsvTask) | ~3000 tokens | ~500 tokens | 52行 CSV 约 3000 tokens |
| Map (ImageTask) | 1 图片 | ~200 tokens | 多模态，Qwen-VL |
| Reduce (单文档) | ~4000 tokens | ~1000 tokens | 聚合 Map 结果 |
| Reduce (全局) | ~8000 tokens | ~2000 tokens | 最终报告 |

大型文档（400+ 元素）：
- 9 个 ChunkTask + 0-2 个 CsvTask
- Map 总计: 9×2000 + 2×3000 = 24000 tokens（可通过并行减少耗时）
- Reduce 总计: 4000 + 8000 = 12000 tokens
- **单文档全流程: ~36000 tokens**（不含多模态图片）

### 6.8 并行执行模型

```
manifest.json
    │
    ├── doc_0 (223 元素, 5 chunks, 0 csv)
    │   ├── ChunkTask × 5  ─┐
    │   └── (no CsvTask)    ├── 并行 Map (5 并发)
    │                        │
    ├── doc_1 (413 元素, 9 chunks, 0 csv)
    │   └── ChunkTask × 9  ─┤ 并行 Map (9 并发)
    │                        │
    └── doc_2 (8 元素, 1 chunk, 2 csv)
        ├── ChunkTask × 1  ─┤
        └── CsvTask × 2    ─┘ 并行 Map (3 并发)
                    │
                    ▼
              ┌── 文档级 Reduce ──┐
              │  doc_0 → R0      │
              │  doc_1 → R1      │
              │  doc_2 → R2      │
              └──────────────────┘
                    │
                    ▼
              全局 Reduce → 最终报告
```

**预估耗时**（假设单次 LLM 调用 3s）：
- 全并行: max(5, 9, 3) × 3s + 2×3s(reduce) = 27s + 6s ≈ **33 秒**
- 串行: (5+9+3) × 3s + 6s = **57 秒**

---

## 7. 分块策略

| 参数 | 默认值 | 说明 |
|------|--------|------|
| chunk_size | 50 | 每块最多包含的元素数 |
| large_table_threshold | 200 | 超过此行数的 data_ref 在 chunks/index 中标为可独立加载 |
| min_chunks_to_split | 1 | body.md 只有 1 个 chunk 时也生成分块目录 |

**v5 增强**：
- chunks/index.json 包含 `large_tables` 数组，AI 层无需读取完整 body.md 即可判断需要加载哪些 CSV
- `includes_large_table` 正确反映元素分布

---

## 8. 数据处理策略总表

| 数据特征 | 处理方式 |
|---------|---------|
| 配置表/元数据 (≤50行, ≤10列) | 全量 Markdown 表格写入 body.md |
| 业务明细表 (>50行) | body.md 存 data_ref(schema+preview)；全量存入 data/*.csv |
| data_ref 预览 | 智能定位表头行，显示 Columns + 前2行数据 |
| 图片 | 原文件保存到 media/，body.md 标注 image 类型 |
| 嵌入 .docx | 递归提取到新目录 |
| 嵌入 .pdf | PDFBox 提取文本+图片 |
| 嵌入 .zip | 解压后递归，图片路由到 ImageHandler |
| OLE 嵌入对象 | OleExtractor 解包 → TypeDetector 检测 → 路由对应 handler |
| 递归深度 | 默认限制 10 层 |
| 原始文档 | 保存为 {原名}.{ext} |
| 大文档 | body.md 保留完整版 + chunks/ 分块版 |

---

## 9. 解析层与 AI 层边界

```
解析层（Java）              AI 层（MapReduce）
─────────────               ───────────────────
格式解包                    语义理解
全量无损提取                规则匹配
文件输出                    报告生成
不做 OCR                    按需 Qwen-VL 分析图片
不做表格语义理解            按需 LLM 分析表格结构
不做文本清洗                按需在 prompt 中指定忽略规则
提供 chunks/index 索引      按 loading strategy 选择性加载
提供 large_tables 引用      按需加载 data/*.csv
```

---

## 10. 主要代码模块

| 模块 | 层 | 职责 |
|------|----|------|
| `RecursiveExtractor` | 提取 | 入口，协调整个提取流程，全局 seq 计数 |
| `TypeDetector` | 提取 | Tika MIME 类型检测 |
| `OleExtractor` | 提取 | OLE 二进制解包 |
| `DocxHandler` | 提取 | 处理 .docx/.docm |
| `DocHandler` | 提取 | 处理 .doc (HWPF) |
| `ExcelHandler` | 提取 | 处理 .xlsx/.xls，智能预览+大表分流 |
| `PdfHandler` | 提取 | 处理 .pdf |
| `ZipHandler` | 提取 | 处理 .zip |
| `ImageHandler` | 提取 | 处理图片：叶子节点 |
| `PositionTracker` | 提取 | 维护层级 + position 编码 |
| `StoreWriter` | 提取 | 写入 body.md + chunks/ + source + manifest + data/ |
| `JobScheduler` | AI | 解析 manifest + chunks/index，生成 Map Task 列表 |
| `MapExecutor` | AI | 并行执行 Map Task，调用 LLM |
| `ReduceExecutor` | AI | 去重合并 → 文档级 Reduce → 全局 Reduce → 报告 |
| `PromptRegistry` | AI | 管理四种检查类型的 prompt 模板 |
| `ReportWriter` | AI | 生成需求管控报告（JSON + Markdown） |

---

## 11. AI 层输入/输出规格

### 11.1 AI 层输入

```
提取层产物目录/
├── manifest.json              ← 入口：列出所有文档
├── {doc}/chunks/index.json    ← 每个文档的 chunk 索引 + large_tables
├── {doc}/chunks/chunk_*.md    ← Map 的直接输入
├── {doc}/data/*.csv           ← 大表数据，按需加载
└── {doc}/media/*.png          ← 图片，多模态模型按需加载
```

### 11.2 AI 层输出

```
output/{session_id}/
├── 需求管控报告.md             ← 主报告（人可读）
├── 需求管控报告.json           ← 主报告（机器可读，供下游系统消费）
├── findings/
│   ├── pii_findings.json      ← PII 检测详情
│   ├── regulatory_findings.json ← 监管报送详情
│   ├── page_input_findings.json ← 页面录入详情
│   └── knowledge/
│       ├── indicators.json    ← 指标知识
│       ├── tags.json          ← 标签知识
│       ├── data_items.json    ← 数据项知识
│       └── quality_rules.json ← 质量规则知识
```

### 11.3 需求管控报告格式

```json
{
  "session_id": "extract_20260716_234050",
  "source_file": "UR_FS.docx",
  "generated_at": "2026-07-17T10:30:00",
  "summary": {
    "pii_total": 15,
    "pii_high_risk": 3,
    "regulatory_missing": 2,
    "page_input_missing_rules": 5,
    "indicators_extracted": 12,
    "tags_extracted": 8
  },
  "pii_findings": [...],
  "regulatory_findings": [...],
  "page_input_findings": [...],
  "knowledge": {
    "indicators": [...],
    "tags": [...],
    "data_items": [...],
    "quality_rules": [...]
  }
}
```

---

## 12. v5 变更记录

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v2 | - | 基础解析 DocxHandler/ExcelHandler/PdfHandler/ZipHandler |
| v3 | - | 图片/OLE/PDF附件/TypeDetector/DocHandler/ImageHandler |
| v4 | 2026-07-16 | doc_{seq}→源文件名目录、分块策略、原始文档保留、死代码清理 |
| v5 | 2026-07-17 | CSV可见性修复(schema净化+智能preview)、chunks/index.json增加large_tables索引、MapReduce AI检核架构、通用prompt模板、按检查类型加载策略 |

---

## 13. 后续扩展方向

| 方向 | 说明 |
|------|------|
| 增量提取 | 仅重新提取变更的文档，复用未变更的 chunk 结果 |
| chunk 语义摘要 | index.json 增加每个 chunk 的标题/关键词，支持 AI 层更精准的按需加载 |
| 图片 OCR 预处理 | 对含文字的截图/图表做 OCR，结果写入 body.md，减少 Qwen-VL 调用 |
| 检查规则可配置 | 支持用户自定义检核规则，prompt 模板参数化 |
| Reduce 缓存 | 文档级 Reduce 结果缓存，支持反复调整全局 Reduce 逻辑 |
