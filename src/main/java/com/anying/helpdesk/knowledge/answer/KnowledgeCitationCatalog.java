package com.anying.helpdesk.knowledge.answer;

import com.anying.helpdesk.knowledge.dto.KnowledgeDocumentMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 本轮不可变引用目录；偏移采用 Java UTF-16 下标，原文只从检索快照提取。 */
public record KnowledgeCitationCatalog(String citationSetId, List<Source> sources) {
    private static final String NEWLINE = "(?:\\r?\\n|\\r(?!\\n))";
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile(NEWLINE + "[\\t ]*" + NEWLINE
            + "(?:[\\t ]*" + NEWLINE + ")*");
    private static final Pattern EVIDENCE_TAG = Pattern.compile("\\[E[0-9]+]");

    public record Source(String id, Long documentId, int chunkSequence, String sourceName,
                         KnowledgeDocumentMetadata metadata, List<Paragraph> paragraphs) { }
    public record Paragraph(String id, int startOffset, int endOffset, String text, boolean selectable) { }

    /** 按空行划分连续原文，单换行保留在段内；每次生成独立目录身份以拒绝跨轮引用。 */
    public static KnowledgeCitationCatalog create(List<KnowledgeAnswerReport.Evidence> evidence) {
        List<Source> sources = new ArrayList<>();
        for (var item : evidence) {
            List<Paragraph> paragraphs = new ArrayList<>();
            var matcher = PARAGRAPH_BREAK.matcher(item.content());
            int start = 0;
            while (matcher.find()) {
                append(paragraphs, item, start, matcher.start());
                start = matcher.end();
            }
            append(paragraphs, item, start, item.content().length());
            sources.add(new Source(item.id(), item.documentId(), item.chunkSequence(), item.sourceName(),
                    item.metadata(), List.copyOf(paragraphs)));
        }
        return new KnowledgeCitationCatalog(UUID.randomUUID().toString(), List.copyOf(sources));
    }

    /** 保留段内空白和条件，过长或过短的段落仅供理解上下文，不切碎为可引用的半句。 */
    private static void append(List<Paragraph> paragraphs, KnowledgeAnswerReport.Evidence evidence, int start, int end) {
        String text = evidence.content().substring(start, end);
        if (text.isBlank()) return;
        boolean selectable = text.length() <= 1500 && text.strip().length() >= 4
                && !EVIDENCE_TAG.matcher(text).find();
        paragraphs.add(new Paragraph(evidence.id() + "-P" + (paragraphs.size() + 1), start, end, text, selectable));
    }
}
