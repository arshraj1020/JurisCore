#!/bin/bash
# Runs once LocalStack reports ready. Creates the S3 bucket and the SQS queues the
# platform expects, so a fresh checkout has working local infrastructure with no
# manual setup and no AWS account.
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-ap-south-1}"
BUCKET="juriscore-documents"

echo "[juriscore] creating S3 bucket ${BUCKET}"
awslocal s3api create-bucket \
  --bucket "${BUCKET}" \
  --region "${REGION}" \
  --create-bucket-configuration LocationConstraint="${REGION}" >/dev/null

# Case documents are versioned in S3 as well as in the metadata table: the version
# history in the database is what users browse, object versions are the safety net
# against an overwrite or a bad delete.
awslocal s3api put-bucket-versioning \
  --bucket "${BUCKET}" \
  --versioning-configuration Status=Enabled

awslocal s3api put-bucket-encryption \
  --bucket "${BUCKET}" \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'

# Nothing in the platform serves files from a public URL — access is always through
# a short-lived presigned link issued after an authorization check.
awslocal s3api put-public-access-block \
  --bucket "${BUCKET}" \
  --public-access-block-configuration \
  'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'

create_queue_with_dlq() {
  local name="$1"
  local dlq="${name}-dlq"

  echo "[juriscore] creating queue ${name} with dead letter queue ${dlq}"
  awslocal sqs create-queue --queue-name "${dlq}" >/dev/null

  local dlq_arn
  dlq_arn=$(awslocal sqs get-queue-attributes \
    --queue-url "$(awslocal sqs get-queue-url --queue-name "${dlq}" --output text --query QueueUrl)" \
    --attribute-names QueueArn --output text --query 'Attributes.QueueArn')

  # maxReceiveCount 3: a message that fails three times is failing for a reason a
  # fourth attempt will not fix. Parking it in the DLQ keeps one poison message from
  # blocking the queue behind it.
  awslocal sqs create-queue \
    --queue-name "${name}" \
    --attributes "{\"VisibilityTimeout\":\"60\",\"MessageRetentionPeriod\":\"345600\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}" >/dev/null
}

create_queue_with_dlq "juriscore-notifications"
create_queue_with_dlq "juriscore-audit"

echo "[juriscore] local AWS resources ready"
