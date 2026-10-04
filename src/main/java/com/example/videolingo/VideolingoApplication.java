package com.example.videolingo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Password-reset emails are sent off the request thread (see PasswordResetMailer).
@EnableAsync
// Runs queued processing jobs (see pipeline.JobWorker).
@EnableScheduling
public class VideolingoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideolingoApplication.class, args);
    }
}
