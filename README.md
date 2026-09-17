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
| `POST` | `/projects/{projectId}/images` | Issues a presigned S3 PUT for one image. |
| `POST` | `/projects/{projectId}/images/{imageId}/complete` | Verifies the object landed in S3 and marks the image `COMMITTED`. |
| `GET` | `/projects/{projectId}/images/{imageId}` | Image metadata and its public URL. |

The gateway exposes this service publicly at `GET /content`, which calls `/` here
and returns the payload nested under `upstream`, and forwards the
`/projects/{projectId}/images…` routes as they are.

## Image upload

Images go **browser → S3 directly**. This service never receives the bytes; it
issues a short-lived presigned `PUT` and later confirms the object exists.

```
1. POST /projects/{projectId}/images
   { "fileName": "cover.png", "contentType": "image/png", "sizeBytes": 1234 }
   → 201
   {
     "imageId": "…", "key": "projects/{projectId}/images/{uuid}.png",
     "uploadUrl": "https://loresentry-media-prod-….s3.ap-northeast-2.amazonaws.com/…?X-Amz-…",
     "method": "PUT",
     "headers": { "Content-Type": "image/png", "Content-Length": "1234" },
     "expiresAt": "…", "publicUrl": "https://media.loresentry.com/projects/…"
   }

2. browser: PUT uploadUrl with exactly those headers and the file as the body

3. POST /projects/{projectId}/images/{imageId}/complete
   → 200 { "status": "COMMITTED", "publicUrl": "…", … }
```

`Content-Type` and `Content-Length` are part of the signature, so the browser
cannot upload a different type or a larger file than it declared. The URL is
valid for five minutes. `complete` does a `HeadObject` and refuses (`409
object_not_uploaded`) if the object is missing or its size differs from the
declared one; calling it again on a committed image is a no-op.

| Error | Status | When |
| --- | --- | --- |
| `invalid_upload_request` | `400` | Missing `fileName`, a content type outside the allow-list, or a size of zero or above the limit. |
| `image_not_found` | `404` | No such image in that project. |
| `object_not_uploaded` | `409` | `complete` called before the PUT, or the uploaded size does not match. |

Object keys are `projects/{projectId}/images/{uuid}.{ext}` with the extension
derived from the content type, never from the file name. The row in `image` is
`PENDING` until `complete`; rows that never complete are the cleanup target for a
later job.

Credentials come from the pod's ServiceAccount via **EKS Pod Identity** — there is
no access key anywhere. The IAM role only allows `PutObject`, `GetObject` and
`DeleteObject` under `projects/*` of the media bucket. Locally the AWS SDK falls
back to `AWS_PROFILE`, so `export AWS_PROFILE=lorekeeper` is enough to presign
against the real bucket; `complete` needs `s3:GetObject` on it too.

### Configuration

| Property | Env | Default | Notes |
| --- | --- | --- | --- |
| `media.bucket` | `MEDIA_BUCKET` | *(empty)* | `loresentry-media-prod-<account>` in the cluster, from the `media` ConfigMap. |
| `media.region` | `MEDIA_REGION` | `ap-northeast-2` | |
| `media.public-base-url` | `MEDIA_PUBLIC_BASE_URL` | *(empty)* | `https://media.loresentry.com`. Prefixed to the key to build `publicUrl`. |
| `media.upload-url-ttl` | | `5m` | Presigned URL lifetime. Must stay well under the Pod Identity credential lifetime. |
| `media.max-size-bytes` | | `10485760` | 10 MiB. |
| `media.allowed-content-types` | | png, jpeg, webp, gif | Anything else is `400`. |

The AWS resources themselves — bucket, IAM role, Pod Identity association,
CloudFront distribution — are created by [`docs/aws/setup-media.sh`](docs/aws/setup-media.sh)
with the policy documents next to it. The design and the verification steps are in
the `docs` repository (`IMAGE_UPLOAD_S3.md`).

## Database

Schema is managed by **Flyway**; migrations live in
`src/main/resources/db/migration` and run at startup. This means the service now
**fails to start when PostgreSQL is unreachable**, where before it would come up and
report the problem on `/health/db`. That is intended: a schema that may or may not
have been applied is worse than a pod that stays down.

| Migration | Creates |
| --- | --- |
| `V1__create_image.sql` | `image` — one row per uploaded image, keyed by `project_id`. No foreign key yet because the `project` table does not exist. |

Tests disable Flyway (`src/test/resources/application.properties`) and never need a
database.

This service holds the domain rules for the content it owns. Authorization
questions about a project — "may this user open it?" — are answered here, not in
the gateway.

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
endpoints through `MockMvc`, and the image upload flow: the presigner runs for
real against static test credentials so the URL shape and signed headers are
checked, while S3 `HeadObject` and the repository are mocked. No AWS or network
access required.

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

## Not implemented yet

- The domain model itself: projects, files, folders, versions, trash. `image` is
  the only table.
- Authorization on the image endpoints. The gateway does not verify identity yet,
  so "may this user upload into this project?" is not asked anywhere.
- Cleanup of `PENDING` images whose upload never completed.
- Kafka change-event publishing.
