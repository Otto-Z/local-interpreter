//! Cross-device sync engine for Google Cloud Run, GCS (S3-compatible HMAC), and local sync folders.
//! Includes optional client-side ChaCha20-Poly1305 E2EE encryption and gzip compression.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use async_trait::async_trait;
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Nonce};
use chrono::Utc;
use core_model::{ChangeSet, MergeReport};
use core_storage::Store;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use hmac::{Hmac, Mac};
use pbkdf2::pbkdf2_hmac;
use rand::RngCore;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum SyncError {
    #[error("storage error: {0}")]
    Storage(#[from] core_storage::StorageError),
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("remote error ({status}): {body}")]
    Remote { status: u16, body: String },
    #[error("crypto error: {0}")]
    Crypto(String),
}

pub type Result<T> = std::result::Result<T, SyncError>;

// ---------------------------------------------------------------------- Compression + E2EE

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedEnvelope {
    pub version: u8,
    pub salt_b64: String,
    pub nonce_b64: String,
    pub ciphertext_b64: String,
}

/// Optional ChaCha20-Poly1305 end-to-end encryption over gzip-compressed ChangeSet JSON.
pub struct E2eeCodec;

impl E2eeCodec {
    const PBKDF2_ITERS: u32 = 100_000;

    pub fn compress(data: &[u8]) -> Result<Vec<u8>> {
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(data)?;
        Ok(enc.finish()?)
    }

    pub fn decompress(data: &[u8]) -> Result<Vec<u8>> {
        let mut dec = GzDecoder::new(data);
        let mut out = Vec::new();
        dec.read_to_end(&mut out)?;
        Ok(out)
    }

    pub fn encrypt_changeset(cs: &ChangeSet, passphrase: &str) -> Result<EncryptedEnvelope> {
        let json = serde_json::to_vec(cs)?;
        let gz = Self::compress(&json)?;
        let mut salt = [0u8; 16];
        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut salt);
        rand::thread_rng().fill_bytes(&mut nonce_bytes);

        let mut key = [0u8; 32];
        pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), &salt, Self::PBKDF2_ITERS, &mut key);
        let cipher = ChaCha20Poly1305::new((&key).into());
        let ciphertext = cipher
            .encrypt(Nonce::from_slice(&nonce_bytes), gz.as_slice())
            .map_err(|e| SyncError::Crypto(e.to_string()))?;

        Ok(EncryptedEnvelope {
            version: 1,
            salt_b64: B64.encode(salt),
            nonce_b64: B64.encode(nonce_bytes),
            ciphertext_b64: B64.encode(ciphertext),
        })
    }

    pub fn decrypt_changeset(env: &EncryptedEnvelope, passphrase: &str) -> Result<ChangeSet> {
        let salt = B64
            .decode(&env.salt_b64)
            .map_err(|e| SyncError::Crypto(e.to_string()))?;
        let nonce_bytes = B64
            .decode(&env.nonce_b64)
            .map_err(|e| SyncError::Crypto(e.to_string()))?;
        let ciphertext = B64
            .decode(&env.ciphertext_b64)
            .map_err(|e| SyncError::Crypto(e.to_string()))?;
        if nonce_bytes.len() != 12 {
            return Err(SyncError::Crypto("invalid nonce length".into()));
        }
        let mut key = [0u8; 32];
        pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), &salt, Self::PBKDF2_ITERS, &mut key);
        let cipher = ChaCha20Poly1305::new((&key).into());
        let gz = cipher
            .decrypt(Nonce::from_slice(&nonce_bytes), ciphertext.as_slice())
            .map_err(|_| SyncError::Crypto("decryption failed (wrong passphrase or corrupted data)".into()))?;
        let json = Self::decompress(&gz)?;
        Ok(serde_json::from_slice(&json)?)
    }
}

// ---------------------------------------------------------------------- SyncTransport trait

#[async_trait]
pub trait SyncTransport: Send + Sync {
    async fn push(&self, changes: &ChangeSet) -> Result<()>;
    async fn pull(&self, since_ms: i64) -> Result<Vec<ChangeSet>>;
}

// ---------------------------------------------------------------------- 1. Google Cloud Run HTTP transport

#[derive(Clone)]
pub struct CloudRunTransport {
    base_url: String,
    auth_token: String,
    client: Client,
}

