package org.example.gametopupkh.admin.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.gametopupkh.admin.dto.AdminDashboardSummaryResponse;
import org.example.gametopupkh.admin.service.AdminDashboardService;
import org.example.gametopupkh.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@SecurityRequirement(name = "bearerAuth")
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AdminDashboardSummaryResponse>> summary() {
        return ResponseEntity.ok(ApiResponse.ok(service.getSummary()));
    }
}
