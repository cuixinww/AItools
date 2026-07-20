# 智能文档解析与检核系统 — 设计规格说明书（v6）

## 1. 概述

对 UR/FS 需求文档进行**全量无损提取**，支持多层嵌套文件（word → excel/pdf/word/zip/图片 → 更深层），将内容展开为平级目录结构。提取层输出结构化的 body.md + chunks/ + data/ + manifest.json，供 AI 检核层按需加载与定向检索。

**v6 更新重点**：三阶段提取架构（UNPACK → TRIAGE+IMAGE → PARSE）、内存隔离、图文回填、无关文档过滤、合并单元格填充、多区域自动切分、CSV 切片索引、文档级优先检索 + 系统层自动导航。

---

## 2. 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 提取层运行环境 |
| Apache POI (ooxml) | 5.3.0 | 解析 .docx / .xlsx |
| Apache POI (scratchpad) | 5.3.0 | HWPF 解析 .doc |
| Apache PDFBox | 3.0.2 | 解析 .pdf，Y 坐标排序 |
| Apache Tika | 2.9.2 | MIME 类型检测 |
| JUnit 5 + AssertJ | - | 单元测试 |
| LLM API | - | Phase 1.5 快判+视觉描述 / AI 检核层 |
| 视觉大模型 API | - | Phase 1.5 图片内容理解 |

---

## 3. 系统总体架构：三阶段提取

```
┌──────────────────────────────────────────────────────────────┐
│                   Phase 1: UNPACK（纯拆包）                    │
│                                                              │
│  输入: 原始文档流                                              │
│  职责: 递归提取嵌入文件+原始图片，写目录结构+source文件+manifest骨架│
│  内存: 每次只 hold 一个文件                                      │
│  不解析段落/表格内容                                            │
│                                                              │
│  输出:                                                        │
│    extracted/{session_id}/                                    │
│    ├── manifest.json              ← 骨架 (status: pending)    │
│    ├── {doc}/                      ← 每文档一个目录            │
│    │   ├── {doc}.{ext}            ← 原始二进制                │
│    │   └── media/                  ← 原始图片                  │
│    └── ...                                                    │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│                Phase 1.5: TRIAGE + IMAGE（并行预处理）          │
│                                                              │
│  TRIAGE: 每个嵌入文件 → LLM 快判（标题+前3段/前50行）→ 标记    │
│          relevant / filtered                                  │
│                                                              │
│  IMAGE: 每张图片 → 视觉大模型 → 描述+标题 → media/{img}.json   │
│                                                              │
│  manifest 更新: status → relevant | filtered（附原因）         │
└──────────────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│                   Phase 2: PARSE（逐文件解析）                  │
│                                                              │
│  遍历 manifest → 跳过 filtered → 打开文件迭代段落/表格          │
│  遇到图片位置 → 读取 media/{img}.json → 描述内联到 body.md      │
│  大表 → data/*.csv + CSV 切片 → body.md 写 data_ref+预览       │
│                                                              │
│  输出:                                                        │
│    {doc}/                                                      │
│    ├── {doc}.md                  ← body.md（图片描述已内联）    │
│    ├── chunks/                    ← 50元素/chunk + index.json  │
│    ├── data/                      ← 完整 CSV + CSV 切片        │
│    │   ├── 开发排期.csv           ← 原始完整 CSV               │
│    │   └── 开发排期/              ← CSV 切片目录（>500行时）    │
│    │       ├── index.json         ← {columns, total_rows, ...} │
│    │       └── chunk_NNNN.csv    ← 100行/chunk                │
│    └── media/                     ← {img}.json 已由 Phase 1.5 写入│
│                                                              │
│  manifest 补充: element_count, data_ref_count, image_count     │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 设计原则

- **内存隔离**：三阶段均无递归，每个文件独立处理，任何时候只 open 一个文件
- **可重试**：Phase 1 产物落盘后，Phase 1.5/2 失败不需要重新拆包
- **可审计**：文件保留在磁盘上，filtered 文件也可事后复查
- **图片先行**：视觉理解在 Phase 1.5 并行完成，Phase 2 只管回填

### 3.2 目录结构（最终输出）

```
extracted/{session_id}/
├── manifest.json
├── {根文档}/                              ← 以 sanitized 源文件名命名
│   ├── {根文档}.{ext}                    ← 原始二进制副本
│   ├── {根文档}.md                       ← body.md（图片描述已内联）
│   ├── chunks/
│   │   ├── index.json
│   │   └── chunk_NNNN.md
│   ├── media/
│   │   ├── img_X.png                     ← 原始图片
│   │   └── img_X.json                    ← 视觉描述（Phase 1.5）
│   └── data/
│       ├── 开发排期.csv                   ← 完整 CSV（从表头行开始）
│       └── 开发排期/                      ← CSV 切片目录（行数 >500 时）
│           ├── index.json
│           └── chunk_NNNN.csv            ← 100 行/chunk
├── {嵌入文档A}/
│   └── ...
├── {嵌入文档B}/                            ← 被过滤的文件
│   ├── {嵌入文档B}.docx                   ← 保留文件，不解析
│   └── (无 body.md, 无 chunks/)
└── {同名则加 _2 后缀}/
```

---

## 4. Phase 1: UNPACK 详细设计

### 4.1 流程

```
extract(is, fileName):
  1. 读 byte[] rawBytes
  2. TypeDetector 检测真实 MIME 类型
  3. 用 POI/Tika 打开文档，不迭代段落/表格，仅:
     a. 提取嵌入文件（/word/embeddings/, OLE, PDF 附件, ZIP 条目）
     b. 提取原始图片（drawing, PDResources, workbook.getAllPictures()）
  4. 写目录结构 + source 文件 + 原始图片到 media/
  5. 每个嵌入文件 → 写入子目录 → 递归步骤 1（无内存递归：写磁盘后释放引用）
  6. 写 manifest 骨架（seq, dir, source, source_copy, type, parent, status=pending）
