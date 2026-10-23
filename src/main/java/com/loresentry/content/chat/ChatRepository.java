package com.loresentry.content.chat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ChatRepository {

    private final JdbcClient jdbcClient;

    ChatRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    boolean projectExists(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner"
                        + " and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query(Integer.class).optional().isPresent();
    }

    Optional<ChatResponses.Project> project(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select id, name, description from projects"
                        + " where id = :id and owner_user_id = :owner and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query((rows, index) -> new ChatResponses.Project(
                        rows.getObject("id", UUID.class),
                        rows.getString("name"),
                        rows.getString("description")))
                .optional();
    }

    List<ChatResponses.Manuscript> manuscripts(UUID projectId,
            Map<UUID, List<ChatResponses.Property>> properties) {
        return jdbcClient
                .sql("""
                        select d.id, d.title, e.name as episode_name, d.revision_no, d.body_text,
                               d.updated_at
                        from document d
                        left join episode_folders e on e.id = d.episode_id
                        where d.project_id = :project and d.folder_id = 4 and d.trashed_at is null
                        order by (e.id is null), e.rank, e.id, d.rank, d.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    return new ChatResponses.Manuscript(
                            id,
                            rows.getString("title"),
                            index + 1,
                            rows.getString("episode_name"),
                            properties.getOrDefault(id, List.of()),
                            rows.getLong("revision_no"),
                            rows.getString("body_text"),
                            rows.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }

    List<ChatResponses.Setting> settings(UUID projectId,
            Map<UUID, List<ChatResponses.Property>> properties) {
        return jdbcClient
                .sql("""
                        select d.id, f.code as folder_code, d.title, d.revision_no, d.body_text,
                               d.updated_at
                        from document d
                        join base_folders f on f.id = d.folder_id
                        where d.project_id = :project and d.folder_id <> 4 and d.trashed_at is null
                        order by f.position, d.rank, d.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    return new ChatResponses.Setting(
                            id,
                            rows.getString("folder_code"),
                            rows.getString("title"),
                            properties.getOrDefault(id, List.of()),
                            rows.getLong("revision_no"),
                            rows.getString("body_text"),
                            rows.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }

    Map<UUID, List<ChatResponses.Property>> properties(UUID projectId) {
        Map<UUID, List<ChatResponses.Property>> byDocument = new LinkedHashMap<>();
        jdbcClient
                .sql("""
                        select p.document_id, p.property_key, p.text_value
                        from document_properties p
                        join document d on d.id = p.document_id
                        where d.project_id = :project and d.trashed_at is null
                          and p.text_value <> ''
                        order by p.document_id, p.position, p.property_key, p.id
                        """)
                .param("project", projectId)
                .query(rows -> {
                    byDocument.computeIfAbsent(rows.getObject("document_id", UUID.class),
                                    id -> new ArrayList<>())
                            .add(new ChatResponses.Property(
                                    rows.getString("property_key"), rows.getString("text_value")));
                });
        return byDocument;
    }

    List<ChatResponses.Relation> relations(UUID projectId) {
        return jdbcClient
                .sql("""
                        select r.low_document_id, r.high_document_id, r.description
                        from document_relations r
                        join document low on low.id = r.low_document_id
                        join document high on high.id = r.high_document_id
                        where low.project_id = :project and low.trashed_at is null
                          and high.project_id = :project and high.trashed_at is null
                        order by r.low_document_id, r.high_document_id
                        """)
                .param("project", projectId)
                .query((rows, index) -> new ChatResponses.Relation(
                        rows.getObject("low_document_id", UUID.class),
                        rows.getObject("high_document_id", UUID.class),
                        rows.getString("description")))
                .list();
    }

    List<ChatResponses.Memo> memos(UUID projectId) {
        return jdbcClient
                .sql("""
                        select m.id, m.scope, m.document_id, m.title, m.body, m.updated_at
                        from memo m
                        where m.project_id = :project
                          and (m.scope = 'PROJECT'
                               or (m.scope = 'FILE' and exists (
                                       select 1 from document d
                                       where d.id = m.document_id and d.project_id = :project
                                         and d.trashed_at is null)))
                        order by m.created_at, m.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> new ChatResponses.Memo(
                        rows.getObject("id", UUID.class),
                        rows.getString("scope"),
                        rows.getObject("document_id", UUID.class),
                        rows.getString("title"),
                        rows.getString("body"),
                        rows.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    List<ChatResponses.MapManuscript> mapManuscripts(UUID projectId,
            Map<UUID, List<ChatResponses.Property>> properties) {
        return jdbcClient
                .sql("""
                        select d.id, d.title, e.name as episode_name, d.revision_no,
                               char_length(d.title) + char_length(d.body_text) as base_chars, d.updated_at
                        from document d
                        left join episode_folders e on e.id = d.episode_id
                        where d.project_id = :project and d.folder_id = 4 and d.trashed_at is null
                        order by (e.id is null), e.rank, e.id, d.rank, d.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    List<ChatResponses.Property> own = properties.getOrDefault(id, List.of());
                    return new ChatResponses.MapManuscript(
                            id,
                            rows.getString("title"),
                            index + 1,
                            rows.getString("episode_name"),
                            own,
                            rows.getLong("revision_no"),
                            rows.getLong("base_chars") + propertyChars(own),
                            rows.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }

    List<ChatResponses.MapSetting> mapSettings(UUID projectId,
            Map<UUID, List<ChatResponses.Property>> properties) {
        return jdbcClient
                .sql("""
                        select d.id, f.code as folder_code, d.title, d.revision_no,
                               char_length(d.title) + char_length(d.body_text) as base_chars, d.updated_at
                        from document d
                        join base_folders f on f.id = d.folder_id
                        where d.project_id = :project and d.folder_id <> 4 and d.trashed_at is null
                        order by f.position, d.rank, d.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    List<ChatResponses.Property> own = properties.getOrDefault(id, List.of());
                    return new ChatResponses.MapSetting(
                            id,
                            rows.getString("folder_code"),
                            rows.getString("title"),
                            own,
                            rows.getLong("revision_no"),
                            rows.getLong("base_chars") + propertyChars(own),
                            rows.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }

    List<ChatResponses.MapMemo> mapMemos(UUID projectId) {
        return jdbcClient
                .sql("""
                        select m.id, m.scope, m.document_id, m.title,
                               coalesce(char_length(m.title), 0) + char_length(m.body) as chars, m.updated_at
                        from memo m
                        where m.project_id = :project
                          and (m.scope = 'PROJECT'
                               or (m.scope = 'FILE' and exists (
                                       select 1 from document d
                                       where d.id = m.document_id and d.project_id = :project
                                         and d.trashed_at is null)))
                        order by m.created_at, m.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> new ChatResponses.MapMemo(
                        rows.getObject("id", UUID.class),
                        rows.getString("scope"),
                        rows.getObject("document_id", UUID.class),
                        rows.getString("title"),
                        rows.getLong("chars"),
                        rows.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    List<ChatResponses.Manuscript> manuscriptsById(UUID projectId, List<UUID> ids,
            Map<UUID, List<ChatResponses.Property>> properties) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient
                .sql("""
                        select * from (
                            select d.id, d.title, e.name as episode_name, d.revision_no, d.body_text,
                                   d.updated_at,
                                   row_number() over (order by (e.id is null), e.rank, e.id, d.rank, d.id)
                                       as ordinal
                            from document d
                            left join episode_folders e on e.id = d.episode_id
                            where d.project_id = :project and d.folder_id = 4 and d.trashed_at is null
                        ) m
                        where m.id in (:ids)
                        order by m.ordinal
                        """)
                .param("project", projectId)
                .param("ids", ids)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    return new ChatResponses.Manuscript(
                            id,
                            rows.getString("title"),
                            rows.getInt("ordinal"),
                            rows.getString("episode_name"),
                            properties.getOrDefault(id, List.of()),
                            rows.getLong("revision_no"),
                            rows.getString("body_text"),
                            rows.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }

    List<ChatResponses.Setting> settingsById(UUID projectId, List<UUID> ids,
            Map<UUID, List<ChatResponses.Property>> properties) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient
                .sql("""
                        select d.id, f.code as folder_code, d.title, d.revision_no, d.body_text,
                               d.updated_at
                        from document d
                        join base_folders f on f.id = d.folder_id
                        where d.project_id = :project and d.folder_id <> 4 and d.trashed_at is null
                          and d.id in (:ids)
                        order by f.position, d.rank, d.id
                        """)
                .param("project", projectId)
                .param("ids", ids)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    return new ChatResponses.Setting(
                            id,
                            rows.getString("folder_code"),
                            rows.getString("title"),
                            properties.getOrDefault(id, List.of()),
                            rows.getLong("revision_no"),
                            rows.getString("body_text"),
                            rows.getObject("updated_at", OffsetDateTime.class));
                })
                .list();
    }

    List<ChatResponses.Memo> memosById(UUID projectId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient
                .sql("""
                        select m.id, m.scope, m.document_id, m.title, m.body, m.updated_at
                        from memo m
                        where m.project_id = :project and m.id in (:ids)
                          and (m.scope = 'PROJECT'
                               or (m.scope = 'FILE' and exists (
                                       select 1 from document d
                                       where d.id = m.document_id and d.project_id = :project
                                         and d.trashed_at is null)))
                        order by m.created_at, m.id
                        """)
                .param("project", projectId)
                .param("ids", ids)
                .query((rows, index) -> new ChatResponses.Memo(
                        rows.getObject("id", UUID.class),
                        rows.getString("scope"),
                        rows.getObject("document_id", UUID.class),
                        rows.getString("title"),
                        rows.getString("body"),
                        rows.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    private static long propertyChars(List<ChatResponses.Property> properties) {
        long chars = 0;
        for (ChatResponses.Property property : properties) {
            chars += property.value().codePointCount(0, property.value().length());
        }
        return chars;
    }

    long size(UUID projectId) {
        return jdbcClient
                .sql("""
                        select
                            (select coalesce(sum(char_length(d.title) + char_length(d.body_text)), 0)
                             from document d
                             where d.project_id = :project and d.trashed_at is null)
                          + (select coalesce(sum(char_length(p.text_value)), 0)
                             from document_properties p
                             join document d on d.id = p.document_id
                             where d.project_id = :project and d.trashed_at is null
                               and p.text_value <> '')
                          + (select coalesce(sum(char_length(r.description)), 0)
                             from document_relations r
                             join document low on low.id = r.low_document_id
                             join document high on high.id = r.high_document_id
                             where low.project_id = :project and low.trashed_at is null
                               and high.project_id = :project and high.trashed_at is null)
                          + (select coalesce(sum(coalesce(char_length(m.title), 0)
                                                 + char_length(m.body)), 0)
                             from memo m
                             where m.project_id = :project
                               and (m.scope = 'PROJECT'
                                    or (m.scope = 'FILE' and exists (
                                            select 1 from document d
                                            where d.id = m.document_id and d.project_id = :project
                                              and d.trashed_at is null))))
                        """)
                .param("project", projectId)
                .query(Long.class)
                .single();
    }
}
