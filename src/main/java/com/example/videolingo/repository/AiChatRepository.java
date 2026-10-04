package com.example.videolingo.repository;

import com.example.videolingo.entity.AiChat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatRepository extends JpaRepository<AiChat, Long> {

    List<AiChat> findByVideoIdOrderByUpdatedAtDesc(Long videoId);
}
