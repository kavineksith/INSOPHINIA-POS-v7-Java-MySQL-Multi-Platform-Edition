package com.insophinia.pos.repository;

import com.insophinia.pos.entity.BillChannelSendLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BillChannelSendLogRepository extends JpaRepository<BillChannelSendLog, UUID> {
    List<BillChannelSendLog> findByBillIdOrderBySentAtDesc(UUID billId);
}
