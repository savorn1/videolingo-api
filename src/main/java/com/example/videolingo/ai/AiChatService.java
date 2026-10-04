package com.example.videolingo.ai;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.example.videolingo.dto.AiChatDtos.ChatDto;
import com.example.videolingo.dto.AiChatDtos.MessageDto;
import com.example.videolingo.dto.AiChatDtos.TurnDto;
import com.example.videolingo.dto.AiUsageDto;
import com.example.videolingo.entity.AiChat;
import com.example.videolingo.entity.AiChatMessage;
import com.example.videolingo.entity.AiFeature;
import com.example.videolingo.entity.AiUsageRecord;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.AiChatMessageRepository;
import com.example.videolingo.repository.AiChatRepository;
import com.example.videolingo.repository.AiUsageRepository;
import com.example.videolingo.repository.TranscriptRepository;
import com.example.videolingo.repository.VideoRepository;
import com.example.videolingo.settings.SettingsService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// AI Chat about a video, grounded in one transcript. A turn is only stored once
// Claude has answered — a failed call leaves the conversation unchanged, so
// the learner can just resend.
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiContextService contextService;
    private final AiClientService aiClient;
    private final SettingsService settings;
    private final AiChatRepository chatRepository;
    private final AiChatMessageRepository messageRepository;
    private final AiUsageRepository usageRepository;
    private final VideoRepository videoRepository;
    private final TranscriptRepository transcriptRepository;
    private final TransactionTemplate tx;

    public ChatDto create(Long videoId, Long transcriptId, String username) {
        aiClient.requireReady(AiFeature.CHAT);
        AiContextService.Loaded loaded = contextService.load(videoId, transcriptId);
        AiChat chat = chatRepository.save(AiChat.builder()
                .videoId(videoId)
                .transcriptId(loaded.transcript().getId())
                .title("New chat")
                .createdBy(username)
                .build());
        return toDto(chat, List.of());
    }

    public List<ChatDto> listForVideo(Long videoId) {
        return chatRepository.findByVideoIdOrderByUpdatedAtDesc(videoId).stream()
                .map(c -> toDto(c, null))
                .toList();
    }

    public ChatDto get(Long chatId) {
        AiChat chat = find(chatId);
        return toDto(chat, messageRepository.findByChatIdOrderByIdAsc(chatId));
    }

    public TurnDto send(Long chatId, String content, String username) {
        String text = content.strip();
        int max = settings.ai().maxChatMessageChars();
        if (text.length() > max) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Messages can be at most " + max + " characters");
        }
        AiChat chat = find(chatId);
        AiContextService.Loaded loaded = contextService.load(chat.getVideoId(), chat.getTranscriptId());

        List<MessageParam> history = messageRepository.findByChatIdOrderByIdAsc(chatId).stream()
                .map(m -> MessageParam.builder()
                        .role(
                                m.getRole() == AiChatMessage.Role.USER
                                        ? MessageParam.Role.USER
                                        : MessageParam.Role.ASSISTANT)
                        .content(m.getContent())
                        .build())
                .toList();
        // Same two leading blocks as generation (shared cached prefix), then the chat note.
        List<TextBlockParam> system = new ArrayList<>(PromptBuilder.system(loaded.context()));
        system.add(TextBlockParam.builder().text(PromptBuilder.chatSystemNote()).build());

        var result = aiClient.chat(
                new AiClientService.Call(AiFeature.CHAT, chat.getVideoId(), chat.getTranscriptId(), chatId, username),
                system,
                history,
                text);
        String reply = result.value().isBlank() ? "(No answer.)" : result.value();

        return tx.execute(status -> {
            AiChatMessage userMessage = messageRepository.save(AiChatMessage.builder()
                    .chatId(chatId)
                    .role(AiChatMessage.Role.USER)
                    .content(text)
                    .build());
            AiChatMessage assistant = messageRepository.save(AiChatMessage.builder()
                    .chatId(chatId)
                    .role(AiChatMessage.Role.ASSISTANT)
                    .content(reply)
                    .usageId(result.usage().getId())
                    .build());
            AiChat fresh = find(chatId);
            if (fresh.getMessageCount() == 0) {
                fresh.setTitle(text.length() > 80 ? text.substring(0, 77).strip() + "…" : text);
            }
            fresh.setMessageCount(fresh.getMessageCount() + 2);
            fresh = chatRepository.save(fresh);
            return new TurnDto(toDto(fresh, null), toMessage(userMessage, null), toMessage(assistant, result.usage()));
        });
    }

    public void delete(Long chatId) {
        tx.executeWithoutResult(status -> {
            AiChat chat = find(chatId);
            messageRepository.deleteByChatId(chatId);
            chatRepository.delete(chat);
        });
    }

    // ── mapping ───────────────────────────────────────────────────────────

    private AiChat find(Long id) {
        return chatRepository
                .findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Chat not found with id: " + id));
    }

    private ChatDto toDto(AiChat chat, List<AiChatMessage> messages) {
        List<AiChatMessage> all =
                messages != null ? messages : messageRepository.findByChatIdOrderByIdAsc(chat.getId());
        Map<Long, AiUsageRecord> usage =
                usageRepository
                        .findAllById(all.stream()
                                .map(AiChatMessage::getUsageId)
                                .filter(Objects::nonNull)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(AiUsageRecord::getId, Function.identity()));
        BigDecimal total = usage.values().stream()
                .map(AiUsageRecord::getCostUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String videoTitle = videoRepository
                .findById(chat.getVideoId())
                .map(v -> v.getTitle())
                .orElse(null);
        String transcriptLanguage = transcriptRepository
                .findById(chat.getTranscriptId())
                .map(t -> t.getLanguage())
                .orElse(null);
        return new ChatDto(
                chat.getId(),
                chat.getVideoId(),
                videoTitle,
                chat.getTranscriptId(),
                transcriptLanguage,
                chat.getTitle(),
                chat.getMessageCount(),
                total,
                chat.getCreatedBy(),
                chat.getCreatedAt(),
                chat.getUpdatedAt(),
                messages == null
                        ? null
                        : all.stream()
                                .map(m -> toMessage(m, usage.get(m.getUsageId())))
                                .toList());
    }

    private static MessageDto toMessage(AiChatMessage m, AiUsageRecord usage) {
        return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt(), AiUsageDto.of(usage));
    }
}
