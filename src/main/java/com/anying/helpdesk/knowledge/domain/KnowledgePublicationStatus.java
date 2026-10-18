package com.anying.helpdesk.knowledge.domain;

/**
 * 表示文档的业务发布状态，与上传、解析和索引处理状态独立。
 */
public enum KnowledgePublicationStatus {
    DRAFT,
    PUBLISHED,
    WITHDRAWN
}
