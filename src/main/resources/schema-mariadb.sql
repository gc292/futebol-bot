CREATE TABLE IF NOT EXISTS guild_settings (
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    manual_team_size INTEGER NOT NULL DEFAULT 5 CHECK(manual_team_size BETWEEN 1 AND 12),
    minimum_win_rate_games INTEGER NOT NULL DEFAULT 5 CHECK(minimum_win_rate_games >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS authorized_roles (
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(guild_id, role_id),
    FOREIGN KEY (guild_id) REFERENCES guild_settings(guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS players (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(80) NOT NULL,
    discord_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    rating DOUBLE NOT NULL,
    roster_order INTEGER NOT NULL,
    UNIQUE(guild_id, name), UNIQUE(guild_id, discord_id), UNIQUE(guild_id, id),
    FOREIGN KEY (guild_id) REFERENCES guild_settings(guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS divisions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(source IN ('BOT','MANUAL')),
    mode VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(mode IN ('TEAMS','REMAKE','MANUAL')),
    team_size INTEGER NOT NULL CHECK(team_size BETWEEN 1 AND 12),
    UNIQUE(guild_id, id),
    FOREIGN KEY (guild_id) REFERENCES guild_settings(guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS division_players (
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    division_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    team VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(team IN ('A','B')),
    position INTEGER NOT NULL,
    PRIMARY KEY(division_id, player_id),
    UNIQUE(division_id, team, position),
    FOREIGN KEY(guild_id, division_id) REFERENCES divisions(guild_id, id),
    FOREIGN KEY(guild_id, player_id) REFERENCES players(guild_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS matches (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    division_id BIGINT NOT NULL UNIQUE,
    goals_a INTEGER NOT NULL CHECK(goals_a >= 0),
    goals_b INTEGER NOT NULL CHECK(goals_b >= 0),
    played_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_by VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    UNIQUE(guild_id, id),
    FOREIGN KEY(guild_id, division_id) REFERENCES divisions(guild_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE INDEX IF NOT EXISTS matches_chronology ON matches(guild_id, played_at, id);

CREATE INDEX IF NOT EXISTS division_player_history ON division_players(guild_id, player_id, division_id);

CREATE TABLE IF NOT EXISTS drafts (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(action IN ('CREATE','EDIT','DELETE')),
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(status IN ('ACTIVE','READY','CONFIRMED','CANCELLED')),
    division_id BIGINT,
    target_match_id BIGINT,
    target_version INTEGER,
    team_size INTEGER NOT NULL,
    goals_a INTEGER,
    goals_b INTEGER,
    played_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    confirmed_match_id BIGINT,
    UNIQUE(guild_id, id),
    FOREIGN KEY(guild_id, division_id) REFERENCES divisions(guild_id, id),
    FOREIGN KEY (guild_id) REFERENCES guild_settings(guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS draft_players (
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_id BIGINT NOT NULL,
    team VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(team IN ('A','B')),
    position INTEGER NOT NULL,
    PRIMARY KEY(draft_id, player_id),
    UNIQUE(draft_id, team, position),
    FOREIGN KEY(guild_id, draft_id) REFERENCES drafts(guild_id, id),
    FOREIGN KEY(guild_id, player_id) REFERENCES players(guild_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS rating_polls (
    id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_id BIGINT NOT NULL,
    form_url TEXT,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CREATING' CHECK(status IN ('CREATING','OPEN','CLOSED')),
    vote_count INTEGER NOT NULL DEFAULT 0,
    average DOUBLE,
    updated_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE(guild_id,player_id),
    FOREIGN KEY(guild_id,player_id) REFERENCES players(guild_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS removed_players (
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    player_id BIGINT NOT NULL,
    removed_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(guild_id,player_id),
    FOREIGN KEY(guild_id,player_id) REFERENCES players(guild_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS rating_cleanup (
    poll_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    guild_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    complete INTEGER NOT NULL DEFAULT 0 CHECK(complete IN (0,1)),
    FOREIGN KEY (guild_id) REFERENCES guild_settings(guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS native_polls (
    poll_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    FOREIGN KEY (poll_id) REFERENCES rating_polls(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS rating_votes (
    poll_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    voter_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    score DOUBLE NOT NULL CHECK(score >= 0 AND score <= 100),
    PRIMARY KEY(poll_id,voter_id),
    FOREIGN KEY (poll_id) REFERENCES native_polls(poll_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS schema_metadata (id INTEGER PRIMARY KEY, version INTEGER NOT NULL) ENGINE=InnoDB;

INSERT IGNORE INTO schema_metadata VALUES(1,1);

CREATE TABLE IF NOT EXISTS web_sessions (
 token_hash CHAR(64) CHARACTER SET ascii PRIMARY KEY,
 guild_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
 expires_at BIGINT NOT NULL,
 INDEX web_sessions_expiry(expires_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS web_password_attempts (
 attempt_key CHAR(64) CHARACTER SET ascii PRIMARY KEY,
 attempts INTEGER NOT NULL,
 expires_at BIGINT NOT NULL,
 INDEX web_attempts_expiry(expires_at)
) ENGINE=InnoDB;
