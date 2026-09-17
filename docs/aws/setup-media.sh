#!/usr/bin/env bash
set -euo pipefail

export AWS_PROFILE="${AWS_PROFILE:-lorekeeper}"
export AWS_PAGER=""

REGION=ap-northeast-2
CLUSTER=lore-sentry-k8s
NAMESPACE=prod
SERVICE_ACCOUNT=content-api
ROLE_NAME=lore-sentry-content-role
POLICY_NAME=lore-sentry-content-media-policy
OAC_NAME=loresentry-media-oac
MEDIA_HOST=media.loresentry.com
CERT_DOMAIN=loresentry.com

DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="loresentry-media-prod-${ACCOUNT}"

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
aws s3api put-bucket-cors \
  --bucket "$BUCKET" \
  --cors-configuration "file://${DIR}/media-bucket-cors.json"
aws s3api put-bucket-lifecycle-configuration \
  --bucket "$BUCKET" \
  --lifecycle-configuration "file://${DIR}/media-bucket-lifecycle.json"

echo "== 2. IAM role for the content pod"
sed "s/__BUCKET__/${BUCKET}/g" "${DIR}/iam-content-media-policy.json" > "${WORK}/policy.json"
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
if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam update-assume-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-document "file://${DIR}/iam-content-pod-identity-trust.json"
else
  aws iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document "file://${DIR}/iam-content-pod-identity-trust.json" \
    --description "content-api pod: presign and verify user image uploads in ${BUCKET}"
fi
aws iam attach-role-policy --role-name "$ROLE_NAME" --policy-arn "$POLICY_ARN"
ROLE_ARN="arn:aws:iam::${ACCOUNT}:role/${ROLE_NAME}"

echo "== 3. EKS Pod Identity association"
aws eks describe-addon --cluster-name "$CLUSTER" --addon-name eks-pod-identity-agent \
  --query 'addon.status' --output text
ASSOC_ID="$(aws eks list-pod-identity-associations \
  --cluster-name "$CLUSTER" --namespace "$NAMESPACE" --service-account "$SERVICE_ACCOUNT" \
  --query 'associations[0].associationId' --output text)"
if [ "$ASSOC_ID" = "None" ] || [ -z "$ASSOC_ID" ]; then
  aws eks create-pod-identity-association \
    --cluster-name "$CLUSTER" \
    --namespace "$NAMESPACE" \
    --service-account "$SERVICE_ACCOUNT" \
    --role-arn "$ROLE_ARN"
else
  aws eks update-pod-identity-association \
    --cluster-name "$CLUSTER" \
    --association-id "$ASSOC_ID" \
    --role-arn "$ROLE_ARN"
fi

echo "== 4. CloudFront origin access control"
OAC_ID="$(aws cloudfront list-origin-access-controls \
  --query "OriginAccessControlList.Items[?Name=='${OAC_NAME}'].Id | [0]" --output text)"
if [ "$OAC_ID" = "None" ] || [ -z "$OAC_ID" ]; then
  OAC_ID="$(aws cloudfront create-origin-access-control \
    --origin-access-control-config \
      "Name=${OAC_NAME},OriginAccessControlOriginType=s3,SigningBehavior=always,SigningProtocol=sigv4" \
    --query OriginAccessControl.Id --output text)"
fi
echo "oac=${OAC_ID}"

echo "== 5. CloudFront distribution"
CERT_ARN="$(aws acm list-certificates --region us-east-1 \
  --query "CertificateSummaryList[?DomainName=='${CERT_DOMAIN}'].CertificateArn | [0]" --output text)"
if [ "$CERT_ARN" = "None" ] || [ -z "$CERT_ARN" ]; then
  echo "no ACM certificate for ${CERT_DOMAIN} in us-east-1" >&2
  exit 1
fi
DIST_ID="$(aws cloudfront list-distributions \
  --query "DistributionList.Items[?contains(Aliases.Items || \`[]\`, '${MEDIA_HOST}')].Id | [0]" --output text)"
if [ "$DIST_ID" = "None" ] || [ -z "$DIST_ID" ]; then
  sed \
    -e "s|__CALLER_REFERENCE__|media-$(date +%s)|" \
    -e "s|__MEDIA_HOST__|${MEDIA_HOST}|" \
    -e "s|__BUCKET__|${BUCKET}|" \
    -e "s|__REGION__|${REGION}|" \
    -e "s|__OAC_ID__|${OAC_ID}|" \
    -e "s|__CERT_ARN__|${CERT_ARN}|" \
    "${DIR}/media-distribution.json" > "${WORK}/distribution.json"
  DIST_ID="$(aws cloudfront create-distribution \
    --distribution-config "file://${WORK}/distribution.json" \
    --query Distribution.Id --output text)"
fi
DIST_DOMAIN="$(aws cloudfront get-distribution --id "$DIST_ID" \
  --query Distribution.DomainName --output text)"
echo "distribution=${DIST_ID} ${DIST_DOMAIN}"

echo "== 6. Bucket policy: only this distribution may read"
sed \
  -e "s|__BUCKET__|${BUCKET}|" \
  -e "s|__ACCOUNT__|${ACCOUNT}|" \
  -e "s|__DISTRIBUTION_ID__|${DIST_ID}|" \
  "${DIR}/media-bucket-policy.json" > "${WORK}/bucket-policy.json"
aws s3api put-bucket-policy --bucket "$BUCKET" --policy "file://${WORK}/bucket-policy.json"

cat <<SUMMARY

done.

  bucket        ${BUCKET}
  role          ${ROLE_ARN}
  oac           ${OAC_ID}
  distribution  ${DIST_ID}  ${DIST_DOMAIN}

remaining manual step (Cloudflare DNS, proxy OFF):
  ${MEDIA_HOST}  CNAME  ${DIST_DOMAIN}
SUMMARY
