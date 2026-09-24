package com.loresentry.content.project;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 프로젝트의 "마지막 작업 시각"을 올린다.
 *
 * <p>프로젝트 목록은 최근 작업 순이다(요구사항 §2.2). 그런데 그 시각이 프로젝트 자체를 고칠 때만
 * 움직이면, 원고를 한 시간 썼는데도 목록에서는 이름만 바꾼 다른 프로젝트가 위에 남는다. 사용자가
 * 인식하는 "작업"은 문서 편집이므로 파일·문서를 바꿀 때도 같은 트랜잭션에서 올린다.
 *
 * <p>별도 컴포넌트로 둔 이유는 문서·파일·버전 세 곳이 같은 일을 해야 하고, 그때마다 프로젝트
 * 저장소를 끌어오면 도메인 사이에 양방향 의존이 생기기 때문이다.
 */
@Component
public class ProjectActivity {

    private final JdbcClient jdbcClient;

    public ProjectActivity(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 프로젝트를 직접 안다. */
    public void touch(UUID projectId) {
        jdbcClient.sql("update projects set updated_at = now() where id = :id")
                .param("id", projectId)
                .update();
    }

    /** 문서만 아는 호출자를 위해. 소유권은 호출자가 이미 확인했다. */
    public void touchByDocument(UUID documentId) {
        jdbcClient
                .sql("update projects set updated_at = now() "
                        + "where id = (select project_id from document where id = :document)")
                .param("document", documentId)
                .update();
    }
}
