package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.DlqNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** HIS 通知死信（V10）。 */
public interface DlqNotificationRepository extends JpaRepository<DlqNotification, Long> {

    List<DlqNotification> findByTaskIdAndResolvedAtIsNull(UUID taskId);

    List<DlqNotification> findByResolvedAtIsNullOrderByCreatedAtDesc();
}
