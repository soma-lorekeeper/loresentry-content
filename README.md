# loresentry-content

Content service for Lore Sentry — the core creative-writing domain service.

Owns projects, files and folders, sections and favorites, document bodies and
custom attributes, file references, versions, memos, trash, and the source data
for search. Publishes content change events to Kafka so downstream services such
as `loresentry-graph-rag` can stay in sync.

- Stack: Spring Boot
- Database: MySQL
- Deployed to the `prod` namespace of the `lore-sentry-k8s` EKS cluster via Argo CD
