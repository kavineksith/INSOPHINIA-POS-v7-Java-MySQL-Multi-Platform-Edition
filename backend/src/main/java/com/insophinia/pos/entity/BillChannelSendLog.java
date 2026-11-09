package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Records every WhatsApp / email send per bill (text vs PDF version).
 * Used for audit + retry + showing the operator "already sent on
 * 2026-10-18 14:32 via WhatsApp (PDF)".
 *
 * {@code channel} discriminates WhatsApp / email. {@code format} discriminates
 * text (HTML for email, plain text for WhatsApp) vs PDF (full A4 receipt
 * attached as a PDF for both channels).
 */
@Getter
@Setter
@Entity
@Table(name = "bill_channel_send_logs")
public class BillChannelSendLog extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SendChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SendFormat format;

    @Column(nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SendStatus status = SendStatus.sent;

    @Column(name = "failure_reason", columnDefinition = "MEDIUMTEXT")
    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sent_by")
    private User sentBy;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}
