package com.stock.system.admin.service;

import com.stock.system.admin.entity.SuperAdminOperationLog;
import com.stock.system.admin.mapper.SuperAdminOperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOperationLogService {

    private final SuperAdminOperationLogMapper operationLogMapper;

    public AdminOperationLogService(SuperAdminOperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    public void recordSuccess(
            String action,
            Long targetId,
            String beforeStatus,
            String afterStatus
    ) {
        insert(action, targetId, beforeStatus, afterStatus, "SUCCESS");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String action, Long targetId, String attemptedStatus) {
        insert(action, targetId, null, attemptedStatus, "FAILED");
    }

    private void insert(
            String action,
            Long targetId,
            String beforeStatus,
            String afterStatus,
            String result
    ) {
        SuperAdminOperationLog log = new SuperAdminOperationLog();
        log.setAction(action);
        log.setTargetId(targetId);
        log.setBeforeStatus(beforeStatus);
        log.setAfterStatus(afterStatus);
        log.setResult(result);
        operationLogMapper.insert(log);
    }
}
