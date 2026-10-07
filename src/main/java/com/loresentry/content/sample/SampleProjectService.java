package com.loresentry.content.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.loresentry.content.document.DocumentService;
import com.loresentry.content.document.DocumentSnapshot;
import com.loresentry.content.file.FileRequests;
import com.loresentry.content.file.FileResponses;
import com.loresentry.content.file.FileService;
import com.loresentry.content.project.CreateProjectRequest;
import com.loresentry.content.project.Project;
import com.loresentry.content.project.ProjectService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 처음 온 사용자가 둘러볼 예시 프로젝트를 만든다.
 *
 * <p>SQL 로 직접 넣지 않고 사용자가 쓰는 것과 같은 서비스를 지난다. 기본 폴더, 순서 값, 이름 중복,
 * 같은 프로젝트 안의 관계, revision 과 자동 버전이 모두 평소 규칙대로 생긴다 — 예시가 실제 문서와
 * 다르게 생기면 예시에서만 나는 버그가 생긴다. 한 트랜잭션이라 중간에 실패하면 반쪽 프로젝트가 남지
 * 않는다.
 *
 * <p>한국어와 영어 두 벌이 있다. 둘 다 기동 때 한 번 읽고 검사해 두고, 요청마다 고르기만 한다.
 */
@Service
public class SampleProjectService {

    private static final String MANUSCRIPT = "MANUSCRIPT";

    private static final String ENGLISH = "en";

    private final ProjectService projects;

    private final FileService files;

    private final DocumentService documents;

    private final SampleSeed korean = SampleSeed.load(SampleSeed.KOREAN);

    private final SampleSeed english = SampleSeed.load(SampleSeed.ENGLISH);

    private final JsonMapper json = JsonMapper.builder().build();

    public SampleProjectService(ProjectService projects, FileService files, DocumentService documents) {
        this.projects = projects;
        this.files = files;
        this.documents = documents;
    }

    @Transactional
    public Project create(UUID ownerUserId, String locale) {
        SampleSeed seed = seedFor(locale);
        Project project = projects.create(ownerUserId,
                new CreateProjectRequest(freeName(ownerUserId, seed.name()), seed.description()));
        UUID projectId = project.id();

        Map<String, Created> created = new HashMap<>();
        List<Pending> order = new ArrayList<>();

        // 설정을 먼저 저장하고 원고를 뒤에 저장한다. 마지막으로 저장한 문서가 "마지막 작업 파일"이
        // 되므로, 예시를 열면 마지막 회차가 보인다.
        for (SampleSeed.Setting setting : seed.settings()) {
            UUID id = createDocument(ownerUserId, projectId, setting.folderCode(), null, setting.title());
            created.put(setting.key(), new Created(id, setting.folderCode()));
            order.add(new Pending(setting.key(), setting.title(), setting.description(), setting.body()));
        }
        for (SampleSeed.Episode episode : seed.episodes()) {
            UUID episodeId = createEpisode(ownerUserId, projectId, episode.title());
            for (SampleSeed.Manuscript manuscript : episode.manuscripts()) {
                UUID id = createDocument(ownerUserId, projectId, MANUSCRIPT, episodeId, manuscript.title());
                created.put(manuscript.key(), new Created(id, MANUSCRIPT));
                order.add(new Pending(manuscript.key(), manuscript.title(), manuscript.description(),
                        manuscript.body()));
            }
        }

        Map<String, Map<String, String>> links = symmetricLinks(seed);
        for (Pending pending : order) {
            List<DocumentSnapshot.Relation> relations = new ArrayList<>();
            links.getOrDefault(pending.key(), Map.of()).forEach((target, description) -> {
                Created other = created.get(target);
                // 관계 키는 서버가 대상 문서의 분류에서 꺼낸다. 보내도 쓰이지 않는다.
                relations.add(new DocumentSnapshot.Relation(null, other.id(), description));
            });
            documents.save(ownerUserId, created.get(pending.key()).id(), 0, null, new DocumentSnapshot(
                    pending.title(), body(pending.body()), null,
                    List.of(new DocumentSnapshot.TextProperty("description", pending.description())),
                    relations));
        }

        return projects.getActive(ownerUserId, projectId);
    }

    /**
     * 로케일은 소문자 {@code ko} 와 {@code en} 뿐이다. {@code en} 이 아니면 없든 모르는 값이든 한국어
     * 예시다 — 로케일을 보내지 않는 예전 프론트와 게이트웨이도 지금까지처럼 한국어 예시를 받는다.
     */
    private SampleSeed seedFor(String locale) {
        return ENGLISH.equals(locale) ? english : korean;
    }

    /**
     * 이미 같은 이름의 활성 프로젝트가 있으면 {@code (2)}, {@code (3)} … 을 붙인다. 이름 비교는
     * 유일 인덱스처럼 대소문자를 가리지 않는다.
     */
    private String freeName(UUID ownerUserId, String base) {
        Set<String> taken = projects.listActive(ownerUserId).stream()
                .map(project -> project.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        String name = base;
        for (int suffix = 2; taken.contains(name.toLowerCase(Locale.ROOT)); suffix++) {
            name = base + " (" + suffix + ")";
        }
        return name;
    }

    /**
     * 저장은 그 문서가 걸린 관계 전체를 들어온 목록과 똑같이 맞춘다. 한 행이 두 문서의 것이므로,
     * 뒤에 저장한 문서가 그 쌍을 빠뜨리면 앞에서 만든 행이 지워진다. 그래서 문서마다 자기가 걸린
     * 관계를 다 들고 저장한다.
     */
    private Map<String, Map<String, String>> symmetricLinks(SampleSeed seed) {
        Map<String, Map<String, String>> links = new HashMap<>();
        for (SampleSeed.Link link : seed.relations()) {
            String description = link.description() == null ? "" : link.description();
            links.computeIfAbsent(link.from(), key -> new LinkedHashMap<>()).put(link.to(), description);
            links.computeIfAbsent(link.to(), key -> new LinkedHashMap<>()).put(link.from(), description);
        }
        return links;
    }

    private UUID createEpisode(UUID ownerUserId, UUID projectId, String title) {
        FileResponses.Episode episode = (FileResponses.Episode) files.create(ownerUserId, projectId,
                new FileRequests.Create("episode", title, null, null));
        return episode.id();
    }

    private UUID createDocument(UUID ownerUserId, UUID projectId, String folderCode, UUID episodeId,
            String title) {
        FileResponses.Document document = (FileResponses.Document) files.create(ownerUserId, projectId,
                new FileRequests.Create("document", title, folderCode, episodeId));
        return document.id();
    }

    /** 빈 줄로 나뉜 문단마다 문단 노드 하나. 프론트의 {@code bodyFromParagraphs} 와 같은 모양이다. */
    private JsonNode body(List<String> paragraphs) {
        ArrayNode content = json.createArrayNode();
        for (String paragraph : paragraphs) {
            ObjectNode block = content.addObject().put("type", "paragraph");
            block.putArray("content").addObject().put("type", "text").put("text", paragraph);
        }
        ObjectNode body = json.createObjectNode();
        body.put("schema_version", 1);
        body.putObject("doc").put("type", "doc").set("content", content);
        return body;
    }

    private record Created(UUID id, String folderCode) {
    }

    private record Pending(String key, String title, String description, List<String> body) {
    }
}
