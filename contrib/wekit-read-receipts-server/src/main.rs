use axum::{
    Json, Router,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::get,
};
use base64::{Engine as _, engine::general_purpose};
use chrono::Utc;
use libsql::{Builder, Connection};
use serde::Serialize;
use std::{collections::HashMap, net::SocketAddr, sync::Arc};
use tracing::{debug, error, info, warn};

// 1x1 transparent PNG file bytes to serve as the tracking pixel
const TRACKING_PIXEL: &[u8] = &[
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
    0x42, 0x60, 0x82,
];

struct AppState {
    db: Connection,
}

#[derive(Serialize)]
struct HitRecord {
    uuid: String,
    ip: String,
    msg: String,
    timestamp: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();

    let db_url =
        std::env::var("TURSO_DATABASE_URL").unwrap_or_else(|_| "file:read_receipts.db".to_string());
    let auth_token = std::env::var("TURSO_AUTH_TOKEN").unwrap_or_default();

    let db = if db_url.starts_with("file:") {
        Builder::new_local(db_url.replace("file:", ""))
            .build()
            .await?
    } else {
        Builder::new_remote(db_url, auth_token).build().await?
    };

    let conn = db.connect()?;

    // Create hits table — logs each pixel request by UUID
    conn.execute(
        "CREATE TABLE IF NOT EXISTS hits (
            uuid TEXT NOT NULL,
            ip TEXT NOT NULL,
            msg TEXT NOT NULL DEFAULT '',
            timestamp TEXT NOT NULL
        );",
        (),
    )
    .await?;

    let app = Router::new()
        .route("/", get(serve_index))
        .route("/pixel", get(serve_tracking_pixel))
        .route("/receipts", get(list_all_receipts))
        .route("/receipts/{uuid}", get(list_receipts_for_uuid))
        .with_state(Arc::new(AppState { db: conn }));

    let addr = SocketAddr::from(([0, 0, 0, 0], 8080));
    info!("server launching on http://{addr}");

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to register SIGINT handler");
        info!("received SIGINT, shutting down gracefully...");
    })
    .await?;

    Ok(())
}

/// Serves the static index HTML page.
async fn serve_index() -> impl IntoResponse {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/html; charset=utf-8")
        .body(axum::body::Body::from(include_str!("../index.html")))
        .unwrap()
}

/// Serves the 1x1 transparent PNG and logs the requester's IP + timestamp against the UUID.
/// Also dumps all query parameters and request headers at INFO level for debugging.
async fn serve_tracking_pixel(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
    headers: HeaderMap,
) -> impl IntoResponse {
    // Debug-log all query parameters
    let query_debug: Vec<String> = params.iter().map(|(k, v)| format!("{k} = {v}")).collect();
    let query_debug = query_debug.join(", ");

    // Debug-log all request headers
    let header_debug: Vec<String> = headers
        .iter()
        .map(|(k, v)| {
            let val = v.to_str().unwrap_or("<binary>");
            format!("{k}: {val}")
        })
        .collect();
    let header_debug = header_debug.join("\n");

    debug!("/pixel request\n  Query: {query_debug}\n  Headers:\n{header_debug}");

    // Extract real client IP from standard reverse-proxy headers (Cloudflare, Nginx, etc.)
    let client_ip = headers
        .get("X-Forwarded-For")
        .and_then(|h| h.to_str().ok())
        .and_then(|s| s.split(',').next())
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|| "0.0.0.0".to_string());

    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();

    if let Some(uuid) = params.get("uuid") {
        // Decode base64-encoded message text; default to empty string on any failure
        let msg: String = params
            .get("msg")
            .and_then(|m| general_purpose::URL_SAFE_NO_PAD.decode(m.as_bytes()).ok())
            .and_then(|bytes| {
                String::from_utf8(bytes)
                    .ok()
                    .map(|s| s.chars().take(500).collect())
            })
            .unwrap_or_default();

        if let Err(e) = state
            .db
            .execute(
                "INSERT INTO hits (uuid, ip, msg, timestamp) VALUES (?1, ?2, ?3, ?4)",
                (uuid.as_str(), client_ip, msg.as_str(), now),
            )
            .await
        {
            error!("failed to log hit: {e}");
        }
    } else {
        warn!("/pixel request without 'id' query parameter — hit not logged");
    }

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "image/png")
        .header(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .header(header::PRAGMA, "no-cache")
        .body(axum::body::Body::from(TRACKING_PIXEL))
        .unwrap()
}

/// Returns every recorded read receipt, newest first.
/// Supports optional `?q=` query parameter to filter by message text content.
async fn list_all_receipts(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<HitRecord>>, (StatusCode, String)> {
    let q = params.get("q").map(|s| s.as_str()).unwrap_or("");

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits ORDER BY timestamp DESC",
                (),
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    } else {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits WHERE msg LIKE ?1 ORDER BY timestamp DESC",
                libsql::params![format!("%{}%", q)],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    };

    let mut receipts = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        receipts.push(HitRecord {
            uuid: row.get_str(0).unwrap_or_default().to_string(),
            ip: row.get_str(1).unwrap_or_default().to_string(),
            msg: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
        });
    }

    Ok(Json(receipts))
}

/// Returns all read receipts for a specific UUID, newest first.
/// Supports optional `?q=` query parameter to filter by message text content.
async fn list_receipts_for_uuid(
    State(state): State<Arc<AppState>>,
    Path(uuid): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<HitRecord>>, (StatusCode, String)> {
    let q = params.get("q").map(|s| s.as_str()).unwrap_or("");

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits WHERE uuid = ?1 ORDER BY timestamp DESC",
                libsql::params![uuid],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    } else {
        state
            .db
            .query(
                "SELECT uuid, ip, msg, timestamp FROM hits WHERE uuid = ?1 AND msg LIKE ?2 ORDER BY timestamp DESC",
                libsql::params![uuid, format!("%{}%", q)],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    };

    let mut receipts = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        receipts.push(HitRecord {
            uuid: row.get_str(0).unwrap_or_default().to_string(),
            ip: row.get_str(1).unwrap_or_default().to_string(),
            msg: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
        });
    }

    Ok(Json(receipts))
}
