package com.praveen.multicloud.repository;

import com.praveen.multicloud.entity.ActivityLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLogEntity, Long> {
    List<ActivityLogEntity> findTop100ByOrderByActivityTimeDesc();
    List<ActivityLogEntity> findTop100ByUser_IdOrderByActivityTimeDesc(Long userId);
}
