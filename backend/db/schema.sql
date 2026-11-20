-- Tech Live PostgreSQL Production Schema
-- Covers all 19 core domain entities with RBAC, payment ledger integrity,
-- resumable video pipeline, cloud FFmpeg workers, and dynamic system settings.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(40) UNIQUE NOT NULL CHECK (name IN ('USER', 'ADMIN', 'SUPER_ADMIN')),
    description TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO roles (name, description) VALUES
    ('USER', 'Standard Tech Live creator account'),
    ('ADMIN', 'Operations & payment verification administrator'),
    ('SUPER_ADMIN', 'Full platform owner with withdrawal & settings authority')
ON CONFLICT (name) DO NOTHING;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    firebase_uid VARCHAR(128) UNIQUE,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash TEXT,
    display_name VARCHAR(120) NOT NULL,
    avatar_url TEXT,
    role_id UUID NOT NULL REFERENCES roles(id),
    account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'BANNED')),
    daily_used_seconds INTEGER NOT NULL DEFAULT 0 CHECK (daily_used_seconds >= 0),
    daily_usage_date DATE NOT NULL DEFAULT CURRENT_DATE,
    terms_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(account_status);

CREATE TABLE subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(32) UNIQUE NOT NULL CHECK (code IN ('FREE', 'MONTHLY', 'LIFETIME')),
    name VARCHAR(80) NOT NULL,
    price_bdt NUMERIC(12, 2) NOT NULL CHECK (price_bdt >= 0),
    daily_limit_seconds INTEGER, -- NULL means unlimited
    storage_limit_mb INTEGER NOT NULL CHECK (storage_limit_mb >= 0),
    max_cloud_duration_hours INTEGER NOT NULL DEFAULT 168,
    allow_cloud_upload BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES subscription_plans(id),
    status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    starts_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_subscriptions_user ON subscriptions(user_id, status);

CREATE TABLE payment_methods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(32) UNIQUE NOT NULL CHECK (code IN ('BKASH', 'NAGAD', 'CARD', 'GATEWAY')),
    display_name VARCHAR(64) NOT NULL,
    instructions TEXT NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    payment_method_id UUID NOT NULL REFERENCES payment_methods(id),
    plan_code VARCHAR(32) NOT NULL CHECK (plan_code IN ('MONTHLY', 'LIFETIME')),
    transaction_id VARCHAR(120) UNIQUE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'BDT',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REJECTED', 'REFUNDED')),
    notes TEXT,
    verified_by UUID REFERENCES users(id),
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_user_status ON payments(user_id, status);
CREATE INDEX idx_payments_status_created ON payments(status, created_at DESC);

CREATE TABLE withdrawals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL REFERENCES users(id),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'BDT',
    method VARCHAR(32) NOT NULL CHECK (method IN ('BKASH', 'NAGAD', 'BANK_TRANSFER')),
    destination VARCHAR(180) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE videos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(140) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(80) NOT NULL,
    file_size_bytes BIGINT NOT NULL CHECK (file_size_bytes > 0 AND file_size_bytes <= 1073741824),
    uploaded_bytes BIGINT NOT NULL DEFAULT 0,
    duration_seconds INTEGER NOT NULL DEFAULT 0,
    resolution VARCHAR(32) NOT NULL,
    fps INTEGER NOT NULL DEFAULT 30,
    video_codec VARCHAR(40) NOT NULL,
    audio_codec VARCHAR(40) NOT NULL,
    storage_object_key TEXT NOT NULL,
    thumbnail_object_key TEXT,
    processing_stage VARCHAR(32) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_videos_user ON videos(user_id, created_at DESC);

CREATE TABLE video_processing_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    stage VARCHAR(32) NOT NULL,
    progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE stream_destinations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    platform VARCHAR(32) NOT NULL CHECK (platform IN ('YOUTUBE', 'FACEBOOK', 'TIKTOK', 'TELEGRAM', 'CUSTOM_RTMP', 'CUSTOM_RTMPS')),
    protocol VARCHAR(24) NOT NULL CHECK (protocol IN ('RTMP', 'RTMPS', 'OAUTH_API')),
    rtmp_url TEXT NOT NULL,
    encrypted_stream_key TEXT NOT NULL,
    encrypted_oauth_refresh_token TEXT,
    channel_name VARCHAR(120),
    connection_status VARCHAR(24) NOT NULL DEFAULT 'DISCONNECTED',
    supports_60fps BOOLEAN NOT NULL DEFAULT TRUE,
    supports_4k BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_destinations_user ON stream_destinations(user_id);

