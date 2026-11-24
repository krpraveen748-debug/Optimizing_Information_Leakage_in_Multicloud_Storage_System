package com.praveen.multicloud.controller;

import com.praveen.multicloud.service.ActivityLogService;
import com.praveen.multicloud.service.AdminAnalyticsService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAnalyticsControllerTest {
    @Test
    void distributionCsvNeutralizesFormulaCells() {
        AdminAnalyticsService analytics = mock(AdminAnalyticsService.class);
        when(analytics.providerDistribution()).thenReturn(List.of(Map.of(
                "provider", " =HYPERLINK(\"https://example.test\")",
                "chunkCount", 1,
                "storageBytes", 4)));
        AdminAnalyticsController controller =
                new AdminAnalyticsController(analytics, mock(ActivityLogService.class));

        String csv = new String(controller.distributionCsv().getBody(), StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"' =HYPERLINK(\"\"https://example.test\"\")\""));
        assertTrue(csv.contains(",1,4"));
    }
}
