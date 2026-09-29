package com.intelli.rfid.wayside.tagtool;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/tags/**}: COMMISSION scope by core's ScopeRules (writing tags is destructive).
 * A {@code ReaderException} maps to 409 through core's handler; a bad argument is a 400 here.
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagToolController {

    private final TagToolService tags;

    public TagToolController(TagToolService tags) {
        this.tags = tags;
    }

    @GetMapping("/scan")
    public List<TagToolService.ScannedTag> scan() {
        return tags.scan();
    }

    public record WriteRequest(String tid, String epc) {}

    @PostMapping("/epc")
    public TagToolService.WriteResult write(@RequestBody WriteRequest request) {
        return tags.write(request.tid(), request.epc());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", e.getMessage()));
    }
}
