package com.acme.opsqueue.audit;

import java.util.List;
import org.springframework.data.domain.Page;

public record AuditPage(
        List<AuditLog> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public AuditPage {
        content = List.copyOf(content);
    }

    static AuditPage from(Page<AuditLog> source) {
        return new AuditPage(
                source.getContent(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
