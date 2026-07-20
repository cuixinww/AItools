package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import com.dg.tools.extractor.model.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 递归式文档提取器（RecursiveExtractor）。
 *
 * 该组件是整个文档解析层的统一入口，负责将一份原始文档（可能内部嵌套了
 * 其它文档 / 图片 / 压缩包）逐层展开并抽取为磁盘上的结构化目录。
 *
 * 设计上采用「两阶段 + 一个预留扩展点」的处理流程，强调内存隔离与可重试性：
 *
 * 阶段 1（UNPACK，拆包）：
 *     基于队列（Queue）做广度优先的嵌入式文件与图片抽取。
 *     此阶段只做「二进制级别」的拆包，不解析段落 / 表格等文本内容。
 *     每个文件被读取、拆包、写入磁盘后立即释放其内存引用；
 *     拆出的内嵌文件会被重新放入队列，等待后续延迟处理（递归下一层）。
 *
 * 阶段 1.5（TRIAGE + IMAGE，分诊 + 图像理解）：
 *     预留的扩展点，目前为空操作（no-op），所有文件都会原样通过。
 *     未来计划在此接入：
 *       - LLM 快速判断（TRIAGE）：过滤与需求无关的文档；
 *       - 视觉大模型（IMAGE）：为每张图片生成文字描述（img.json）。
 *
 * 阶段 2（PARSE，解析）：
 *     对每一份已落盘的文件做完整内容解析（一次只处理一个文件，无递归）。
 *     读取拆包产物，解析段落 / 表格 / 图片，将图片描述（若已生成）内联回填，
 *     最终写出 body.md + chunks/ + data/ 等结构化产物。
 *
 * 线程安全性说明：
 *     被 @Component 标记为单例，但单例本身除了一份不可变的共享配置外不持有可变状态。
 *     每一次提取任务的全部可变状态都封装在内部的 Session 对象里，
 *     因此并发调用 extract(...) 是安全的（每次调用各自独立的 Session）。
 */
@Component
public class RecursiveExtractor {

    /** 递归展开的最大深度，防止内嵌文件无限嵌套导致栈 / 队列爆炸。 */
    private static final int MAX_DEPTH = 10;

    /** 单文件大小上限（200 MB），超过则直接拒绝处理，避免内存溢出。 */
    private static final int MAX_FILE_SIZE = 200 * 1024 * 1024; // 200 MB per file

    /** 已注册的各类型文档处理器列表（按支持的扩展名路由）。 */
    private List<DocumentHandler> handlers;

    /** 负责将所有解析产物写入磁盘的工具类（无状态，可复用）。 */
    private final StoreWriter storeWriter = new StoreWriter();

    /** 负责基于文件内容 / 文件名探测真实 MIME 类型并映射到扩展名。 */
    private final TypeDetector typeDetector = new TypeDetector();

    /**
     * 默认构造器：注册内置的一组处理器，覆盖 docx / doc / xlsx / xls / pdf / zip / 图片。
     * 处理器顺序即为默认路由优先级（当多个处理器都能匹配时，靠前的优先）。
     */
    public RecursiveExtractor() {
        this.handlers = List.of(
                new DocxHandler(),
                new DocHandler(),
                new ExcelHandler(),
                new PdfHandler(),
                new ZipHandler(),
                new ImageHandler()
        );
    }

    /**
     * 供测试或外部定制时替换处理器集合。
     *
     * @param handlerArray 新的处理器数组（可变参数）
     */
    public void setHandlers(DocumentHandler... handlerArray) {
        this.handlers = List.of(handlerArray);
    }

    // ==================== 对外公开入口 ====================

    /**
     * 提取入口：针对单个输入流启动一次完整的「拆包 + 解析」流程。
     *
     * @param is         原始文档输入流（会被完整读入内存做后续处理）
     * @param fileName   原始文件名（用于类型探测与目录命名）
     * @param sessionId  本次提取会话的唯一标识（写入 manifest.json）
     * @param outputDir  输出根目录，所有产物会写到 outputDir/sessionId 结构下
     */
    public void extract(InputStream is, String fileName, String sessionId, Path outputDir) {
        new Session(sessionId, outputDir).run(is, fileName);
    }

