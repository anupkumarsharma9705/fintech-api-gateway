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

    private final FeedbackService feedbackService = mock(FeedbackService.class);
    private final FeedbackController controller = new FeedbackController(feedbackService);

    @Test
    void falsePositiveDelegatesToTheServiceAndReturnsResolution() {
        FeedbackRequest request = new FeedbackRequest();
        request.setIp("203.0.113.5");
        request.setReason("confirmed false positive");

        ResponseEntity<Map<String, String>> response = controller.reportFalsePositive(request);

        verify(feedbackService).handleFalsePositive("203.0.113.5", "confirmed false positive");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("resolved", response.getBody().get("status"));
        assertEquals("203.0.113.5", response.getBody().get("ip"));
        assertTrue(response.getBody().get("message").contains("MEDIUM"));
    }

    @Test
    void requestValidationRejectsBlankIp() {
        FeedbackRequest request = new FeedbackRequest();
        request.setIp("   ");

        Set<ConstraintViolation<FeedbackRequest>> violations = validate(request);

        assertTrue(violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("ip")));
    }

    @Test
    void requestValidationRejectsOverlongIp() {
        FeedbackRequest request = new FeedbackRequest();
        request.setIp("1".repeat(65));

        assertTrue(validate(request).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("ip")));
    }

    @Test
    void requestValidationRejectsOverlongReason() {
        FeedbackRequest request = new FeedbackRequest();
        request.setIp("203.0.113.5");
        request.setReason("x".repeat(401));

        assertTrue(validate(request).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("reason")));
    }

    @Test
    void validRequestHasNoViolations() {
        FeedbackRequest request = new FeedbackRequest();
        request.setIp("203.0.113.5");
        request.setReason("support ticket 1234");

        assertTrue(validate(request).isEmpty());
    }

    private Set<ConstraintViolation<FeedbackRequest>> validate(FeedbackRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }
}
