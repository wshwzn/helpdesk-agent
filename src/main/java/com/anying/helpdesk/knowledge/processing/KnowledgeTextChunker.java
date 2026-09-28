package com.anying.helpdesk.knowledge.processing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeTextChunker {

    private static final String SENTENCE_ENDINGS = "。！？；.!?;";

    private final int chunkSize;
    private final int chunkOverlap;

    /**
     * 使用配置的块大小和重叠量初始化分块器，并校验二者关系。
     */
    public KnowledgeTextChunker(KnowledgeProcessingProperties properties) {
        if (properties.getChunkOverlap() >= properties.getChunkSize()) {
            throw new IllegalStateException("knowledge chunk overlap must be smaller than chunk size");
        }
        this.chunkSize = properties.getChunkSize();
        this.chunkOverlap = properties.getChunkOverlap();
    }

    /**
     * 优先在段落、换行和中英文句末处分块，并保留相邻块重叠文本。
     */
    public List<DocumentChunk> chunk(String sourceText) {
        String text = normalize(sourceText);
        if (text.isEmpty()) {
            return List.of();
        }
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int maximumEnd = Math.min(start + chunkSize, text.length());
            int end = maximumEnd == text.length()
                    ? maximumEnd
                    : locateNaturalBreak(text, start, maximumEnd);
            int contentStart = skipWhitespaceForward(text, start, end);
            int contentEnd = skipWhitespaceBackward(text, contentStart, end);
            if (contentStart < contentEnd) {
                chunks.add(new DocumentChunk(
                        chunks.size() + 1,
                        contentStart,
                        contentEnd,
                        text.substring(contentStart, contentEnd)
                ));
            }
            if (end >= text.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - chunkOverlap);
            start = skipWhitespaceForward(text, nextStart, end);
            if (start >= end) {
                start = end;
            }
        }
        return List.copyOf(chunks);
    }

    /**
     * 返回分块前使用的规范化文本，便于预览接口报告一致的字符数量和偏移量。
     */
    public String normalize(String sourceText) {
        if (sourceText == null) {
            return "";
        }
        return sourceText
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /**
     * 在块长度后半段按语义边界优先级寻找最合适的结束位置。
     */
    private int locateNaturalBreak(String text, int start, int maximumEnd) {
        int minimumEnd = start + Math.max(1, chunkSize / 2);
        int paragraphBreak = text.lastIndexOf("\n\n", maximumEnd - 2);
        if (paragraphBreak >= minimumEnd) {
            return paragraphBreak + 2;
        }
        int lineBreak = text.lastIndexOf('\n', maximumEnd - 1);
        if (lineBreak >= minimumEnd) {
            return lineBreak + 1;
        }
        for (int index = maximumEnd - 1; index >= minimumEnd; index--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        for (int index = maximumEnd - 1; index >= minimumEnd; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index + 1;
            }
        }
        return maximumEnd;
    }

    /**
     * 跳过区间开头的空白字符，避免生成以空白开头的块。
     */
    private int skipWhitespaceForward(String text, int start, int upperBound) {
        int index = start;
        while (index < upperBound && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * 跳过区间末尾的空白字符，避免块尾保存无意义空白。
     */
    private int skipWhitespaceBackward(String text, int lowerBound, int end) {
        int index = end;
        while (index > lowerBound && Character.isWhitespace(text.charAt(index - 1))) {
            index--;
        }
        return index;
    }
}
