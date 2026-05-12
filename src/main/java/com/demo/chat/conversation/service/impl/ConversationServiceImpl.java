package com.demo.chat.conversation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.demo.chat.conversation.dto.ConversationCreateRequest;
import com.demo.chat.conversation.dto.ConversationDetail;
import com.demo.chat.conversation.dto.ConversationSummary;
import com.demo.chat.conversation.dto.ConversationUpdateRequest;
import com.demo.chat.conversation.dto.MessageVO;
import com.demo.chat.conversation.entity.Conversation;
import com.demo.chat.conversation.entity.Message;
import com.demo.chat.conversation.enums.ConversationStatus;
import com.demo.chat.conversation.enums.TitleSource;
import com.demo.chat.conversation.mapper.ConversationMapper;
import com.demo.chat.conversation.mapper.MessageMapper;
import com.demo.chat.conversation.service.ConversationService;
import com.demo.chat.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
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
    private final MessageMapper messageMapper;
    private final ChatMemoryRepository chatMemoryRepository;

    public ConversationServiceImpl(ConversationMapper conversationMapper,
                                   MessageMapper messageMapper,
                                   ChatMemoryRepository chatMemoryRepository) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    @Override
    @Transactional
    public ConversationSummary create(Long userId, ConversationCreateRequest request) {
        // 由调用方负责生成 conversationId，这里用 snowflake 或时间戳
        String rawId = String.valueOf(System.currentTimeMillis());
        String conversationId = "u_" + userId + "_" + rawId;

        Conversation entity = new Conversation(conversationId, userId, request.modelId());
        if (request.title() != null && !request.title().isBlank()) {
            entity.setTitle(request.title());
            entity.setTitleSource(TitleSource.USER.name());
        }

        conversationMapper.insert(entity);
        log.info("Conversation created: id={}, userId={}, conversationId={}",
                entity.getId(), userId, conversationId);

        return toSummary(entity);
    }

    @Override
    @Transactional
    public ConversationSummary getOrCreate(Long userId, String conversationId, String modelId) {
        Conversation existing = findByConversationId(conversationId);
        if (existing != null) {
            // 已存在且未删除，直接返回
            if (!ConversationStatus.DELETED.name().equals(existing.getStatus())) {
                return toSummary(existing);
            }
            // 已删除则恢复为活跃
            conversationMapper.updateStatus(existing.getId(), ConversationStatus.ACTIVE.name());
            existing.setStatus(ConversationStatus.ACTIVE.name());
            existing.setModelId(modelId);
            log.info("Conversation restored: id={}, conversationId={}", existing.getId(), conversationId);
            return toSummary(existing);
        }

        // 自动创建
        Conversation entity = new Conversation(conversationId, userId, modelId);
        conversationMapper.insert(entity);
        log.info("Conversation auto-created: id={}, userId={}, conversationId={}",
                entity.getId(), userId, conversationId);
        return toSummary(entity);
    }

    @Override
    public List<ConversationSummary> list(Long userId, String status, int page, int size) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .ne(Conversation::getStatus, ConversationStatus.DELETED.name());

        if (status != null && !status.isBlank()) {
            wrapper.eq(Conversation::getStatus, status);
        }

        // 置顶优先，然后按最后消息时间降序
        wrapper.orderByDesc(Conversation::getPinned)
               .orderByDesc(Conversation::getLastMessageAt);

        // 分页
        wrapper.last("LIMIT " + size + " OFFSET " + (page - 1) * size);

        List<Conversation> conversations = conversationMapper.selectList(wrapper);
        return conversations.stream().map(this::toSummary).toList();
    }

    @Override
    public ConversationDetail getDetail(Long userId, String conversationId) {
        Conversation conv = findAndVerify(userId, conversationId);
        List<MessageVO> messages = buildMessageTree(conv.getConversationId());
        return toDetail(conv, messages);
    }

    @Override
    @Transactional
    public void update(Long userId, String conversationId, ConversationUpdateRequest request) {
        Conversation conv = findAndVerify(userId, conversationId);

        if (request.title() != null) {
            conversationMapper.updateTitle(conv.getId(), request.title(), TitleSource.USER.name());
        }
        if (request.pinned() != null) {
            conversationMapper.updatePinned(conv.getId(), request.pinned());
        }
        if (request.status() != null) {
            if (!isValidStatus(request.status())) {
                throw new BusinessException("无效的会话状态: " + request.status());
            }
            conversationMapper.updateStatus(conv.getId(), request.status());
        }
        log.info("Conversation updated: id={}, userId={}", conv.getId(), userId);
    }

    @Override
    @Transactional
    public void delete(Long userId, String conversationId) {
        Conversation conv = findAndVerify(userId, conversationId);

        // 软删除会话
        conversationMapper.updateStatus(conv.getId(), ConversationStatus.DELETED.name());

        // 清空 Spring AI memory
        chatMemoryRepository.deleteByConversationId(conversationId);

        log.info("Conversation deleted: id={}, userId={}, conversationId={}",
                conv.getId(), userId, conversationId);
    }

    @Override
    public List<MessageVO> listMessages(Long userId, String conversationId) {
        findAndVerify(userId, conversationId);
        return buildMessageTree(conversationId);
    }

    @Override
    @Transactional
    public void onNewMessages(String conversationId, String userContent, int delta) {
        Conversation conv = findByConversationId(conversationId);
        if (conv == null) {
            log.warn("onNewMessages: conversation not found for {}", conversationId);
            return;
        }

        // 更新消息计数和最后消息时间
        conversationMapper.incrementMessageCount(conversationId, delta, LocalDateTime.now());

        // 首次消息时自动设置标题（仅 SYSTEM 生成且标题为空时）
        if (conv.getMessageCount() == 0 && conv.getTitle() == null
                && TitleSource.SYSTEM.name().equals(conv.getTitleSource())
                && userContent != null && !userContent.isBlank()) {
            String autoTitle = userContent.length() > AUTO_TITLE_MAX_LENGTH
                    ? userContent.substring(0, AUTO_TITLE_MAX_LENGTH) + "…"
                    : userContent;
            conversationMapper.updateTitle(conv.getId(), autoTitle, TitleSource.SYSTEM.name());
        }
    }

    // ==================== 内部方法 ====================

    private Conversation findByConversationId(String conversationId) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationId, conversationId);
        return conversationMapper.selectOne(wrapper);
    }

    private Conversation findAndVerify(Long userId, String conversationId) {
        Conversation conv = findByConversationId(conversationId);
        if (conv == null || ConversationStatus.DELETED.name().equals(conv.getStatus())) {
            throw new BusinessException("会话不存在");
        }
        if (!conv.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
        return conv;
    }

    /**
     * 构建消息树（根消息 + 一层子消息）
     */
    private List<MessageVO> buildMessageTree(String conversationId) {
        List<Message> roots = messageMapper.selectRootMessages(conversationId);
        if (roots.isEmpty()) {
            return Collections.emptyList();
        }
        return roots.stream().map(root -> {
            List<Message> children = messageMapper.selectChildren(root.getId());
            List<MessageVO> childVOs = children.stream()
                    .map(this::toMessageVO)
                    .toList();
            MessageVO rootVO = toMessageVO(root);
            return new MessageVO(
                    rootVO.id(), rootVO.parentId(), rootVO.role(), rootVO.content(),
                    rootVO.status(), rootVO.modelId(), rootVO.thinkingEnabled(),
                    rootVO.tokenUsage(), rootVO.durationMs(), rootVO.createdAt(),
                    childVOs.isEmpty() ? null : childVOs
            );
        }).toList();
    }

    private boolean isValidStatus(String status) {
        try {
            ConversationStatus.valueOf(status);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ==================== 转换方法 ====================

    private ConversationSummary toSummary(Conversation c) {
        return new ConversationSummary(
                c.getId(), c.getConversationId(), c.getTitle(), c.getTitleSource(),
                c.getModelId(), Boolean.TRUE.equals(c.getPinned()), c.getStatus(),
                c.getMessageCount() != null ? c.getMessageCount() : 0,
                c.getLastMessageAt(), c.getCreatedAt()
        );
    }

    private ConversationDetail toDetail(Conversation c, List<MessageVO> messages) {
        return new ConversationDetail(
                c.getId(), c.getConversationId(), c.getTitle(), c.getTitleSource(),
                c.getModelId(), Boolean.TRUE.equals(c.getPinned()), c.getStatus(),
                c.getMessageCount() != null ? c.getMessageCount() : 0,
                c.getLastMessageAt(), c.getCreatedAt(), messages
        );
    }

    private MessageVO toMessageVO(Message m) {
        return new MessageVO(
                m.getId(), m.getParentId(), m.getRole(), m.getContent(),
                m.getStatus(), m.getModelId(), m.getThinkingEnabled(),
                m.getTokenUsage(), m.getDurationMs(), m.getCreatedAt(),
                null  // children 由 buildMessageTree 单独填充
        );
    }
}
