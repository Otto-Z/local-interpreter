//! Google Cloud Run Sync Server + iPhone Knowledge Review PWA.
//!
//! Features:
//! - Monotonic server-assigned sequence numbers (`server_seq`) so offline devices with clock skew
//!   never miss late-arriving changes from other devices.
//! - Scale-to-zero durability via Google Cloud Storage (GCS) compressed snapshot persistence
//!   authenticating automatically via GCP Cloud Run Metadata Server ADC.
//! - Bearer token and/or Cloud IAP email whitelist authentication.
//! - Embedded Apple-HIG iPhone PWA (`index.html`, `manifest.webmanifest`, `sw.js`) with offline cache.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::Duration;

use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use core_model::{
    now_ms, Bookmark, ChangeSet, Course, GlossaryEntry, MergeReport, Question, SearchHit, Segment,
    Session,
};
use core_storage::Store;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tower_http::compression::CompressionLayer;
use tower_http::cors::{Any, CorsLayer};
use tracing::{error, info, warn};

const INDEX_HTML: &str = include_str!("../static/index.html");
const MANIFEST_JSON: &str = include_str!("../static/manifest.webmanifest");
const SW_JS: &str = include_str!("../static/sw.js");

#[derive(Debug, Clone)]
pub struct CloudConfig {
    pub db_path: Option<String>,
    pub auth_token: String,
    pub allowed_emails: Vec<String>,
    pub gcs_bucket: Option<String>,
    pub gcs_object: String,
}

impl Default for CloudConfig {
    fn default() -> Self {
        Self {
            db_path: None,
            auth_token: String::new(),
            allowed_emails: Vec::new(),
            gcs_bucket: None,
            gcs_object: "local-interpreter/state.json.gz".to_string(),
        }
    }
}

/// Persisted snapshot stored in GCS for Cloud Run scale-to-zero durability.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct CloudSnapshot {
    version: u32,
    latest_seq: i64,
    entity_seqs: HashMap<String, i64>,
    state: ChangeSet,
}

#[derive(Clone)]
pub struct AppState {
    pub store: Store,
    pub cfg: CloudConfig,
    /// Maps `"<kind>:<id>" -> server_seq` for lossless monotonic pull pagination.
    pub seq_state: Arc<Mutex<SeqIndex>>,
    http: Client,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct SeqIndex {
    pub latest_seq: i64,
    pub entity_seqs: HashMap<String, i64>,
}

impl SeqIndex {
    fn bump(&mut self, kind: &str, id: &str) -> i64 {
        self.latest_seq += 1;
        let seq = self.latest_seq;
        self.entity_seqs.insert(format!("{kind}:{id}"), seq);
        seq
    }

    fn seq_of(&self, kind: &str, id: &str) -> i64 {
        self.entity_seqs
            .get(&format!("{kind}:{id}"))
            .copied()
            .unwrap_or(0)
    }
}

impl AppState {
    const SEQ_META_KEY: &'static str = "cloud_seq_index";

    pub async fn init(cfg: CloudConfig) -> anyhow::Result<Self> {
        let store = match &cfg.db_path {
            Some(path) => Store::open(path, "cloud-server")?,
            None => Store::open_in_memory("cloud-server")?,
        };
        let initial_seq = store
            .get_meta(Self::SEQ_META_KEY)?
            .and_then(|raw| serde_json::from_str::<SeqIndex>(&raw).ok())
            .unwrap_or_default();
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build()?;
        let state = Self {
            store,
            cfg,
            seq_state: Arc::new(Mutex::new(initial_seq)),
            http,
        };
        // Restore state from GCS if configured (Cloud Run cold start).
        if state.cfg.gcs_bucket.is_some() {
            if let Err(e) = state.restore_from_gcs().await {
                warn!("GCS cold-start restore skipped or failed: {e}");
            }
        }
        Ok(state)
    }

    async fn persist_state(&self) {
        if let Ok(json) = {
            let idx = self.seq_state.lock().await;
            serde_json::to_string(&*idx)
        } {
            if let Err(e) = self.store.set_meta(Self::SEQ_META_KEY, &json) {
                error!("Failed to persist SeqIndex to SQLite: {e}");
            }
        }
        if let Err(e) = self.persist_to_gcs().await {
            error!("Failed to persist snapshot to GCS: {e}");
        }
    }

