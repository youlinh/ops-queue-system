package com.acme.opsqueue.task;

import java.util.List;

public record TaskPage<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