    // ==================== 单次提取会话（线程安全） ====================

    /**
     * Session 封装「一次提取任务」的全部可变状态。
     * 每个 extract(...) 调用都会 new 一个独立的 Session，因此彼此互不干扰。
     */
    private class Session {
        /** 本次会话标识。 */
        final String sessionId;

        /** 输出根目录。 */
        final Path baseDir;

        /** 已拆包文档的元信息列表（用于生成 manifest.json）。 */
        final List<StoreWriter.DocInfo> docInfos = new ArrayList<>();

        /** 已占用的目录名集合，用于同名消歧（避免覆盖）。 */
        final Set<String> usedDirNames = new HashSet<>();

        /** 文档序号自增计数器（seq 从 0 开始）。 */
        int docSeq = 0;

        Session(String sessionId, Path baseDir) {
            this.sessionId = sessionId;
            this.baseDir = baseDir;
        }

        /**
         * 一次会话的主流程：先拆包（阶段 1），再解析（阶段 2），
         * 并在关键节点写 / 更新 manifest.json。
         */
        void run(InputStream is, String fileName) {
            // ---- 阶段 1：UNPACK（拆包） ----
            Deque<QueueItem> queue = new ArrayDeque<>();
            // 根文档作为队列中的第一个任务入队
            queue.add(new QueueItem(readBytes(is, fileName), fileName, null, 0));

            // 广度优先消费队列，直到全部内嵌文件都被拆包完毕
            while (!queue.isEmpty()) {
                QueueItem item = queue.poll();
                phase1Unpack(item, queue);
            }

            // 拆包完成后写出第一版 manifest（此时各文档状态为 pending）
            writeManifest();

            // ---- 阶段 1.5：TRIAGE + IMAGE（预留扩展点，当前为空操作） ----

            // ---- 阶段 2：PARSE（解析） ----
            phase2Parse();

            // 解析完成后再写一次 manifest（补充 element_count 等统计、更新状态）
            writeManifest();
        }

        // ==================== 阶段 1：UNPACK（拆包） ====================

        /**
         * 对队列中的单个文件做拆包处理。
         * 仅抽取内嵌文件与图片的「原始二进制」，不解析正文文本。
         * 拆出的内嵌文件会被投入队列进入下一层递归。
         *
         * @param item  当前待拆包文件（含原始字节、文件名、父级信息、递归深度）
         * @param queue 待处理队列（用于放入新拆出的内嵌文件）
         */
        private void phase1Unpack(QueueItem item, Deque<QueueItem> queue) {
            // 超过最大递归深度则停止，避免无限嵌套
            if (item.depth >= MAX_DEPTH) return;

            byte[] rawBytes = item.rawBytes;
            String fileName = item.fileName;
            String parentInfo = item.parentInfo;

            // 根据文件内容 / 文件名选出合适的处理器
            DocumentHandler handler = selectHandler(rawBytes, fileName);
            if (handler == null) return;

            // 调用处理器做二进制拆包（只拿内嵌文件 + 图片）
            UnpackResult unpacked = handler.unpack(new ByteArrayInputStream(rawBytes), fileName);
            if (unpacked == null) return;

            // 分配一个唯一的文档序号与目录名
            int seq = docSeq++;
            String docType = unpacked.getFileType();
            if (docType == null || docType.isEmpty()) {
                // 处理器未给出类型时，回退用类型探测器推测扩展名
                docType = typeDetector.detectExtension(rawBytes, fileName);
            }
            String baseName = fileNameToBaseName(fileName);
            String docDirName = allocateDirName(baseName);
            Path docDir = baseDir.resolve(docDirName);

            try {
                // 1) 保存原始文件副本，便于事后审计与重试
                storeWriter.writeSourceFile(docDir, rawBytes, fileName);

                // 2) 写出阶段 1 拆出的原始图片到 media/ 目录
                for (ImageFile img : unpacked.getImages()) {
                    Path mediaDir = docDir.resolve("media");
                    Files.createDirectories(mediaDir);
                    Files.write(mediaDir.resolve(img.getFileName()), img.getData());
                }

                // 3) 记录该文档的元信息（用于 manifest）
                docInfos.add(new StoreWriter.DocInfo(
                        seq, docDirName, fileName, docDirName + "/" + fileName,
                        docType, parentInfo != null ? parentInfo : "root", "pending"
                ));

                // 4) 将内嵌文件重新入队，等待阶段 1 继续向下拆包
                for (EmbeddedFile emb : unpacked.getEmbeddedFiles()) {
                    queue.add(new QueueItem(emb.getData(), emb.getFileName(),
                            docDirName + ", pos=" + emb.getPosition(), item.depth + 1));
                }

            } catch (Exception e) {
                throw new RuntimeException("Phase 1 failed for: " + docDirName, e);
            }
        }

