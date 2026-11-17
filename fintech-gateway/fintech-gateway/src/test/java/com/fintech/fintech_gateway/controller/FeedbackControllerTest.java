package com.fintech.fintech_gateway.controller;

import com.fintech.fintech_gateway.controller.FeedbackController.FeedbackRequest;
import com.fintech.fintech_gateway.feedback.FeedbackService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeedbackControllerTest {

    private static final String TENANT = "tenant-a";
    private static final String IP = "203.0.113.5";

    private final FeedbackService feedbackService = mock(FeedbackService.class);
    private final FeedbackController controller = new FeedbackController(feedbackService);

    @Test
    void falsePositiveDelegatesToTheServiceAndReturnsResolution() {
        FeedbackRequest request = request(TENANT, IP, "confirmed false positive");

        ResponseEntity<Map<String, String>> response = controller.reportFalsePositive(request);

        verify(feedbackService).handleFalsePositive(TENANT, IP, "confirmed false positive");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("resolved", response.getBody().get("status"));
        assertEquals(TENANT, response.getBody().get("tenant"));
        assertEquals(IP, response.getBody().get("ip"));
        assertTrue(response.getBody().get("message").contains("MEDIUM"));
    }

    @Test
    void requestValidationRejectsMissingTenant() {
        FeedbackRequest request = request(null, IP, "reason");

        assertTrue(validate(request).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("tenantId")));
    }

    @Test
    void requestValidationRejectsBlankIp() {
        FeedbackRequest request = request(TENANT, "   ", "reason");

        assertTrue(validate(request).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("ip")));
    }

    @Test
    void requestValidationRejectsOverlongIp() {
        FeedbackRequest request = request(TENANT, "1".repeat(65), "reason");

        assertTrue(validate(request).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("ip")));
    }

    @Test
    void requestValidationRejectsOverlongReason() {
        FeedbackRequest request = request(TENANT, IP, "x".repeat(401));

        assertTrue(validate(request).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("reason")));
    }

    @Test
    void validRequestHasNoViolations() {
        assertTrue(validate(request(TENANT, IP, "support ticket 1234")).isEmpty());
    }

    private static FeedbackRequest request(String tenant, String ip, String reason) {
        FeedbackRequest request = new FeedbackRequest();
        request.setTenantId(tenant);
        request.setIp(ip);
        request.setReason(reason);
        return request;
    }

    private Set<ConstraintViolation<FeedbackRequest>> validate(FeedbackRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }
}
