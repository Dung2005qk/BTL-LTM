-- GeoDuel schema. Chạy: mysql -u root -p < database/schema.sql
-- Tạo database chính (geoduel) và database kiểm thử (geoduel_test) cùng cấu trúc.
-- Chạy lại an toàn: mọi bảng dùng IF NOT EXISTS.

CREATE DATABASE IF NOT EXISTS geoduel CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS geoduel_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ===================== geoduel =====================
USE geoduel;

CREATE TABLE IF NOT EXISTS users (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(32)  NOT NULL UNIQUE,
  password_hash CHAR(64)     NOT NULL,
  salt          CHAR(32)     NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  elo           INT          NOT NULL DEFAULT 1000,
  wins          INT          NOT NULL DEFAULT 0,
  losses        INT          NOT NULL DEFAULT 0,
  draws         INT          NOT NULL DEFAULT 0,
  total_score   BIGINT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_leaderboard (elo DESC, wins DESC, total_score DESC)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS locations (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  slug       VARCHAR(64)  NOT NULL UNIQUE,
  name       VARCHAR(128) NOT NULL,
  country    VARCHAR(64)  NOT NULL,
  target_lat DOUBLE       NOT NULL,
  target_lng DOUBLE       NOT NULL,
  active     TINYINT(1)   NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS location_images (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  location_id INT          NOT NULL,
  file_path   VARCHAR(255) NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  is_pano     TINYINT(1)   NOT NULL DEFAULT 0,
  CONSTRAINT fk_li_location FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS matches (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  player1_id    INT       NOT NULL,
  player2_id    INT       NOT NULL,
  started_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at      TIMESTAMP NULL,
  total_score1  INT       NOT NULL DEFAULT 0,
  total_score2  INT       NOT NULL DEFAULT 0,
  result        ENUM('P1_WIN','P2_WIN','DRAW') NULL,
  end_reason    ENUM('NORMAL','P1_QUIT','P2_QUIT','P1_DISCONNECT','P2_DISCONNECT') NOT NULL DEFAULT 'NORMAL',
  elo_change1   INT       NOT NULL DEFAULT 0,
  elo_change2   INT       NOT NULL DEFAULT 0,
  elo_after1    INT       NOT NULL DEFAULT 0,
  elo_after2    INT       NOT NULL DEFAULT 0,
  CONSTRAINT fk_m_p1 FOREIGN KEY (player1_id) REFERENCES users(id),
  CONSTRAINT fk_m_p2 FOREIGN KEY (player2_id) REFERENCES users(id),
  INDEX idx_history_p1 (player1_id, started_at DESC),
  INDEX idx_history_p2 (player2_id, started_at DESC)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS match_rounds (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  match_id    INT NOT NULL,
  round_no    INT NOT NULL,
  location_id INT NOT NULL,
  guess1_lat  DOUBLE NULL,
  guess1_lng  DOUBLE NULL,
  dist1_km    DOUBLE NULL,
  score1      INT NOT NULL DEFAULT 0,
  guess2_lat  DOUBLE NULL,
  guess2_lng  DOUBLE NULL,
  dist2_km    DOUBLE NULL,
  score2      INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_r_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
  CONSTRAINT fk_r_location FOREIGN KEY (location_id) REFERENCES locations(id),
  UNIQUE KEY uq_match_round (match_id, round_no)
) ENGINE=InnoDB;

-- ===================== geoduel_test (cùng cấu trúc) =====================
USE geoduel_test;

CREATE TABLE IF NOT EXISTS users (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(32)  NOT NULL UNIQUE,
  password_hash CHAR(64)     NOT NULL,
  salt          CHAR(32)     NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  elo           INT          NOT NULL DEFAULT 1000,
  wins          INT          NOT NULL DEFAULT 0,
  losses        INT          NOT NULL DEFAULT 0,
  draws         INT          NOT NULL DEFAULT 0,
  total_score   BIGINT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_leaderboard (elo DESC, wins DESC, total_score DESC)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS locations (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  slug       VARCHAR(64)  NOT NULL UNIQUE,
  name       VARCHAR(128) NOT NULL,
  country    VARCHAR(64)  NOT NULL,
  target_lat DOUBLE       NOT NULL,
  target_lng DOUBLE       NOT NULL,
  active     TINYINT(1)   NOT NULL DEFAULT 1
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS location_images (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  location_id INT          NOT NULL,
  file_path   VARCHAR(255) NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  is_pano     TINYINT(1)   NOT NULL DEFAULT 0,
  CONSTRAINT fk_li_location_t FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS matches (
  id            INT AUTO_INCREMENT PRIMARY KEY,
  player1_id    INT       NOT NULL,
  player2_id    INT       NOT NULL,
  started_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at      TIMESTAMP NULL,
  total_score1  INT       NOT NULL DEFAULT 0,
  total_score2  INT       NOT NULL DEFAULT 0,
  result        ENUM('P1_WIN','P2_WIN','DRAW') NULL,
  end_reason    ENUM('NORMAL','P1_QUIT','P2_QUIT','P1_DISCONNECT','P2_DISCONNECT') NOT NULL DEFAULT 'NORMAL',
  elo_change1   INT       NOT NULL DEFAULT 0,
  elo_change2   INT       NOT NULL DEFAULT 0,
  elo_after1    INT       NOT NULL DEFAULT 0,
  elo_after2    INT       NOT NULL DEFAULT 0,
  CONSTRAINT fk_m_p1_t FOREIGN KEY (player1_id) REFERENCES users(id),
  CONSTRAINT fk_m_p2_t FOREIGN KEY (player2_id) REFERENCES users(id),
  INDEX idx_history_p1 (player1_id, started_at DESC),
  INDEX idx_history_p2 (player2_id, started_at DESC)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS match_rounds (
  id          INT AUTO_INCREMENT PRIMARY KEY,
  match_id    INT NOT NULL,
  round_no    INT NOT NULL,
  location_id INT NOT NULL,
  guess1_lat  DOUBLE NULL,
  guess1_lng  DOUBLE NULL,
  dist1_km    DOUBLE NULL,
  score1      INT NOT NULL DEFAULT 0,
  guess2_lat  DOUBLE NULL,
  guess2_lng  DOUBLE NULL,
  dist2_km    DOUBLE NULL,
  score2      INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_r_match_t FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
  CONSTRAINT fk_r_location_t FOREIGN KEY (location_id) REFERENCES locations(id),
  UNIQUE KEY uq_match_round (match_id, round_no)
) ENGINE=InnoDB;
