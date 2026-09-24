# loresentry-content

Content service for Lore Sentry — the core creative-writing domain service.

Owns projects, files and folders, sections and favorites, document bodies and
custom attributes, file references, versions, memos, trash, and the source data
for search. Publishes content change events to Kafka so downstream services such
as `loresentry-graph-rag` can stay in sync.

Reached only through `loresentry-gateway` — this service is `ClusterIP` and has
no route from outside the cluster.

```
Cloudflare → ALB → gateway → content
```

Browsers never reach this service, so it has no CORS configuration — the gateway is
the only CORS boundary.

## Stack

| | Version | Notes |
| --- | --- | --- |
| Java | **21** (LTS) | Virtual threads are stable here. Toolchain-pinned in `build.gradle`. |
| Spring Boot | **4.1.1** | Same line as `loresentry-gateway` and `loresentry-authentication`. |
| Spring Framework | 7.0.9 | Pulled in by Boot 4.1.1. |
| Web stack | `spring-boot-starter-webmvc` | **Servlet MVC, not WebFlux.** Boot 4 renamed the old `-web` starter. |
| Concurrency | Virtual threads | `spring.threads.virtual.enabled=true` |
| Build | Gradle 9.7.1 (wrapper) | No local Gradle install needed — use `./gradlew`. |
| Container base | `eclipse-temurin:21-jdk-alpine` → `21-jre-alpine` | Multi-stage; the runtime image carries only the JRE. |
| Port | 8000 | Platform convention; Spring's own default is 8080. |

