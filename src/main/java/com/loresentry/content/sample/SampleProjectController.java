package com.loresentry.content.sample;

import java.net.URI;
import java.util.UUID;

import com.loresentry.content.project.Project;
import com.loresentry.content.project.ProjectResponse;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SampleProjectController {

    private final SampleProjectService samples;

    public SampleProjectController(SampleProjectService samples) {
        this.samples = samples;
    }

    /**
     * 응답은 {@code POST /projects} 와 같다. 화면이 만든 프로젝트와 예시를 구분할 이유가 없다.
     *
     * <p>{@code locale=en} 이면 영어 예시를 만든다. 없거나 다른 값이면 한국어 예시다.
     */
    @PostMapping("/projects/sample")
    public ResponseEntity<ProjectResponse> create(
            @CurrentUser UUID userId,
            @RequestParam(name = "locale", required = false) String locale) {
        Project project = samples.create(userId, locale);
        return ResponseEntity.created(URI.create("/projects/" + project.id()))
                .body(ProjectResponse.from(project));
    }
}
