package com.loresentry.content.user;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserDataRepository {

    private final JdbcClient jdbcClient;

    UserDataRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 휴지통 프로젝트와 PENDING 이미지도 포함한다. 업로드만 끝나고 확인되지 않은 객체도 S3 에 있다. */
    List<String> imageKeysOf(UUID ownerUserId) {
        return jdbcClient
                .sql("""
                        select i.s3_key
                        from image i
                        join projects p on p.id = i.project_id
                        where p.owner_user_id = :owner
                        """)
                .param("owner", ownerUserId)
                .query(String.class)
                .list();
    }

    /** 문서·버전·에피소드·이미지 행·메모·즐겨찾기·최신화·작업공간은 외래 키 CASCADE 가 지운다. */
    int deleteProjects(UUID ownerUserId) {
        return jdbcClient.sql("delete from projects where owner_user_id = :owner")
                .param("owner", ownerUserId)
                .update();
    }

    /** 작업공간 행은 사용자 키도 갖는다. 남의 프로젝트를 열었던 기록이 생겨도 함께 지운다. */
    int deleteWorkspaceStates(UUID ownerUserId) {
        return jdbcClient.sql("delete from workspace_state where owner_user_id = :owner")
                .param("owner", ownerUserId)
                .update();
    }

    int deleteFeedback(UUID userId) {
        return jdbcClient.sql("delete from feedback where user_id = :user")
                .param("user", userId)
                .update();
    }
}
