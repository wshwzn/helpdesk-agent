package com.anying.helpdesk.knowledge.service;

import com.anying.helpdesk.knowledge.domain.KnowledgeDocument;

/** 上传前的事务结果；只有 CREATED 需要继续写入独立对象。 */
public record KnowledgeRevisionPreparation(String outcome, KnowledgeDocument document) {
}