    async fn gcp_access_token(&self) -> anyhow::Result<String> {
        if let Ok(tok) = std::env::var("GCS_ACCESS_TOKEN") {
            if !tok.trim().is_empty() {
                return Ok(tok);
            }
        }
        let resp = self
            .http
            .get("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")
            .header("Metadata-Flavor", "Google")
            .timeout(Duration::from_secs(5))
            .send()
            .await?;
        if !resp.status().is_success() {
            anyhow::bail!("metadata server returned {}", resp.status());
        }
        let val: serde_json::Value = resp.json().await?;
        val["access_token"]
            .as_str()
            .map(String::from)
            .ok_or_else(|| anyhow::anyhow!("missing access_token in metadata response"))
    }

    async fn restore_from_gcs(&self) -> anyhow::Result<()> {
        let Some(bucket) = &self.cfg.gcs_bucket else {
            return Ok(());
        };
        let token = self.gcp_access_token().await?;
        let obj = url_encode(&self.cfg.gcs_object);
        let url = format!("https://storage.googleapis.com/storage/v1/b/{bucket}/o/{obj}?alt=media");
        let resp = self.http.get(&url).bearer_auth(&token).send().await?;
        if resp.status() == StatusCode::NOT_FOUND {
            info!("No existing GCS snapshot at gs://{bucket}/{} — starting fresh", self.cfg.gcs_object);
            return Ok(());
        }
        if !resp.status().is_success() {
            anyhow::bail!("GCS download failed: {}", resp.status());
        }
        let gz = resp.bytes().await?;
        let mut dec = GzDecoder::new(gz.as_ref());
        let mut json = Vec::new();
        dec.read_to_end(&mut json)?;
        let snap: CloudSnapshot = serde_json::from_slice(&json)?;
        self.store.apply_changeset(&snap.state)?;
        let mut idx = self.seq_state.lock().await;
        idx.latest_seq = snap.latest_seq;
        idx.entity_seqs = snap.entity_seqs;
        info!(
            "Restored {} records (latest_seq={}) from gs://{bucket}/{}",
            snap.state.total_items(),
            snap.latest_seq,
            self.cfg.gcs_object
        );
        Ok(())
    }

    async fn persist_to_gcs(&self) -> anyhow::Result<()> {
        let Some(bucket) = &self.cfg.gcs_bucket else {
            return Ok(());
        };
        let token = self.gcp_access_token().await?;
        let all = self.store.export_changes_since(0)?;
        let (latest_seq, entity_seqs) = {
            let idx = self.seq_state.lock().await;
            (idx.latest_seq, idx.entity_seqs.clone())
        };
        let snap = CloudSnapshot {
            version: 1,
            latest_seq,
            entity_seqs,
            state: all,
        };
        let raw = serde_json::to_vec(&snap)?;
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(&raw)?;
        let gz = enc.finish()?;

        let obj = url_encode(&self.cfg.gcs_object);
        let url = format!(
            "https://storage.googleapis.com/upload/storage/v1/b/{bucket}/o?uploadType=media&name={obj}"
        );
        let resp = self
            .http
            .post(&url)
            .bearer_auth(&token)
            .header(header::CONTENT_TYPE, "application/gzip")
            .body(gz)
            .send()
            .await?;
        if !resp.status().is_success() {
            let st = resp.status();
            let body = resp.text().await.unwrap_or_default();
            anyhow::bail!("GCS upload failed ({st}): {body}");
        }
        Ok(())
    }
}

pub fn build_router(state: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        .route("/healthz", get(healthz))
        .route("/", get(serve_index))
        .route("/manifest.webmanifest", get(serve_manifest))
        .route("/sw.js", get(serve_sw))
        .route("/api/v1/sync/push", post(sync_push))
        .route("/api/v1/sync/pull", get(sync_pull))
        .route("/api/v1/knowledge", get(get_knowledge))
        .route("/api/v1/sessions/:id", get(get_session_detail))
        .route("/api/v1/sessions/:id/note", post(update_session_note))
        .route("/api/v1/bookmarks/toggle", post(toggle_bookmark))
        .route("/api/v1/search", get(search_knowledge))
        .layer(DefaultBodyLimit::max(32 * 1024 * 1024))
        .layer(CompressionLayer::new())
        .layer(cors)
        .with_state(state)
}

fn check_auth(cfg: &CloudConfig, headers: &HeaderMap) -> Result<(), (StatusCode, &'static str)> {
    if !cfg.allowed_emails.is_empty() {
        let email = headers
            .get("x-goog-authenticated-user-email")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.trim_start_matches("accounts.google.com:").to_ascii_lowercase())
            .unwrap_or_default();
        if !cfg
            .allowed_emails
            .iter()
            .any(|allowed| allowed.eq_ignore_ascii_case(&email))
        {
            return Err((StatusCode::FORBIDDEN, "email not in allowed whitelist"));
        }
    }

