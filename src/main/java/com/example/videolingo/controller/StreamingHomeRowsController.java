package com.example.videolingo.controller;

import com.example.videolingo.dto.ApiResponse;
import com.example.videolingo.streaming.StreamingHomeRowsService;
import com.example.videolingo.streaming.StreamingHomeRowsService.HomeRow;
import com.example.videolingo.streaming.StreamingHomeRowsService.HomeRowRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// RuengFlix's home page rows — module "streaming": reading needs READ, everything else WRITE.
@RestController
@RequestMapping("/api/admin/streaming/home-rows")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','USER')")
public class StreamingHomeRowsController {

    private final StreamingHomeRowsService service;

    public record OrderRequest(List<Long> ids) {}

    @GetMapping
    public ResponseEntity<ApiResponse<List<HomeRow>>> rows() {
        return ResponseEntity.ok(ApiResponse.success(service.rows()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<HomeRow>> create(@RequestBody HomeRowRequest body, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Row added", service.create(body, auth == null ? null : auth.getName())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<HomeRow>> update(@PathVariable long id, @RequestBody HomeRowRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Row saved", service.update(id, body)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Row deleted", null));
    }

    @PutMapping("/order")
    public ResponseEntity<ApiResponse<List<HomeRow>>> reorder(@RequestBody OrderRequest body) {
        return ResponseEntity.ok(ApiResponse.success("Order saved", service.reorder(body == null ? null : body.ids())));
    }
}
