-- =============================================================================
-- V9 · Notificaciones y solicitudes del estudiante con flujo de aprobación.
-- =============================================================================

create table notification
(
    id         uuid primary key      default uuidv7(),
    user_id    uuid         not null references user_account (id),
    channel    varchar(10)  not null check (channel in ('IN_APP', 'EMAIL')),
    category   varchar(30)  not null,
    title      varchar(160) not null,
    body       text         not null,
    link       varchar(300),
    sent_at    timestamptz,
    read_at    timestamptz,
    created_at timestamptz  not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid
);
create index ix_notification_user on notification (user_id, created_at desc);

create or replace function guard_notification() returns trigger
    language plpgsql as
$$
begin
    if (to_jsonb(new) - 'read_at' - 'sent_at' - 'updated_at' - 'updated_by') is distinct from (to_jsonb(old) - 'read_at' - 'sent_at' - 'updated_at' - 'updated_by') then
        raise exception 'LEY-2: una notificación emitida no se edita' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_notification_guard before update on notification for each row execute function guard_notification();

create table student_request
(
    id          uuid primary key      default uuidv7(),
    student_id  uuid         not null references student (id),
    type        varchar(20)  not null check (type in ('CERTIFICATE', 'WITHDRAWAL', 'HOMOLOGATION', 'THIRD_ENROLLMENT', 'SPECIAL_ENROLLMENT', 'RECTIFICATION', 'OTHER')),
    subject     varchar(160) not null,
    detail      text         not null check (length(trim(detail)) >= 10),
    status      varchar(12)  not null default 'SUBMITTED' check (status in ('SUBMITTED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED')),
    due_at      timestamptz  not null,
    resolved_at timestamptz,
    resolved_by uuid,
    resolution  text,
    created_at  timestamptz  not null default now(),
    created_by  uuid,
    updated_at  timestamptz,
    updated_by  uuid,
    version     bigint       not null default 0,
    check (status not in ('APPROVED', 'REJECTED') or (resolved_at is not null and resolution is not null))
);
create index ix_student_request on student_request (student_id, created_at desc);
create index ix_student_request_status on student_request (status, due_at);

create table student_request_event
(
    id          uuid primary key     default uuidv7(),
    request_id  uuid        not null references student_request (id),
    from_status varchar(12),
    to_status   varchar(12) not null,
    note        text,
    actor_id    uuid,
    occurred_at timestamptz not null default now()
);
create trigger trg_request_event_no_update before update on student_request_event for each row execute function forbid_update();

create table student_request_attachment
(
    request_id uuid        not null references student_request (id),
    file_id    uuid        not null references stored_file (id),
    created_at timestamptz not null default now(),
    primary key (request_id, file_id)
);

create or replace function log_request_status() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' or new.status <> old.status then
        if tg_op = 'UPDATE' and old.status in ('APPROVED', 'REJECTED', 'CANCELLED') then
            raise exception 'LEY-2: una solicitud resuelta es definitiva' using errcode = 'P0001';
        end if;
        insert into student_request_event (request_id, from_status, to_status, note, actor_id)
        values (new.id, case when tg_op = 'UPDATE' then old.status end, new.status, new.resolution, app_actor());
    end if;
    return new;
end
$$;
create trigger trg_request_status after insert or update of status on student_request for each row execute function log_request_status();
