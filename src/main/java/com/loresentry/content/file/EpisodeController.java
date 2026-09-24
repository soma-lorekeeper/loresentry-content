package com.loresentry.content.file;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 에피소드는 문서가 아니므로 {@code /files}와 경로를 나눈다. 같은 네임스페이스에 두면 클라이언트가
 * 어떤 id가 문서인지 폴더인지 알아야 요청을 만들 수 있다.
 */
@RestController
public class EpisodeController {

    private final FileService files;

    public EpisodeController(FileService files) {
        this.files = files;
    }

    @PatchMapping("/episodes/{episodeId}")
    public FileResponses.Episode rename(
            @CurrentUser UUID userId,
            @PathVariable UUID episodeId,
            @RequestBody FileRequests.Rename request) {
        return files.renameEpisode(userId, episodeId, request);
    }

    @DeleteMapping("/episodes/{episodeId}")
    public ResponseEntity<Void> delete(@CurrentUser UUID userId, @PathVariable UUID episodeId) {
        files.deleteEpisode(userId, episodeId);
        return ResponseEntity.noContent().build();
    }
}
