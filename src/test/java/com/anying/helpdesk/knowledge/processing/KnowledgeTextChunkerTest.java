package com.anying.helpdesk.knowledge.processing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeTextChunkerTest {

    /**
     * 验证中文文本优先按自然边界切分并保留相邻重叠区域。
     */
    @Test
    @DisplayName("中文文本按自然边界分块并保留重叠")
    void chunksChineseTextAtNaturalBoundariesWithOverlap() {
        KnowledgeProcessingProperties properties = properties(30, 6);
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(properties);
        String text = "第一段介绍登录流程。用户需要输入账号和密码。\n\n"
                + "第二段介绍重置流程。验证码将在五分钟内失效。\n\n"
                + "第三段介绍安全建议。不要向任何人泄露密码。";

        List<DocumentChunk> chunks = chunker.chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(30));
        assertThat(chunks).extracting(DocumentChunk::sequence)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, chunks.size()).boxed().toList());
        assertThat(chunks.get(1).startOffset()).isLessThan(chunks.get(0).endOffset());
        assertThat(chunks.get(0).content()).endsWith("。");
    }

    /**
     * 验证换行与空白规范化后不会生成空分块。
     */
    @Test
    @DisplayName("空白文本不生成分块")
    void ignoresBlankText() {
        KnowledgeTextChunker chunker = new KnowledgeTextChunker(properties(20, 5));

        assertThat(chunker.chunk(" \r\n\t \n ")).isEmpty();
        assertThat(chunker.normalize("第一行\r\n\r\n\r\n第二行"))
                .isEqualTo("第一行\n\n第二行");
    }

    /**
     * 验证重叠量不能达到或超过单块最大长度。
     */
    @Test
    @DisplayName("非法分块参数会在初始化时失败")
    void rejectsInvalidOverlap() {
        assertThatThrownBy(() -> new KnowledgeTextChunker(properties(20, 20)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 创建指定块大小与重叠量的测试配置。
     */
    private KnowledgeProcessingProperties properties(int chunkSize, int overlap) {
        KnowledgeProcessingProperties properties = new KnowledgeProcessingProperties();
        properties.setChunkSize(chunkSize);
        properties.setChunkOverlap(overlap);
        return properties;
    }
}
