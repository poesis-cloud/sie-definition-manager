-- ============================================================
-- V1__gsm_init.sql  –  GSM canonical schema (merged V1-V10)
--
-- Fixes applied during merge:
--   1. Column name kept as 'statement' (entity truth; V10's
--      rename to 'compilation' was stale).
--   2. norm.qualifier_id added (NormEntity FK, was missing).
--   3. archetype.schema_uri removed (entity dropped it).
--   4. Version CHECK fixed: >= 0 (V5 set DEFAULT 0 but kept > 0).
--   5. subject_type dropped from transition table (V6 change).
--   6. Effector/Receptor columns: output/input_archetype_id (V9).
--   7. directive.purpose dematerialized (V7) — stored in statement JSONB only.
--   8. version column dropped (design-phase cleanup; never used for queries).
--   9. pre_status made NOT NULL (DRAFT implicit; no initial transition needed).
-- ============================================================
BEGIN;

-- ============================================================
-- §1  Extensions + UUIDv7
-- ============================================================
CREATE
    extension IF NOT EXISTS pgcrypto;

CREATE
    OR replace FUNCTION uuid_v7() RETURNS uuid AS $$ DECLARE v_time BIGINT :=(
        EXTRACT(
            epoch
        FROM
            clock_timestamp()
        )* 1000
    )::BIGINT;

v_bytes bytea;

BEGIN -- 48 bits timestamp (ms) + 80 bits random = 128 bits

v_bytes := SUBSTRING( int8send( v_time ) FROM 3 FOR 6 )|| gen_random_bytes(10);

-- Version 7 (bits 48-51 = 0111): byte index 6, high nibble

v_bytes := set_byte(
    v_bytes,
    6,
    (
        get_byte(
            v_bytes,
            6
        )& 15
    )| 112
);

-- Variant 10 (bits 64-65): byte index 8, high 2 bits

v_bytes := set_byte(
    v_bytes,
    8,
    (
        get_byte(
            v_bytes,
            8
        )& 63
    )| 128
);

RETURN encode(
    v_bytes,
    'hex'
)::uuid;
END;

$$ LANGUAGE plpgsql volatile;

-- ============================================================
-- §2  PostgreSQL enum types
-- ============================================================

do $$ BEGIN CREATE
    TYPE ascription_status AS enum(
        'DRAFT',
        'PROPOSED',
        'APPROVED',
        'ACTIVE',
        'SUSPENDED',
        'DEPRECATED',
        'RETIRED',
        'ABANDONED',
        'REJECTED'
    );

EXCEPTION
WHEN duplicate_object THEN NULL;
END $$;

do $$ BEGIN CREATE
    TYPE definition_subject_type AS enum(
        'ARCHETYPE',
        'STRUCTURE',
        'MECHANISM',
        'EFFECTOR',
        'RECEPTOR',
        'INTERACTION',
        'DIRECTIVE',
        'NORM'
    );

EXCEPTION
WHEN duplicate_object THEN NULL;
END $$;

-- ============================================================
-- §3  Tables
-- ============================================================
-- 3a. definition --------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS definition(
            id uuid NOT NULL DEFAULT uuid_v7(),
            subject_type definition_subject_type NOT NULL,
            CONSTRAINT definition_pk PRIMARY KEY(id)
        );

-- 3b. archetype (self-ref FK: archetype_id → own table) ----------
CREATE
    TABLE
        IF NOT EXISTS archetype(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL,
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            CONSTRAINT archetype_pk PRIMARY KEY(id),
            CONSTRAINT archetype_typed_by_fk FOREIGN KEY(archetype_id) REFERENCES archetype(id)
        );

-- 3c. structure ---------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS STRUCTURE(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            CONSTRAINT structure_pk PRIMARY KEY(id)
        );

-- 3d. mechanism ---------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS mechanism(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            structure_id uuid NOT NULL REFERENCES STRUCTURE(id),
            CONSTRAINT mechanism_pk PRIMARY KEY(id)
        );

-- 3e. effector ----------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS effector(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            mechanism_id uuid NOT NULL REFERENCES mechanism(id),
            output_archetype_id uuid NOT NULL REFERENCES archetype(id),
            CONSTRAINT effector_pk PRIMARY KEY(id)
        );

