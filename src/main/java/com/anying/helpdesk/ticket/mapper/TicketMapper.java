package com.anying.helpdesk.ticket.mapper;

import com.anying.helpdesk.ticket.domain.Ticket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
