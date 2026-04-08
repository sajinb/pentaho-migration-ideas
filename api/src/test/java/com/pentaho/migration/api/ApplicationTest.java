package com.pentaho.migration.api;

import com.pentaho.migration.api.model.ExecutionRecord;
import com.pentaho.migration.api.service.ExecutionService;
import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests: verifies the Spring context loads and the REST endpoints respond correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationTest {

    @LocalServerPort  int port;
    @Autowired TestRestTemplate rest;
    @Autowired ExecutionService executionService;

    @TempDir static Path tmp;  // static so it survives across test methods

    // -------------------------------------------------------------------------
    // 1. Context loads (implicit — if Spring fails to start, all tests fail)
    // -------------------------------------------------------------------------

    @Test
    void contextLoads() {
        // passes if Spring Boot starts without error
    }

    // -------------------------------------------------------------------------
    // 2. POST /api/transformations/run → 202 + execution record
    // -------------------------------------------------------------------------

    @Test
    void postTransformationRun_returns202() throws Exception {
        Path input  = tmp.resolve("smoke_in.csv");
        Path output = tmp.resolve("smoke_out.csv");
        Files.writeString(input, "name\nAlice\nBob\n");

        TransformationDefinition def = pipeline(input.toString(), output.toString());

        ResponseEntity<ExecutionRecord> response = rest.postForEntity(
                "http://localhost:" + port + "/api/transformations/run",
                def,
                ExecutionRecord.class);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getId());
        assertEquals("transformation", response.getBody().getType());
    }

    // -------------------------------------------------------------------------
    // 3. GET /api/executions/{id} → eventually COMPLETED
    // -------------------------------------------------------------------------

    @Test
    void getExecution_returnsFinalStatus() throws Exception {
        Path input  = tmp.resolve("poll_in.csv");
        Path output = tmp.resolve("poll_out.csv");
        Files.writeString(input, "name\nCarol\nDave\n");

        // Submit via service directly (faster than HTTP for this test)
        ExecutionRecord rec = executionService.submitTransformation(
                pipeline(input.toString(), output.toString()));

        // Poll until terminal state (max 5 s)
        ExecutionRecord.Status status = null;
        for (int i = 0; i < 50; i++) {
            ResponseEntity<ExecutionRecord> r = rest.getForEntity(
                    "http://localhost:" + port + "/api/executions/" + rec.getId(),
                    ExecutionRecord.class);
            assertEquals(HttpStatus.OK, r.getStatusCode());
            status = r.getBody().getStatus();
            if (status == ExecutionRecord.Status.COMPLETED
                    || status == ExecutionRecord.Status.FAILED) break;
            Thread.sleep(100);
        }
        assertEquals(ExecutionRecord.Status.COMPLETED, status,
                "Execution did not complete in time");
        assertTrue(Files.exists(output));
    }

    // -------------------------------------------------------------------------
    // 4. GET /api/executions/{unknown} → 404
    // -------------------------------------------------------------------------

    @Test
    void getExecution_unknownId_returns404() {
        ResponseEntity<String> r = rest.getForEntity(
                "http://localhost:" + port + "/api/executions/does-not-exist",
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    // -------------------------------------------------------------------------
    // 5. GET /api/executions → list (may be empty or contain prior test runs)
    // -------------------------------------------------------------------------

    @Test
    void listExecutions_returns200() {
        ResponseEntity<List> r = rest.getForEntity(
                "http://localhost:" + port + "/api/executions",
                List.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertNotNull(r.getBody());
    }

    // -------------------------------------------------------------------------
    // 6. Actuator health endpoint is reachable
    // -------------------------------------------------------------------------

    @Test
    void actuatorHealth_returns200() {
        ResponseEntity<Map> r = rest.getForEntity(
                "http://localhost:" + port + "/actuator/health",
                Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("UP", r.getBody().get("status"));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static TransformationDefinition pipeline(String inputPath, String outputPath) {
        StepDefinition src = new StepDefinition();
        src.id = "src"; src.type = "CsvInput";
        src.params = Map.of("filePath", inputPath);

        StepDefinition out = new StepDefinition();
        out.id = "out"; out.type = "TextFileOutput";
        out.params = Map.of("filePath", outputPath);

        HopDefinition hop = new HopDefinition();
        hop.from = "src"; hop.to = "out"; hop.enabled = true;

        TransformationDefinition def = new TransformationDefinition();
        def.name = "smoke"; def.steps = List.of(src, out); def.hops = List.of(hop);
        return def;
    }
}
