#!/usr/bin/env bash
# 그래프 최신화 입력을 주고받는 비공개 버킷과, content 파드가 그 refresh/ 아래에 입력을 올리는 권한을 만든다.
# content 역할(lore-sentry-content-role)은 setup-media.sh 가 만든다. graph-rag 역할과 Pod Identity는
# loresentry-graph-rag 의 deploy/setup-refresh.sh 가 이 스크립트 다음에 만든다.
# 다시 실행해도 안전하다: 버킷 설정은 덮어쓰고, 정책은 새 기본 버전으로 갱신한다.
set -euo pipefail

export AWS_PROFILE="${AWS_PROFILE:-lorekeeper}"
export AWS_PAGER=""

REGION=ap-northeast-2
ROLE_NAME=lore-sentry-content-role
POLICY_NAME=lore-sentry-content-refresh-policy

DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="loresentry-refresh-prod-${ACCOUNT}"

echo "account=${ACCOUNT} bucket=${BUCKET}"

echo "== 1. S3 bucket"
if aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "bucket exists"
else
  aws s3api create-bucket \
    --bucket "$BUCKET" \
    --region "$REGION" \
    --create-bucket-configuration "LocationConstraint=${REGION}"
fi
aws s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption \
  --bucket "$BUCKET" \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-lifecycle-configuration \
  --bucket "$BUCKET" \
  --lifecycle-configuration "file://${DIR}/refresh-bucket-lifecycle.json"
aws s3api put-bucket-tagging \
  --bucket "$BUCKET" \
  --tagging 'TagSet=[{Key=Project,Value=lore-sentry},{Key=Component,Value=graph-refresh}]'

echo "== 2. Refresh access for the content pod"
if ! aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  echo "role ${ROLE_NAME} is missing; run setup-media.sh first" >&2
  exit 1
fi
sed "s/__BUCKET__/${BUCKET}/g" "${DIR}/iam-content-refresh-policy.json" > "${WORK}/policy.json"
POLICY_ARN="arn:aws:iam::${ACCOUNT}:policy/${POLICY_NAME}"
if aws iam get-policy --policy-arn "$POLICY_ARN" >/dev/null 2>&1; then
  VERSION="$(aws iam create-policy-version \
    --policy-arn "$POLICY_ARN" \
    --policy-document "file://${WORK}/policy.json" \
    --set-as-default \
    --query PolicyVersion.VersionId --output text)"
  echo "policy exists, new default version ${VERSION}"
  for v in $(aws iam list-policy-versions --policy-arn "$POLICY_ARN" \
      --query 'Versions[?IsDefaultVersion==`false`].VersionId' --output text); do
    aws iam delete-policy-version --policy-arn "$POLICY_ARN" --version-id "$v"
  done
else
  aws iam create-policy \
    --policy-name "$POLICY_NAME" \
    --policy-document "file://${WORK}/policy.json"
fi
# media 정책과 분리해 붙인다. setup-media.sh 를 다시 실행해도 refresh 권한이 지워지지 않는다.
aws iam attach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN"

cat <<SUMMARY

done.

  bucket  ${BUCKET}
  policy  ${POLICY_ARN} -> ${ROLE_NAME}

next: run deploy/setup-refresh.sh in loresentry-graph-rag for the graph-rag role.
The content pod already uses ${ROLE_NAME}, so the new access applies without a restart.
SUMMARY