Boot 4 moved several test annotations. The one this repo uses is
`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` — not the Boot 3
`org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.

## Endpoints

| Method | Path | Behaviour |
| --- | --- | --- |
| `GET` | `/health` | `{"status":"ok"}`. Used by the Kubernetes probes. |
| `GET` | `/` | `{"service":"content-api"}` |
| `GET` | `/health/db` | Reports whether the PostgreSQL connection works. `503` with the driver error otherwise. |
| `GET` | `/projects` | Active projects, most recently worked first. |
| `GET` | `/projects/trash` | Trashed projects, most recently trashed first. |
| `POST` | `/projects` | Creates a project. `201` with a `Location` header. |
| `GET` | `/projects/{id}` | One active project. A trashed project is `404` — the requirement says it cannot be opened. |
| `PATCH` | `/projects/{id}` | Partial update of `name` and `description`. No optimistic locking: last write wins. |
| `POST` | `/projects/{id}/trash` | Moves to the trash. `204`, and repeating it does not push `trashed_at` forward. |
| `POST` | `/projects/{id}/restore` | Restores. `409 duplicate` when the name now clashes with an active project. |
| `DELETE` | `/projects/{id}` | Permanent delete, **only from the trash** — `409 PROJECT_NOT_TRASHED` otherwise. |
| `GET` | `/projects/{id}/files` | The file tree as three normalised lists: folders, episodes, documents. |
| `GET` | `/projects/{id}/files/trash` | Trashed documents with the folder and episode they came from. |
| `POST` | `/projects/{id}/files` | Creates a document, or an episode folder with `"kind":"episode"`. |
| `PATCH` | `/files/{id}` | Renames a document. |
| `PATCH` | `/files/{id}/position` | Moves and reorders. Names the sibling to insert before. |
| `POST` | `/files/{id}/trash` · `/restore` | Trash and restore. |
| `DELETE` | `/files/{id}` | Permanent delete, only from the trash. |
| `PATCH DELETE` | `/episodes/{id}` | Renames an episode; deleting one keeps its chapters. |
| `GET` | `/files/{id}/content` | Title, body, text properties and relation chips. |
| `PUT` | `/files/{id}/content` | Conditional save. Needs `If-Match`; `X-Save-Id` makes a retry idempotent. |
| `PUT` | `/files/{id}/lock` | Locks or unlocks editing. |
| `GET POST` | `/files/{id}/versions` | Lists versions with their snapshots; `POST` names one. |
| `POST` | `/files/{id}/versions/{vid}/restore` | Restores as a **new** revision. Needs `If-Match`. |
| `DELETE` | `/files/{id}/versions/{vid}` | Deletes a version. |
| `GET` | `/projects/{id}/search?q=` | Title and body search inside one project. |
| `POST` | `/projects/{id}/images` | Issues a presigned upload ticket and records the image as `PENDING`. |
| `POST` | `/projects/{id}/images/{iid}/complete` | Confirms the object against S3 and commits. Idempotent. |
| `GET` | `/projects/{id}/images/{iid}` | One image. `public_url` only once committed. |

The gateway exposes this service publicly at `GET /content`, which calls `/` here
and returns the payload nested under `upstream`. **The project endpoints are not
relayed yet**: `api.loresentry.com` has no authentication, so relaying them would
put an unauthenticated CRUD surface on the public internet. The relay ships with
the gateway's JWT verification.

## Identity

Every `/projects` request carries the caller's user id in the same header
`loresentry-authentication` reads, with the same rejection rules — the gateway
has no reason to label identity differently per service.

```
X-User-Id: <authentication user id (canonical UUID)>
```

This service **trusts the header without verifying it**; verification belongs to
the gateway alone, or the same logic is copied into four services. When the
gateway gains authentication it must strip any client-supplied header of this
name before setting its own — without that line anyone can impersonate any user.

| Header | Result |
| --- | --- |
| Absent | `401 USER_CONTEXT_REQUIRED` — no identity to act on |
| Not a canonical UUID | `400 INVALID_REQUEST` — identity was readable and wrong |
| Present more than once | `400 INVALID_REQUEST` — which one is the gateway's is unknowable |

Everything is scoped to `owner_user_id`. Another owner's project answers
`PROJECT_NOT_FOUND`, not `403`: a `403` confirms the id exists, and the frontend
has no error code that distinguishes the two.

## JSON and errors

Request and response fields use **snake case**, matching
`loresentry-authentication`. Unknown fields and wrongly typed values are
rejected rather than dropped, so a client typo fails loudly instead of looking
like a save that changed nothing.

Errors carry `code`, `message` and `next_action` — the same three fields the
authentication service returns, because both answer the same frontend through
the same gateway. `code` is the contract; `message` is diagnostic English and is
never shown to a user.

```json
{ "code": "PROJECT_NAME_TAKEN", "message": "Project name is already in use.", "next_action": "NONE" }
```

| Status | `code` |
| --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_PROJECT_NAME`, `INVALID_PROJECT_DESCRIPTION`, `INVALID_FILE_TITLE`, `INVALID_FILE_LOCATION`, `INVALID_RELATION_TARGET`, `INVALID_UPLOAD_REQUEST` |
| 401 | `USER_CONTEXT_REQUIRED` |
| 404 | `PROJECT_NOT_FOUND`, `FILE_NOT_FOUND`, `VERSION_NOT_FOUND`, `IMAGE_NOT_FOUND`, `NOT_FOUND` (no such path) |
| 409 | `PROJECT_NAME_TAKEN`, `PROJECT_NOT_TRASHED` |
| 500 | `INTERNAL_ERROR` |

`DOCUMENT_CONFLICT` is the one error that carries more than those three fields.
A conditional save that loses the race answers `409` with `current` (the document
as it now stands) and `base` (the snapshot of the revision the client held, or
`null` when no version kept it), so the client can attempt a three-way merge
without a second request that could race again.

The full specification, including what was deliberately left out and why, is in
the `docs` repository (`CONTENT_PROJECT_API.md`).

## Files, documents and versions

**The tree endpoint returns three normalised lists, not a tree.** Base folders
are global seed rows and episodes are per-project, so a server-built tree would
have to invent ids for category folders that exist in no table — and a client
holding such an id could not use it for anything else. The client composes the
tree, and owns the presentation metadata (labels, per-type icons) that has no
column here.

**Ranks are fractional index strings the server computes.** A move names the
sibling to insert *before*; the server reads the neighbours and picks a value
between them, so one row is updated and the others are never renumbered. Letting
the client send the value would let two clients produce the same one. `rank`
columns use the `C` collation so Java's string comparison and PostgreSQL's
ordering agree.

**Saves are conditional.** `If-Match` carries the revision the client believes it
edited, and it is required — an unconditional save silently overwrites whoever
saved last. `X-Save-Id` is remembered on the row, so a client that never saw the
response can retry without producing a second identical revision.