-- 3f. receptor ----------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS receptor(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            mechanism_id uuid NOT NULL REFERENCES mechanism(id),
            input_archetype_id uuid NOT NULL REFERENCES archetype(id),
            CONSTRAINT receptor_pk PRIMARY KEY(id)
        );

-- 3g. interaction -------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS interaction(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            effector_id uuid NOT NULL REFERENCES effector(id),
            receptor_id uuid NOT NULL REFERENCES receptor(id),
            CONSTRAINT interaction_pk PRIMARY KEY(id)
        );

-- 3h. directive ---------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS directive(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            structure_id uuid NOT NULL REFERENCES STRUCTURE(id),
            qualifier_id uuid NOT NULL REFERENCES archetype(id),
            CONSTRAINT directive_pk PRIMARY KEY(id)
        );

-- 3i. norm --------------------------------------------------------
CREATE
    TABLE
        IF NOT EXISTS norm(
            id uuid NOT NULL DEFAULT uuid_v7(),
            definition_id uuid NOT NULL REFERENCES definition(id),
            archetype_id uuid NOT NULL REFERENCES archetype(id),
            STATEMENT jsonb NOT NULL DEFAULT '{}'::jsonb,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            status ascription_status NOT NULL DEFAULT 'DRAFT',
            structure_id uuid NOT NULL REFERENCES STRUCTURE(id),
            qualifier_id uuid NOT NULL REFERENCES archetype(id),
            CONSTRAINT norm_pk PRIMARY KEY(id)
        );

-- 3j. ascription_status_transition --------------------------------
CREATE
    TABLE
        IF NOT EXISTS ascription_status_transition(
            id uuid NOT NULL DEFAULT uuid_v7(),
            ascription_id uuid NOT NULL,
            pre_status ascription_status NOT NULL,
            post_status ascription_status NOT NULL,
            "timestamp" timestamptz NOT NULL DEFAULT clock_timestamp(),
            CONSTRAINT ast_pk PRIMARY KEY(id),
            CONSTRAINT ast_no_noop_transition CHECK(
                pre_status IS DISTINCT
            FROM
                post_status
            )
        );

-- ============================================================
-- §4  Indexes
-- ============================================================
-- 4a. definition --------------------------------------------------
CREATE
    INDEX IF NOT EXISTS idx_definition_subject_type ON
    definition(subject_type);

