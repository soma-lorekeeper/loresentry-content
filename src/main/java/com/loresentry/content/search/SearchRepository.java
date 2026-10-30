package com.loresentry.content.search;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.loresentry.content.manuscript.Manuscripts;
import com.loresentry.content.manuscript.Purpose;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {

    private static final String DOCUMENTS = """
            select d.id, d.project_id, d.title, f.code as folder_code, e.name as episode_name,
                   d.body_text_enc, d.updated_at
            from document d
            join base_folders f on f.id = d.folder_id
            left join episode_folders e on e.id = d.episode_id
            join projects p on p.id = d.project_id
            where d.project_id = :project
              and p.owner_user_id = :owner
              and d.trashed_at is null
            """;

    private final JdbcClient jdbcClient;

    private final Manuscripts manuscripts;

    public SearchRepository(JdbcClient jdbcClient, Manuscripts manuscripts) {
        this.jdbcClient = jdbcClient;
        this.manuscripts = manuscripts;
    }

    record Row(UUID id, String title, String folderCode, String episodeName, String bodyText,
            OffsetDateTime updatedAt) {
    }

    List<Row> search(UUID ownerUserId, UUID projectId, String query, int limit) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<Ranked> ranked = new ArrayList<>();
        jdbcClient.sql(DOCUMENTS)
                .param("project", projectId)
                .param("owner", ownerUserId)
                .query(rows -> {
                    UUID id = rows.getObject("id", UUID.class);
                    String title = rows.getString("title");
                    String body = manuscripts.open(rows.getObject("project_id", UUID.class), id, Purpose.BODY_TEXT,
                            rows.getBytes("body_text_enc"));
                    String lowerTitle = title.toLowerCase(Locale.ROOT);
                    int bucket = lowerTitle.equals(needle) ? 0
                            : lowerTitle.contains(needle) ? 1
                            : body.toLowerCase(Locale.ROOT).contains(needle) ? 2
                            : -1;
                    if (bucket >= 0) {
                        ranked.add(new Ranked(bucket, new Row(id, title, rows.getString("folder_code"),
                                rows.getString("episode_name"), body,
                                rows.getObject("updated_at", OffsetDateTime.class))));
                    }
                });
        return ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::bucket)
                        .thenComparing(item -> item.row().updatedAt(), Comparator.reverseOrder()))
                .limit(limit)
                .map(Ranked::row)
                .toList();
    }

    private record Ranked(int bucket, Row row) {
    }
}
