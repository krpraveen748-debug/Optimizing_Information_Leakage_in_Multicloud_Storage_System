package com.praveen.multicloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praveen.multicloud.entity.UserEntity;
import com.praveen.multicloud.entity.UserRole;
import com.praveen.multicloud.entity.UserStatus;
import com.praveen.multicloud.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:file-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:file-test-schema.sql",
        "app.chunk.size=4",
        "app.upload.max-size-bytes=4096",
        "app.leakage.high-similarity-threshold=0.3"
})
@AutoConfigureMockMvc
class FileUploadIntegrationTest {
    private static final String PASSWORD = "correct horse battery staple";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "multicloud-file-tests-" + UUID.randomUUID());
    private static final String TEST_KEY_ID = "file-integration-v1";
    private static final String TEST_KEY_RING = generatedKeyRing();

    @Autowired private FileService files;
    @Autowired private ChunkingService chunking;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void storageDirectories(DynamicPropertyRegistry registry) {
        registry.add("app.storage.cloud1", () -> STORAGE_ROOT.resolve("cloud1").toString());
        registry.add("app.storage.cloud2", () -> STORAGE_ROOT.resolve("cloud2").toString());
        registry.add("app.storage.cloud3", () -> STORAGE_ROOT.resolve("cloud3").toString());
        registry.add("app.encryption.key-ring", () -> TEST_KEY_RING);
        registry.add("app.encryption.active-key-id", () -> TEST_KEY_ID);
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM placement_strategy_results");
        jdbc.update("DELETE FROM placement_experiments");
        jdbc.update("DELETE FROM provider_leakage_pairs");
        jdbc.update("DELETE FROM provider_leakage_analyses");
        jdbc.update("DELETE FROM leakage_analysis");
        jdbc.update("DELETE FROM activity_logs");
        jdbc.update("DELETE FROM file_chunks");
        jdbc.update("DELETE FROM chunk_metadata");
        jdbc.update("DELETE FROM files");
        jdbc.update("DELETE FROM users");
        jdbc.update("UPDATE cloud_providers SET total_chunks = 0, total_storage_used = 0");
        clearStoredChunks();
    }

    @AfterAll
    static void removeTemporaryStorage() throws IOException {
        if (Files.exists(STORAGE_ROOT)) {
            try (Stream<Path> paths = Files.walk(STORAGE_ROOT)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void chunksHashesAndReconstructsUploadedContent() throws Exception {
        UserEntity user = createUser("owner@example.test");
        byte[] content = {1, 2, 3, 4, 5, 6, 7, 8, 9};

        var result = files.upload(file("notes.txt", "text/plain", content), user.getId());

        assertEquals(3, result.get("chunkCount"));
        assertEquals(0, result.get("deduplicatedChunks"));
        assertEquals(ChunkingService.sha256(content), result.get("sha256"));
        assertEquals("PASSED", result.get("validationStatus"));
        assertEquals("SHA-256", result.get("hashAlgorithm"));
        assertEquals("ENCRYPTED", result.get("encryptionStatus"));
        assertEquals("AES-256-GCM", result.get("encryptionAlgorithm"));
        assertEquals("LEAKAGE_AWARE", result.get("placementStrategy"));
        assertEquals("text/plain", result.get("contentType"));
        assertEquals(3, ((List<?>) result.get("providerDistribution")).stream()
                .mapToInt(provider -> ((Number) ((Map<?, ?>) provider).get("chunk_count")).intValue())
                .sum());
        List<ChunkingService.Chunk> expectedChunks = chunking.split(new java.io.ByteArrayInputStream(content));
        List<String> storedHashes = jdbc.query(
                "SELECT chunk_hash FROM file_chunks WHERE file_id = ? ORDER BY chunk_number",
                (row, index) -> row.getString(1),
                ((Number) result.get("fileId")).longValue());
        assertEquals(expectedChunks.stream().map(ChunkingService.Chunk::hash).toList(), storedHashes);
        assertEquals(ChunkingService.sha256(content), jdbc.queryForObject(
                "SELECT file_sha256 FROM files WHERE id = ?", String.class,
                ((Number) result.get("fileId")).longValue()));
        Map<String, Object> details = files.details(
                ((Number) result.get("fileId")).longValue(), user.getId());
        assertEquals("LEAKAGE_AWARE", details.get("placement_strategy"));
        assertEquals("ENCRYPTED", details.get("encryptionStatus"));
        assertEquals(ChunkingService.sha256(content), details.get("file_sha256"));
        assertEquals(3, ((List<?>) details.get("chunks")).size());
        assertEquals(7, jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_logs WHERE user_id = ? AND file_name = ?",
                Integer.class, user.getId(), "notes.txt"));
        assertEquals("ENCRYPTED", jdbc.queryForObject(
                "SELECT encryption_status FROM file_chunks WHERE file_id = ? ORDER BY chunk_number LIMIT 1",
                String.class, ((Number) result.get("fileId")).longValue()));
        String firstStoragePath = jdbc.queryForObject(
                "SELECT storage_path FROM file_chunks WHERE file_id = ? ORDER BY chunk_number LIMIT 1",
                String.class, ((Number) result.get("fileId")).longValue());
        assertFalse(Arrays.equals(expectedChunks.getFirst().data(), Files.readAllBytes(STORAGE_ROOT.resolve(firstStoragePath))));

        FileService.DownloadedFile downloaded =
                files.download(((Number) result.get("fileId")).longValue(), user.getId());
        assertArrayEquals(content, downloaded.content());
        assertEquals("notes.txt", downloaded.filename());
        assertEquals("notes.txt", jdbc.queryForObject(
                "SELECT file_name FROM activity_logs WHERE user_id = ? AND activity_type = 'FILE_DOWNLOAD'",
                String.class, user.getId()));
    }

    @Test
    void storesRepeatedChunksOnceAndTracksEveryReference() throws Exception {
        UserEntity user = createUser("dedupe@example.test");
        byte[] content = {9, 8, 7, 6};
        var first = files.upload(file("first.bin", "application/octet-stream", content), user.getId());
        var second = files.upload(file("second.bin", "application/octet-stream", content), user.getId());

        assertEquals(0, first.get("deduplicatedChunks"));
        assertEquals(1, second.get("deduplicatedChunks"));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT reference_count FROM chunk_metadata WHERE chunk_hash = ?",
                Long.class, ChunkingService.sha256(content)));
        assertEquals(1, storedChunkCount());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT storage_path) FROM file_chunks", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT encryption_iv) FROM file_chunks", Integer.class));
        assertEquals(1, ((Number) files.listFiles(user.getId()).getFirst().get("deduplicated_chunks")).intValue());
    }

    @Test
    void concurrentDuplicateUploadsKeepOneContentObjectAndAccurateReferences() throws Exception {
        UserEntity user = createUser("concurrent@example.test");
        byte[] content = {3, 5, 7, 9};
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return files.upload(file("parallel-a.bin", "application/octet-stream", content), user.getId());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return files.upload(file("parallel-b.bin", "application/octet-stream", content), user.getId());
            });
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertEquals(2L, jdbc.queryForObject(
                "SELECT reference_count FROM chunk_metadata WHERE chunk_hash = ?",
                Long.class, ChunkingService.sha256(content)));
        assertEquals(1, storedChunkCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM files", Integer.class));
    }

    @Test
    void acceptsEmptyAndSmallerThanOneChunkFiles() throws Exception {
        UserEntity user = createUser("small@example.test");
        byte[] empty = new byte[0];
        byte[] small = {11, 12};

        var emptyUpload = files.upload(file("empty.dat", "application/octet-stream", empty), user.getId());
        var smallUpload = files.upload(file("small.dat", "application/octet-stream", small), user.getId());

        assertEquals(0, emptyUpload.get("chunkCount"));
        assertEquals(ChunkingService.sha256(empty), emptyUpload.get("sha256"));
        assertArrayEquals(empty, files.download(((Number) emptyUpload.get("fileId")).longValue(), user.getId()).content());
        assertEquals(1, smallUpload.get("chunkCount"));
        assertArrayEquals(small, files.download(((Number) smallUpload.get("fileId")).longValue(), user.getId()).content());
    }

    @Test
    void encryptsAndReconstructsPdfTextDocxJpegAndZipBytes() throws Exception {
        UserEntity user = createUser("formats@example.test");
        List<FormatFixture> fixtures = List.of(
                new FormatFixture("sample.pdf", "application/pdf",
                        "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
                                .getBytes(StandardCharsets.US_ASCII)),
                new FormatFixture("sample.txt", "text/plain",
                        "Multicloud file round-trip.\n".getBytes(StandardCharsets.UTF_8)),
                new FormatFixture("sample.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        zipBytes(Map.of(
                                "[Content_Types].xml", "<Types/>",
                                "_rels/.rels", "<Relationships/>",
                                "word/document.xml", "<document/>"))),
                new FormatFixture("sample.jpg", "image/jpeg", jpegBytes()),
                new FormatFixture("sample.zip", "application/zip",
                        zipBytes(Map.of("sample.txt", "ZIP payload"))));

        for (FormatFixture fixture : fixtures) {
            long fileId = ((Number) files.upload(
                    file(fixture.filename(), fixture.contentType(), fixture.content()), user.getId())
                    .get("fileId")).longValue();
            FileService.DownloadedFile downloaded = files.download(fileId, user.getId());
            assertEquals(fixture.filename(), downloaded.filename());
            assertArrayEquals(fixture.content(), downloaded.content(), fixture.filename());
        }
    }

    @Test
    void deletingOneFilePreservesChunksUntilTheLastReferenceIsRemoved() throws Exception {
        UserEntity user = createUser("delete@example.test");
        byte[] content = {4, 3, 2, 1};
        long firstFile = ((Number) files.upload(
                file("first.bin", "application/octet-stream", content), user.getId()).get("fileId")).longValue();
        long secondFile = ((Number) files.upload(
                file("second.bin", "application/octet-stream", content), user.getId()).get("fileId")).longValue();
        String path = jdbc.queryForObject(
                "SELECT storage_path FROM file_chunks WHERE file_id = ?", String.class, firstFile);
        Path chunkPath = STORAGE_ROOT.resolve(path);

        files.delete(firstFile, user.getId());
        assertTrue(Files.exists(chunkPath));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT reference_count FROM chunk_metadata WHERE chunk_hash = ?",
                Long.class, ChunkingService.sha256(content)));

        files.delete(secondFile, user.getId());
        assertFalse(Files.exists(chunkPath));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chunk_metadata", Integer.class));
    }

    @Test
    void failedUploadRollsBackRowsAndRemovesNewlyWrittenContent() throws Exception {
        UserEntity user = createUser("failure@example.test");
        byte[] content = {1, 1, 1, 1};
        String hash = ChunkingService.sha256(content);
        jdbc.update("""
                INSERT INTO chunk_metadata (chunk_hash, chunk_size, reference_count, created_at)
                VALUES (?, 2, 1, CURRENT_TIMESTAMP)
                """, hash);

        assertThrows(FileService.FileIntegrityException.class,
                () -> files.upload(file("collision.bin", "application/octet-stream", content), user.getId()));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM files", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_chunks", Integer.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT reference_count FROM chunk_metadata WHERE chunk_hash = ?", Long.class, hash));
        assertEquals(0, storedChunkCount());
    }

    @Test
    void rejectsInvalidNamesTypesAndOversizedFiles() throws Exception {
        UserEntity user = createUser("validation@example.test");
        assertThrows(IllegalArgumentException.class, () ->
                files.upload(file("../escape.bin", "application/octet-stream", new byte[]{1}), user.getId()));
        assertThrows(IllegalArgumentException.class, () ->
                files.upload(file("bad.bin", "not a media type", new byte[]{1}), user.getId()));
        assertThrows(FileService.FileTooLargeException.class, () ->
                files.upload(file("large.bin", "application/octet-stream", new byte[4097]), user.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM files", Integer.class));
    }

    @Test
    void fileApiRequiresLoginAndRestrictsOperationsToTheOwner() throws Exception {
        mvc.perform(get("/api/files")).andExpect(status().isUnauthorized());
        UserEntity owner = createUser("api-owner@example.test");
        UserEntity other = createUser("api-other@example.test");
        MockHttpSession ownerSession = authenticatedSession(owner.getEmail());
        MockHttpSession otherSession = authenticatedSession(other.getEmail());
        byte[] content = {5, 4, 3, 2, 1};
        String csrf = csrf(ownerSession);

        MvcResult upload = mvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "private.bin", "application/octet-stream", content))
                        .session(ownerSession)
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(2))
                .andReturn();
        long fileId = objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("fileId").asLong();

        mvc.perform(get("/api/files").session(otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/files/{id}", fileId).session(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/files/{id}/download", fileId).session(otherSession))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/{id}", fileId)
                        .session(otherSession)
                        .header("X-CSRF-TOKEN", csrf(otherSession)))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/files/{id}/download", fileId).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(result -> assertArrayEquals(content, result.getResponse().getContentAsByteArray()));
        mvc.perform(delete("/api/files/{id}", fileId)
                        .session(ownerSession)
                        .header("X-CSRF-TOKEN", csrf(ownerSession)))
                .andExpect(status().isNoContent());
    }

    @Test
    void dashboardStorageAndProviderDistributionAreOwnerScopedAndDeduplicated() throws Exception {
        UserEntity owner = createUser("dashboard-owner@example.test");
        UserEntity other = createUser("dashboard-other@example.test");
        byte[] repeatedContent = {1, 2, 3, 4, 5, 6, 7, 8};
        files.upload(file("first.bin", "application/octet-stream", repeatedContent), owner.getId());
        files.upload(file("second.bin", "application/octet-stream", repeatedContent), owner.getId());
        files.upload(file("other.bin", "application/octet-stream",
                new byte[]{9, 10, 11, 12}), other.getId());

        MockHttpSession ownerSession = authenticatedSession(owner.getEmail());
        JsonNode summary = objectMapper.readTree(mvc.perform(get("/api/dashboard/summary")
                        .session(ownerSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(2, summary.get("totalFiles").asInt());
        assertEquals(4, summary.get("totalChunks").asInt());
        assertEquals(8, summary.get("personalStorageBytes").asLong());
        assertEquals(4, sumChunkCounts(summary.get("cloudDistribution")));

        JsonNode distribution = objectMapper.readTree(mvc.perform(get("/api/placement/distribution")
                        .session(ownerSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(4, sumChunkCounts(distribution));
    }

    @Test
    void similarityApiComparesEncryptedUploadsAndEnforcesOwnership() throws Exception {
        MockHttpSession anonymous = new MockHttpSession();
        mvc.perform(post("/api/similarity/compare")
                        .session(anonymous)
                        .header("X-CSRF-TOKEN", csrf(anonymous))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":1,"candidateFileId":2}
                                """))
                .andExpect(status().isUnauthorized());

        UserEntity owner = createUser("similarity-owner@example.test");
        UserEntity other = createUser("similarity-other@example.test");
        byte[] content = {1, 2, 3, 4, 5, 6, 7, 8};
        long firstFileId = ((Number) files.upload(
                file("first.bin", "application/octet-stream", content), owner.getId()).get("fileId")).longValue();
        long secondFileId = ((Number) files.upload(
                file("second.bin", "application/octet-stream", content), owner.getId()).get("fileId")).longValue();
        long privateFileId = ((Number) files.upload(
                file("private.bin", "application/octet-stream", new byte[]{9, 10, 11, 12}),
                other.getId()).get("fileId")).longValue();
        MockHttpSession ownerSession = authenticatedSession(owner.getEmail());

        mvc.perform(post("/api/similarity/compare")
                        .session(ownerSession)
                        .header("X-CSRF-TOKEN", csrf(ownerSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"candidateFileId":%d}
                                """.formatted(firstFileId, secondFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.estimatedJaccardSimilarity").value(1.0))
                .andExpect(jsonPath("$.similarityPercent").value(100.0))
                .andExpect(jsonPath("$.minHashPermutationCount").value(128))
                .andExpect(jsonPath("$.bloomPositiveCount").value(2))
                .andExpect(jsonPath("$.exactMatchingChunkCount").value(2))
                .andExpect(jsonPath("$.chunkHashes").doesNotExist())
                .andExpect(jsonPath("$.similaritySignature").doesNotExist());

        mvc.perform(get("/api/similarity/files/{fileId}/compare/{candidateFileId}",
                        firstFileId, secondFileId)
                        .session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.similarityPercent").value(100.0));

        mvc.perform(get("/api/dashboard/activity?limit=100").session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activityType").value("SIMILARITY_ANALYSIS"))
                .andExpect(jsonPath("$[0].fileName").value("first.bin"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].technicalDetails").exists());

        mvc.perform(post("/api/similarity/compare")
                        .session(ownerSession)
                        .header("X-CSRF-TOKEN", csrf(ownerSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"candidateFileId":%d}
                                """.formatted(firstFileId, firstFileId)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/similarity/compare")
                        .session(ownerSession)
                        .header("X-CSRF-TOKEN", csrf(ownerSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"candidateFileId":%d}
                                """.formatted(firstFileId, privateFileId)))
                .andExpect(status().isNotFound());

        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM file_chunks WHERE encryption_status = 'ENCRYPTED'", Integer.class));
    }

    @Test
    void similarityApiReportsWhenSignaturesAreUnavailable() throws Exception {
        UserEntity owner = createUser("similarity-empty@example.test");
        long emptyFileId = ((Number) files.upload(
                file("empty.bin", "application/octet-stream", new byte[0]), owner.getId()).get("fileId")).longValue();
        long nonEmptyFileId = ((Number) files.upload(
                file("non-empty.bin", "application/octet-stream", new byte[]{1, 2, 3, 4}),
                owner.getId()).get("fileId")).longValue();
        MockHttpSession ownerSession = authenticatedSession(owner.getEmail());

        mvc.perform(post("/api/similarity/compare")
                        .session(ownerSession)
                        .header("X-CSRF-TOKEN", csrf(ownerSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileId":%d,"candidateFileId":%d}
                                """.formatted(emptyFileId, nonEmptyFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNATURES_UNAVAILABLE"))
                .andExpect(jsonPath("$.estimatedJaccardSimilarity").doesNotExist())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void placementExperimentsAreAdminOnlyAndPersistAllStrategies() throws Exception {
        UserEntity admin = createAdmin("placement-admin@example.test");
        UserEntity ordinaryUser = createUser("placement-user@example.test");
        MockHttpSession userSession = authenticatedSession(ordinaryUser.getEmail());
        MockHttpSession adminSession = authenticatedSession(admin.getEmail());

        mvc.perform(post("/api/admin/placement/experiments")
                        .session(userSession)
                        .header("X-CSRF-TOKEN", csrf(userSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\":17}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/placement/experiments")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", csrf(adminSession))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seed\":17}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputChunkCount").value(0))
                .andExpect(jsonPath("$.strategies.ROUND_ROBIN").exists())
                .andExpect(jsonPath("$.strategies.RANDOM").exists())
                .andExpect(jsonPath("$.strategies.LEAKAGE_AWARE").exists());

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM placement_experiments", Integer.class));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM placement_strategy_results", Integer.class));
        mvc.perform(get("/api/placement/history").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seed").value(17));
        MvcResult report = mvc.perform(get("/api/admin/reports/placement.csv").session(adminSession))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(report.getResponse().getContentAsString().contains("ROUND_ROBIN"));
        assertTrue(report.getResponse().getContentAsString().contains("cloud1"));
    }

    @Test
    void adminCanRunPersistAndViewPairwiseProviderAnalysis() throws Exception {
        UserEntity owner = createUser("provider-analysis-owner@example.test");
        UserEntity ordinaryUser = createUser("provider-analysis-user@example.test");
        UserEntity admin = createAdmin("provider-analysis-admin@example.test");
        mvc.perform(get("/api/admin/leakage/analysis/history"))
                .andExpect(status().isUnauthorized());
        files.upload(file("overlap-a.bin", "application/octet-stream",
                new byte[]{1, 1, 1, 1, 2, 2, 2, 2}), owner.getId());
        long secondFileId = ((Number) files.upload(
                file("overlap-b.bin", "application/octet-stream",
                        new byte[]{2, 2, 2, 2, 3, 3, 3, 3}), owner.getId()).get("fileId")).longValue();
        Long cloud1Id = jdbc.queryForObject(
                "SELECT id FROM cloud_providers WHERE provider_name = 'cloud1'", Long.class);
        jdbc.update("""
                UPDATE file_chunks
                   SET cloud_provider_id = ?
                 WHERE file_id = ? AND chunk_number = 0
                """, cloud1Id, secondFileId);

        MockHttpSession userSession = authenticatedSession(ordinaryUser.getEmail());
        mvc.perform(post("/api/admin/leakage/analysis")
                        .session(userSession)
                        .header("X-CSRF-TOKEN", csrf(userSession)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/leakage/analysis/history").session(userSession))
                .andExpect(status().isForbidden());

        MockHttpSession adminSession = authenticatedSession(admin.getEmail());
        MvcResult runResult = mvc.perform(post("/api/admin/leakage/analysis")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", csrf(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.highSimilarityThreshold").value(0.3))
                .andExpect(jsonPath("$.analyzedPairCount").value(3))
                .andExpect(jsonPath("$.highSimilarityPairCount").value(1))
                .andExpect(jsonPath("$.pairComparisons").isArray())
                .andExpect(jsonPath("$.pairComparisons[0].providerA").value("cloud1"))
                .andExpect(jsonPath("$.pairComparisons[0].providerB").value("cloud2"))
                .andExpect(jsonPath("$.pairComparisons[0].sharedFingerprintCount").value(1))
                .andExpect(jsonPath("$.pairComparisons[0].unionFingerprintCount").value(3))
                .andExpect(jsonPath("$.pairComparisons[0].highSimilarity").value(true))
                .andExpect(jsonPath("$.pairComparisons[0].chunkHash").doesNotExist())
                .andReturn();
        JsonNode run = objectMapper.readTree(runResult.getResponse().getContentAsString());
        long analysisId = run.get("analysisId").asLong();
        assertEquals(1.0 / 3.0,
                run.path("pairComparisons").get(0).path("jaccardSimilarity").asDouble(), 0.000001);
        assertEquals(1.0 / 9.0, run.path("overallJaccardSimilarity").asDouble(), 0.000001);

        mvc.perform(get("/api/admin/leakage/analysis/latest").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.pairComparisons.length()").value(3));
        mvc.perform(get("/api/admin/leakage/analysis/{analysisId}/pairs", analysisId)
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/admin/leakage/analysis/history").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        MvcResult leakageReport = mvc.perform(get("/api/admin/reports/leakage.csv").session(adminSession))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(leakageReport.getResponse().getContentAsString().contains("cloud1"));
        assertTrue(leakageReport.getResponse().getContentAsString().contains("cloud2"));

        mvc.perform(post("/api/admin/leakage/analysis")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", csrf(adminSession)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/leakage/analysis/history").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void adminAnalysisShowsNoDataInsteadOfInventingProviderFingerprints() throws Exception {
        UserEntity admin = createAdmin("provider-analysis-empty@example.test");
        MockHttpSession adminSession = authenticatedSession(admin.getEmail());

        mvc.perform(post("/api/admin/leakage/analysis")
                        .session(adminSession)
                        .header("X-CSRF-TOKEN", csrf(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_DATA"))
                .andExpect(jsonPath("$.overallJaccardSimilarity").doesNotExist())
                .andExpect(jsonPath("$.analyzedPairCount").value(0))
                .andExpect(jsonPath("$.pairComparisons.length()").value(3))
                .andExpect(jsonPath("$.pairComparisons[0].dataAvailable").value(false));
    }

    @Test
    void detectsCorruptChunkDuringDownload() throws Exception {
        UserEntity user = createUser("integrity@example.test");
        byte[] content = {2, 4, 6, 8};
        long fileId = ((Number) files.upload(
                file("integrity.bin", "application/octet-stream", content), user.getId()).get("fileId")).longValue();
        String path = jdbc.queryForObject(
                "SELECT storage_path FROM file_chunks WHERE file_id = ?", String.class, fileId);
        Files.write(STORAGE_ROOT.resolve(path), new byte[]{8, 6, 4, 2});

        assertThrows(FileService.FileIntegrityException.class, () -> files.download(fileId, user.getId()));
    }

    @Test
    void continuesToReadLegacyUnencryptedChunks() throws Exception {
        UserEntity user = createUser("legacy@example.test");
        byte[] content = {6, 5, 4};
        String hash = ChunkingService.sha256(content);
        Path legacyPath = STORAGE_ROOT.resolve("cloud2/file-legacy/chunk-0.bin");
        Files.createDirectories(legacyPath.getParent());
        Files.write(legacyPath, content);
        jdbc.update("""
                INSERT INTO files (
                    user_id, original_filename, stored_filename, file_size, content_type,
                    file_sha256, total_chunks, upload_status, created_at
                ) VALUES (?, 'legacy.bin', 'legacy-id', ?, 'application/octet-stream',
                          NULL, 1, 'UPLOADED', CURRENT_TIMESTAMP)
                """, user.getId(), content.length);
        long fileId = jdbc.queryForObject("SELECT MAX(id) FROM files", Long.class);
        jdbc.update("""
                INSERT INTO chunk_metadata (chunk_hash, chunk_size, reference_count, created_at)
                VALUES (?, ?, 1, CURRENT_TIMESTAMP)
                """, hash, content.length);
        jdbc.update("""
                INSERT INTO file_chunks (
                    file_id, chunk_number, chunk_hash, chunk_size, cloud_provider_id,
                    storage_path, encryption_status, encryption_iv, encryption_key_id, created_at
                ) VALUES (?, 0, ?, ?, 2, 'cloud2/file-legacy/chunk-0.bin',
                          'NOT_ENCRYPTED', NULL, NULL, CURRENT_TIMESTAMP)
                """, fileId, hash, content.length);

        assertArrayEquals(content, files.download(fileId, user.getId()).content());
    }

    private UserEntity createUser(String email) {
        UserEntity user = new UserEntity();
        user.setUsername("user-" + UUID.randomUUID());
        user.setFullName("Test User");
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return users.saveAndFlush(user);
    }

    private UserEntity createAdmin(String email) {
        UserEntity admin = createUser(email);
        admin.setRole(UserRole.ADMIN);
        return users.saveAndFlush(admin);
    }

    private MockHttpSession authenticatedSession(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String csrf = csrf(session);
        mvc.perform(post("/api/auth/login")
                        .session(session)
                        .header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().isOk());
        return session;
    }

    private String csrf(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode token = objectMapper.readTree(result.getResponse().getContentAsString());
        return token.get("token").asText();
    }

    private static long sumChunkCounts(JsonNode providers) {
        long total = 0;
        for (JsonNode provider : providers) {
            total += provider.get("chunk_count") != null
                    ? provider.get("chunk_count").asLong()
                    : provider.get("chunkCount").asLong();
        }
        return total;
    }

    private long storedChunkCount() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) {
            return 0;
        }
        try (Stream<Path> stored = Files.walk(STORAGE_ROOT)) {
            return stored.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".chunk"))
                    .count();
        }
    }

    private static String generatedKeyRing() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String result = TEST_KEY_ID + "=" + Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        return result;
    }

    private void clearStoredChunks() {
        if (!Files.exists(STORAGE_ROOT)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not clear temporary test storage.", e);
        }
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static byte[] zipBytes(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) {
            throw new IOException("JPEG encoder is unavailable.");
        }
        return output.toByteArray();
    }

    private record FormatFixture(String filename, String contentType, byte[] content) {}
}
