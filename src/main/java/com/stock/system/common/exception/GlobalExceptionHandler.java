package com.stock.system.common.exception;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.api.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedStream(AsyncRequestNotUsableException exception) {
        // 客户端已离开，容器正在结束该响应；不能再向已提交的 SSE 写入 JSON 错误。
        LOGGER.debug("Async response disconnected");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        Result<Void> result = new Result<>(errorCode.name(), exception.getMessage(), null);
        return ResponseEntity.status(errorCode.httpStatus()).body(result);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse(ErrorCode.INVALID_REQUEST.message());
        Result<Void> result = new Result<>(ErrorCode.INVALID_REQUEST.name(), message, null);
        return ResponseEntity.badRequest().body(result);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleParameterTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.error(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> handleLockConflict(
            PessimisticLockingFailureException exception
    ) {
        LOGGER.warn("Concurrent database operation failed", exception);
        ErrorCode errorCode = ErrorCode.CONCURRENT_OPERATION_CONFLICT;
        return ResponseEntity.status(errorCode.httpStatus()).body(Result.error(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("Unexpected request failure", exception);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.httpStatus()).body(Result.error(errorCode));
    }
}