    if !cfg.auth_token.is_empty() {
        let bearer = headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.strip_prefix("Bearer "))
            .unwrap_or("");
        let alt = headers
            .get("x-sync-token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if !constant_time_eq(bearer, &cfg.auth_token) && !constant_time_eq(alt, &cfg.auth_token) {
            return Err((StatusCode::UNAUTHORIZED, "invalid or missing sync token"));
        }
    }
    Ok(())
}

fn constant_time_eq(a: &str, b: &str) -> bool {
    let ab = a.as_bytes();
    let bb = b.as_bytes();
    if ab.len() != bb.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in ab.iter().zip(bb.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

// ---------------------------------------------------------------------- PWA static handlers

async fn healthz() -> impl IntoResponse {
    Json(serde_json::json!({ "ok": true, "service": "local-interpreter-cloud" }))
}

async fn serve_index() -> impl IntoResponse {
    Html(INDEX_HTML)
}

async fn serve_manifest() -> impl IntoResponse {
    (
        [(header::CONTENT_TYPE, "application/manifest+json; charset=utf-8")],
        MANIFEST_JSON,
    )
}

async fn serve_sw() -> impl IntoResponse {
    (
        [
            (header::CONTENT_TYPE, "application/javascript; charset=utf-8"),
            (header::CACHE_CONTROL, "no-cache"),
        ],
        SW_JS,
    )
}

// ---------------------------------------------------------------------- Sync API

#[derive(Debug, Serialize, Deserialize)]
pub struct PushResponse {
    pub ok: bool,
    pub server_cursor: i64,
    pub report: MergeReport,
}

async fn sync_push(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(cs): Json<ChangeSet>,
) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }

    let report = match state.store.apply_changeset(&cs) {
        Ok(r) => r,
        Err(e) => {
            return (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response();
        }
    };

    let server_cursor = {
        let mut idx = state.seq_state.lock().await;
        if report.total_applied() > 0 {
            for c in &cs.courses {
                idx.bump("course", &c.id);
            }
            for s in &cs.sessions {
                idx.bump("session", &s.id);
            }
            for seg in &cs.segments {
                idx.bump("segment", &seg.id);
            }
            for g in &cs.glossary {
                idx.bump("glossary", &g.id);
            }
            for q in &cs.questions {
                idx.bump("question", &q.id);
            }
            for b in &cs.bookmarks {
                idx.bump("bookmark", &b.id);
            }
            for n in &cs.summaries {
                idx.bump("summary", &n.id);
            }
        }
        idx.latest_seq
    };

    if report.total_applied() > 0 {
        state.persist_state().await;
    }

    Json(PushResponse {
        ok: true,
        server_cursor,
        report,
    })
    .into_response()
}

#[derive(Debug, Deserialize)]
struct PullQuery {
    #[serde(default)]
    since: i64,
}

async fn sync_pull(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<PullQuery>,
) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }

    let since = q.since;
    // Fast O(1) short-circuit when client is already caught up — zero SQLite I/O!
    let seq_snapshot = {
        let idx = state.seq_state.lock().await;
        if since >= idx.latest_seq {
            let mut empty = ChangeSet::empty("cloud-server", since);
            empty.server_cursor = Some(idx.latest_seq);
            return Json(empty).into_response();
        }
        idx.clone()
    };

    let all = match state.store.export_changes_since(0) {
        Ok(c) => c,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    };

    let mut out = ChangeSet::empty("cloud-server", since);
    out.server_cursor = Some(seq_snapshot.latest_seq);

    out.courses = all
        .courses
        .into_iter()
        .filter(|c| seq_snapshot.seq_of("course", &c.id) > since)
        .collect();
    out.sessions = all
        .sessions
        .into_iter()
        .filter(|s| seq_snapshot.seq_of("session", &s.id) > since)
        .collect();
    out.segments = all
        .segments
        .into_iter()
        .filter(|s| seq_snapshot.seq_of("segment", &s.id) > since)
        .collect();
    out.glossary = all
        .glossary
        .into_iter()
        .filter(|g| seq_snapshot.seq_of("glossary", &g.id) > since)
        .collect();
    out.questions = all
        .questions
        .into_iter()
        .filter(|item| seq_snapshot.seq_of("question", &item.id) > since)
        .collect();
    out.bookmarks = all
        .bookmarks
        .into_iter()
        .filter(|b| seq_snapshot.seq_of("bookmark", &b.id) > since)
        .collect();
    out.summaries = all
        .summaries
        .into_iter()
        .filter(|n| seq_snapshot.seq_of("summary", &n.id) > since)
        .collect();

    Json(out).into_response()
}

