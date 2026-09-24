package com.loresentry.content.file;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class FileRequests {

    private FileRequests() {
    }

    /**
     * {@code kind}가 {@code document}면 {@code folder_code}가 필요하고, {@code episode}면
     * 원고 아래에 에피소드 폴더를 만든다. 사용자가 만들 수 있는 폴더는 에피소드뿐이다(§4.1).
     */
    public record Create(
            String kind,
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_id") UUID episodeId) {
    }

    public record Rename(String title) {
    }

    /**
     * 옮길 위치와, 그 위치에서 <b>어느 형제 앞에</b> 둘지. {@code before_file_id}가 없으면 맨 끝이다.
     * 순서 값은 서버가 이웃에서 계산한다 — 클라이언트가 보내면 두 클라이언트가 같은 값을 만들 수 있다.
     */
    public record Move(
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_id") UUID episodeId,
            @JsonProperty("before_file_id") UUID beforeFileId) {
    }
}
