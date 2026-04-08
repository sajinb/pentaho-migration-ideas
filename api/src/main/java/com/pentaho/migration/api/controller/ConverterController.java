package com.pentaho.migration.api.controller;

import com.pentaho.migration.converter.PentahoProjectConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * REST endpoint for converting a Pentaho project zip (one KJB + N KTRs)
 * to a zip of equivalent YAML job definitions.
 *
 * <h3>Upload format</h3>
 * <pre>
 * POST /api/convert
 * Content-Type: multipart/form-data
 * file: &lt;zip containing .kjb and .ktr files&gt;
 * </pre>
 *
 * <h3>Response</h3>
 * {@code 200 OK} with {@code Content-Type: application/zip} — a zip whose entries
 * mirror the input structure but with {@code .yaml} extensions.
 */
@RestController
@RequestMapping("/api/convert")
public class ConverterController {

    private final PentahoProjectConverter converter;

    public ConverterController(PentahoProjectConverter converter) {
        this.converter = converter;
    }

    /**
     * Convert an uploaded Pentaho project zip to YAML definitions.
     *
     * @param file multipart zip upload containing {@code .kjb} and/or {@code .ktr} files
     * @return zip of converted {@code .yaml} files
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convert(@RequestParam("file") MultipartFile file)
            throws Exception {

        Path inputZip  = Files.createTempFile("pentaho-in-",  ".zip");
        Path outputZip = Files.createTempFile("pentaho-out-", ".zip");
        try {
            file.transferTo(inputZip);
            converter.convert(inputZip, outputZip);
            byte[] body = Files.readAllBytes(outputZip);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"converted.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(body);
        } finally {
            Files.deleteIfExists(inputZip);
            Files.deleteIfExists(outputZip);
        }
    }
}
