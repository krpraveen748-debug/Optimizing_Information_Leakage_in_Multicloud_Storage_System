package com.praveen.multicloud.controller;

import com.praveen.multicloud.service.FileService;
import com.praveen.multicloud.service.LeakageAwarePlacementService;
import com.praveen.multicloud.security.AuthenticatedUser;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "http://localhost:8080")
public class FileController {
    private final FileService files;
    public FileController(FileService files) { this.files = files; }

    @PostMapping(value="/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", required = false) String strategy) {
        LeakageAwarePlacementService.Strategy placementStrategy =
                LeakageAwarePlacementService.Strategy.LEAKAGE_AWARE;
        if (strategy != null && !strategy.isBlank()) {
            try {
                placementStrategy = LeakageAwarePlacementService.Strategy.valueOf(strategy.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Strategy must be ROUND_ROBIN, RANDOM, or LEAKAGE_AWARE.");
            }
        }
        return files.upload(file, user.getId(), placementStrategy);
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return files.listFiles(user.getId());
    }

    @GetMapping("/{id}")
    public Map<String,Object> details(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        return files.details(id, user.getId());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) throws Exception {
        FileService.DownloadedFile download = files.download(id, user.getId());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(download.filename(), StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(download.content());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        files.delete(id, user.getId());
        return ResponseEntity.noContent().build();
    }
}
