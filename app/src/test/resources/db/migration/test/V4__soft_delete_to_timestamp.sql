-- 将 is_deleted 改为 delete_at（毫秒时间戳），并调整相关唯一索引

DO $$
BEGIN
    -- ================== t_user ==================
    IF EXISTS (SELECT 1
               FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 't_user'
                 AND column_name = 'is_deleted') THEN
        ALTER TABLE t_user ADD COLUMN IF NOT EXISTS delete_at BIGINT DEFAULT 0;
        UPDATE t_user
        SET delete_at = CASE
                            WHEN is_deleted = 1 THEN (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
                            ELSE 0
            END;
        ALTER TABLE t_user ALTER COLUMN delete_at SET NOT NULL;
        ALTER TABLE t_user DROP COLUMN is_deleted;
    END IF;

    IF EXISTS (SELECT 1
               FROM information_schema.table_constraints
               WHERE table_schema = 'public'
                 AND table_name = 't_user'
                 AND constraint_name = 't_user_username_key') THEN
        ALTER TABLE t_user DROP CONSTRAINT t_user_username_key;
        ALTER TABLE t_user ADD CONSTRAINT uk_user_username_delete_at UNIQUE (username, delete_at);
    END IF;

    -- ================== t_role ==================
    IF EXISTS (SELECT 1
               FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 't_role'
                 AND column_name = 'is_deleted') THEN
        ALTER TABLE t_role ADD COLUMN IF NOT EXISTS delete_at BIGINT DEFAULT 0;
        UPDATE t_role
        SET delete_at = CASE
                            WHEN is_deleted = 1 THEN (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
                            ELSE 0
            END;
        ALTER TABLE t_role ALTER COLUMN delete_at SET NOT NULL;
        ALTER TABLE t_role DROP COLUMN is_deleted;
    END IF;

    IF EXISTS (SELECT 1
               FROM information_schema.table_constraints
               WHERE table_schema = 'public'
                 AND table_name = 't_role'
                 AND constraint_name = 't_role_code_key') THEN
        ALTER TABLE t_role DROP CONSTRAINT t_role_code_key;
        ALTER TABLE t_role ADD CONSTRAINT uk_role_code_delete_at UNIQUE (code, delete_at);
    END IF;

    -- ================== t_permission ==================
    IF EXISTS (SELECT 1
               FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 't_permission'
                 AND column_name = 'is_deleted') THEN
        ALTER TABLE t_permission ADD COLUMN IF NOT EXISTS delete_at BIGINT DEFAULT 0;
        UPDATE t_permission
        SET delete_at = CASE
                            WHEN is_deleted = 1 THEN (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
                            ELSE 0
            END;
        ALTER TABLE t_permission ALTER COLUMN delete_at SET NOT NULL;
        ALTER TABLE t_permission DROP COLUMN is_deleted;
    END IF;

    IF EXISTS (SELECT 1
               FROM information_schema.table_constraints
               WHERE table_schema = 'public'
                 AND table_name = 't_permission'
                 AND constraint_name = 't_permission_code_key') THEN
        ALTER TABLE t_permission DROP CONSTRAINT t_permission_code_key;
        ALTER TABLE t_permission ADD CONSTRAINT uk_permission_code_delete_at UNIQUE (code, delete_at);
    END IF;

    -- ================== t_resource ==================
    IF EXISTS (SELECT 1
               FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 't_resource'
                 AND column_name = 'is_deleted') THEN
        ALTER TABLE t_resource ADD COLUMN IF NOT EXISTS delete_at BIGINT DEFAULT 0;
        UPDATE t_resource
        SET delete_at = CASE
                            WHEN is_deleted = 1 THEN (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
                            ELSE 0
            END;
        ALTER TABLE t_resource ALTER COLUMN delete_at SET NOT NULL;
        ALTER TABLE t_resource DROP COLUMN is_deleted;
    END IF;

    IF EXISTS (SELECT 1
               FROM information_schema.table_constraints
               WHERE table_schema = 'public'
                 AND table_name = 't_resource'
                 AND constraint_name = 't_resource_code_key') THEN
        ALTER TABLE t_resource DROP CONSTRAINT t_resource_code_key;
        ALTER TABLE t_resource ADD CONSTRAINT uk_resource_code_delete_at UNIQUE (code, delete_at);
    END IF;
END $$;
