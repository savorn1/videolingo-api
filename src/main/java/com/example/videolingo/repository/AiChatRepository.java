package com.example.videolingo.repository;

import com.example.videolingo.entity.AiChat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatRepository extends JpaRepository<AiChat, Long> {

    List<AiChat> findByVideoIdOrderByUpdatedAtDesc(Long videoId);
}
