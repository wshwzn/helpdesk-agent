package com.anying.helpdesk.knowledge.processing;

import com.anying.helpdesk.knowledge.exception.KnowledgeDocumentParsingException;
import lombok.RequiredArgsConstructor;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class TikaKnowledgeDocumentParser implements KnowledgeDocumentParser {

    private final KnowledgeProcessingProperties properties;

    /**
     * 使用 Apache Tika 自动识别并提取正文，同时限制最大提取字符数。
     */
    @Override
    public ParsedDocument parse(InputStream inputStream, String originalName, String contentType) {
        BodyContentHandler handler = new BodyContentHandler(properties.getMaxExtractedCharacters());
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalName);
        metadata.set(Metadata.CONTENT_TYPE, contentType);
        try {
            new AutoDetectParser().parse(inputStream, handler, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException exception) {
            throw new KnowledgeDocumentParsingException("failed to parse knowledge document", exception);
        }
        String text = handler.toString();
        if (text == null || text.isBlank()) {
            throw new KnowledgeDocumentParsingException("document contains no extractable text");
        }
        String detectedContentType = metadata.get(Metadata.CONTENT_TYPE);
        return new ParsedDocument(text, detectedContentType == null ? contentType : detectedContentType);
    }
}