        // ==================== 阶段 2：PARSE（解析） ====================

        /**
         * 遍历阶段 1 产出的所有文档目录，逐个做完整内容解析并写出结构化产物。
         * 状态为 filtered 的文档会被跳过（预留给阶段 1.5 的过滤机制）。
         */
        private void phase2Parse() {
            for (StoreWriter.DocInfo doc : docInfos) {
                // 被标记为过滤掉的文档不参与解析（仅保留原始文件）
                if ("filtered".equals(doc.status)) continue;

                Path docDir = baseDir.resolve(doc.dir);
                Path sourceFile = docDir.resolve(doc.source);

                // 源文件丢失则标记为错误并跳过
                if (!Files.exists(sourceFile)) {
                    doc.status = "error";
                    continue;
                }

                try {
                    // 从磁盘读回原始文件，重新做完整解析（无内存递归）
                    byte[] rawBytes = Files.readAllBytes(sourceFile);
                    DocumentHandler handler = selectHandler(rawBytes, doc.source);
                    if (handler == null) {
                        doc.status = "skipped";
                        continue;
                    }

                    // 调用处理器做全文解析（段落 / 表格 / 图片 / 内嵌文件）
                    ExtractionResult result = handler.extract(
                            new ByteArrayInputStream(rawBytes), doc.source);
                    if (result == null) {
                        doc.status = "error";
                        continue;
                    }

                    // 尝试将图片描述（img.json）内联回填到结果中
                    inlineImageDescriptions(docDir, result);

                    // 计算正文 Markdown 文件名：<基名>.md
                    String baseName = fileNameToBaseName(doc.source);
                    String mdFileName = baseName + ".md";

                    // 写出 body.md / media/ / data/ / chunks/ 等产物
                    storeWriter.writeBody(docDir, mdFileName, result, doc.seq,
                            doc.source, doc.parentInfo);
                    storeWriter.writeMedia(docDir, result);
                    storeWriter.writeLargeTables(docDir, result);
                    storeWriter.writeChunks(docDir, doc.dir, mdFileName, result);

                    // 回填统计信息到 manifest 元信息
                    doc.elementCount = result.getElements().size();
                    doc.dataRefCount = result.getLargeTables().size();
                    doc.imageCount = result.getImages().size();
                    doc.status = "done";

                } catch (Exception e) {
                    // 单文档解析失败不影响其它文档，仅记录并标记错误
                    doc.status = "error";
                    System.err.println("Phase 2 parse error for " + doc.dir + ": " + e.getMessage());
                }
            }
        }

        /**
         * 阶段 1.5（预留）的占位实现。
         * 目前仅检查 media/ 下是否已存在 {图片名}.json 描述文件，
         * 若存在（由未来视觉模型写入）则预留在此处把描述作为 Element 插入结果，
         * 当前实现不修改任何图片状态，保持向后兼容。
         */
        private void inlineImageDescriptions(Path docDir, ExtractionResult result) {
            // 阶段 1.5（未来）：读取图片描述 JSON 并挂接到 result 的元素上。
            // 现在仅探测描述文件是否存在，不实际变更图片状态。
            for (ImageFile img : result.getImages()) {
                Path descFile = docDir.resolve("media").resolve(img.getFileName() + ".json");
                if (Files.exists(descFile)) {
                    // 未来：读取描述内容并插入为一个 Element
                }
            }
        }

        // ==================== 工具方法 ====================

