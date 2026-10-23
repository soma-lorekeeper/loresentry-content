package com.loresentry.content.refresh;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 한 run 의 열린 작업본 수. 폴링(요약)과 검토 화면이 함께 쓴다.
 *
 * @param total   열린 작업본 수
 * @param matched 두 쪽을 맞춘 작업본 수. total 과 같아야 확정할 수 있다
 * @param created 그중 새 설정 문서 제안 수
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DraftCounts(long total, long matched, @JsonProperty("new") long created) {
}
