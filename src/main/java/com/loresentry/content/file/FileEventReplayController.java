package com.loresentry.content.file;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;

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
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(name = "include_trashed", defaultValue = "false") boolean includeTrashed) {
        // after 는 이어서 돌릴 위치(이 id 다음 프로젝트부터)라 참조 param 이다
        AppLogger.current().info("file event replay requested", LogField.param("after_project_id", after),
                LogField.param("limit", limit), LogField.param("include_trashed", includeTrashed));
        List<UUID> projects = replay.projectsAfter(after, Math.clamp(limit, 1, MAX_PROJECTS), includeTrashed);
        int documents = 0;
        for (UUID project : projects) {
            documents += replay.replay(project);
        }
        UUID last = projects.isEmpty() ? null : projects.getLast();
        AppLogger.current().info("file event replay completed", LogField.result("projects", projects.size()),
                LogField.result("documents", documents), LogField.result("last_project_id", last));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Result(projects.size(), documents, last));
    }
}
