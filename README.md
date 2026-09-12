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

The gateway exposes this service publicly at `GET /content`, which calls `/` here
and returns the payload nested under `upstream`.

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

Covers context startup, that virtual threads are actually enabled, and both
endpoints through `MockMvc`. No AWS or network access required.

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

- PostgreSQL persistence for the domain model. The database is not provisioned
  yet, so no driver, ORM or migration tool is wired in — adding one before the
  database exists would only make the container fail to start with
  `Failed to configure a DataSource`.
- Kafka change-event publishing. No broker is running in the cluster yet.
- The domain model itself: projects, files, folders, versions, trash.
