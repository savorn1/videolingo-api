package com.example.videolingo.service;

import com.example.videolingo.settings.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// Async so /forgot-password responds in the same time whether or not the email
// matched an account (a slow SMTP round-trip would otherwise reveal which
// addresses are registered), and so an SMTP outage never fails the request.
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final SettingsService settings;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from}")
    private String from;

    @Async
    public void send(String to, String username, String resetLink, long ttlMinutes) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (mailHost.isBlank() || sender == null) {
            log.info("MAIL_HOST not set — password reset link for {}: {}", username, resetLink);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        replyTo(message);
        message.setTo(to);
        message.setSubject("Reset your " + settings.general().siteName() + " password");
        message.setText("""
                Hi %s,

                We received a request to reset your %s password. Open the link below to choose a new one:

                %s

                This link expires in %d minutes and can only be used once. If you didn't ask for this, you can ignore this email — your password won't change.
                """.formatted(username, settings.general().siteName(), resetLink, ttlMinutes));
        try {
            sender.send(message);
        } catch (Exception e) {
            log.error("Failed to send password reset email to user {}", username, e);
        }
    }

    // Settings › General support address, so replies reach a person.
    private void replyTo(SimpleMailMessage message) {
        String support = settings.general().supportEmail();
        if (support != null && !support.isBlank()) {
            message.setReplyTo(support);
        }
    }
}