        /**
         * 将输入流完整读入字节数组，并做单文件大小上限校验。
         *
         * @throws RuntimeException 当文件超过 MAX_FILE_SIZE 或读取失败时
         */
        private byte[] readBytes(InputStream is, String fileName) {
            try {
                byte[] data = is.readAllBytes();
                if (data.length > MAX_FILE_SIZE) {
                    throw new RuntimeException("File too large: " + fileName
                            + " (" + (data.length / (1024 * 1024)) + " MB), max is "
                            + (MAX_FILE_SIZE / (1024 * 1024)) + " MB");
                }
                return data;
            } catch (Exception e) {
                throw new RuntimeException("Failed to read input stream for: " + fileName, e);
            }
        }

        /**
         * 目录名分配（同名消歧）。
         * 若 baseName 尚未使用则直接使用；否则追加 _2、_3 … 后缀直到不冲突。
         */
        private String allocateDirName(String baseName) {
            if (!usedDirNames.contains(baseName)) {
                usedDirNames.add(baseName);
                return baseName;
            }
            for (int i = 2; ; i++) {
                String candidate = baseName + "_" + i;
                if (!usedDirNames.contains(candidate)) {
                    usedDirNames.add(candidate);
                    return candidate;
                }
            }
        }

        /**
         * 根据文件字节内容与文件名选择匹配的处理器。
         * 优先用内容探测出的扩展名（更可靠），其次回退到文件名后缀匹配。
         */
        private DocumentHandler selectHandler(byte[] rawBytes, String fileName) {
            // detectExtension 返回的是裸扩展名（如 "docx"）；
            // 而各 handler 的 supports(...) 判断的是带点的扩展名（如 ".docx"）
            String detectedExt = typeDetector.detectExtension(rawBytes, fileName);
            String dottedExt = "." + detectedExt;

            // 第一轮：用内容探测出的扩展名匹配
            for (DocumentHandler h : handlers) {
                if (h.supports(dottedExt)) {
                    return h;
                }
            }

            // 第二轮：用原始文件名后缀匹配（兜底）
            for (DocumentHandler h : handlers) {
                if (h.supports(fileName)) {
                    return h;
                }
            }

            return null;
        }

        /**
         * 写出 manifest.json（覆盖式写入，包含当前所有文档的元信息）。
         */
        private void writeManifest() {
            String json = StoreWriter.buildManifestJson(sessionId,
                    docInfos.isEmpty() ? "unknown" : docInfos.get(0).source,
                    docInfos);
            try {
                Files.writeString(baseDir.resolve("manifest.json"), json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException("Failed to write manifest.json", e);
            }
        }
    }

    // ==================== 内部静态辅助类型 ====================

    /**
     * 队列中的待处理项，封装一个已读入内存的文件及其上下文信息。
     */
    private static class QueueItem {
        /** 文件的原始字节内容。 */
        final byte[] rawBytes;

        /** 文件名（含扩展名）。 */
        final String fileName;

        /** 父级文档信息（格式为 "目录名, pos=N"），根文档为 null。 */
        final String parentInfo;

        /** 当前递归深度。 */
        final int depth;

        QueueItem(byte[] rawBytes, String fileName, String parentInfo, int depth) {
            this.rawBytes = rawBytes;
            this.fileName = fileName;
            this.parentInfo = parentInfo;
            this.depth = depth;
        }
    }

    /**
     * 将原始文件名转换为安全的目录基名（去除扩展名并做字符清洗）。
     * 规则：
     *   - 去掉最后一个 "." 之后的扩展名；
     *   - 仅保留字母、数字、中文、部分 unicode 文字、点、连字符、下划线，其余替换为 "_"；
     *   - 合并连续下划线，并去掉首尾的点 / 下划线；
     *   - 结果为空时回退为 "unknown"。
     *
     * @param fileName 原始文件名
     * @return 清洗后的目录基名
     */
    static String fileNameToBaseName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "unknown";
        String name = fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\u0400-\\u04FF\\u0600-\\u06FF\\uAC00-\\uD7AF.\\-_]", "_");
        name = name.replaceAll("_+", "_");
        name = name.replaceAll("^[._]+|[._]+$", "");
        if (name.isEmpty()) name = "unknown";
        return name;
    }
}
