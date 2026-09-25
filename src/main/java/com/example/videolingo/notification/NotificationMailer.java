package com.example.videolingo.notification;

import com.example.videolingo.entity.Notification;
import com.example.videolingo.entity.NotificationChannel;
import com.example.videolingo.entity.NotificationEvent;
import com.example.videolingo.entity.NotificationEventType;
import com.example.videolingo.entity.NotificationStatus;
import com.example.videolingo.repository.NotificationEventRepository;
import com.example.videolingo.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.videolingo.settings.SettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

// Delivers queued email notifications off the request thread, only after the
// send's transaction has committed (so the rows exist and a rolled-back send
// never emails anyone). Each email is its own transaction: one bad address
// doesn't hold up or undo the rest.
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationMailer {

    /** Published by NotificationService once PENDING email rows are saved. */
    public record EmailsQueued(List<Long> notificationIds) {
    }

    private final ObjectProvider<JavaMailSender> mailSender;
    private final SettingsService settings;
    private final NotificationRepository notificationRepository;
    private final NotificationEventRepository eventRepository;
    private final TransactionTemplate tx;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${app.mail.from}")
    private String from;

    public boolean configured() {
        return !mailHost.isBlank() && mailSender.getIfAvailable() != null;
    }

    public String from() {
        return from;
    }

    @Async
    @TransactionalEventListener
    public void onQueued(EmailsQueued event) {
        for (Long id : event.notificationIds()) {
            try {
                deliver(id);
            } catch (RuntimeException e) {
                log.error("Email notification {} could not be processed", id, e);
            }
        }
    }

    private void deliver(Long id) {
        Notification n = notificationRepository.findById(id).orElse(null);
        if (n == null || n.getChannel() != NotificationChannel.EMAIL || n.getStatus() != NotificationStatus.PENDING) {
            return;
        }
        String error = null;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!configured() || sender == null) {
            error = "Email isn't configured on the server (MAIL_HOST is empty)";
        } else {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            replyTo(message);
            message.setTo(n.getRecipientEmail());
            message.setSubject(n.getSubject());
            message.setText(n.getBody());
            try {
                sender.send(message);
            } catch (Exception e) {
                log.warn("Email notification {} to {} failed: {}", id, n.getRecipientUsername(), e.getMessage());
                error = rootMessage(e);
            }
        }
        String failure = error;
        tx.executeWithoutResult(status -> {
            Notification fresh = notificationRepository.findById(id).orElseThrow();
            fresh.setAttempts(fresh.getAttempts() + 1);
            if (failure == null) {
                fresh.setStatus(NotificationStatus.SENT);
                fresh.setSentAt(LocalDateTime.now());
                fresh.setErrorMessage(null);
            } else {
                fresh.setStatus(NotificationStatus.FAILED);
                fresh.setErrorMessage(truncate(failure));
            }
            notificationRepository.save(fresh);
            eventRepository.save(NotificationEvent.builder()
                    .notificationId(id)
                    .type(failure == null ? NotificationEventType.SENT : NotificationEventType.FAILED)
                    .detail(failure == null ? "Emailed to " + fresh.getRecipientEmail() : truncate(failure))
                    .build());
        });
    }

    // Mail exceptions bury the SMTP server's reply ("550 5.1.1 mailbox
    // unavailable") under generic wrappers; MailSendException keeps the
    // per-message exception aside rather than as its cause.
    static String rootMessage(Exception e) {
        Throwable t = e instanceof MailSendException send && !send.getFailedMessages().isEmpty()
                ? send.getFailedMessages().values().iterator().next() : e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage() == null ? "" : t.getMessage().strip();
        return message.isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private static String truncate(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 997) + "…";
    }

    // Settings › General support address, so replies reach a person.
    private void replyTo(SimpleMailMessage message) {
        String support = settings.general().supportEmail();
        if (support != null && !support.isBlank()) {
            message.setReplyTo(support);
        }
    }
}
