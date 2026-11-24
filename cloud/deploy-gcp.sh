#!/usr/bin/env bash
set -euo pipefail

# One-command deploy to Google Cloud Run + GCS durable snapshot bucket.
# Usage:
#   PROJECT_ID=my-gcp-project REGION=asia-east1 SYNC_AUTH_TOKEN=$(openssl rand -hex 24) ./cloud/deploy-gcp.sh

PROJECT_ID="${PROJECT_ID:?Please set PROJECT_ID}"
REGION="${REGION:-asia-east1}"
SERVICE_NAME="${SERVICE_NAME:-local-interpreter-cloud}"
GCS_BUCKET="${GCS_BUCKET:-${PROJECT_ID}-local-interpreter-sync}"
SYNC_AUTH_TOKEN="${SYNC_AUTH_TOKEN:?Please set SYNC_AUTH_TOKEN (e.g. openssl rand -hex 24)}"
ALLOWED_EMAILS="${ALLOWED_EMAILS:-}"

echo "==> Ensuring GCS bucket gs://${GCS_BUCKET} exists..."
gcloud storage buckets describe "gs://${GCS_BUCKET}" --project="${PROJECT_ID}" >/dev/null 2>&1 || \
  gcloud storage buckets create "gs://${GCS_BUCKET}" --project="${PROJECT_ID}" --location="${REGION}" --uniform-bucket-level-access

IMAGE="gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest"
echo "==> Building container image ${IMAGE}..."
gcloud builds submit --project="${PROJECT_ID}" --config=- . <<EOF
steps:
  - name: 'gcr.io/cloud-builders/docker'
    args: ['build', '-f', 'cloud/Dockerfile', '-t', '${IMAGE}', '.']
images: ['${IMAGE}']
EOF

echo "==> Deploying to Cloud Run (${REGION})..."
gcloud run deploy "${SERVICE_NAME}" \
  --project="${PROJECT_ID}" \
  --region="${REGION}" \
  --image="${IMAGE}" \
  --allow-unauthenticated \
  --min-instances=0 \
  --max-instances=2 \
  --memory=256Mi \
  --set-env-vars="GCS_BUCKET=${GCS_BUCKET},SYNC_AUTH_TOKEN=${SYNC_AUTH_TOKEN},ALLOWED_EMAILS=${ALLOWED_EMAILS}"

URL="$(gcloud run services describe "${SERVICE_NAME}" --project="${PROJECT_ID}" --region="${REGION}" --format='value(status.url)')"
echo ""
echo "✅ Deployed! Open on your iPhone Safari and tap 'Add to Home Screen':"
echo "   ${URL}"
echo "   Configure the same URL and SYNC_AUTH_TOKEN in macOS & Android Tablet settings."
