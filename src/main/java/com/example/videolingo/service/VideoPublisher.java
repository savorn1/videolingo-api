package com.example.videolingo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Once a minute, enables the disabled videos whose scheduled publish time has come.
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoPublisher {

    private final VideoService videoService;

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 30 * 1000)
    public void publishDue() {
        int published = videoService.publishDue();
        if (published > 0) {
            log.info("Published {} scheduled video(s)", published);
        }
    }
}
