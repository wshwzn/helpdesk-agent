package com.anying.helpdesk.knowledge.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("knowledge_documents")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long knowledgeBaseId;
    private String originalName;
    private String storageProvider;
    private String bucketName;
    private String objectKey;
    private String contentType;
    private String fileExtension;
    private Long sizeBytes;
    private String sha256;
    private String sourceKey;
    private String businessVersion;
    private String applicabilityScope;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private KnowledgePublicationStatus publicationStatus;
    private KnowledgeDocumentStatus status;
    private Integer chunkCount;
    private String errorCode;
    private String activeIndexBuildId;
    private Long createdBy;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