```

### 4.2 消歧规则

- 目录名: `fileNameToBaseName(fileName)` sanizie → 同名追加 `_2`、`_3` 后缀
- sanizie: 保留中文/英文/数字/`.`/`-`/`_`，其他替换为 `_`，合并连续下划线，去头尾
- parent 引用: `"{目录名}, pos={position}"` 格式

---

## 5. Phase 1.5: TRIAGE + IMAGE 详细设计

### 5.1 TRIAGE: 文档过滤

**触发条件**: 所有嵌入文件都需进行快判。

**LLM 快判输入**: 文档标题 + 前 3 段（或前 50 行，取短者）

**LLM 快判输出**:
```json
{
  "relevant": true,
  "confidence": 0.95,
  "reason": "需求规格说明书，与项目直接相关"
}
```

或:
```json
{
  "relevant": false,
  "confidence": 0.92,
  "reason": "无关文档：保险告知函模板"
}
```

**目标过滤类型**: 书面告知函、保险说明书、短信模板、邮件模板、示例代码

**处理**: relevant=false → manifest 标记 `status: "filtered"` + `filter_reason` + `filter_confidence`。文件保留在磁盘上不删除，Phase 2 跳过不解析。

### 5.2 IMAGE: 图片内容提取

**触发条件**: Phase 1 提取的所有图片。

**处理**: 调用视觉大模型，输出 JSON 保存到 `media/{img_filename}.json`。

**输出格式（统一基础字段 + 流程图可追加 structure）**:

```json
{
  "title": "用户信息录入画面入力项目",
  "category": "UI_MOCKUP",
  "description": "该画面包含以下入力项目：\n- 姓名：文本入力框\n- 身份证号：文本入力框\n- 联系电话：文本入力框\n- 家庭地址：文本入力框\n\n页面底部包含【提交】【重置】两个按钮。"
}
```

**category 枚举**:

| 值 | 含义 | 标题前缀 |
|----|------|---------|
| `UI_MOCKUP` | UI 原型/线框图 | "XXX画面入力项目" / "XXX画面照会内容" |
| `FLOWCHART` | 流程图/架构图 | "XXX流程图" |
| `TABLE_SCREENSHOT` | 表格截图 | "XXX表" |
| `FORMULA` | 公式/计算逻辑 | "XXX计算公式" |
| `SIGNATURE_STAMP` | 签章/印章 | "签章：XXX" |
| `OTHER` | 其他 | LLM 自行总结 |

**流程图/公式扩展字段**（可选，仅 FLOWCHART/FORMULA）:

```json
{
  "structure": {
    "nodes": [
      {"id": "1", "name": "提交采购申请", "role": "采购专员"},
      {"id": "2", "name": "部门审批", "role": "部门经理", "deadline": "2工作日"},
      {"id": "3", "name": "财务审核", "role": "财务主管"},
      {"id": "4", "name": "总经理审批", "role": "总经理", "condition": "金额>10万"},
      {"id": "5", "name": "采购执行", "role": "采购专员"},
      {"id": "6", "name": "归档", "role": "系统自动"}
    ],
    "edges": [
      {"from": "1", "to": "2"},
      {"from": "2", "to": "3"},
      {"from": "3", "to": "4", "label": "金额>10万"},
      {"from": "3", "to": "5", "label": "金额≤10万"},
      {"from": "4", "to": "5"},
      {"from": "5", "to": "6"}
    ],
    "start_node": "1",
    "end_nodes": ["6"]
  }
}
```

---

## 6. Phase 2: PARSE 详细设计

### 6.1 流程

```
for each doc in manifest where status != "filtered":
  1. 打开 source 文件
  2. 按文档类型调用对应 Handler
  3. Handler 迭代时遇到图片：
     → 读取 media/{img}.json
     → 将 title + description 内联到 body.md 原位置
  4. 写入 body.md, chunks/, data/, media/
  5. manifest 补充: element_count, data_ref_count, image_count, status="done"