-- 4b. Per-class-table common indexes (definition_id, status, GIN) -
-- archetype
CREATE
    INDEX IF NOT EXISTS idx_archetype_definition ON
    archetype(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_archetype_status ON
    archetype(status);

CREATE
    INDEX IF NOT EXISTS gin_archetype_stmt ON
    archetype
        USING gin(STATEMENT);

-- structure
CREATE
    INDEX IF NOT EXISTS idx_structure_definition ON
    STRUCTURE(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_structure_status ON
    STRUCTURE(status);

CREATE
    INDEX IF NOT EXISTS gin_structure_stmt ON
    STRUCTURE
        USING gin(STATEMENT);

-- mechanism
CREATE
    INDEX IF NOT EXISTS idx_mechanism_definition ON
    mechanism(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_mechanism_status ON
    mechanism(status);

CREATE
    INDEX IF NOT EXISTS idx_mechanism_structure ON
    mechanism(structure_id);

CREATE
    INDEX IF NOT EXISTS gin_mechanism_stmt ON
    mechanism
        USING gin(STATEMENT);

-- effector
CREATE
    INDEX IF NOT EXISTS idx_effector_definition ON
    effector(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_effector_status ON
    effector(status);

CREATE
    INDEX IF NOT EXISTS idx_effector_mechanism ON
    effector(mechanism_id);

CREATE
    INDEX IF NOT EXISTS idx_effector_output_arch ON
    effector(output_archetype_id);

CREATE
    INDEX IF NOT EXISTS gin_effector_stmt ON
    effector
        USING gin(STATEMENT);

-- receptor
CREATE
    INDEX IF NOT EXISTS idx_receptor_definition ON
    receptor(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_receptor_status ON
    receptor(status);

CREATE
    INDEX IF NOT EXISTS idx_receptor_mechanism ON
    receptor(mechanism_id);

CREATE
    INDEX IF NOT EXISTS idx_receptor_input_arch ON
    receptor(input_archetype_id);

CREATE
    INDEX IF NOT EXISTS gin_receptor_stmt ON
    receptor
        USING gin(STATEMENT);

-- interaction
CREATE
    INDEX IF NOT EXISTS idx_interaction_definition ON
    interaction(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_interaction_status ON
    interaction(status);

CREATE
    INDEX IF NOT EXISTS idx_interaction_effector ON
    interaction(effector_id);

CREATE
    INDEX IF NOT EXISTS idx_interaction_receptor ON
    interaction(receptor_id);

CREATE
    INDEX IF NOT EXISTS gin_interaction_stmt ON
    interaction
        USING gin(STATEMENT);

-- directive
CREATE
    INDEX IF NOT EXISTS idx_directive_definition ON
    directive(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_directive_status ON
    directive(status);

CREATE
    INDEX IF NOT EXISTS idx_directive_structure ON
    directive(structure_id);

CREATE
    INDEX IF NOT EXISTS idx_directive_qualifier ON
    directive(qualifier_id);

CREATE
    INDEX IF NOT EXISTS idx_directive_stmt_purpose ON
    directive(
        (
            STATEMENT ->> 'purpose'
        )
    );

CREATE
    INDEX IF NOT EXISTS gin_directive_stmt ON
    directive
        USING gin(STATEMENT);

-- norm
CREATE
    INDEX IF NOT EXISTS idx_norm_definition ON
    norm(definition_id);

CREATE
    INDEX IF NOT EXISTS idx_norm_status ON
    norm(status);

CREATE
    INDEX IF NOT EXISTS idx_norm_structure ON
    norm(structure_id);

CREATE
    INDEX IF NOT EXISTS idx_norm_qualifier ON
    norm(qualifier_id);

CREATE
    INDEX IF NOT EXISTS gin_norm_stmt ON
    norm
        USING gin(STATEMENT);

-- 4c. Transition table indexes ------------------------------------
CREATE
    INDEX IF NOT EXISTS idx_ast_ascription ON
    ascription_status_transition(ascription_id);

CREATE
    INDEX IF NOT EXISTS idx_ast_latest_lookup ON
    ascription_status_transition(
        ascription_id,
        "timestamp" DESC,
        id DESC
    );

CREATE
    INDEX IF NOT EXISTS idx_ast_edge ON
    ascription_status_transition(
        ascription_id,
        pre_status,
        post_status
    );

-- 4d. Lifecycle uniqueness (at most one ACTIVE / APPROVED per def) -
CREATE
    UNIQUE INDEX IF NOT EXISTS uq_archetype_active ON
    archetype(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_archetype_approved ON
    archetype(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_structure_active ON
    STRUCTURE(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_structure_approved ON
    STRUCTURE(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_mechanism_active ON
    mechanism(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_mechanism_approved ON
    mechanism(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_effector_active ON
    effector(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_effector_approved ON
    effector(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_receptor_active ON
    receptor(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_receptor_approved ON
    receptor(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_interaction_active ON
    interaction(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_interaction_approved ON
    interaction(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_directive_active ON
    directive(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_directive_approved ON
    directive(definition_id)
WHERE
    status = 'APPROVED';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_norm_active ON
    norm(definition_id)
WHERE
    status = 'ACTIVE';

CREATE
    UNIQUE INDEX IF NOT EXISTS uq_norm_approved ON
    norm(definition_id)
WHERE
    status = 'APPROVED';

-- 4e. Expression indexes — GSM §9 identity uniqueness -------------
-- Structure.purpose globally unique among ACTIVE
CREATE
    UNIQUE INDEX IF NOT EXISTS uq_structure_purpose ON
    STRUCTURE(
        (
            STATEMENT ->> 'purpose'
        )
    )
WHERE
    status = 'ACTIVE';

-- Mechanism.function unique within owning Structure among ACTIVE
CREATE
    UNIQUE INDEX IF NOT EXISTS uq_mechanism_function ON
    mechanism(
        structure_id,
        (
            STATEMENT ->> 'function'
        )
    )
WHERE
    status = 'ACTIVE';

-- Archetype.title globally unique among ACTIVE
CREATE
    UNIQUE INDEX IF NOT EXISTS uq_archetype_title ON
    archetype(
        (
            STATEMENT ->> 'title'
        )
    )
WHERE
    status = 'ACTIVE';

-- ============================================================
-- §5  Seed data — GSM base archetypes
--     Now loaded at application startup by ArchetypeSeedRunner
--     from classpath:statement/*.json.
--     Single source of truth: def/statement/
-- ============================================================
-- ============================================================
-- §6  Trigger functions
-- ============================================================
-- 6a. Assign PK id if null ----------------------------------------
CREATE
    OR replace FUNCTION tgf_assign_id() RETURNS TRIGGER AS $$ BEGIN IF NEW.id IS NULL THEN NEW.id := uuid_v7();
END IF;

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6b. Assign authoritative creation timestamp ---------------------
CREATE
    OR replace FUNCTION tgf_assign_timestamp() RETURNS TRIGGER AS $$ BEGIN NEW."timestamp" := clock_timestamp();

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6c. Helper: resolve ascription class table from id --------------
CREATE
    OR replace FUNCTION gsm_find_ascription_table(
        p_id uuid
    ) RETURNS text AS $$ DECLARE tbl text;

BEGIN SELECT
    sub.tbl INTO
        tbl
    FROM
        (
            SELECT
                'archetype'::text AS tbl
            FROM
                archetype
            WHERE
                id = p_id
        UNION ALL SELECT
                'structure'
            FROM
                STRUCTURE
            WHERE
                id = p_id
        UNION ALL SELECT
                'mechanism'
            FROM
                mechanism
            WHERE
                id = p_id
        UNION ALL SELECT
                'effector'
            FROM
                effector
            WHERE
                id = p_id
        UNION ALL SELECT
                'receptor'
            FROM
                receptor
            WHERE
                id = p_id
        UNION ALL SELECT
                'interaction'
            FROM
                interaction
            WHERE
                id = p_id
        UNION ALL SELECT
                'directive'
            FROM
                directive
            WHERE
                id = p_id
        UNION ALL SELECT
                'norm'
            FROM
                norm
            WHERE
                id = p_id
        ) sub LIMIT 1;

RETURN tbl;
END;

$$ LANGUAGE plpgsql stable;

-- 6d. Validate transition.ascription_id references an ascription --
CREATE
    OR replace FUNCTION tgf_assert_transition_ascription_exists() RETURNS TRIGGER AS $$ BEGIN IF gsm_find_ascription_table(NEW.ascription_id) IS NULL THEN raise EXCEPTION 'ascription_status_transition.ascription_id = % references no ascription row',
    NEW.ascription_id
        USING errcode = 'foreign_key_violation';
END IF;

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6e. Sync ascription status from transition ----------------------
CREATE
    OR replace FUNCTION tgf_sync_ascription_status() RETURNS TRIGGER AS $$ DECLARE tbl text;

BEGIN tbl := gsm_find_ascription_table(NEW.ascription_id);

IF tbl IS NULL THEN raise EXCEPTION 'tgf_sync_ascription_status: no ascription row for id = %',
NEW.ascription_id
    USING errcode = 'foreign_key_violation';
END IF;

EXECUTE format(
    'update %I set status = $1 where id = $2',
    tbl
)
    USING NEW.post_status::ascription_status,
NEW.ascription_id;

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6g. Verify ascription status matches latest transition ----------
-- Re-reads current row status instead of relying on NEW, so that
-- deferred evaluation after multiple cascaded updates (from split
-- status-sync and version-assign triggers) sees the final state.
CREATE
    OR replace FUNCTION tgf_assert_ascription_status_matches_history() RETURNS TRIGGER AS $$ DECLARE current_status ascription_status;

expected ascription_status;

BEGIN EXECUTE format(
    'select status from %I where id = $1',
    TG_TABLE_NAME
) INTO
    current_status
        USING NEW.id;

IF current_status IS NULL THEN RETURN NEW;
END IF;

SELECT
    t.post_status INTO
        expected
    FROM
        ascription_status_transition t
    WHERE
        t.ascription_id = NEW.id
    ORDER BY
        t."timestamp" DESC,
        t.id DESC LIMIT 1;

IF expected IS NULL THEN IF current_status <> 'DRAFT' THEN raise EXCEPTION '% id=%: status=% but no transition history (expected DRAFT)',
TG_TABLE_NAME,
NEW.id,
current_status
    USING errcode = 'check_violation';
END IF;

elsif current_status <> expected THEN raise EXCEPTION '% id=%: status=% but latest transition says %',
TG_TABLE_NAME,
NEW.id,
current_status,
expected
    USING errcode = 'check_violation';
END IF;

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6h. Transition rows are immutable -------------------------------
CREATE
    OR replace FUNCTION tgf_reject_transition_mutation() RETURNS TRIGGER AS $$ BEGIN raise EXCEPTION 'ascription_status_transition rows are immutable (attempted %)',
    TG_OP
        USING errcode = 'restrict_violation';
END;

$$ LANGUAGE plpgsql;

-- 6i. Block direct status column updates --------------------------
CREATE
    OR replace FUNCTION tgf_reject_status_update() RETURNS TRIGGER AS $$ BEGIN -- Allow trigger-cascaded updates (sync trigger operates at depth >= 2)
IF pg_trigger_depth()>= 2 THEN RETURN NEW;
END IF;

IF OLD.status IS DISTINCT
FROM
NEW.status THEN raise EXCEPTION '% id=%: direct status update forbidden; insert into ascription_status_transition instead',
TG_TABLE_NAME,
NEW.id
    USING errcode = 'restrict_violation';
END IF;

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6j. Block PK (id) changes --------------------------------------
CREATE
    OR replace FUNCTION tgf_reject_id_update() RETURNS TRIGGER AS $$ BEGIN IF OLD.id IS DISTINCT
FROM
    NEW.id THEN raise EXCEPTION '% id=%: primary key (id) is immutable',
    TG_TABLE_NAME,
    OLD.id
        USING errcode = 'restrict_violation';
END IF;

RETURN NEW;
END;

$$ LANGUAGE plpgsql;

-- 6k. Prevent ascription delete when transitions exist ------------
CREATE
    OR replace FUNCTION tgf_restrict_ascription_delete_when_transitions_exist() RETURNS TRIGGER AS $$ DECLARE cnt BIGINT;

BEGIN SELECT
    COUNT(*) INTO
        cnt
    FROM
        ascription_status_transition
    WHERE
        ascription_id = OLD.id;

IF cnt > 0 THEN raise EXCEPTION '% id=%: cannot delete; % transition(s) exist',
TG_TABLE_NAME,
OLD.id,
cnt
    USING errcode = 'restrict_violation';
END IF;

RETURN OLD;
END;

$$ LANGUAGE plpgsql;

-- ============================================================
-- §7  Trigger attachments
-- ============================================================
-- Attach the standard 6-trigger set to each ascription class table.
-- PostgreSQL requires one CREATE TRIGGER per trigger, so we
-- enumerate all 8 tables × 6 triggers.
-- ---- archetype ----
CREATE
    TRIGGER trg_archetype_assign_id BEFORE INSERT
        ON
        archetype FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_archetype_assign_timestamp BEFORE INSERT
        ON
        archetype FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_archetype_reject_status_update BEFORE UPDATE
        OF status ON
        archetype FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_archetype_reject_id_update BEFORE UPDATE
        OF id ON
        archetype FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_archetype_restrict_delete BEFORE DELETE
        ON
        archetype FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_archetype_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            archetype DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- structure ----
CREATE
    TRIGGER trg_structure_assign_id BEFORE INSERT
        ON
        STRUCTURE FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_structure_assign_timestamp BEFORE INSERT
        ON
        STRUCTURE FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_structure_reject_status_update BEFORE UPDATE
        OF status ON
        STRUCTURE FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_structure_reject_id_update BEFORE UPDATE
        OF id ON
        STRUCTURE FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_structure_restrict_delete BEFORE DELETE
        ON
        STRUCTURE FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_structure_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            STRUCTURE DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- mechanism ----
CREATE
    TRIGGER trg_mechanism_assign_id BEFORE INSERT
        ON
        mechanism FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_mechanism_assign_timestamp BEFORE INSERT
        ON
        mechanism FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_mechanism_reject_status_update BEFORE UPDATE
        OF status ON
        mechanism FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_mechanism_reject_id_update BEFORE UPDATE
        OF id ON
        mechanism FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_mechanism_restrict_delete BEFORE DELETE
        ON
        mechanism FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_mechanism_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            mechanism DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- effector ----
CREATE
    TRIGGER trg_effector_assign_id BEFORE INSERT
        ON
        effector FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_effector_assign_timestamp BEFORE INSERT
        ON
        effector FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_effector_reject_status_update BEFORE UPDATE
        OF status ON
        effector FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_effector_reject_id_update BEFORE UPDATE
        OF id ON
        effector FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_effector_restrict_delete BEFORE DELETE
        ON
        effector FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_effector_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            effector DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- receptor ----
CREATE
    TRIGGER trg_receptor_assign_id BEFORE INSERT
        ON
        receptor FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_receptor_assign_timestamp BEFORE INSERT
        ON
        receptor FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_receptor_reject_status_update BEFORE UPDATE
        OF status ON
        receptor FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_receptor_reject_id_update BEFORE UPDATE
        OF id ON
        receptor FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_receptor_restrict_delete BEFORE DELETE
        ON
        receptor FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_receptor_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            receptor DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- interaction ----
CREATE
    TRIGGER trg_interaction_assign_id BEFORE INSERT
        ON
        interaction FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_interaction_assign_timestamp BEFORE INSERT
        ON
        interaction FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_interaction_reject_status_update BEFORE UPDATE
        OF status ON
        interaction FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_interaction_reject_id_update BEFORE UPDATE
        OF id ON
        interaction FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_interaction_restrict_delete BEFORE DELETE
        ON
        interaction FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_interaction_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            interaction DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- directive ----
CREATE
    TRIGGER trg_directive_assign_id BEFORE INSERT
        ON
        directive FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_directive_assign_timestamp BEFORE INSERT
        ON
        directive FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_directive_reject_status_update BEFORE UPDATE
        OF status ON
        directive FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_directive_reject_id_update BEFORE UPDATE
        OF id ON
        directive FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_directive_restrict_delete BEFORE DELETE
        ON
        directive FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_directive_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            directive DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- norm ----
CREATE
    TRIGGER trg_norm_assign_id BEFORE INSERT
        ON
        norm FOR EACH ROW EXECUTE FUNCTION tgf_assign_id();

CREATE
    TRIGGER trg_norm_assign_timestamp BEFORE INSERT
        ON
        norm FOR EACH ROW EXECUTE FUNCTION tgf_assign_timestamp();

CREATE
    TRIGGER trg_norm_reject_status_update BEFORE UPDATE
        OF status ON
        norm FOR EACH ROW EXECUTE FUNCTION tgf_reject_status_update();

CREATE
    TRIGGER trg_norm_reject_id_update BEFORE UPDATE
        OF id ON
        norm FOR EACH ROW EXECUTE FUNCTION tgf_reject_id_update();

CREATE
    TRIGGER trg_norm_restrict_delete BEFORE DELETE
        ON
        norm FOR EACH ROW EXECUTE FUNCTION tgf_restrict_ascription_delete_when_transitions_exist();

CREATE
    CONSTRAINT TRIGGER trg_norm_status_matches_history AFTER INSERT
        OR UPDATE
            ON
            norm DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION tgf_assert_ascription_status_matches_history();

-- ---- ascription_status_transition ----
CREATE
    TRIGGER trg_ast_assert_ascription_exists BEFORE INSERT
        ON
        ascription_status_transition FOR EACH ROW EXECUTE FUNCTION tgf_assert_transition_ascription_exists();

CREATE
    TRIGGER trg_ast_sync_ascription_status AFTER INSERT
        ON
        ascription_status_transition FOR EACH ROW EXECUTE FUNCTION tgf_sync_ascription_status();

CREATE
    TRIGGER trg_ast_reject_mutation BEFORE UPDATE
        OR DELETE
            ON
            ascription_status_transition FOR EACH ROW EXECUTE FUNCTION tgf_reject_transition_mutation();

-- ============================================================
-- §8  View — cross-type query convenience
-- ============================================================
CREATE
    OR replace VIEW ascription_all AS SELECT
        'ARCHETYPE'::definition_subject_type AS subject_type,
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        archetype
UNION ALL SELECT
        'STRUCTURE',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        STRUCTURE
UNION ALL SELECT
        'MECHANISM',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        mechanism
UNION ALL SELECT
        'EFFECTOR',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        effector
UNION ALL SELECT
        'RECEPTOR',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        receptor
UNION ALL SELECT
        'INTERACTION',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        interaction
UNION ALL SELECT
        'DIRECTIVE',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        directive
UNION ALL SELECT
        'NORM',
        definition_id,
        id,
        "timestamp",
        archetype_id,
        STATEMENT,
        status
    FROM
        norm;

COMMIT;