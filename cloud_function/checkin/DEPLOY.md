# Deploying `checkin` (fixing HTTP 401 “not authorized to invoke this service”)

If you see HTTP **401** in Cloud Logging with a message like:

- `The request was not authorized to invoke this service`
- log name: `run.googleapis.com/requests`

…that **is Cloud Run IAM rejecting the request before your code runs**.

This project’s `checkin` handler already does *app-level* auth by requiring a **Firebase Auth ID token**:

- Android sends `Authorization: Bearer <firebase-id-token>`
- `cloud_function/checkin/index.js` calls `admin.auth().verifyIdToken(token)`

So the correct setup is:

- **Cloud Run allows unauthenticated invocations** (so traffic can reach your code)
- **Your code enforces Firebase Auth** (real security gate)

## Option A (recommended): allow unauthenticated invoke (Cloud Run IAM)

### Using Google Cloud Console

1. Go to **Cloud Run** → **Services**
2. Open the service that backs the function (often named `checkin`) in region **us-central1**
3. Go to **Permissions**
4. **Grant access**
   - **New principals**: `allUsers`
   - **Role**: `Cloud Run Invoker` (`roles/run.invoker`)
5. Save

### Using `gcloud`

First, confirm the service name:

```bash
gcloud run services list --region us-central1 --project myaidiet
```

Then grant public invocation:

```bash
gcloud run services add-iam-policy-binding checkin ^
  --region us-central1 ^
  --project myaidiet ^
  --member "allUsers" ^
  --role "roles/run.invoker"
```

After this, **Cloud Run will stop returning platform 401**, and your handler will return:

- `401 { "text": "AUTH_REQUIRED" }` if the Firebase token is missing, or
- `401 { "text": "AUTH_INVALID" }` if the token is bad/expired

## Option B: deploy with `--allow-unauthenticated` (gen2 Cloud Functions)

If you deploy as a **gen2 Cloud Function**, include `--allow-unauthenticated`:

```bash
gcloud functions deploy checkin ^
  --gen2 ^
  --region us-central1 ^
  --project myaidiet ^
  --runtime nodejs20 ^
  --entry-point checkin ^
  --trigger-http ^
  --allow-unauthenticated ^
  --source cloud_function/checkin
```

> Note: you still must set required env vars/secrets (e.g. `OPENAI_API_KEY`) the same way you currently do.

## Why this is safe

Allowing unauthenticated invocation at Cloud Run **does not make your API public** in practice here,
because `index.js` hard-requires a Firebase ID token and rejects all other requests.


