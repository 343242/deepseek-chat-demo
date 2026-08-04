package com.smart.rag.conversation.service.impl;

import com.smart.rag.conversation.dto.MessageVO;
import com.smart.rag.conversation.dto.MessageCursorPage;
import com.smart.rag.conversation.entity.Message;
import com.smart.rag.conversation.mapper.MessageMapper;
import com.smart.rag.conversation.service.ConversationMessageService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话消息服务实现（面向内部模块）
 */
@Service
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final MessageMapper messageMapper;

    public ConversationMessageServiceImpl(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public Message saveMessage(Message message) {
        messageMapper.insert(message);
        return message;
    }

    @Override
    public int getMessageCount(String conversationId) {
        return messageMapper.countByConversationId(conversationId);
    }

    @Override
    public List<MessageVO> buildMessageTree(String conversationId) {
        // 一次查出所有消息，内存分组，解决 N+1 问题
        List<Message> allMessages = messageMapper.selectAllByConversationId(conversationId);
        if (allMessages.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 parentId 分组
        Map<Long, List<Message>> childrenMap = allMessages.stream()
                .filter(m -> m.getParentId() != null)
                .collect(Collectors.groupingBy(Message::getParentId));

        // 筛选根消息并组装树
        return allMessages.stream()
                .filter(m -> m.getParentId() == null)
                .map(root -> toTreeVO(root, childrenMap))
                .toList();
    }

    @Override
    public MessageCursorPage buildMessageTreePaged(String conversationId, Long before, int limit) {
        // 多取 1 条根消息用于判断 hasMore
        List<Message> roots = messageMapper.selectRootsPage(conversationId, before, limit + 1);
        if (roots.isEmpty()) {
            return new MessageCursorPage(Collections.emptyList(), null, false);
        }

        boolean hasMore = roots.size() > limit;
        // 截取本页根消息（id DESC = 最新在前），复制后反转为时间升序
        List<Message> paged = new ArrayList<>(hasMore ? roots.subList(0, limit) : roots);
        Collections.reverse(paged);

        // 查这些根消息的子消息（ASSISTANT 回复）
        List<Long> rootIds = paged.stream().map(Message::getId).toList();
        Map<Long, List<Message>> childrenMap = messageMapper.selectChildrenOfRoots(conversationId, rootIds)
                .stream()
                .collect(Collectors.groupingBy(Message::getParentId));

        List<MessageVO> tree = paged.stream()
                .map(root -> toTreeVO(root, childrenMap))
                .toList();

        // nextCursor = 本页最早的根消息 id（反转后第一个）；无更多历史时为 null
        Long nextCursor = hasMore ? paged.get(0).getId() : null;
        return new MessageCursorPage(tree, nextCursor, hasMore);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        // 批量软删除：将所有消息状态置为 null 或用其他方式标记
        // 当前 message 表无 status=DELETED 设计，直接物理删除
        // 后续可加 status 字段改为软删除
        List<Message> messages = messageMapper.selectAllByConversationId(conversationId);
        for (Message m : messages) {
            messageMapper.deleteById(m.getId());
        }
    }

    /**
     * 将消息转为树形 VO（含一层子消息）
     */
    private MessageVO toTreeVO(Message msg, Map<Long, List<Message>> childrenMap) {
        List<Message> children = childrenMap.getOrDefault(msg.getId(), Collections.emptyList());
        List<MessageVO> childVOs = children.stream()
                .map(this::toMessageVO)
                .toList();

        return new MessageVO(
                msg.getId(), msg.getParentId(), msg.getRole(), msg.getContent(),
                msg.getStatus() != null ? msg.getStatus().getValue() : null,
                msg.getModelId(), msg.getThinkingEnabled(),
                msg.getTokenUsage(), msg.getDurationMs(), msg.getCreatedAt(),
                childVOs.isEmpty() ? null : childVOs
        );
    }

    private MessageVO toMessageVO(Message m) {
        return new MessageVO(
                m.getId(), m.getParentId(), m.getRole(), m.getContent(),
                m.getStatus() != null ? m.getStatus().getValue() : null,
                m.getModelId(), m.getThinkingEnabled(),
                m.getTokenUsage(), m.getDurationMs(), m.getCreatedAt(),
                null
        );
    }
}
