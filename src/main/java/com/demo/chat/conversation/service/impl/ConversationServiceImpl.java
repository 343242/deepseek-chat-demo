package com.demo.chat.conversation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.chat.common.uuid.UuidV7;
import com.demo.chat.conversation.dto.ConversationCreateRequest;
import com.demo.chat.conversation.dto.ConversationDetail;
import com.demo.chat.conversation.dto.ConversationSummary;
import com.demo.chat.conversation.dto.ConversationUpdateRequest;
import com.demo.chat.conversation.dto.MessageVO;
import com.demo.chat.conversation.entity.Conversation;
import com.demo.chat.conversation.enums.ConversationStatus;
import com.demo.chat.conversation.enums.TitleSource;
import com.demo.chat.conversation.mapper.ConversationMapper;
import com.demo.chat.conversation.service.ConversationMessageService;
import com.demo.chat.conversation.service.ConversationService;
import com.demo.chat.conversation.util.ConversationIdUtil;
import com.demo.chat.common.errorcode.ErrorCode;
import com.demo.chat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话管理服务实现
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);

    /** 自动标题最大长度 */
    private static final int AUTO_TITLE_MAX_LENGTH = 20;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageService messageService;
    private final ChatMemoryRepository chatMemoryRepository;
    private final TransactionTemplate transactionTemplate;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   ConversationMessageService messageService,
                                   ChatMemoryRepository chatMemoryRepository,
                                   TransactionTemplate transactionTemplate) {
        this.conversationMapper = conversationMapper;
        this.messageService = messageService;
        this.chatMemoryRepository = chatMemoryRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ConversationSummary create(Long userId, ConversationCreateRequest request) {
        String rawId = UuidV7.generateCompact();
        String conversationId = ConversationIdUtil.buildIsolatedId(userId, rawId);

        Conversation entity = new Conversation(conversationId, userId, request.modelId());
        if (request.title() != null && !request.title().isBlank()) {
            entity.setTitle(request.title().strip());
            entity.setTitleSource(TitleSource.USER);
        }

        conversationMapper.insert(entity);
        log.info("Conversation created: id={}, userId={}, conversationId={}",
                entity.getId(), userId, conversationId);

        return toSummary(entity);
    }

    @Override
    public ConversationSummary getOrCreate(Long userId, String conversationId, String modelId) {
        Conversation existing = findByConversationId(conversationId);
        if (existing != null) {
            if (existing.getStatus() != ConversationStatus.DELETED) {
                return toSummary(existing);
            }
            // 已删除则恢复为活跃
            conversationMapper.updateStatus(existing.getId(), ConversationStatus.ACTIVE.getValue());
            existing.setStatus(ConversationStatus.ACTIVE);
            existing.setModelId(modelId);
            log.info("Conversation restored: id={}, conversationId={}", existing.getId(), conversationId);
            return toSummary(existing);
        }

        // 自动创建（并发安全：唯一约束兜底）
        Conversation entity = new Conversation(conversationId, userId, modelId);
        try {
            conversationMapper.insert(entity);
            log.info("Conversation auto-created: id={}, userId={}, conversationId={}",
                    entity.getId(), userId, conversationId);
        } catch (DuplicateKeyException e) {
            // 并发创建冲突，重新查询
            log.debug("Conversation concurrent create detected, re-querying: {}", conversationId);
            existing = findByConversationId(conversationId);
            if (existing != null && existing.getStatus() != ConversationStatus.DELETED) {
                return toSummary(existing);
            }
            throw e;
        }
        return toSummary(entity);
    }

    @Override
    public List<ConversationSummary> list(Long userId, String status, int page, int size) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .ne(Conversation::getStatus, ConversationStatus.DELETED);

        if (status != null && !status.isBlank()) {
            wrapper.eq(Conversation::getStatus, ConversationStatus.valueOf(status));
        }

        // 置顶优先，然后按最后消息时间降序
        wrapper.orderByDesc(Conversation::getPinned)
               .orderByDesc(Conversation::getLastMessageAt);

        Page<Conversation> pageResult = conversationMapper.selectPage(
                new Page<>(page, size), wrapper);

        return pageResult.getRecords().stream().map(this::toSummary).toList();
    }

    @Override
    public ConversationDetail getDetail(Long userId, String conversationId) {
        Conversation conv = findAndVerify(userId, conversationId);
        List<MessageVO> messages = messageService.buildMessageTree(conv.getConversationId());
        return toDetail(conv, messages);
    }

    @Override
    public void update(Long userId, String conversationId, ConversationUpdateRequest request) {
        Conversation conv = findAndVerify(userId, conversationId);

        if (request.title() != null) {
            conversationMapper.updateTitle(conv.getId(), request.title().strip(), TitleSource.USER.getValue());
        }
        if (request.pinned() != null) {
            conversationMapper.updatePinned(conv.getId(), request.pinned());
        }
        if (request.status() != null) {
            ConversationStatus newStatus = ConversationStatus.valueOf(request.status());
            conversationMapper.updateStatus(conv.getId(), newStatus.getValue());
        }
        log.info("Conversation updated: id={}, userId={}", conv.getId(), userId);
    }

    @Override
    public void delete(Long userId, String conversationId) {
        Conversation conv = findAndVerify(userId, conversationId);

        transactionTemplate.executeWithoutResult(status -> {
            // 软删除会话
            conversationMapper.updateStatus(conv.getId(), ConversationStatus.DELETED.getValue());
            // 清理 message 表记录
            messageService.deleteByConversationId(conversationId);
        });

        // 清空 Spring AI memory（在事务外执行，不影响主事务）
        try {
            chatMemoryRepository.deleteByConversationId(conversationId);
        } catch (Exception e) {
            log.warn("Failed to clear Spring AI memory for conversation {}: {}", conversationId, e.getMessage());
        }

        log.info("Conversation deleted: id={}, userId={}, conversationId={}",
                conv.getId(), userId, conversationId);
    }

    @Override
    public List<MessageVO> listMessages(Long userId, String conversationId) {
        findAndVerify(userId, conversationId);
        return messageService.buildMessageTree(conversationId);
    }

    @Override
    public void onNewMessages(String conversationId, String userContent, int delta) {
        Conversation conv = findByConversationId(conversationId);
        if (conv == null) {
            log.warn("onNewMessages: conversation not found for {}", conversationId);
            return;
        }

        // 原子递增消息计数 + 更新最后消息时间
        conversationMapper.incrementMessageCount(conversationId, delta, LocalDateTime.now());

        // 首次消息时自动设置标题（CAS 防并发）
        if (userContent != null && !userContent.isBlank()) {
            String sanitized = userContent.strip().replace("\n", " ");
            String autoTitle = sanitized.length() > AUTO_TITLE_MAX_LENGTH
                    ? sanitized.substring(0, AUTO_TITLE_MAX_LENGTH) + "…"
                    : sanitized;
            conversationMapper.updateTitleIfFirst(conv.getId(), autoTitle, TitleSource.SYSTEM.getValue());
        }
    }

    // ==================== 内部方法 ====================

    private Conversation findByConversationId(String conversationId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getConversationId, conversationId));
    }

    private Conversation findAndVerify(Long userId, String conversationId) {
        Conversation conv = findByConversationId(conversationId);
        if (conv == null || conv.getStatus() == ConversationStatus.DELETED) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        if (!conv.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_ACCESS_DENIED);
        }
        return conv;
    }

    // ==================== 转换方法 ====================

    private ConversationSummary toSummary(Conversation c) {
        return new ConversationSummary(
                c.getId(), c.getConversationId(), c.getTitle(),
                c.getTitleSource() != null ? c.getTitleSource().getValue() : null,
                c.getModelId(), Boolean.TRUE.equals(c.getPinned()),
                c.getStatus() != null ? c.getStatus().getValue() : null,
                c.getMessageCount() != null ? c.getMessageCount() : 0,
                c.getLastMessageAt(), c.getCreatedAt()
        );
    }

    private ConversationDetail toDetail(Conversation c, List<MessageVO> messages) {
        return new ConversationDetail(
                c.getId(), c.getConversationId(), c.getTitle(),
                c.getTitleSource() != null ? c.getTitleSource().getValue() : null,
                c.getModelId(), Boolean.TRUE.equals(c.getPinned()),
                c.getStatus() != null ? c.getStatus().getValue() : null,
                c.getMessageCount() != null ? c.getMessageCount() : 0,
                c.getLastMessageAt(), c.getCreatedAt(), messages
        );
    }
}
