package com.stock.system.admin.dto;

public record OperationLogQueryDTO(int page, int size, String action, String result) {
}
