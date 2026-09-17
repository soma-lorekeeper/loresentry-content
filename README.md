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

The gateway exposes this service publicly at `GET /content`, which calls `/` here
and returns the payload nested under `upstream`.

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
checked, while `HeadObject` and `DeleteObject` are mocked. No AWS or network
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

- The domain model itself: projects, files, folders, versions, trash. No schema
  migration tool is wired in yet.
- Image upload endpoints and the table that records uploaded images. The S3
  storage layer is ready; the API and persistence come with the domain work.
- Kafka change-event publishing.