// ---------------------------------------------------------------------- iPhone Knowledge PWA APIs

#[derive(Debug, Serialize)]
struct KnowledgeOverview {
    synced_at: i64,
    courses: Vec<Course>,
    sessions: Vec<Session>,
    glossary: Vec<GlossaryEntry>,
    bookmarks: Vec<Bookmark>,
    questions: Vec<Question>,
}

async fn get_knowledge(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }
    let res = (|| -> core_storage::Result<KnowledgeOverview> {
        Ok(KnowledgeOverview {
            synced_at: now_ms(),
            courses: state.store.list_courses()?,
            sessions: state.store.list_sessions(None)?,
            glossary: state.store.list_glossary(None)?,
            bookmarks: state.store.list_bookmarks(None)?,
            questions: state.store.list_questions(None)?,
        })
    })();
    match res {
        Ok(payload) => Json(payload).into_response(),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    }
}

#[derive(Debug, Serialize)]
struct SessionDetailPayload {
    session: Session,
    segments: Vec<Segment>,
    bookmarks: Vec<Bookmark>,
    questions: Vec<Question>,
}

async fn get_session_detail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }
    let session = match state.store.get_session(&id) {
        Ok(Some(s)) if s.deleted_at.is_none() => s,
        Ok(_) => return (StatusCode::NOT_FOUND, "session not found").into_response(),
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    };
    let segments = state.store.list_segments(&id).unwrap_or_default();
    let bookmarks = state.store.list_bookmarks(Some(&id)).unwrap_or_default();
    let questions = state.store.list_questions(Some(&id)).unwrap_or_default();
    Json(SessionDetailPayload {
        session,
        segments,
        bookmarks,
        questions,
    })
    .into_response()
}

#[derive(Debug, Deserialize)]
struct NoteBody {
    user_note: String,
}

async fn update_session_note(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Json(body): Json<NoteBody>,
) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }
    match state.store.update_session_user_note(&id, &body.user_note) {
        Ok(session) => {
            state.seq_state.lock().await.bump("session", &session.id);
            state.persist_state().await;
            Json(session).into_response()
        }
        Err(e) => (StatusCode::BAD_REQUEST, e.to_string()).into_response(),
    }
}

#[derive(Debug, Deserialize)]
struct BookmarkToggleBody {
    session_id: String,
    segment_id: String,
    #[serde(default)]
    note: String,
}

async fn toggle_bookmark(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(body): Json<BookmarkToggleBody>,
) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }
    match state
        .store
        .toggle_bookmark(&body.session_id, &body.segment_id, &body.note)
    {
        Ok((bookmarked, bm)) => {
            state.seq_state.lock().await.bump("bookmark", &bm.id);
            state.persist_state().await;
            Json(serde_json::json!({ "bookmarked": bookmarked, "bookmark": bm })).into_response()
        }
        Err(e) => (StatusCode::BAD_REQUEST, e.to_string()).into_response(),
    }
}

#[derive(Debug, Deserialize)]
struct SearchQuery {
    q: String,
    #[serde(default = "default_search_limit")]
    limit: usize,
}

