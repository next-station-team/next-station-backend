package com.nextstation.backend.alarm;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlarmRepository extends JpaRepository<Alarm, UUID> {
    List<Alarm> findByDeviceIdAndSessionIdOrderByScheduledAt(UUID deviceId, UUID sessionId);

    @Query("select a.id from Alarm a where a.status = :status and a.nextAttemptAt <= :now order by a.nextAttemptAt, a.id")
    List<UUID> findDueIds(@Param("status") AlarmStatus status, @Param("now") Instant now, Pageable page);

    @Query("select a.device.id from Alarm a where a.id = :id")
    Optional<UUID> findDeviceId(@Param("id") UUID id);

    List<Alarm> findByDeviceIdAndStatus(UUID deviceId, AlarmStatus status);
}
