package com.loresentry.content.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V10 은 {@code document_relations} 의 칸을 <b>지운다.</b> 합치는 규칙이 틀리면 사용자가 이어 둔
 * 관계가 영구히 사라지고, 되돌릴 자리가 없다. 그래서 예전 모양 그대로 행을 넣어 두고 실제로
 * 마이그레이션을 돌려 본다 — 이미 끝난 스키마에서는 이 경로를 다시 밟을 수 없다.
 */
@Testcontainers
class RelationCollapseTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final String MANUSCRIPT = "11111111-1111-7111-8111-111111111111";

    private static final String CHARACTER = "22222222-2222-7222-8222-222222222222";

    private static final String PLACE = "33333333-3333-7333-8333-333333333333";

    private static Flyway upTo(String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    private static List<String> column(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    @BeforeAll
    static void migrateOverLegacyRows() throws Exception {
        assertThat(upTo("9").migrate().success).isTrue();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement, "insert into projects (owner_user_id, name) "
                    + "values (uuidv7(), 'Glass Garden') returning id").getFirst();
            for (String[] document : new String[][] {
                    {MANUSCRIPT, "4", "제1화 회귀"}, {CHARACTER, "2", "유중혁"}, {PLACE, "3", "충무로역"}}) {
                statement.execute("insert into document (id, project_id, folder_id, title, rank) values ('"
                        + document[0] + "', '" + project + "', " + document[1] + ", '"
                        + document[2] + "', 'a" + document[1] + "')");
            }

            // 미러링이 하던 일: 같은 관계가 양쪽에 한 행씩. 설명은 한쪽에만 적혀 있을 수 있다.
            statement.execute("insert into document_relations "
                    + "(document_id, relation_key, target_document_id, description, position) values "
                    + "('" + MANUSCRIPT + "', 'related_character', '" + CHARACTER + "', '', 10), "
                    + "('" + CHARACTER + "', 'related_manuscript', '" + MANUSCRIPT + "', '첫 등장', 10), "
                    // 미러링 이전에 만들어져 한쪽만 남은 관계.
                    + "('" + CHARACTER + "', 'related_place', '" + PLACE + "', '자주 머문다', 20), "
                    // 쌍이 아닌 행. 지금 API 는 막지만 예전 행이 있을 수 있다.
                    + "('" + PLACE + "', 'related_place', '" + PLACE + "', '', 30)");
        }

        assertThat(upTo("10").migrate().success).isTrue();
    }

    @Test
    void leavesOneRowPerPair() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement, "select count(*)::text from document_relations"))
                    .containsExactly("2");
        }
    }

    @Test
    void keepsTheDescriptionThatWasWrittenOnEitherSide() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // 설명은 연결의 것이다. 양쪽 행 중 한쪽에만 적혀 있어도 합친 행이 그것을 가진다.
            assertThat(column(statement, "select description from document_relations where "
                    + "low_document_id = least('" + MANUSCRIPT + "'::uuid, '" + CHARACTER + "'::uuid) and "
                    + "high_document_id = greatest('" + MANUSCRIPT + "'::uuid, '" + CHARACTER + "'::uuid)"))
                    .containsExactly("첫 등장");
        }
    }

    @Test
    void keepsARelationThatOnlyEverHadOneRow() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement, "select description from document_relations where "
                    + "low_document_id = least('" + CHARACTER + "'::uuid, '" + PLACE + "'::uuid) and "
                    + "high_document_id = greatest('" + CHARACTER + "'::uuid, '" + PLACE + "'::uuid)"))
                    .containsExactly("자주 머문다");
        }
    }

    @Test
    void refusesToStoreThePairTwice() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // 뒤집어 넣는 것은 유니크가 아니라 순서 규칙이 막는다.
            assertThat(column(statement, "select count(*)::text from pg_constraint "
                    + "where conname = 'ck_document_relations_pair'")).containsExactly("1");
        }
    }

    /** 분류마다 관계 키가 하나씩 있어야 한다. 비어 있으면 그 분류의 관계가 읽을 때 사라진다. */
    @Test
    void givesEveryFolderARelationKey() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement,
                    "select code || '=' || relation_key from base_folders order by id"))
                    .containsExactly("WORLDVIEW=related_worldview", "CHARACTER=related_character",
                            "LOCATION=related_place", "MANUSCRIPT=related_manuscript",
                            "ORGANIZATION=related_organization", "ITEM=related_item",
                            "EVENT=related_event");
        }
    }
}
