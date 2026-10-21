package com.loresentry.content.outbox;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** gateway 에 노출하지 않는 내부 경로다. 운영자가 port-forward 로 직접 부른다. */
@RestController
class FileEventReplayController {

    private static final int MAX_PROJECTS = 100;

    private final FileEventReplay replay;

    FileEventReplayController(FileEventReplay replay) {
        this.replay = replay;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Result(int projects, int documents, UUID last) {
    }

    @PostMapping("/internal/admin/file-events/replay")
    public ResponseEntity<Result> replay(@RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "20") int limit) {
        List<UUID> projects = replay.projectsAfter(after, Math.clamp(limit, 1, MAX_PROJECTS));
        int documents = 0;
        for (UUID project : projects) {
            documents += replay.replay(project);
        }
        UUID last = projects.isEmpty() ? null : projects.getLast();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Result(projects.size(), documents, last));
    }
}