CREATE TABLE stream_presets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    resolution VARCHAR(16) NOT NULL,
    fps INTEGER NOT NULL CHECK (fps IN (24, 30, 60)),
    video_bitrate_kbps INTEGER NOT NULL,
    audio_bitrate_kbps INTEGER NOT NULL,
    orientation VARCHAR(24) NOT NULL CHECK (orientation IN ('LANDSCAPE_16_9', 'PORTRAIT_9_16')),
    aspect_mode VARCHAR(24) NOT NULL CHECK (aspect_mode IN ('FIT', 'CROP', 'STRETCH', 'BLUR_BG')),
    destination_ids UUID[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE streams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video_id UUID REFERENCES videos(id) ON DELETE SET NULL,
    title VARCHAR(140) NOT NULL,
    description TEXT,
    source_type VARCHAR(24) NOT NULL CHECK (source_type IN ('CAMERA', 'SCREEN', 'CLOUD_VIDEO')),
    orientation VARCHAR(24) NOT NULL,
    aspect_mode VARCHAR(24) NOT NULL,
    resolution VARCHAR(16) NOT NULL,
    fps INTEGER NOT NULL,
    video_bitrate_kbps INTEGER NOT NULL,
    audio_bitrate_kbps INTEGER NOT NULL,
    loop_mode VARCHAR(24) NOT NULL DEFAULT 'ONCE',
    planned_duration_seconds INTEGER NOT NULL DEFAULT 10800 CHECK (planned_duration_seconds <= 604800),
    duration_seconds INTEGER NOT NULL DEFAULT 0,
    dropped_frames_percent NUMERIC(5, 2) NOT NULL DEFAULT 0.0,
    data_transferred_mb NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    reconnect_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'LIVE' CHECK (status IN ('LIVE', 'RECONNECTING', 'RUNNING', 'COMPLETED', 'STOPPED', 'FAILED', 'SCHEDULED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ
);
CREATE INDEX idx_streams_user_status ON streams(user_id, status);

CREATE TABLE scheduled_streams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    title VARCHAR(140) NOT NULL,
    scheduled_start_at TIMESTAMPTZ NOT NULL,
    duration_hours INTEGER NOT NULL CHECK (duration_hours BETWEEN 1 AND 168),
    loop_mode VARCHAR(24) NOT NULL DEFAULT 'ONCE',
    recurrence VARCHAR(16) NOT NULL DEFAULT 'ONCE' CHECK (recurrence IN ('ONCE', 'DAILY', 'WEEKLY')),
    destination_ids UUID[] NOT NULL DEFAULT '{}',
    resolution VARCHAR(16) NOT NULL DEFAULT '1080p',
    fps INTEGER NOT NULL DEFAULT 30,
    orientation VARCHAR(24) NOT NULL DEFAULT 'LANDSCAPE_16_9',
    aspect_mode VARCHAR(24) NOT NULL DEFAULT 'FIT',
    status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_schedules_due ON scheduled_streams(status, scheduled_start_at);

CREATE TABLE stream_workers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stream_id UUID NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    node_name VARCHAR(80) NOT NULL,
    process_pid INTEGER,
    cpu_percent INTEGER NOT NULL DEFAULT 0,
    ram_percent INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL CHECK (status IN ('STARTING', 'RUNNING', 'RECONNECTING', 'STOPPED', 'FAILED')),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE stream_logs (
    id BIGSERIAL PRIMARY KEY,
    stream_id UUID NOT NULL REFERENCES streams(id) ON DELETE CASCADE,
    level VARCHAR(16) NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_stream_logs_stream ON stream_logs(stream_id, created_at DESC);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(48) NOT NULL,
    title VARCHAR(120) NOT NULL,
    message TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, is_read, created_at DESC);

CREATE TABLE storage_usage (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    used_bytes BIGINT NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
    limit_bytes BIGINT NOT NULL DEFAULT 1073741824,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_email VARCHAR(255),
    action VARCHAR(100) NOT NULL,
    target VARCHAR(200) NOT NULL,
    severity VARCHAR(24) NOT NULL DEFAULT 'INFO',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_logs_created ON audit_logs(created_at DESC);

CREATE TABLE system_settings (
    id VARCHAR(32) PRIMARY KEY DEFAULT 'global',
    free_daily_limit_seconds INTEGER NOT NULL DEFAULT 10800,
    monthly_price_bdt NUMERIC(12, 2) NOT NULL DEFAULT 100.00,
    lifetime_price_bdt NUMERIC(12, 2) NOT NULL DEFAULT 1500.00,
    max_upload_size_mb INTEGER NOT NULL DEFAULT 1024,
    free_storage_limit_mb INTEGER NOT NULL DEFAULT 1024,
    monthly_storage_limit_mb INTEGER NOT NULL DEFAULT 10240,
    lifetime_storage_limit_mb INTEGER NOT NULL DEFAULT 25600,
    max_cloud_duration_hours INTEGER NOT NULL DEFAULT 168,
    max_concurrent_streams INTEGER NOT NULL DEFAULT 3,
    allowed_resolutions TEXT[] NOT NULL DEFAULT ARRAY['360p','480p','720p','1080p','1440p','2160p'],
    allowed_fps INTEGER[] NOT NULL DEFAULT ARRAY[24,30,60],
    maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE,
    announcement_message TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
