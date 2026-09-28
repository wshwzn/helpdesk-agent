package com.anying.helpdesk.knowledge.processing;

import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentParsingException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaKnowledgeDocumentParserTest {

    private final TikaKnowledgeDocumentParser parser = createParser();

    /**
     * 验证纯文本和 Markdown 都能提取正文。
     */
    @Test
    @DisplayName("解析 TXT 和 Markdown 文档")
    void parsesTextAndMarkdown() {
        ParsedDocument text = parse("帮助中心正文".getBytes(), "guide.txt", "text/plain");
        ParsedDocument markdown = parse(
                "# 登录帮助\n\n请检查账号。".getBytes(),
                "guide.md",
                "text/markdown"
        );

        assertThat(text.text()).contains("帮助中心正文");
        assertThat(markdown.text()).contains("登录帮助", "请检查账号");
    }

    /**
     * 验证 PDF 文档可以通过 Tika 提取正文。
     */
    @Test
    @DisplayName("解析 PDF 文档")
    void parsesPdf() throws IOException {
        ParsedDocument parsed = parse(createPdf("PDF support guide"), "guide.pdf", "application/pdf");

        assertThat(parsed.text()).contains("PDF support guide");
        assertThat(parsed.detectedContentType()).contains("pdf");
    }

    /**
     * 验证 DOCX 文档可以通过 Tika 提取正文。
     */
    @Test
    @DisplayName("解析 DOCX 文档")
    void parsesDocx() throws IOException {
        ParsedDocument parsed = parse(
                createDocx("DOCX support guide"),
                "guide.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        assertThat(parsed.text()).contains("DOCX support guide");
        assertThat(parsed.detectedContentType()).contains("wordprocessingml");
    }

    /**
     * 验证没有正文的文档返回明确的解析失败。
     */
    @Test
    @DisplayName("空文档解析失败")
    void rejectsDocumentWithoutText() {
        assertThatThrownBy(() -> parse(new byte[0], "empty.txt", "text/plain"))
                .isInstanceOf(KnowledgeDocumentParsingException.class)
                .hasMessage("failed to parse knowledge document");
    }

    /**
     * 使用字节数组调用文档解析器。
     */
    private ParsedDocument parse(byte[] content, String filename, String contentType) {
        return parser.parse(new ByteArrayInputStream(content), filename, contentType);
    }

    /**
     * 创建包含一行英文正文的最小测试 PDF。
     */
    private byte[] createPdf(String content) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream pageContent = new PDPageContentStream(document, page)) {
                pageContent.beginText();
                pageContent.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                pageContent.newLineAtOffset(50, 700);
                pageContent.showText(content);
                pageContent.endText();
            }
            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * 创建包含一段正文的测试 DOCX。
     */
    private byte[] createDocx(String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(content);
            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * 创建限制提取字符数的 Tika 解析器实例。
     */
    private TikaKnowledgeDocumentParser createParser() {
        KnowledgeProcessingProperties properties = new KnowledgeProcessingProperties();
        properties.setMaxExtractedCharacters(100_000);
        return new TikaKnowledgeDocumentParser(properties);
    }
}
