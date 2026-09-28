package com.anying.helpdesk.knowledge.processing;

import java.io.InputStream;

public interface KnowledgeDocumentParser {

    /**
     * 将受支持的原始文档转换为统一纯文本。
     */
    ParsedDocument parse(InputStream inputStream, String originalName, String contentType);
}
