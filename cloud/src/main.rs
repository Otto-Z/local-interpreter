use local_interpreter_cloud::{build_router, AppState, CloudConfig};
use tokio::net::TcpListener;
use tracing::info;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "info,local_interpreter_cloud=debug".into()),
        )
        .init();

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(8080);

    let allowed_emails: Vec<String> = std::env::var("ALLOWED_EMAILS")
        .unwrap_or_default()
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(String::from)
        .collect();

    let cfg = CloudConfig {
        db_path: std::env::var("DB_PATH").ok().filter(|s| !s.is_empty()),
        auth_token: std::env::var("SYNC_AUTH_TOKEN").unwrap_or_default(),
        allowed_emails,
        gcs_bucket: std::env::var("GCS_BUCKET").ok().filter(|s| !s.is_empty()),
        gcs_object: std::env::var("GCS_OBJECT")
            .ok()
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "local-interpreter/state.json.gz".into()),
    };

    let state = AppState::init(cfg).await?;
    let app = build_router(state);
    let addr = format!("0.0.0.0:{port}");
    let listener = TcpListener::bind(&addr).await?;
    info!("local-interpreter-cloud listening on http://{addr}");
    axum::serve(listener, app).await?;
    Ok(())
}
