package com.praveen.multicloud.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class RecoveryEmailSender {
    private final ObjectProvider<JavaMailSender> senders;
    private final String host;
    private final String username;
    private final String password;
    private final String from;

    public RecoveryEmailSender(
            ObjectProvider<JavaMailSender> senders,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${app.recovery.mail-from:no-reply@localhost}") String from) {
        this.senders = senders;
        this.host = host;
        this.username = username;
        this.password = password;
        this.from = from;
    }

    public boolean isConfigured() {
        return !host.isBlank() && !username.isBlank() && !password.isBlank()
                && !from.isBlank() && senders.getIfAvailable() != null;
    }

    public void sendPasswordReset(String email, String resetLink, int expiresInMinutes) {
        JavaMailSender sender = senders.getIfAvailable();
        if (!isConfigured() || sender == null) {
            throw new AccountRecoveryService.RecoveryUnavailableException();
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("CloudVault password reset");
        message.setText("""
                A password reset was requested for your CloudVault account.

                Open this one-time link to choose a new password:
                %s

                The link expires in %d minutes. If you did not request this, you can ignore this message.
                """.formatted(resetLink, expiresInMinutes));
        sender.send(message);
    }
}
