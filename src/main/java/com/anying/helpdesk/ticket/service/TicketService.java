package com.anying.helpdesk.ticket.service;

import com.anying.helpdesk.common.response.PageResponse;
import com.anying.helpdesk.ticket.domain.Ticket;
import com.anying.helpdesk.ticket.domain.TicketStatus;
import com.anying.helpdesk.ticket.dto.CancelTicketRequest;
import com.anying.helpdesk.ticket.dto.CreateTicketRequest;
import com.anying.helpdesk.ticket.dto.ResolveTicketRequest;
import com.anying.helpdesk.ticket.dto.TicketResponse;
import com.anying.helpdesk.ticket.exception.ConcurrentTicketUpdateException;
import com.anying.helpdesk.ticket.exception.InvalidTicketStatusException;
import com.anying.helpdesk.ticket.exception.TicketAccessDeniedException;
import com.anying.helpdesk.ticket.exception.TicketNotFoundException;
import com.anying.helpdesk.ticket.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TicketService {

    private static final DateTimeFormatter TICKET_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TicketMapper ticketMapper;

    @Transactional
    public TicketResponse create(CreateTicketRequest request, Long creatorId) {
        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo(now));
        ticket.setTitle(request.title().trim());
        ticket.setDescription(request.description().trim());
        ticket.setPriority(request.priority());
        ticket.setStatus(TicketStatus.PENDING);
        ticket.setCreatorId(creatorId);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);
        return TicketResponse.from(ticket);
    }

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id, Long customerId) {
        Ticket ticket = requireTicket(id);
        requireCreator(ticket, customerId);
        return TicketResponse.from(ticket);
    }

    /**
     * 按面向客户的唯一工单号查询详情，并继续执行客户归属校验。
     */
    @Transactional(readOnly = true)
    public TicketResponse getByTicketNo(String ticketNo, Long customerId) {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new IllegalArgumentException("ticketNo must not be blank");
        }
        String normalizedTicketNo = ticketNo.trim().toUpperCase(Locale.ROOT);
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketNo, normalizedTicketNo));
        if (ticket == null) {
            throw new TicketNotFoundException(normalizedTicketNo);
        }
        requireCreator(ticket, customerId);
        return TicketResponse.from(ticket);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> page(long page, long size, Long creatorId, TicketStatus status) {
        LambdaQueryWrapper<Ticket> query = basePageQuery(status)
                .eq(Ticket::getCreatorId, creatorId);
        return executePage(page, size, query);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketResponse> supportPage(long page, long size, TicketStatus status, Long assigneeId) {
        LambdaQueryWrapper<Ticket> query = basePageQuery(status)
                .eq(assigneeId != null, Ticket::getAssigneeId, assigneeId);
        return executePage(page, size, query);
    }

    @Transactional
    public TicketResponse claim(Long id, Long supportId) {
        Ticket ticket = requireTicket(id);
        requireStatus(ticket, TicketStatus.PENDING, "claim");
        LocalDateTime now = LocalDateTime.now();
        ticket.setStatus(TicketStatus.PROCESSING);
        ticket.setAssigneeId(supportId);
        ticket.setAssignedAt(now);
        ticket.setUpdatedAt(now);
        updateWithOptimisticLock(ticket);
        return TicketResponse.from(ticket);
    }

    @Transactional
    public TicketResponse resolve(Long id, ResolveTicketRequest request, Long supportId) {
        Ticket ticket = requireTicket(id);
        requireStatus(ticket, TicketStatus.PROCESSING, "resolve");
        if (!supportId.equals(ticket.getAssigneeId())) {
            throw new TicketAccessDeniedException("only the assigned support agent can resolve this ticket");
        }
        LocalDateTime now = LocalDateTime.now();
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolution(request.resolution().trim());
        ticket.setResolvedAt(now);
        ticket.setUpdatedAt(now);
        updateWithOptimisticLock(ticket);
        return TicketResponse.from(ticket);
    }

    @Transactional
    public TicketResponse cancel(Long id, CancelTicketRequest request, Long customerId) {
        Ticket ticket = requireTicket(id);
        requireCreator(ticket, customerId);
        requireCancellableStatus(ticket);
        LocalDateTime now = LocalDateTime.now();
        ticket.setStatus(TicketStatus.CANCELLED);
        ticket.setCancelReason(request.reason().trim());
        ticket.setCancelledAt(now);
        ticket.setUpdatedAt(now);
        updateWithOptimisticLock(ticket);
        return TicketResponse.from(ticket);
    }

    private LambdaQueryWrapper<Ticket> basePageQuery(TicketStatus status) {
        return new LambdaQueryWrapper<Ticket>()
                .eq(status != null, Ticket::getStatus, status)
                .orderByDesc(Ticket::getCreatedAt);
    }

    private PageResponse<TicketResponse> executePage(
            long page,
            long size,
            LambdaQueryWrapper<Ticket> query
    ) {
        Page<Ticket> result = ticketMapper.selectPage(Page.of(page, size), query);
        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                result.getRecords().stream().map(TicketResponse::from).toList()
        );
    }

    private Ticket requireTicket(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new TicketNotFoundException(id);
        }
        return ticket;
    }

    private void requireStatus(Ticket ticket, TicketStatus expected, String operation) {
        if (ticket.getStatus() != expected) {
            throw new InvalidTicketStatusException(operation, ticket.getStatus());
        }
    }

    private void requireCreator(Ticket ticket, Long customerId) {
        if (!customerId.equals(ticket.getCreatorId())) {
            throw new TicketAccessDeniedException("customers can only access their own tickets");
        }
    }

    private void requireCancellableStatus(Ticket ticket) {
        if (ticket.getStatus() != TicketStatus.PENDING
                && ticket.getStatus() != TicketStatus.PROCESSING) {
            throw new InvalidTicketStatusException("cancel", ticket.getStatus());
        }
    }

    private void updateWithOptimisticLock(Ticket ticket) {
        if (ticketMapper.updateById(ticket) == 0) {
            throw new ConcurrentTicketUpdateException(ticket.getId());
        }
    }

    private String generateTicketNo(LocalDateTime now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "HD" + TICKET_TIME_FORMAT.format(now) + suffix;
    }
}