```

### 6.2 图文顺序保证

#### DocxHandler

使用 `XWPFDocument.getBodyElements()` 按文档 XML 原始元素顺序遍历（IBodyElement 包含 XWPFParagraph, XWPFTable，图片 Drawing 以 CT 对象形式内嵌在 XWPFRun 中）。OLE 嵌入对象在 XWPFRun 中也按 run 顺序处理。

保证：图片/嵌入的前后段落就是它实际的上下文。

#### PdfHandler

继承 `PDFTextStripper`，使用 `PDFStreamEngine` 监听图片绘制指令。

按 **Y 坐标从上到下排序**合并文字段和图片：
- 记录每段文字的 Y 坐标 + position 编号
- 记录每张图片的 Y 坐标
- 按 Y 坐标合并排序，写 body.md

不收集像素级坐标。对 UR/FS 文档的单栏线性排版覆盖 95%+ 真实场景。

### 6.3 各 Handler 规格（Phase 2 增强）

#### DocxHandler

| 内容 | 说明 |
|------|------|
| 段落文字 | `XWPFParagraph` 提取，删除线用 `~~...~~` 标记 |
| 表格 | `XWPFTable` 转 Markdown 表格 |
| 内嵌图片 | 读取 media/{img}.json → title + description 内联 |
| OLE 嵌入对象 | `/word/embeddings/` → OleExtractor + TypeDetector |
| 页眉/页脚 | 提取 header/footer 段落和表格 |
| embed 元素 | 生成 `TYPE: embed | file: xxx` 标记 |

#### ExcelHandler

| 内容 | 说明 |
|------|------|
| Sheet 识别 | 每个 Sheet 生成 `sheet_header` 元素 |
| 合并单元格 | 向下填充 + 向右填充（POI `getMergedRegions()`）|
| 删除线 | 检出 `CellStyle.getFont().getStrikeout()`，包裹 `~~...~~` |
| 多区域切分 | 按列填充率自动检测：连续 ≥2 列填充率=0% 视为分割线 |
| 小表 (≤50行, ≤10列) | 全量 Markdown 表格写入 body.md |
| 大表 (>50行) | body.md 存 data_ref(schema+preview)，全量写 data/*.csv |
| data_ref 预览 | 智能定位表头行(`findHeaderRowIndex`)，显示 Columns + 前2行数据 |
| data_ref schema | 过滤空单元格，用 `\|` 分隔 |
| CSV 输出 | 从表头行开始写（跳过 title/描述行）|
| CSV 切片 | 行数 >500 时创建切片目录，100 行/chunk |
| 内嵌图片 | `workbook.getAllPictures()` |

##### 多区域切分算法

```
1. 对整个 Sheet 计算每列填充率 = 非空单元格数 / 总行数
2. 填充率 = 0% 的列 → 完全空列
3. 连续 ≥2 列填充率=0% → 区域分割线
4. 每个区域独立产出表格/data_ref
```

##### 合并单元格处理

```
rows 1-3, col A 合并为 "XX部门"
  → row 1A = "XX部门", row 2A = "XX部门", row 3A = "XX部门"（向下+向右填充）
