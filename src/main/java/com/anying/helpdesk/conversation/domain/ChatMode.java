package com.anying.helpdesk.conversation.domain;

/** 新请求统一规范为 KNOWLEDGE；AGENT 只作为输入别名和历史记录标记，旧记录不补造核验。 */
public enum ChatMode { AGENT, KNOWLEDGE }
