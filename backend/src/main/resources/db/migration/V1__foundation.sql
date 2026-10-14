-- =============================================================================
-- V1 · Fundaciones: extensiones, contexto del actor, auditoría, parámetros,
--      archivos, cola de trabajos y bitácora de procesos.
-- LEY-1 (nada quemado): la tabla parameter guarda todo valor de negocio con vigencia.
-- LEY-2 (nada se borra): funciones forbid_delete / forbid_update y auditoría append-only.
-- =============================================================================

create extension if not exists btree_gist;

-- Contexto que la aplicación fija por transacción con set_config(..., true)
create or replace function app_actor() returns uuid
    language sql stable as
$$ select nullif(current_setting('unisfera.actor_id', true), '')::uuid $$;

create or replace function app_ip() returns text
    language sql stable as
$$ select nullif(current_setting('unisfera.actor_ip', true), '') $$;

create or replace function app_request_id() returns text
    language sql stable as
$$ select nullif(current_setting('unisfera.request_id', true), '') $$;

create or replace function app_process() returns text
    language sql stable as
$$ select nullif(current_setting('unisfera.process', true), '') $$;

-- LEY-2 ---------------------------------------------------------------------
create or replace function forbid_delete() returns trigger
    language plpgsql as
$$
begin
    raise exception 'LEY-2: no se permite % sobre %; use anulación con motivo', tg_op, tg_table_name
        using errcode = 'P0001';
end
$$;

create or replace function forbid_update() returns trigger
    language plpgsql as
$$
begin
    raise exception 'LEY-2: los registros de % son inmutables (solo se agregan)', tg_table_name
        using errcode = 'P0001';
end
$$;

create or replace function touch_insert() returns trigger
    language plpgsql as
$$
begin
    new.created_at := coalesce(new.created_at, now());
    new.created_by := coalesce(new.created_by, app_actor());
    return new;
end
$$;

create or replace function touch_update() returns trigger
    language plpgsql as
$$
begin
    new.created_at := old.created_at;
    new.created_by := old.created_by;
    new.updated_at := now();
    new.updated_by := coalesce(app_actor(), new.updated_by);
    return new;
end
$$;

-- Auditoría -----------------------------------------------------------------
create table audit_log
(
    id          uuid        not null default uuidv7(),
    occurred_at timestamptz not null default now(),
    table_name  text        not null,
    record_id   text,
    action      text        not null check (action in ('INSERT', 'UPDATE', 'DENIED', 'READ_SENSITIVE', 'EXPORT', 'LOGIN')),
    old_values  jsonb,
    new_values  jsonb,
    actor_id    uuid,
    actor_ip    text,
    request_id  text,
    detail      text,
    primary key (id, occurred_at)
) partition by range (occurred_at);

create table audit_log_default partition of audit_log default;

create or replace function ensure_audit_partition(p_year int) returns void
    language plpgsql as
$$
declare
    v_name text := format('audit_log_y%s', p_year);
begin
    if to_regclass(v_name) is null then
        execute format(
                'create table %I partition of audit_log for values from (%L) to (%L)',
                v_name, make_date(p_year, 1, 1), make_date(p_year + 1, 1, 1));
    end if;
end
$$;

do
$$
    begin
        for y in 2023..2031
            loop
                perform ensure_audit_partition(y);
            end loop;
    end
$$;

create index ix_audit_record on audit_log (table_name, record_id);
create index ix_audit_actor on audit_log (actor_id, occurred_at);
create index ix_audit_time on audit_log (occurred_at);

create trigger trg_audit_log_no_update before update on audit_log for each row execute function forbid_update();
create trigger trg_audit_log_no_delete before delete on audit_log for each row execute function forbid_delete();
create trigger trg_audit_log_no_truncate before truncate on audit_log for each statement execute function forbid_delete();

create or replace function audit_mask(p jsonb) returns jsonb
    language sql immutable as
$$
select coalesce(jsonb_object_agg(e.key, case
                                            when e.key ~ '(password|_enc$|token_hash|secret)' then to_jsonb('***'::text)
                                            else e.value end), '{}'::jsonb)
from jsonb_each(p) as e
$$;

create or replace function audit_row_change() returns trigger
    language plpgsql as
$$
declare
    v_old jsonb;
    v_new jsonb;
begin
    if tg_op = 'INSERT' then
        v_new := audit_mask(to_jsonb(new));
    else
        select jsonb_object_agg(n.key, n.value)
        into v_new
        from jsonb_each(to_jsonb(new)) n
        where (to_jsonb(old) -> n.key) is distinct from n.value
          and n.key not in ('updated_at', 'updated_by', 'version');
        if v_new is null then
            return new;
        end if;
        select jsonb_object_agg(o.key, o.value) into v_old from jsonb_each(to_jsonb(old)) o where v_new ? o.key;
        v_new := audit_mask(v_new);
        v_old := audit_mask(v_old);
    end if;
    insert into audit_log (table_name, record_id, action, old_values, new_values, actor_id, actor_ip, request_id)
    values (tg_table_name, to_jsonb(new) ->> 'id', tg_op, v_old, v_new, app_actor(), app_ip(), app_request_id());
    return new;
