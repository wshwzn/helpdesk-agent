package com.anying.helpdesk.conversation.mapper;

import com.anying.helpdesk.conversation.domain.Conversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
