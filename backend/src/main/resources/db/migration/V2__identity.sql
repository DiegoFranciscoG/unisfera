-- =============================================================================
-- V2 · Personas, cuentas, roles, permisos, sesiones e intentos de acceso.
-- La cédula se valida también en la base (SEC-01): misma regla en BD, dominio y UI.
-- =============================================================================

-- Algoritmo módulo 10 de la cédula ecuatoriana (investigacion.md #9, #10)
create or replace function is_valid_cedula(p text) returns boolean
    language plpgsql immutable as
$$
declare
    v_province int;
    v_total    int := 0;
    v_digit    int;
begin
    if p is null or p !~ '^[0-9]{10}$' then
        return false;
    end if;
    v_province := substr(p, 1, 2)::int;
    if not (v_province between 1 and 24 or v_province = 30) then
        return false;
    end if;
    if v_province <> 30 and substr(p, 3, 1)::int >= 6 then
        return false;
    end if;
    for i in 1..9
        loop
            v_digit := substr(p, i, 1)::int * (case when i % 2 = 1 then 2 else 1 end);
            if v_digit >= 10 then
                v_digit := v_digit - 9;
            end if;
            v_total := v_total + v_digit;
        end loop;
    return ((10 - v_total % 10) % 10) = substr(p, 10, 1)::int;
end
$$;

create table person
(
    id             uuid primary key      default uuidv7(),
    id_type        varchar(10)  not null check (id_type in ('CEDULA', 'PASSPORT')),
    id_number      varchar(20)  not null,
    first_names    varchar(120) not null check (length(trim(first_names)) > 1),
    last_names     varchar(120) not null check (length(trim(last_names)) > 1),
    birth_date     date         not null check (birth_date > date '1900-01-01'),
    sex            varchar(10)  not null check (sex in ('HOMBRE', 'MUJER')),
    marital_status varchar(15) check (marital_status in ('SOLTERO', 'CASADO', 'DIVORCIADO', 'VIUDO', 'UNION_DE_HECHO')),
    nationality    char(3)      not null check (nationality ~ '^[A-Z]{3}$'),
    address        varchar(300),
    phone_mobile   varchar(20) check (phone_mobile ~ '^\+?[0-9 ]{7,20}$'),
    phone_home     varchar(20) check (phone_home ~ '^\+?[0-9 ]{7,20}$'),
    personal_email varchar(160) check (personal_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    photo_file_id  uuid references stored_file (id),
    created_at     timestamptz  not null default now(),
    created_by     uuid,
    updated_at     timestamptz,
    updated_by     uuid,
    version        bigint       not null default 0,
    constraint uq_person_identity unique (id_type, id_number),
    constraint ck_person_cedula check (id_type <> 'CEDULA' or is_valid_cedula(id_number)),
    constraint ck_person_passport check (id_type <> 'PASSPORT' or id_number ~ '^[A-Z0-9]{5,20}$')
);

-- Qué campos de la ficha puede editar el propio estudiante (lo define el administrador)
create table person_field_policy
(
    field_name       varchar(40) primary key,
    student_editable boolean     not null,
    description      text        not null,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid
);

create table emergency_contact
(
    id           uuid primary key      default uuidv7(),
    person_id    uuid         not null references person (id),
    full_name    varchar(160) not null,
    relationship varchar(40)  not null,
    phone        varchar(20)  not null check (phone ~ '^\+?[0-9 ]{7,20}$'),
    status       varchar(10)  not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at   timestamptz  not null default now(),
    created_by   uuid,
    updated_at   timestamptz,
    updated_by   uuid,
    version      bigint       not null default 0
);
create index ix_emergency_contact_person on emergency_contact (person_id);

create table person_document
(
    id               uuid primary key     default uuidv7(),
    person_id        uuid        not null references person (id),
    doc_type         varchar(40) not null,
    file_id          uuid        not null references stored_file (id),
    valid_until      date,
    status           varchar(10) not null default 'VALID' check (status in ('VALID', 'EXPIRED', 'ANNULLED')),
    annulled_at      timestamptz,
    annulled_by      uuid,
    annulment_reason text,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    updated_at       timestamptz,
    updated_by       uuid,
    version          bigint      not null default 0,
    check (status <> 'ANNULLED' or annulment_reason is not null)
);

-- Consentimientos LOPDP (art. 8 y 26) por finalidad
create table consent
(
    id           uuid primary key     default uuidv7(),
    person_id    uuid        not null references person (id),
    purpose      varchar(40) not null check (purpose in ('WELFARE_SENSITIVE', 'SOCIOECONOMIC', 'PHOTO', 'COMMUNICATIONS')),
    text_version varchar(20) not null,
    granted_at   timestamptz not null default now(),
    granted_ip   varchar(64),
    revoked_at   timestamptz,
    created_at   timestamptz not null default now(),
    created_by   uuid,
    updated_at   timestamptz,
    updated_by   uuid
);
create unique index uq_consent_active on consent (person_id, purpose) where revoked_at is null;

create table role
(
    id           uuid primary key      default uuidv7(),
    code         varchar(30)  not null unique,
    name         varchar(80)  not null,
    description  text         not null,
    requires_mfa boolean      not null default false,
    read_only    boolean      not null default false,
    created_at   timestamptz  not null default now(),
    created_by   uuid,
    updated_at   timestamptz,
    updated_by   uuid
);

create table permission
(
    id          uuid primary key     default uuidv7(),
    code        varchar(40) not null unique check (code ~ '^[A-Z][A-Z_]+$'),
    module      varchar(30) not null,
    description text        not null,
    is_write    boolean     not null
);

create table role_permission
(
    role_id       uuid not null references role (id),
    permission_id uuid not null references permission (id),
    primary key (role_id, permission_id)
);

-- Un rol de solo lectura (AUDITOR) jamás recibe un permiso de escritura
create or replace function guard_role_permission() returns trigger
    language plpgsql as
$$
begin
    if exists (select 1 from role r, permission p where r.id = new.role_id and p.id = new.permission_id and r.read_only and p.is_write) then
        raise exception 'SEC-05: un rol de solo lectura no puede recibir permisos de escritura' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_role_permission_guard before insert on role_permission for each row execute function guard_role_permission();

create table user_account
(
    id                           uuid primary key     default uuidv7(),
    person_id                    uuid        not null unique references person (id),
    username                     varchar(20) not null unique,
    password_hash                varchar(255) not null,
    password_changed_at          timestamptz,
    password_expires_at          timestamptz,
    must_change_password         boolean     not null default true,
    status                       varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    failed_attempts              int         not null default 0 check (failed_attempts >= 0),
    locked_until                 timestamptz,
    last_login_at                timestamptz,
    mfa_enabled                  boolean     not null default false,
    mfa_secret_enc               bytea,
    institutional_email          varchar(160) unique check (institutional_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    institutional_email_verified boolean     not null default false,
    disabled_at                  timestamptz,
    disabled_by                  uuid,
    disabled_reason              text,
    created_at                   timestamptz not null default now(),
    created_by                   uuid,
    updated_at                   timestamptz,
    updated_by                   uuid,
    version                      bigint      not null default 0,
    check (status <> 'DISABLED' or disabled_reason is not null),
    check (not mfa_enabled or mfa_secret_enc is not null)
);

-- El usuario es siempre el número de identificación de su persona
create or replace function guard_user_username() returns trigger
    language plpgsql as
$$
begin
    if new.username <> (select id_number from person where id = new.person_id) then
        raise exception 'SEC-01: el usuario debe ser la cédula o el pasaporte de la persona' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_user_username before insert or update of username, person_id on user_account
    for each row execute function guard_user_username();

create table password_history
(
    id            uuid primary key      default uuidv7(),
    user_id       uuid         not null references user_account (id),
    password_hash varchar(255) not null,
    created_at    timestamptz  not null default now()
);
create index ix_password_history_user on password_history (user_id, created_at desc);

create table user_role
(
    id         uuid primary key     default uuidv7(),
    user_id    uuid        not null references user_account (id),
    role_id    uuid        not null references role (id),
    granted_at timestamptz not null default now(),
    granted_by uuid,
    revoked_at timestamptz,
    revoked_by uuid,
    created_at timestamptz not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid
);
create unique index uq_user_role_active on user_role (user_id, role_id) where revoked_at is null;

create table auth_session
(
    id                 uuid primary key     default uuidv7(),
    user_id            uuid        not null references user_account (id),
    family_id          uuid        not null,
    refresh_token_hash char(64)    not null unique,
    mfa_verified       boolean     not null default false,
    ip                 varchar(64),
    user_agent         varchar(255),
    expires_at         timestamptz not null,
    last_seen_at       timestamptz not null default now(),
    revoked_at         timestamptz,
    revoked_reason     varchar(40),
    replaced_by_id     uuid references auth_session (id),
    created_at         timestamptz not null default now(),
    created_by         uuid,
    updated_at         timestamptz,
    updated_by         uuid
);
create index ix_auth_session_user on auth_session (user_id) where revoked_at is null;
create index ix_auth_session_family on auth_session (family_id);

create table login_attempt
(
    id                 uuid primary key     default uuidv7(),
    username_attempted varchar(40) not null,
    user_id            uuid references user_account (id),
    ip                 varchar(64),
    user_agent         varchar(255),
    success            boolean     not null,
    reason             varchar(40) not null,
    attempted_at       timestamptz not null default now()
);
create index ix_login_attempt_user on login_attempt (username_attempted, attempted_at desc);
create index ix_login_attempt_ip on login_attempt (ip, attempted_at desc);

create table password_reset_token
(
    id           uuid primary key     default uuidv7(),
    user_id      uuid        not null references user_account (id),
    token_hash   char(64)    not null unique,
    expires_at   timestamptz not null,
    used_at      timestamptz,
    requested_ip varchar(64),
    created_at   timestamptz not null default now()
);
