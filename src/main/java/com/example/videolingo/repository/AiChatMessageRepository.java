package com.example.videolingo.repository;

import com.example.videolingo.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findByChatIdOrderByIdAsc(Long chatId);

    @Modifying
    @Query("delete from AiChatMessage m where m.chatId = :chatId")
    void deleteByChatId(@Param("chatId") Long chatId);
}