```

##### 删除线处理

```
Excel: cell.getCellStyle().getFont().getStrikeout() → 包裹 ~~...~~
Word:  XWPFRun.isStrikeThrough() → 包裹 ~~...~~
body.md: | 字段 | 类型 | ~~删除列~~ |
```

#### PdfHandler

| 内容 | 说明 |
|------|------|
| 文本提取 | PDFBox 按 Y 坐标排序 |
| 图片提取 | `PDResources` → `PDImageXObject`，按 Y 坐标混排 |
| 图片回填 | 读取 media/{img}.json → 内联 title + description |
| 内嵌附件 | `PDDocumentCatalog` 附加文件 |
| 表格结构 | 不处理，解析层不做语义理解 |

#### 其余 Handler

| Handler | 职责 |
|---------|------|
| DocHandler | .doc (HWPF): 段落/表格/嵌入对象提取 |
| ImageHandler | 叶子节点：保存到 media/ |
| ZipHandler | 解压递归：图片路由到 ImageHandler |

---

## 7. 输出格式

### 7.1 body.md 格式

```
# DOC: {seq}
# SOURCE: {文件名}
# PARENT: {父级目录名}, pos={父级position}    ← 非根文档才有
{空行}
# POS: {N} | TYPE: {type} | {metadata}
{content}
```

### 7.2 图片回填后的 body.md 示例

```
# POS: 3 | TYPE: image | file: media/img_0.png | title: 用户信息录入画面入力项目
**用户信息录入画面入力项目**（UI原型图）
该画面包含以下入力项目：
- 姓名：文本入力框
- 身份证号：文本入力框
- 联系电话：文本入力框
- 家庭地址：文本入力框
页面底部包含【提交】【重置】两个按钮。
```

### 7.3 data_ref 格式（含智能预览）

```
# POS: 5 | TYPE: data_ref | schema: 模块 | 任务名称 | 负责人 | 优先级 | 预估工时(人天) | ... | rows: 37 | file: data/开发排期.csv
Columns: 模块 | 任务名称 | 负责人 | 优先级 | 预估工时(人天) | 实际工时(人天) | 计划开始 | 计划结束 | ...
Row 1: 采购管理, 需求分析与原型设计, 张晓明, P0, 15, 12, 2026-08-01, 2026-08-20, ...
Row 2: 采购管理, 后端API开发, 刘芳, P0, 30, 25, 2026-08-21, 2026-09-30, ...
```

### 7.4 CSV 切片 index.json

```
data/{sheet_name}/
├── index.json        ← {"name": "开发排期.csv", "total_rows": 5000, "chunk_size": 100, "columns": [...], "chunks": [...]}
├── chunk_0001.csv    ← 行 1-100
├── chunk_0002.csv    ← 行 101-200
└── ...
```

### 7.5 chunks/index.json 格式

```json
{
  "source": "doc/dir/doc.md",
  "total_elements": 8,
  "total_large_tables": 2,
  "total_images": 1,
  "chunk_size": 50,
  "chunks": [
    {"file": "chunk_0001.md", "pos_range": [0, 49], "element_count": 50, "includes_large_table": true}
  ],
  "large_tables": [
    {"name": "开发排期.csv", "sheet": "开发排期", "pos": 5, "rows": 5000, "file": "data/开发排期.csv", "csv_chunks_dir": "data/开发排期/", "csv_chunks": 50, "csv_chunk_size": 100, "in_chunk": "chunk_0001.md"}
  ]
}
```

### 7.6 manifest.json 格式

```json
{
  "session_id": "extract_20260717_100000",
  "source_file": "UR_FS.docx",
  "docs": [
    {"seq": 0, "dir": "UR_FS", "source": "UR_FS.docx", "source_copy": "UR_FS/UR_FS.docx", "type": "docx", "element_count": 223, "data_ref_count": 0, "image_count": 5, "parent": "root", "status": "done"},
    {"seq": 1, "dir": "数据字典", "source": "数据字典.xlsx", "source_copy": "数据字典/数据字典.xlsx", "type": "xlsx", "element_count": 12, "data_ref_count": 1, "image_count": 0, "parent": "UR_FS, pos=15", "status": "done"},
    {"seq": 2, "dir": "保险告知函", "source": "Microsoft_Word_Document.docx", "source_copy": "保险告知函/Microsoft_Word_Document.docx", "type": "docx", "element_count": 0, "data_ref_count": 0, "image_count": 0, "parent": "UR_FS, pos=8", "status": "filtered", "filter_reason": "无关文档：保险告知函模板", "filter_confidence": 0.92}
  ]
}
```

**status 枚举**: `pending` | `processing` | `done` | `filtered`

---

## 8. AI 检核层检索设计

### 8.1 检索模型：文档级优先 + 系统层自动导航

```
原则: AI 优先加载完整 body.md（≤300元素，在 token 预算内）。
     超过 300 元素时，先读 chunks/index.json，用关键词定位目标 chunk，
     加载目标 chunk + 上下文窗口（前后各 2 chunk）。

