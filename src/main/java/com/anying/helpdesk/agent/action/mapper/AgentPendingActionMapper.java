package com.anying.helpdesk.agent.action.mapper;

import com.anying.helpdesk.agent.action.domain.AgentPendingAction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentPendingActionMapper extends BaseMapper<AgentPendingAction> {
}