fn default_search_limit() -> usize {
    40
}

async fn search_knowledge(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<SearchQuery>,
) -> Response {
    if let Err(err) = check_auth(&state.cfg, &headers) {
        return err.into_response();
    }
    match state.store.search(&q.q, q.limit) {
        Ok(hits) => Json::<Vec<SearchHit>>(hits).into_response(),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()).into_response(),
    }
}

fn url_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{:02X}", b));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_model::KnowledgePoint;
    use core_sync::{CloudRunTransport, SyncManager};
    use tokio::net::TcpListener;

    async fn spawn_test_server(token: &str) -> String {
        let cfg = CloudConfig {
            db_path: None,
            auth_token: token.to_string(),
            allowed_emails: Vec::new(),
            gcs_bucket: None,
            gcs_object: String::new(),
        };
        let state = AppState::init(cfg).await.unwrap();
        let app = build_router(state);
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        format!("http://{addr}")
    }

    #[tokio::test]
    async fn end_to_end_mac_to_cloud_to_iphone_and_android_tablet_sync() {
        let token = "test-secret-token-2026";
        let base_url = spawn_test_server(token).await;

        // 1. Mac finishes a class and pushes to Google Cloud Run
        let mac = Store::open_in_memory("macbook-pro").unwrap();
        let course = mac.create_course("Machine Learning", "课堂").unwrap();
        let session = mac
            .create_session(Some(course.id.clone()), "system", "en", "zh")
            .unwrap();
        let seg = mac
            .add_segment(
                &session.id,
                0.0,
                3.5,
                "en",
                "Layer normalization stabilizes Transformer training.",
                "zh",
                "层归一化稳定了 Transformer 的训练过程。",
                false,
            )
            .unwrap();
        mac.update_session_knowledge(
            &session.id,
            Some("Transformer 归一化"),
            Some("讲解 LayerNorm 与 BatchNorm 的核心区别。"),
            Some(vec![KnowledgePoint {
                title: "Layer Normalization".into(),
                explanation: "在单个样本的特征维度上计算均值和方差，不依赖 batch size。".into(),
                importance: 3,
                tags: vec!["Transformer".into(), "考点".into()],
                source_quote: "Layer normalization stabilizes Transformer training.".into(),
            }]),
            Some("completed"),
        )
        .unwrap();
        mac.upsert_glossary_term(&course.id, "LayerNorm", "层归一化", "NLP 标配", "ai")
            .unwrap();

        let mac_sync = SyncManager::new(
            mac.clone(),
            CloudRunTransport::new(&base_url, token),
        );
        let st_mac = mac_sync.sync_now().await.unwrap();
        assert_eq!(st_mac.pushed_items, 4);

        // 2. Unauthorized iPhone request must be rejected (401)
        let http = Client::new();
        let unauth = http
            .get(format!("{base_url}/api/v1/knowledge"))
            .send()
            .await
            .unwrap();
        assert_eq!(unauth.status(), StatusCode::UNAUTHORIZED);

        // 3. Authenticated iPhone PWA fetches knowledge points, searches, bookmarks & adds note!
        let ov: serde_json::Value = http
            .get(format!("{base_url}/api/v1/knowledge"))
            .bearer_auth(token)
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert_eq!(ov["sessions"].as_array().unwrap().len(), 1);
        assert_eq!(
            ov["sessions"][0]["knowledge_points"][0]["title"],
            "Layer Normalization"
        );

        let search_hits: Vec<SearchHit> = http
            .get(format!("{base_url}/api/v1/search?q=LayerNorm"))
            .bearer_auth(token)
            .send()
            .await
            .unwrap()
            .json()
            .await
            .unwrap();
        assert!(!search_hits.is_empty());

        // iPhone user bookmarks the segment and adds a review note
        let bm_res = http
            .post(format!("{base_url}/api/v1/bookmarks/toggle"))
            .bearer_auth(token)
            .json(&serde_json::json!({
                "session_id": session.id,
                "segment_id": seg.id,
                "note": "iPhone 地铁复习标记"
            }))
            .send()
            .await
            .unwrap();
        assert!(bm_res.status().is_success());

        let note_res = http
            .post(format!("{base_url}/api/v1/sessions/{}/note", session.id))
            .bearer_auth(token)
            .json(&serde_json::json!({ "user_note": "iPhone 补充：和 RMSNorm 对比复习" }))
            .send()
            .await
            .unwrap();
        assert!(note_res.status().is_success());

        // 4. Android Tablet syncs from Cloud Run -> gets Mac's lesson + iPhone's bookmark & note!
        let tablet = Store::open_in_memory("android-tablet").unwrap();
        let tab_sync = SyncManager::new(
            tablet.clone(),
            CloudRunTransport::new(&base_url, token),
        );
        let st_tab = tab_sync.sync_now().await.unwrap();
        assert!(st_tab.pulled_items >= 5);
        assert_eq!(
            tablet.get_session(&session.id).unwrap().unwrap().user_note,
            "iPhone 补充：和 RMSNorm 对比复习"
        );
        assert_eq!(tablet.list_bookmarks(Some(&session.id)).unwrap().len(), 1);

        // 5. Mac syncs again -> receives iPhone's edits cleanly!
        let st_mac2 = mac_sync.sync_now().await.unwrap();
        assert_eq!(st_mac2.pushed_items, 0);
        assert!(st_mac2.pulled_items >= 2);
        assert_eq!(
            mac.get_session(&session.id).unwrap().unwrap().user_note,
            "iPhone 补充：和 RMSNorm 对比复习"
        );
        assert_eq!(mac.list_bookmarks(Some(&session.id)).unwrap().len(), 1);

        // 6. Late-arriving offline device (older clock!) pushes -> Mac still receives it thanks to server_seq!
        let offline_pad = Store::open_in_memory("offline-pad").unwrap();
        let mut old_course = offline_pad.create_course("Offline History", "课堂").unwrap();
        old_course.updated_at = 1_000_000; // simulated 1970 clock!
        offline_pad.upsert_course(&old_course).unwrap();
        let off_sync = SyncManager::new(
            offline_pad,
            CloudRunTransport::new(&base_url, token),
        );
        off_sync.sync_now().await.unwrap();

        mac_sync.sync_now().await.unwrap();
        assert!(mac
            .list_courses()
            .unwrap()
            .iter()
            .any(|c| c.name == "Offline History"));

        // 7. PWA static assets serve properly
        let html = http.get(format!("{base_url}/")).send().await.unwrap().text().await.unwrap();
        assert!(html.contains("apple-mobile-web-app-capable"));
    }

    #[tokio::test]
    async fn server_restart_preserves_seq_index_for_incremental_pull() {
        let dir = tempfile::TempDir::new().unwrap();
        let db_file = dir.path().join("cloud.db").to_string_lossy().to_string();

        // Instance 1 receives a course from Mac
        let cfg1 = CloudConfig {
            db_path: Some(db_file.clone()),
            auth_token: "tok".into(),
            ..CloudConfig::default()
        };
        let state1 = AppState::init(cfg1).await.unwrap();
        let listener1 = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url1 = format!("http://{}", listener1.local_addr().unwrap());
        tokio::spawn(async move {
            axum::serve(listener1, build_router(state1)).await.unwrap();
        });

        let mac = Store::open_in_memory("mac-1").unwrap();
        mac.create_course("Persisted Course", "课堂").unwrap();
        SyncManager::new(mac, CloudRunTransport::new(&url1, "tok"))
            .sync_now()
            .await
            .unwrap();

        // Instance 2 restarts against the SAME SQLite file
        let cfg2 = CloudConfig {
            db_path: Some(db_file),
            auth_token: "tok".into(),
            ..CloudConfig::default()
        };
        let state2 = AppState::init(cfg2).await.unwrap();
        let listener2 = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url2 = format!("http://{}", listener2.local_addr().unwrap());
        tokio::spawn(async move {
            axum::serve(listener2, build_router(state2)).await.unwrap();
        });

        // Fresh tablet pulls from restarted instance and MUST receive the persisted course!
        let tab = Store::open_in_memory("tab-1").unwrap();
        let st = SyncManager::new(tab.clone(), CloudRunTransport::new(&url2, "tok"))
            .sync_now()
            .await
            .unwrap();
        assert_eq!(st.pulled_items, 1);
        assert_eq!(tab.list_courses().unwrap()[0].name, "Persisted Course");
    }
}