impl CloudRunTransport {
    pub fn new(base_url: impl Into<String>, auth_token: impl Into<String>) -> Self {
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(45))
            .build()
            .expect("reqwest client build");
        Self {
            base_url: base_url.into().trim().trim_end_matches('/').to_string(),
            auth_token: auth_token.into(),
            client,
        }
    }
}

#[async_trait]
impl SyncTransport for CloudRunTransport {
    async fn push(&self, changes: &ChangeSet) -> Result<()> {
        let url = format!("{}/api/v1/sync/push", self.base_url);
        let mut last_err: Option<SyncError> = None;
        for attempt in 0..3u32 {
            let mut req = self.client.post(&url).json(changes);
            if !self.auth_token.is_empty() {
                req = req.bearer_auth(&self.auth_token);
            }
            match req.send().await {
                Ok(resp) if resp.status().is_success() => return Ok(()),
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let body = resp.text().await.unwrap_or_default();
                    let err = SyncError::Remote { status, body };
                    if status < 500 && status != 429 {
                        return Err(err);
                    }
                    last_err = Some(err);
                }
                Err(e) => {
                    last_err = Some(SyncError::Http(e));
                }
            }
            tokio::time::sleep(Duration::from_millis(250 * (1 << attempt))).await;
        }
        Err(last_err.unwrap_or_else(|| SyncError::Remote {
            status: 500,
            body: "push failed".into(),
        }))
    }

    async fn pull(&self, since_ms: i64) -> Result<Vec<ChangeSet>> {
        let url = format!("{}/api/v1/sync/pull?since={since_ms}", self.base_url);
        let mut req = self.client.get(&url);
        if !self.auth_token.is_empty() {
            req = req.bearer_auth(&self.auth_token);
        }
        let resp = req.send().await?;
        if !resp.status().is_success() {
            let status = resp.status().as_u16();
            let body = resp.text().await.unwrap_or_default();
            return Err(SyncError::Remote { status, body });
        }
        let cs: ChangeSet = resp.json().await?;
        if cs.is_empty() && cs.server_cursor.is_none() {
            Ok(Vec::new())
        } else {
            Ok(vec![cs])
        }
    }
}

// ---------------------------------------------------------------------- 2. Direct GCS (S3-compatible HMAC) transport

/// Pure-Rust SigV4 signer for Google Cloud Storage XML API (`https://storage.googleapis.com`),
/// Cloudflare R2, or AWS S3. Allows serverless encrypted ChangeSet sync straight to a GCS bucket.
#[derive(Clone)]
pub struct GcsS3Transport {
    endpoint: String,
    bucket: String,
    region: String,
    access_key: String,
    secret_key: String,
    prefix: String,
    passphrase: Option<String>,
    client: Client,
}

impl GcsS3Transport {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        endpoint: impl Into<String>,
        bucket: impl Into<String>,
        region: impl Into<String>,
        access_key: impl Into<String>,
        secret_key: impl Into<String>,
        prefix: impl Into<String>,
        passphrase: Option<String>,
    ) -> Self {
        let client = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(45))
            .build()
            .expect("reqwest client build");
        Self {
            endpoint: endpoint.into().trim_end_matches('/').to_string(),
            bucket: bucket.into(),
            region: region.into(),
            access_key: access_key.into(),
            secret_key: secret_key.into(),
            prefix: prefix.into().trim_matches('/').to_string(),
            passphrase,
            client,
        }
    }

    fn sign_request(
        &self,
        method: &str,
        canonical_uri: &str,
        canonical_query: &str,
        payload_hash: &str,
        host: &str,
    ) -> (String, String) {
        let now = Utc::now();
        let amz_date = now.format("%Y%m%dT%H%M%SZ").to_string();
        let date_stamp = now.format("%Y%m%d").to_string();
        let signed_headers = "host;x-amz-content-sha256;x-amz-date";
        let canonical_headers =
            format!("host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n");
        let canonical_request = format!(
            "{method}\n{canonical_uri}\n{canonical_query}\n{canonical_headers}\n{signed_headers}\n{payload_hash}"
        );
        let scope = format!("{date_stamp}/{}/s3/aws4_request", self.region);
        let string_to_sign = format!(
            "AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{}",
            hex::encode(Sha256::digest(canonical_request.as_bytes()))
        );

        let k_date = hmac_sha256(format!("AWS4{}", self.secret_key).as_bytes(), date_stamp.as_bytes());
        let k_region = hmac_sha256(&k_date, self.region.as_bytes());
        let k_service = hmac_sha256(&k_region, b"s3");
        let k_signing = hmac_sha256(&k_service, b"aws4_request");
        let sig = hex::encode(hmac_sha256(&k_signing, string_to_sign.as_bytes()));

        let auth = format!(
            "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed_headers}, Signature={sig}",
            self.access_key
        );
        (amz_date, auth)
    }

    fn host(&self) -> String {
        self.endpoint
            .trim_start_matches("https://")
            .trim_start_matches("http://")
            .split('/')
            .next()
            .unwrap_or("storage.googleapis.com")
            .to_string()
    }
}

