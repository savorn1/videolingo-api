package com.example.videolingo.controller;

import com.example.videolingo.audit.AuditService;
import com.example.videolingo.audit.AuditService.AuditEntry;
import com.example.videolingo.audit.AuditService.AuditFilter;
import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.dto.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The audit log, read-only — module "audit-logs" (READ).
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<PageResponse<AuditEntry>> list(@ModelAttribute AuditFilter filter) {
        return ResponseEntity.ok(auditService.list(filter));
    }

    // Modules that appear in the log, for the filter dropdown.
    @GetMapping("/modules")
    public ResponseEntity<ApiResponse<List<String>>> modules() {
        return ResponseEntity.ok(ApiResponse.success(auditService.modules()));
    }
}
