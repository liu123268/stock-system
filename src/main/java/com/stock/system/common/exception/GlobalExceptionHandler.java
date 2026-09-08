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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.httpStatus()).body(Result.error(errorCode));
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