fn hmac_sha256(key: &[u8], data: &[u8]) -> Vec<u8> {
    type HmacSha256 = Hmac<Sha256>;
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).expect("hmac key");
    mac.update(data);
    mac.finalize().into_bytes().to_vec()
}

#[async_trait]
impl SyncTransport for GcsS3Transport {
    async fn push(&self, changes: &ChangeSet) -> Result<()> {
        let key = format!(
            "{}/changes/{}-{}.json",
            self.prefix, changes.generated_at, changes.device_id
        );
        let uri = format!("/{}/{}", self.bucket, key.trim_start_matches('/'));
        let payload = if let Some(pass) = &self.passphrase {
            serde_json::to_vec(&E2eeCodec::encrypt_changeset(changes, pass)?)?
        } else {
            serde_json::to_vec(changes)?
        };
        let hash = hex::encode(Sha256::digest(&payload));
        let host = self.host();
        let (amz_date, auth) = self.sign_request("PUT", &uri, "", &hash, &host);

        let url = format!("{}{}", self.endpoint, uri);
        let resp = self
            .client
            .put(&url)
            .header("x-amz-date", amz_date)
            .header("x-amz-content-sha256", hash)
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .body(payload)
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(SyncError::Remote {
                status: resp.status().as_u16(),
                body: resp.text().await.unwrap_or_default(),
            });
        }
        Ok(())
    }

    async fn pull(&self, since_ms: i64) -> Result<Vec<ChangeSet>> {
        let list_prefix = format!("{}/changes/", self.prefix.trim_matches('/'));
        let uri = format!("/{}", self.bucket);
        let query = format!("list-type=2&prefix={}", url_encode_simple(&list_prefix));
        let empty_hash = hex::encode(Sha256::digest(b""));
        let host = self.host();
        let (amz_date, auth) = self.sign_request("GET", &uri, &query, &empty_hash, &host);

        let url = format!("{}{}?{}", self.endpoint, uri, query);
        let resp = self
            .client
            .get(&url)
            .header("x-amz-date", amz_date)
            .header("x-amz-content-sha256", empty_hash.clone())
            .header("Authorization", auth)
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(SyncError::Remote {
                status: resp.status().as_u16(),
                body: resp.text().await.unwrap_or_default(),
            });
        }
        let xml = resp.text().await?;
        let keys = extract_xml_keys(&xml);
        let mut out = Vec::new();
        for key in keys {
            // Skip files whose filename timestamp prefix is older than since_ms
            if let Some(ts) = parse_key_timestamp(&key) {
                if ts <= since_ms {
                    continue;
                }
            }
            let obj_uri = format!("/{}/{}", self.bucket, key.trim_start_matches('/'));
            let (d, a) = self.sign_request("GET", &obj_uri, "", &empty_hash, &host);
            let obj_url = format!("{}{}", self.endpoint, obj_uri);
            let r = self
                .client
                .get(&obj_url)
                .header("x-amz-date", d)
                .header("x-amz-content-sha256", empty_hash.clone())
                .header("Authorization", a)
                .send()
                .await?;
            if r.status().is_success() {
                let bytes = r.bytes().await?;
                let cs = if let Some(pass) = &self.passphrase {
                    let env: EncryptedEnvelope = serde_json::from_slice(&bytes)?;
                    E2eeCodec::decrypt_changeset(&env, pass)?
                } else {
                    serde_json::from_slice(&bytes)?
                };
                out.push(cs);
            }
        }
        Ok(out)
    }
}