导航: 子文档通过 # PARENT 头实现自包含，系统层自动追溯父上下文。
     AI 不需要记住"从哪来的"，系统自动扩大上下文窗口。
```

### 8.2 子文档检索时的自动追溯

```
Step 1: AI 在子文档 body.md 发现命中
Step 2: 系统读取 # PARENT: {parent_dir}, pos={N}
Step 3: 自动加载 ../{parent_dir}/{parent_dir}.md
Step 4: 定位 pos=N（embed 元素位置）
Step 5: 读取 pos=N 前后 N 个元素作为上下文
Step 6: 将父上下文注入 LLM prompt 窗口
```

### 8.3 按检查类型的加载策略

| 检查类型 | 触发条件 | 加载 body.md | 加载 CSV | 加载图片描述 |
|---------|---------|-------------|---------|------------|
| PII 检测 | 总是执行 | 全部（全文档） | 全部 CSV 切片 | 需要 |
| 监管报送 | 前端标记=是 | 关键词命中区 | schema 含"报送/监管/上报/披露/合规"时加载 | 需要 |
| 页面录入 | 总是执行 | 全部（全文档） | schema 含"字段/数据项/参数/属性/入力/录入"时加载 | 需要 |
| 知识提取 | 总是执行 | 全部（全文档） | schema 含"指标/KPI/公式/口径/率/比/度"时加载 | 需要 |

### 8.4 CSV 按需加载规则链

不需要 LLM 判断，系统通过**规则链 + schema 字符串匹配**决定：

```
IF 检查类型 = PII检测:
    → 总是加载 CSV

IF 检查类型 = 知识提取:
    → schema 含 "指标|KPI|公式|口径|率|比|度" → 加载
    → 否则跳过

IF 检查类型 = 页面录入检核:
    → schema 含 "字段|数据项|参数|属性|列名|录入|入力" → 加载
    → 否则跳过

