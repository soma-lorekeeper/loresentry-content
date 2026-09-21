package com.loresentry.content.project;

import java.util.List;
import java.util.UUID;

import com.loresentry.content.web.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    /** 요구사항 §2.2. 앞뒤 공백을 제거한 길이 기준이다. */
    static final int NAME_MAX = 255;

    static final int DESCRIPTION_MAX = 500;

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Project> listActive(UUID ownerUserId) {
        return repository.findActive(ownerUserId);
    }

    @Transactional(readOnly = true)
    public List<Project> listTrashed(UUID ownerUserId) {
        return repository.findTrashed(ownerUserId);
    }

    /** 휴지통 프로젝트는 열 수 없으므로(요구사항 §2.2) 조회에서도 없는 것으로 본다. */
    @Transactional(readOnly = true)
    public Project getActive(UUID ownerUserId, UUID projectId) {
        Project project = require(ownerUserId, projectId);
        if (project.isTrashed()) {
            throw notFound();
        }
        return project;
    }

    @Transactional
    public Project create(UUID ownerUserId, CreateProjectRequest request) {
        String name = validateName(request.name());
        String description = validateDescription(request.description());

        return duplicateAware(() -> repository.insert(ownerUserId, name, description));
    }

    @Transactional
    public Project update(UUID ownerUserId, UUID projectId, UpdateProjectRequest request) {
        if (request.name() == null && request.description() == null) {
            throw ApiException.validation("name or description must be provided", null);
        }

        Project current = require(ownerUserId, projectId);
        if (current.isTrashed()) {
            throw notFound();
        }

        String name = request.name() == null ? current.name() : validateName(request.name());
        String description = request.description() == null
                ? current.description()
                : validateDescription(request.description());

        return duplicateAware(() -> repository.update(projectId, name, description));
    }

    /** 이미 휴지통에 있으면 아무것도 하지 않는다. 재시도가 {@code trashed_at}을 밀지 않아야 한다. */
    @Transactional
    public void moveToTrash(UUID ownerUserId, UUID projectId) {
        require(ownerUserId, projectId);
        repository.markTrashed(projectId);
    }

    @Transactional
    public Project restore(UUID ownerUserId, UUID projectId) {
        Project project = require(ownerUserId, projectId);
        if (!project.isTrashed()) {
            return project;
        }

        // 휴지통에 있는 동안 같은 이름으로 새 프로젝트를 만들었을 수 있다. 자동 개명은 하지 않는다 —
        // 사용자가 모르는 사이에 이름이 바뀐다.
        return duplicateAware(() -> repository.markRestored(projectId));
    }

    /** 영구 삭제의 진입점은 휴지통뿐이다(와이어프레임 111–122). 서버도 같은 규칙을 강제한다. */
    @Transactional
    public void deletePermanently(UUID ownerUserId, UUID projectId) {
        Project project = require(ownerUserId, projectId);
        if (!project.isTrashed()) {
            throw ApiException.invalidState("project must be in the trash before it can be deleted");
        }
        repository.delete(projectId);
    }

    private Project require(UUID ownerUserId, UUID projectId) {
        return repository.findById(ownerUserId, projectId).orElseThrow(ProjectService::notFound);
    }

    /**
     * 남의 프로젝트도 404다. 403은 "그 id는 존재한다"를 알려주고, 프론트엔드에도 둘을 구분할
     * 오류 코드가 없다.
     */
    private static ApiException notFound() {
        return ApiException.notFound("project not found");
    }

    /**
     * 중복 이름을 선검사하지 않는다. 선검사는 동시 요청 두 개를 막지 못하고,
     * {@code uq_projects_owner_active_name}이 이미 있으므로 중복 코드다.
     */
    private static Project duplicateAware(java.util.function.Supplier<Project> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException exception) {
            throw ApiException.duplicate("an active project with this name already exists");
        }
    }

    private static String validateName(String raw) {
        if (raw == null) {
            throw ApiException.validation("name is required", "name");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw ApiException.validation("name must not be blank", "name");
        }
        if (name.length() > NAME_MAX) {
            throw ApiException.validation("name must be at most " + NAME_MAX + " characters", "name");
        }
        return name;
    }

    private static String validateDescription(String raw) {
        if (raw == null) {
            return "";
        }
        String description = raw.trim();
        if (description.length() > DESCRIPTION_MAX) {
            throw ApiException.validation(
                    "description must be at most " + DESCRIPTION_MAX + " characters", "description");
        }
        return description;
    }
}