fn url_encode_simple(s: &str) -> String {
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

fn extract_xml_keys(xml: &str) -> Vec<String> {
    let mut keys = Vec::new();
    let mut rest = xml;
    while let Some(start) = rest.find("<Key>") {
        let after = &rest[start + 5..];
        if let Some(end) = after.find("</Key>") {
            keys.push(after[..end].to_string());
            rest = &after[end + 6..];
        } else {
            break;
        }
    }
    keys
}

fn parse_key_timestamp(key: &str) -> Option<i64> {
    let fname = key.rsplit('/').next()?;
    let ts_part = fname.split('-').next()?;
    ts_part.parse::<i64>().ok()
}

// ---------------------------------------------------------------------- 3. Local Folder transport (Google Drive / iCloud Drive / Syncthing)

#[derive(Clone)]
pub struct FolderTransport {
    dir: PathBuf,
    passphrase: Option<String>,
}

impl FolderTransport {
    pub fn new(dir: impl AsRef<Path>, passphrase: Option<String>) -> Self {
        Self {
            dir: dir.as_ref().to_path_buf(),
            passphrase,
        }
    }
}

#[async_trait]
impl SyncTransport for FolderTransport {
    async fn push(&self, changes: &ChangeSet) -> Result<()> {
        let changes_dir = self.dir.join("changes");
        std::fs::create_dir_all(&changes_dir)?;
        let fname = format!("{:016}-{}.json", changes.generated_at, changes.device_id);
        let tmp = changes_dir.join(format!(".{fname}.tmp"));
        let dst = changes_dir.join(&fname);
        let bytes = if let Some(pass) = &self.passphrase {
            serde_json::to_vec_pretty(&E2eeCodec::encrypt_changeset(changes, pass)?)?
        } else {
            serde_json::to_vec_pretty(changes)?
        };
        std::fs::write(&tmp, bytes)?;
        std::fs::rename(&tmp, &dst)?;
        Ok(())
    }

    async fn pull(&self, since_ms: i64) -> Result<Vec<ChangeSet>> {
        let changes_dir = self.dir.join("changes");
        if !changes_dir.exists() {
            return Ok(Vec::new());
        }
        let mut files: Vec<PathBuf> = std::fs::read_dir(&changes_dir)?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().and_then(|s| s.to_str()) == Some("json"))
            .collect();
        files.sort();

        let mut out = Vec::new();
        for path in files {
            if let Some(fname) = path.file_name().and_then(|s| s.to_str()) {
                if let Some(ts) = parse_key_timestamp(fname) {
                    if ts <= since_ms {
                        continue;
                    }
                }
            }
            let bytes = std::fs::read(&path)?;
            let cs = if let Some(pass) = &self.passphrase {
                let env: EncryptedEnvelope = serde_json::from_slice(&bytes)?;
                E2eeCodec::decrypt_changeset(&env, pass)?
            } else {
                serde_json::from_slice(&bytes)?
            };
            out.push(cs);
        }
        Ok(out)
    }
}

// ---------------------------------------------------------------------- SyncManager

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct SyncStats {
    pub pushed_items: usize,
    pub pulled_items: usize,
    pub skipped_stale: usize,
    pub last_pushed_at: i64,
    pub last_pulled_at: i64,
}

/// Orchestrates bidirectional watermark-tracked sync between a local [`Store`] and any [`SyncTransport`].
pub struct SyncManager<T: SyncTransport> {
    store: Store,
    transport: T,
}

impl<T: SyncTransport> SyncManager<T> {
    const KEY_LAST_PUSH: &'static str = "sync_last_pushed_at";
    const KEY_LAST_PULL: &'static str = "sync_last_pulled_at";

    pub fn new(store: Store, transport: T) -> Self {
        Self { store, transport }
    }