IF 检查类型 = 监管报送:
    → schema 含 "报送|监管|上报|披露|合规" → 加载
    → 否则跳过
```

CSV 未加载时，manifest 中标记 `"csv_loaded": false, "reason": "schema does not match check type"`。

---

## 9. 类型检测路由表

| 检测结果 (MIME) | 路由 Handler |
|-----------------|-------------|
| `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | DocxHandler |
| `application/msword` | DocHandler |
| `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | ExcelHandler |
| `application/vnd.ms-excel` | ExcelHandler |
| `application/pdf` | PdfHandler |
| `application/zip` | ZipHandler |
| `image/png`, `image/jpeg`, `image/gif`, `image/bmp`, `image/tiff`, `image/webp` | ImageHandler |

---

## 10. 解析层与 AI 层边界

```
解析层（Java 三阶段）         AI 检核层
───────────────────          ─────────
格式解包                     语义理解
全量无损提取                 规则匹配
文件输出                     报告生成
图片内容回填（视觉模型）      文档级检索
无关文档过滤（LLM 快判）      按需 CSV 加载
CSV 切片 + 索引               系统层自动导航父上下文
合并单元格填充                PII/监管/页面/知识四类检核
多区域自动切分
删除线标记
PDF Y 坐标排序
不做 OCR（已由视觉模型替代）
不做表格语义理解
```

---

## 11. 主要代码模块

| 模块 | 阶段 | 职责 |
|------|------|------|
| `RecursiveExtractor` | Phase 1 | UNPACK 入口，递归拆包，写目录骨架 |
| `TypeDetector` | Phase 1 | Tika MIME 类型检测 |
| `OleExtractor` | Phase 1 | OLE 二进制解包 |
| `TriageProcessor` | Phase 1.5 | LLM 快判过滤无关文档 |
| `ImageDescriber` | Phase 1.5 | 视觉大模型图片描述，写 img.json |
| `ParseExecutor` | Phase 2 | 遍历 manifest，逐文件解析 |
| `DocxHandler` | Phase 2 | .docx/.docm: BodyElement 顺序遍历 |
| `DocHandler` | Phase 2 | .doc (HWPF) |
| `ExcelHandler` | Phase 2 | .xlsx/.xls: 合并填充+多区域切分+大表分流+CSV切片 |
| `PdfHandler` | Phase 2 | .pdf: Y 坐标排序+图文混排 |
| `ZipHandler` | Phase 2 | .zip 解压递归 |
| `ImageHandler` | Phase 2 | 图片叶子节点 |
| `PositionTracker` | Phase 2 | 维护层级 + position 编码 |
| `StoreWriter` | Phase 2 | 写入 body.md + chunks/ + data/ + manifest 补充 |
| `CsvSlicer` | Phase 2 | CSV 切片，100 行/chunk |
| `MergeCellResolver` | Phase 2 | 合并单元格向下+向右填充 |
| `RegionSplitter` | Phase 2 | 列填充率多区域自动切分 |

---

## 12. 变更历史

| 版本 | 日期 | 变更内容 |
|------|------|---------|
| v2 | - | 基础解析：DocxHandler, ExcelHandler, PdfHandler, ZipHandler |
| v3 | - | 图片提取、OLE提取、PDF附件、TypeDetector、DocHandler、ImageHandler |
| v4 | 2026-07-16 | doc_{seq}→源文件名目录、分块策略、原始文档保留、死代码清理 |
| v5 | 2026-07-17 | CSV可见性修复(schema净化+智能preview)、chunks/index.json large_tables索引、MapReduce AI检核架构 |
| v6 | 2026-07-17 | 三阶段提取架构(UNPACK→TRIAGE+IMAGE→PARSE)、内存隔离、图文回填、无关文档过滤(LLM快判)、合并单元格填充、多区域自动切分(列填充率)、删除线标记(~~...~~)、CSV切片(100行/chunk)+规则链按需加载、PDF Y坐标排序、文档级优先检索+子文档自包含+系统层自动导航 |
