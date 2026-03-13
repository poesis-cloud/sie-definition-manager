package io.poesis.sie.defman.controller;

import com.fasterxml.jackson.databind.JsonNode;
import io.poesis.sie.defman.service.OpenApiService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin transport wrapper for the dynamic OpenAPI specification. */
@RestController
@RequestMapping("/api/v1/openapi")
public class OpenApiController extends AbstractController {

    private final OpenApiService openApiService;

    public OpenApiController(OpenApiService openApiService) {
        this.openApiService = openApiService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> getOpenApi() {
        return ResponseEntity.ok(openApiService.buildOpenApi());
    }
}
