package com.insophinia.pos.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final SettingsService settings;
    private final String from;

    public EmailService(JavaMailSender mailSender, SettingsService settings,
                        @Value("${spring.mail.username:noreply@insophinia.local}") String from) {
        this.mailSender = mailSender;
        this.settings = settings;
        this.from = from == null || from.isBlank() ? "noreply@insophinia.local" : from;
    }

    public boolean isEnabled() {
        return settings.getBool(SettingsService.KEY_EMAIL_ENABLED, false);
    }

    public void sendReceipt(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from); helper.setTo(to); helper.setSubject(subject); helper.setText(html, true);
            mailSender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Receipt email delivery failed", e);
        }
    }

    @Async
    public void send(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Email send failed to {}: {}", to, e.getMessage());
        }
    }
}
