package com.fintech.fintech_gateway.controller;

import com.fintech.fintech_gateway.feedback.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/feedback")
@Tag(name = "Internal operations", description = "Operator-only endpoints")
@SecurityRequirement(name = "internalOpsKey")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/false-positive")
    @Operation(summary = "Clear gateway penalties for an IP after a confirmed false positive")
    public ResponseEntity<Map<String, String>> reportFalsePositive(
            @Valid @RequestBody FeedbackRequest request) {
        feedbackService.handleFalsePositive(request.getIp(), request.getReason());
        return ResponseEntity.ok(Map.of(
                "status", "resolved",
                "ip", request.getIp(),
                "message", "IP trust tier reset to MEDIUM"
        ));
    }

    public static class FeedbackRequest {
        @NotBlank
        @Size(max = 64)
        private String ip;

        @Size(max = 400)
        private String reason;

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