**Restoring a version is a new revision, not a rewind.** Rewinding would erase
the restore from the history, leaving no way to explain what changed when. The
state being replaced is kept as a `RESTORE` version first, so there is somewhere
to go back to. Auto versions are kept at most every 5 minutes and expire after
30 days; a named version has no expiry.

**Relations are checked against the project.** The foreign key only proves the
target document exists, so it would happily accept a document from another
project. Self-references are refused too — they make a self-loop in the graph and
mean nothing on the timeline.

## Search

Title and body, inside one project, active documents only. Ranking is in SQL:
exact title, then partial title, then body, most recently updated first within
each group. The server cuts the snippet the UI highlights; returning whole bodies
would put every matched document into one list response.

No full-text index. A project holds hundreds of documents, and the requirement is
substring matching. `tsvector` needs a stemmer, and for Korean that choice
changes the results enough that it belongs with the work on search quality
itself.

## Schema

Flyway runs on startup and applies `src/main/resources/db/migration` to the
`content` database. `V2` seeds the seven global base folders. `V4` adds the
per-location title index, `V5` the save-id column and `V6` the `image` table.
`V3` widens
`projects.name` to 255 characters, adds the unique index behind the
duplicate-name rule, and puts `ON DELETE CASCADE` on the three foreign keys
into `projects` — without it a permanent delete fails on a foreign key.

| Table | Purpose |
| --- | --- |
| `projects` | Top-level unit of creative material, owned by an authentication user id |
| `base_folders` | Global folders (worldview, character, location, manuscript, organization, item, event), seeded once |
| `episode_folders` | Per-project episode folders under the manuscript — the only user-created folder |
| `document` | The single source of truth for a document: whole Markdown body, `rank`, `revision_no` for conditional saves |
| `document_properties` | Text properties such as description and alias |
| `document_relations` | Relation chips; the source data for the graph and timeline |
| `document_versions` | Full snapshots: `AUTO`, `NAMED`, `AI_APPLY`, `RESTORE`, `REFRESH_BASE` |
| `refresh_runs` | One graph-refresh run; at most one `CAPTURING_BASE`/`GENERATING` run per project |
| `refresh_document_drafts` | Per-document refresh work: base version, left and right snapshots, draft revision |
| `outbox_events` | Real document changes waiting to be published to Kafka |
| `image` | Uploaded user images. `PENDING` until `HeadObject` confirms the upload |

Rules the database enforces:

- A document with an `episode_id` must be in the manuscript folder, and the
  episode must belong to the same project.
- `OPEN`, `APPLIED` and `STALE` drafts must carry both snapshots and the left
  revision.
- `rank` columns use the `C` collation so fractional-index strings sort bytewise.
- A project name is unique per owner, case-insensitively, **among active
  projects only** — a trashed project frees its name, and restoring it is what
  fails if the name was taken meanwhile.

Users are referenced by id only; there are no cross-database foreign keys.

## Media storage (S3)

User images are meant to go **browser → S3 directly**: this service issues a
short-lived presigned `PUT`, the browser uploads, and this service later confirms
the object exists. The bytes never pass through here.

