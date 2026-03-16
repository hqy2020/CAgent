-- 用户长期记忆系统 DDL

CREATE TABLE IF NOT EXISTS t_user_memory (
    id                     BIGINT       NOT NULL COMMENT 'Snowflake',
    user_id                BIGINT       NOT NULL,
    memory_type            VARCHAR(16)  NOT NULL COMMENT 'PINNED/INSIGHT/DIGEST',
    content                TEXT         NOT NULL,
    source_conversation_id VARCHAR(32)  DEFAULT NULL,
    source_message_id      BIGINT       DEFAULT NULL,
    weight                 DECIMAL(3,2) NOT NULL DEFAULT 1.00,
    state                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    superseded_by          BIGINT       DEFAULT NULL,
    tags                   VARCHAR(512) DEFAULT NULL COMMENT 'JSON数组',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_user_state (user_id, state, deleted),
    INDEX idx_user_type (user_id, memory_type, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期记忆';

CREATE TABLE IF NOT EXISTS t_user_profile (
    id           BIGINT        NOT NULL COMMENT 'Snowflake',
    user_id      BIGINT        NOT NULL,
    display_name VARCHAR(64)   DEFAULT NULL COMMENT '偏好称呼',
    occupation   VARCHAR(128)  DEFAULT NULL,
    interests    VARCHAR(1024) DEFAULT NULL COMMENT 'JSON数组',
    preferences  TEXT          DEFAULT NULL COMMENT 'JSON对象',
    facts        TEXT          DEFAULT NULL COMMENT 'JSON数组',
    summary      TEXT          DEFAULT NULL COMMENT 'LLM生成的画像摘要',
    version      INT           NOT NULL DEFAULT 1,
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_id (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像';