    pub async fn sync_now(&self) -> Result<SyncStats> {
        let mut last_pushed = self
            .store
            .get_meta(Self::KEY_LAST_PUSH)?
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);
        let mut last_pulled = self
            .store
            .get_meta(Self::KEY_LAST_PULL)?
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);

        // 1. Export & push only locally-authored deltas (immune to remote clock skew & echo loops).
        let local_changes = self.store.export_local_changes_since(last_pushed)?;
        let pushed_items = local_changes.total_items();
        if pushed_items > 0 {
            self.transport.push(&local_changes).await?;
            last_pushed = last_pushed.max(local_changes.max_updated_at());
            self.store
                .set_meta(Self::KEY_LAST_PUSH, &last_pushed.to_string())?;
        }

        // 2. Pull remote deltas & merge deterministically.
        let incoming_batches = self.transport.pull(last_pulled).await?;
        let mut pulled_items = 0usize;
        let mut skipped_stale = 0usize;
        for cs in incoming_batches {
            let cursor = cs.server_cursor.unwrap_or(cs.generated_at);
            if cs.device_id == self.store.device_id() && cs.server_cursor.is_none() {
                last_pulled = last_pulled.max(cursor);
                continue;
            }
            if !cs.is_empty() {
                let report: MergeReport = self.store.apply_changeset(&cs)?;
                pulled_items += report.total_applied();
                skipped_stale += report.skipped_stale;
            }
            last_pulled = last_pulled.max(cursor);
        }

        self.store
            .set_meta(Self::KEY_LAST_PULL, &last_pulled.to_string())?;

        Ok(SyncStats {
            pushed_items,
            pulled_items,
            skipped_stale,
            last_pushed_at: last_pushed,
            last_pulled_at: last_pulled,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_model::KnowledgePoint;
    use tempfile::TempDir;

    #[test]
    fn e2ee_roundtrip_and_wrong_passphrase_rejection() {
        let store = Store::open_in_memory("mac-1").unwrap();
        let c = store.create_course("Systems", "课堂").unwrap();
        let s = store
            .create_session(Some(c.id.clone()), "system", "en", "zh")
            .unwrap();
        store
            .update_session_knowledge(
                &s.id,
                Some("Memory Safety"),
                Some("Ownership prevents UAF"),
                Some(vec![KnowledgePoint {
                    title: "Ownership".into(),
                    explanation: "Single mutable reference or multiple immutable references".into(),
                    importance: 3,
                    tags: vec!["Rust".into()],
                    source_quote: String::new(),
                }]),
                Some("completed"),
            )
            .unwrap();
        let cs = store.export_changes_since(0).unwrap();

        let env = E2eeCodec::encrypt_changeset(&cs, "my-secret-pass").unwrap();
        assert!(!env.ciphertext_b64.contains("Ownership"));

        let decoded = E2eeCodec::decrypt_changeset(&env, "my-secret-pass").unwrap();
        assert_eq!(decoded.sessions.len(), 1);
        assert_eq!(decoded.sessions[0].title, "Memory Safety");

        assert!(E2eeCodec::decrypt_changeset(&env, "wrong-pass").is_err());
    }

    #[tokio::test]
    async fn folder_transport_encrypted_sync_between_mac_and_tablet() {
        let tmp = TempDir::new().unwrap();
        let mac_store = Store::open_in_memory("mac-pro").unwrap();
        let tab_store = Store::open_in_memory("android-pad").unwrap();

        let course = mac_store.create_course("OS", "课堂").unwrap();
        let ses = mac_store
            .create_session(Some(course.id.clone()), "system", "en", "zh")
            .unwrap();
        mac_store
            .add_segment(&ses.id, 0.0, 2.0, "en", "Virtual memory", "zh", "虚拟内存", false)
            .unwrap();

        let mac_sync = SyncManager::new(
            mac_store.clone(),
            FolderTransport::new(tmp.path(), Some("sync-key-123".into())),
        );
        let tab_sync = SyncManager::new(
            tab_store.clone(),
            FolderTransport::new(tmp.path(), Some("sync-key-123".into())),
        );

        let st1 = mac_sync.sync_now().await.unwrap();
        assert_eq!(st1.pushed_items, 3);

        let st2 = tab_sync.sync_now().await.unwrap();
        assert_eq!(st2.pulled_items, 3);
        assert_eq!(tab_store.list_segments(&ses.id).unwrap().len(), 1);

        // Second sync is a clean no-op (no ping-pong echo!)
        let st3 = tab_sync.sync_now().await.unwrap();
        assert_eq!(st3.pushed_items, 0);
        assert_eq!(st3.pulled_items, 0);
    }
}