What exists today is the storage layer only — `media.MediaStorageService` — with
no public endpoint. The domain APIs that will call it (a "give me an upload
ticket" endpoint, a "complete" endpoint, the row that records the image) are
left for the feature work that owns projects and files.

| Method | Does |
| --- | --- |
| `createImageUploadTicket(projectId, contentType, sizeBytes)` | Validates type and size, picks the key `projects/{projectId}/images/{uuid}.{ext}`, returns an `UploadTicket` — presigned PUT URL (5 min), the headers the browser must send, expiry, public URL. |
| `verifyUploaded(key, expectedSizeBytes)` | `HeadObject`; throws `ObjectNotUploadedException` when the object is missing or its size differs. |
| `delete(key)` | `DeleteObject`. |
| `publicUrl(key)` | `media.public-base-url` + key, i.e. the CloudFront URL. |

`Content-Type` and `Content-Length` are part of the signature, so a browser
cannot upload a different type or a larger file than the ticket declares. The
extension comes from the content type, never from a user-supplied file name.
A bad type or size is an `InvalidUploadRequestException`.

Credentials come from the SDK default chain: in the cluster that is **EKS Pod
Identity** on the `content-api` ServiceAccount (no access key anywhere), on a
workstation it is `AWS_PROFILE`. `export AWS_PROFILE=lorekeeper` is enough to
presign against the real bucket locally.

### Configuration

| Property | Env | Default | Notes |
| --- | --- | --- | --- |
| `media.bucket` | `MEDIA_BUCKET` | *(empty)* | `loresentry-media-prod-<account>` in the cluster, from the `media` ConfigMap. |
| `media.region` | `MEDIA_REGION` | `ap-northeast-2` | |
| `media.public-base-url` | `MEDIA_PUBLIC_BASE_URL` | *(empty)* | `https://media.loresentry.com`. |
| `media.upload-url-ttl` | | `5m` | Presigned URL lifetime. Keep it well under the Pod Identity credential lifetime. |
| `media.max-size-bytes` | | `10485760` | 10 MiB. |
| `media.allowed-content-types` | | png, jpeg, webp, gif | |

The AWS resources — bucket, CORS, lifecycle, IAM role, Pod Identity association,
CloudFront distribution, bucket policy — were created by
[`docs/aws/setup-media.sh`](docs/aws/setup-media.sh) from the policy documents
next to it. The design, the API contract proposed for the integration, and the
verification commands are in the `docs` repository (`IMAGE_UPLOAD_S3.md`).

## Run locally

```bash
./gradlew bootRun
curl localhost:8000/health
```

## Test

```bash
./gradlew build
```

Covers context startup, that virtual threads are actually enabled, the health
endpoints through `MockMvc`, and `MediaStorageService`: the presigner runs for
real against static test credentials so the URL shape and signed headers are
checked, while `HeadObject` and `DeleteObject` are mocked. No AWS access
required.

`MigrationTest` and `ProjectApiTest` run against a `postgres:18` container
through Testcontainers, so **Docker must be running**. `MigrationTest` checks
the migrations themselves — the seed, the cross-table rules, the unique index
and the delete cascade. `ProjectApiTest` drives the real HTTP layer down to
that database and covers the behaviour that is easy to get wrong: trimming and
the length limits, case-insensitive duplicate names, a trashed name becoming
free and the restore that then fails, repeated trash and restore leaving the
same result, permanent delete refused outside the trash, and another owner
seeing `PROJECT_NOT_FOUND` everywhere. It also pins the identity header name and
its three rejection cases, so a drift away from the authentication service fails
the build.

## Deploy

`main` push runs [`.github/workflows/ci-cd.yaml`](.github/workflows/ci-cd.yaml):

```
test → docker build → ECR content/api:build-<run>-<attempt>
     → invoke loresentry-update-gitops → commit to loresentry-gitops → Argo CD
```

CI never touches Kubernetes. The image tag in the GitOps repository's
`workload/overlays/prod/kustomization.yaml` is the deployment record, and a
rollback is `git revert` of that commit.

Deployed to the `prod` namespace of the `lore-sentry-k8s` EKS cluster via Argo CD.

## Project activity

A project's `last_worked_at` moves whenever **anything inside it** changes, not
only when its own name or description does. Otherwise an hour spent writing a
manuscript leaves the project below one whose title was renamed, and the list is
supposed to be in most-recently-worked order.

`last_file` is the most recently updated **active** document. There is no
separate view-history table: what a user means by having worked on a file is
having edited it. Trashed documents are excluded — they cannot be opened, so
offering one as the last file would be a dead link.

## Not implemented yet
- Favorites, memos and workspace state. No tables — deliberately out of the
  schema proposal's scope, and whether they even belong on the server is an open
  decision.
- A sweep for images left `PENDING` and their orphaned S3 objects. The rows are
  indexed for it (`ix_image_pending`); nothing runs yet.
- User-created sections and general folders. The folder model question is still
  open, so this is not built either.
- Export to PDF, DOCX and HWP.
- Everything that needs graph-rag or Kafka: the relation graph, the AI graph
  refresh, and publishing `outbox_events`.

- Kafka change-event publishing.
