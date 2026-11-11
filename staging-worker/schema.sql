CREATE TABLE IF NOT EXISTS devices (
 id TEXT PRIMARY KEY, sender TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
 enabled INTEGER NOT NULL DEFAULT 0, fcm_token TEXT, created_at INTEGER NOT NULL,
 last_seen_at INTEGER
);
CREATE INDEX IF NOT EXISTS devices_sender ON devices(sender,enabled);
CREATE TABLE IF NOT EXISTS pairing_codes (
 code_hash TEXT PRIMARY KEY,sender TEXT NOT NULL,expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS jobs (
 id TEXT PRIMARY KEY,device_id TEXT NOT NULL,phone TEXT NOT NULL,text TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PENDING','CLAIMED','DISPATCHED','MODEM_ACCEPTED','FAILED_UNCERTAIN','UNKNOWN')),
 created_at INTEGER NOT NULL,claimed_at INTEGER,completed_at INTEGER
);
CREATE INDEX IF NOT EXISTS jobs_device_status ON jobs(device_id,status,created_at);
CREATE TABLE IF NOT EXISTS job_parts (
 job_id TEXT NOT NULL,part INTEGER NOT NULL,status TEXT NOT NULL,
 PRIMARY KEY(job_id,part)
);
