package com.stock.system.admin.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminGovernanceServiceTest {

    @Mock private SuperAdminService superAdminService;
    @Mock private AdminQueryService queryService;
    @Mock private AdminGovernanceTransactionService transactionService;
    @Mock private AdminOperationLogService operationLogService;
    @InjectMocks private AdminGovernanceService governanceService;

    @Test
    void recordsExpectedBusinessFailureAfterAdminIdentityIsConfirmed() {
        doThrow(new BusinessException(ErrorCode.SUPER_ADMIN_CANNOT_DISABLE_SELF))
                .when(transactionService).disableUser(1L, 1L);

        assertThatThrownBy(() -> governanceService.disableUser(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("唯一超级管理员不能禁用自己的账号");

        verify(operationLogService).recordFailure("DISABLE_USER", 1L, "DISABLED");
    }

    @Test
    void doesNotRecordOrdinaryUserAuthorizationFailureAsGovernanceOperation() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(superAdminService).requireSuperAdmin(9L);

        assertThatThrownBy(() -> governanceService.disableUser(9L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前账号无权执行该操作");

        verify(transactionService, never()).disableUser(9L, 10L);
        verify(operationLogService, never()).recordFailure("DISABLE_USER", 10L, "DISABLED");
    }

    @Test
    void failureLogUsesAnIndependentTransaction() throws Exception {
        Method method = AdminOperationLogService.class.getMethod(
                "recordFailure", String.class, Long.class, String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
