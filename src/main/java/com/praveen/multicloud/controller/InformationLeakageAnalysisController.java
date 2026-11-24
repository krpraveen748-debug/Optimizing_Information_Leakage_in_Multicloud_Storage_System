package com.praveen.multicloud.controller;

import com.praveen.multicloud.dto.ProviderLeakageAnalysisResponse;
import com.praveen.multicloud.dto.ProviderLeakageHistoryItem;
import com.praveen.multicloud.dto.ProviderPairComparisonResponse;
import com.praveen.multicloud.security.AuthenticatedUser;
import com.praveen.multicloud.service.InformationLeakageAnalysisService;
import com.praveen.multicloud.service.ActivityLogService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/leakage/analysis")
public class InformationLeakageAnalysisController {
    private final InformationLeakageAnalysisService analysis;
    private final ActivityLogService activities;

    public InformationLeakageAnalysisController(
            InformationLeakageAnalysisService analysis,
            ActivityLogService activities) {
        this.analysis = analysis;
        this.activities = activities;
    }

    @PostMapping
    public ProviderLeakageAnalysisResponse run(
            @AuthenticationPrincipal AuthenticatedUser user) {
        ProviderLeakageAnalysisResponse response = analysis.runAnalysis(user.getId());
        activities.record(user.getId(), "LEAKAGE_ANALYSIS",
                "Ran provider leakage analysis.");
        return response;
    }

    @GetMapping("/latest")
    public ProviderLeakageAnalysisResponse latest() {
        return analysis.latestAnalysis();
    }

    @GetMapping("/history")
    public List<ProviderLeakageHistoryItem> history() {
        return analysis.history();
    }

    @GetMapping("/{analysisId}/pairs")
    public List<ProviderPairComparisonResponse> pairs(@PathVariable long analysisId) {
        return analysis.pairComparisons(analysisId);
    }
}