end
$$;

-- LEY-1: parámetros con vigencia --------------------------------------------
create table parameter
(
    id            uuid primary key     default uuidv7(),
    key           varchar(120) not null check (key ~ '^[a-z][a-z0-9_.]+$'),
    value         text         not null,
    data_type     varchar(16)  not null check (data_type in ('DECIMAL', 'INTEGER', 'BOOLEAN', 'STRING', 'LIST', 'DATE')),
    unit          varchar(30),
    description   text         not null,
    valid_from    timestamptz  not null,
    valid_to      timestamptz,
    change_reason text,
    created_at    timestamptz  not null default now(),
    created_by    uuid,
    updated_at    timestamptz,
    updated_by    uuid,
    version       bigint       not null default 0,
    constraint ck_parameter_range check (valid_to is null or valid_to > valid_from),
    constraint ex_parameter_validity exclude using gist (key with =, tstzrange(valid_from, valid_to, '[)') with &&)
);

create index ix_parameter_key on parameter (key, valid_from desc);

-- Un parámetro publicado solo puede cerrarse (fijar valid_to una vez); nunca se reescribe.
create or replace function guard_parameter() returns trigger
    language plpgsql as
$$
begin
    if (to_jsonb(new) - 'valid_to' - 'updated_at' - 'updated_by' - 'version')
        is distinct from (to_jsonb(old) - 'valid_to' - 'updated_at' - 'updated_by' - 'version') then
        raise exception 'LEY-1: el valor de un parámetro no se edita; cree una nueva versión con vigencia' using errcode = 'P0001';
    end if;
    if old.valid_to is not null and new.valid_to is distinct from old.valid_to then
        raise exception 'LEY-1: la vigencia de un parámetro cerrado no puede cambiarse' using errcode = 'P0001';
    end if;
    return new;
end
$$;

create trigger trg_parameter_guard before update on parameter for each row execute function guard_parameter();

create or replace function param_text(p_key text, p_at timestamptz default now()) returns text
    language plpgsql stable as
$$
declare
    v text;
begin
    select value into v
    from parameter
    where key = p_key and valid_from <= p_at and (valid_to is null or valid_to > p_at);
    if v is null then
        raise exception 'LEY-1: el parámetro % no tiene valor vigente en %', p_key, p_at using errcode = 'P0001';
    end if;
    return v;
end
$$;

create or replace function param_numeric(p_key text, p_at timestamptz default now()) returns numeric
    language sql stable as
$$ select param_text(p_key, p_at)::numeric $$;

create or replace function param_bool(p_key text, p_at timestamptz default now()) returns boolean
    language sql stable as
$$ select param_text(p_key, p_at)::boolean $$;

-- Archivos ------------------------------------------------------------------
create table stored_file
(
    id              uuid primary key      default uuidv7(),
    original_name   varchar(255) not null,
    stored_name     varchar(80)  not null unique,
    content_type    varchar(100) not null,
    size_bytes      bigint       not null check (size_bytes > 0),
    sha256          char(64)     not null,
    purpose         varchar(40)  not null,
    owner_person_id uuid,
    created_at      timestamptz  not null default now(),
    created_by      uuid
);

-- Cola de trabajos en segundo plano (consumida con FOR UPDATE SKIP LOCKED) -----
create table background_job
(
    id             uuid primary key     default uuidv7(),
    job_type       varchar(40) not null,
    payload        jsonb       not null default '{}'::jsonb,
    status         varchar(16) not null default 'QUEUED' check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempts       int         not null default 0 check (attempts >= 0),
    run_after      timestamptz not null default now(),
    locked_at      timestamptz,
    finished_at    timestamptz,
    result_file_id uuid references stored_file (id),
    result_summary jsonb,
    error          text,
    requested_by   uuid,
    created_at     timestamptz not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid
);

create index ix_background_job_queue on background_job (run_after) where status = 'QUEUED';

-- Bitácora de procesos (cierres, cargas masivas, trabajos programados) ----------
create table process_log
(
    id          uuid primary key     default uuidv7(),
    process     varchar(60) not null,
    status      varchar(16) not null check (status in ('STARTED', 'SUCCEEDED', 'FAILED')),
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    actor_id    uuid,
    summary     jsonb,
    error       text,
    created_at  timestamptz not null default now(),
    created_by  uuid,
    updated_at  timestamptz,
    updated_by  uuid
);
