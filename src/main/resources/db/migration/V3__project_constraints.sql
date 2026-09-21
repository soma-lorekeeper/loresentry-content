ALTER TABLE projects ALTER COLUMN name TYPE VARCHAR(255);

CREATE UNIQUE INDEX uq_projects_owner_active_name
    ON projects (owner_user_id, lower(name))
    WHERE trashed_at IS NULL;

ALTER TABLE document DROP CONSTRAINT fk_document_project;
ALTER TABLE document ADD CONSTRAINT fk_document_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE;

ALTER TABLE episode_folders DROP CONSTRAINT fk_episode_folders_project;
ALTER TABLE episode_folders ADD CONSTRAINT fk_episode_folders_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE;

ALTER TABLE refresh_runs DROP CONSTRAINT fk_refresh_runs_project;
ALTER TABLE refresh_runs ADD CONSTRAINT fk_refresh_runs_project
    FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE;
