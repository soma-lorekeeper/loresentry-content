package com.loresentry.content.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Domain writes and their immutable snapshots share the caller's transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ContentEvents {
    private final JdbcClient jdbc;

    public ContentEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Acquired before domain writes; released at commit, so each owner's seq follows commit order.
     */
    public void lock(UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:owner,7102401))")
                .param("owner", "content-owner:" + userId)
                .query((rs, n) -> 1)
                .single();
    }

    public List<UUID> neighbors(UUID fileId) {
        return jdbc.sql(
                        """
select case when low_document_id = :id then high_document_id else low_document_id end
from document_relations where low_document_id = :id or high_document_id = :id
""")
                .param("id", fileId)
                .query(UUID.class)
                .list();
    }

    public void fileChanged(UUID fileId, String kind) {
        UUID eventId =
                jdbc.sql(
                                """
insert into outbox_events (event_type, aggregate_id, event_key, payload)
select 'FileChanged', d.id, d.project_id::text,
    jsonb_build_object('project_id',d.project_id,'file_id',d.id,
        'revision_no',d.revision_no,'change_type',case when d.trashed_at is not null then 'TRASHED' else :kind end,
        'changed_fields',jsonb_build_array('TITLE','BODY','FOLDER','RELATIONS'),
        'file',case when d.trashed_at is not null then null else jsonb_build_object(
            'title',d.title,'folder_code',f.code,'body_text',d.body_text,
            'description',coalesce((select text_value from document_properties
                where document_id=d.id and property_key='description' order by position limit 1),'')) end,
        'relations',case when d.trashed_at is not null then null else coalesce((
            select jsonb_agg(jsonb_build_object('relation_id',r.id,
                'target_document_id',case when r.low_document_id=d.id then r.high_document_id else r.low_document_id end,
                'description',r.description) order by r.id)
            from document_relations r where r.low_document_id=d.id or r.high_document_id=d.id),'[]'::jsonb) end)
from document d join base_folders f on f.id=d.folder_id where d.id=:id
returning id
""")
                        .param("id", fileId)
                        .param("kind", kind)
                        .query(UUID.class)
                        .single();
        jdbc.sql(
                        """
                        insert into projection_heads(project_id,file_id,event_id)
                        select project_id,id,:event from document where id=:id
                        on conflict(project_id,file_id) do update set event_id=excluded.event_id
                        """)
                .param("id", fileId)
                .param("event", eventId)
                .update();
    }

    public void fileDeleted(UUID projectId, UUID fileId) {
        UUID event =
                jdbc.sql(
                                """
insert into outbox_events(event_type,aggregate_id,event_key,payload)
values('FileChanged',:file,:project::text,jsonb_build_object(
    'project_id',:project::text,'file_id',:file::text,'change_type','DELETED',
    'file',null,'relations',null,'changed_fields',jsonb_build_array('RELATIONS')))
returning id
""")
                        .param("file", fileId)
                        .param("project", projectId)
                        .query(UUID.class)
                        .single();
        jdbc.sql(
                        """
insert into projection_heads(project_id,file_id,event_id) values(:project,:file,:event)
on conflict(project_id,file_id) do update set event_id=excluded.event_id
""")
                .param("project", projectId)
                .param("file", fileId)
                .param("event", event)
                .update();
    }

    public void projectDeleted(UUID projectId, String reason) {
        jdbc.sql(
                        """
insert into outbox_events(event_type,aggregate_id,event_key,payload)
values('ProjectDeleted',:id,:id::text,jsonb_build_object('project_id',:id::text,'reason',:reason))
""")
                .param("id", projectId)
                .param("reason", reason)
                .update();
    }

    public void seedMissing(UUID projectId) {
        jdbc.sql(
                        "select d.id from document d left join projection_heads h on h.file_id=d.id"
                            + " and h.project_id=d.project_id where d.project_id=:project and"
                            + " h.event_id is null order by d.id")
                .param("project", projectId)
                .query(UUID.class)
                .list()
                .forEach(id -> fileChanged(id, "UPDATED"));
    }

    public List<UUID> projects(UUID userId) {
        return jdbc.sql("select id from projects where owner_user_id=:user order by id")
                .param("user", userId)
                .query(UUID.class)
                .list();
    }

    public void userDeleted(UUID userId) {
        jdbc.sql("select id from projects where owner_user_id=:user order by id")
                .param("user", userId)
                .query(UUID.class)
                .list()
                .forEach(id -> projectDeleted(id, "OWNER_DELETED"));
    }
}
