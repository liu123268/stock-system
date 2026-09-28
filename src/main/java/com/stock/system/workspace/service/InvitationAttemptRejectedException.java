package com.stock.system.workspace.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;

/** 加入事务尚未写入成员关系时的预期失败；计数需要随事务提交。 */
final class InvitationAttemptRejectedException extends BusinessException {

    InvitationAttemptRejectedException(ErrorCode errorCode) {
        super(errorCode);
    }
}
