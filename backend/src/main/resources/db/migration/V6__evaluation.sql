-- =============================================================================
-- V6 · Esquemas de evaluación, notas, asistencia, actas y kardex.
-- RN-11 pesos = 100 · RN-12 escala · RN-13 cierre completo · RN-14 inmutabilidad
-- RN-15 asistencia · RN-16 promedio ponderado · RN-17 kardex solo con actas cerradas
-- =============================================================================

create table evaluation_scheme
(
    id                   uuid primary key       default uuidv7(),
    period_id            uuid          not null references academic_period (id),
    career_id            uuid references career (id),
    scale_min            numeric(5, 2) not null,
    scale_max            numeric(5, 2) not null,
    passing_grade        numeric(5, 2) not null,
    min_attendance_pct   numeric(5, 2) not null check (min_attendance_pct between 0 and 100),
    max_component_weight numeric(5, 2) not null check (max_component_weight > 0 and max_component_weight <= 100),
    status               varchar(10)   not null default 'ACTIVE' check (status in ('DRAFT', 'ACTIVE')),
    created_at           timestamptz   not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint        not null default 0,
    constraint uq_scheme unique nulls not distinct (period_id, career_id),
    check (scale_min >= 0 and scale_min < scale_max),
    check (passing_grade > scale_min and passing_grade <= scale_max)
);

create table evaluation_component
(
    id         uuid primary key       default uuidv7(),
    scheme_id  uuid          not null references evaluation_scheme (id),
    name       varchar(80)   not null,
    kind       varchar(12)   not null check (kind in ('FORMATIVE', 'SUMMATIVE', 'FINAL')),
    weight     numeric(5, 2) not null check (weight > 0 and weight <= 100),
    sort_order smallint      not null,
    created_at timestamptz   not null default now(),
    created_by uuid,
    updated_at timestamptz,
    updated_by uuid,
    version    bigint        not null default 0,
    constraint uq_component_order unique (scheme_id, sort_order),
    constraint uq_component_name unique (scheme_id, name)
);

-- RN-11: la suma de pesos del esquema debe ser exactamente 100 al confirmar la transacción
create or replace function check_scheme_weights() returns trigger
    language plpgsql as
$$
declare
    v_sum numeric;
    v_max numeric;
begin
    select coalesce(sum(c.weight), 0), max(s.max_component_weight)
    into v_sum, v_max
    from evaluation_scheme s
             left join evaluation_component c on c.scheme_id = s.id
    where s.id = new.scheme_id
    group by s.id;
    if v_sum <> 100 then
        raise exception 'RN-11: los pesos del esquema suman % y deben sumar exactamente 100', v_sum using errcode = 'P0001';
    end if;
    if exists (select 1 from evaluation_component where scheme_id = new.scheme_id and weight > v_max) then
        raise exception 'RN-11: ningún componente puede pesar más de % %%', v_max using errcode = 'P0001';
    end if;
    return null;
end
$$;
create constraint trigger trg_scheme_weights
    after insert or update on evaluation_component
    deferrable initially deferred
    for each row execute function check_scheme_weights();

-- Con notas registradas, el componente ya no cambia de peso
create or replace function guard_component_update() returns trigger
    language plpgsql as
$$
begin
    if new.weight <> old.weight and exists (select 1 from grade where component_id = old.id) then
        raise exception 'RN-11: el componente ya tiene notas registradas y su peso no puede cambiar' using errcode = 'P0001';
    end if;
    return new;
end
$$;

create table grade
(
    id                   uuid primary key       default uuidv7(),
    enrollment_detail_id uuid          not null references enrollment_detail (id),
    component_id         uuid          not null references evaluation_component (id),
    value                numeric(5, 2) not null check (value >= 0),
    recorded_by          uuid,
    recorded_at          timestamptz   not null default now(),
    created_at           timestamptz   not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint        not null default 0,
    constraint uq_grade unique (enrollment_detail_id, component_id)
);
create index ix_grade_component on grade (component_id);

create trigger trg_component_update before update on evaluation_component for each row execute function guard_component_update();

create table grade_history
(
    id         uuid primary key     default uuidv7(),
    grade_id   uuid        not null references grade (id),
    old_value  numeric(5, 2),
    new_value  numeric(5, 2) not null,
    changed_by uuid,
    changed_at timestamptz not null default now(),
    ip         varchar(64)
);
create index ix_grade_history on grade_history (grade_id, changed_at);

create table class_session
(
    id           uuid primary key      default uuidv7(),
    section_id   uuid         not null references section (id),
    period_id    uuid         not null references academic_period (id),
    session_date date         not null,
    starts_at    time         not null,
    topic        varchar(200) not null,
    recorded_by  uuid,
    created_at   timestamptz  not null default now(),
    created_by   uuid,
    updated_at   timestamptz,
    updated_by   uuid,
    constraint uq_class_session unique (section_id, session_date, starts_at)
);

-- Asistencia particionada por período (tabla grande: una fila por estudiante y sesión)
create table attendance
(
    id                   uuid        not null default uuidv7(),
    period_id            uuid        not null references academic_period (id),
    class_session_id     uuid        not null references class_session (id),
    enrollment_detail_id uuid        not null references enrollment_detail (id),
    status               varchar(10) not null check (status in ('PRESENT', 'ABSENT', 'LATE', 'JUSTIFIED')),
    recorded_by          uuid,
    recorded_at          timestamptz not null default now(),
    created_at           timestamptz not null default now(),
    created_by           uuid,
    updated_at           timestamptz,
    updated_by           uuid,
    version              bigint      not null default 0,
    primary key (id, period_id),
    constraint uq_attendance unique (class_session_id, enrollment_detail_id, period_id)
) partition by list (period_id);

create table attendance_default partition of attendance default;
create index ix_attendance_detail on attendance (enrollment_detail_id);

create or replace function create_period_partitions() returns trigger
    language plpgsql as
$$
begin
    execute format('create table if not exists %I partition of attendance for values in (%L)',
                   'attendance_' || replace(lower(new.code), '-', '_'), new.id);
    return new;
end
$$;
create trigger trg_period_partitions after insert on academic_period for each row execute function create_period_partitions();

create table grade_act
(
    id                uuid primary key     default uuidv7(),
    section_id        uuid        not null references section (id),
    period_id         uuid        not null references academic_period (id),
    type              varchar(15) not null check (type in ('ORIGINAL', 'RECTIFICATION')),
    status            varchar(10) not null default 'DRAFT' check (status in ('DRAFT', 'CLOSED', 'RECTIFIED')),
    rectifies_act_id  uuid references grade_act (id),
    superseded_by_id  uuid references grade_act (id),
    authorization_id  uuid references exception_authorization (id),
    reason            text,
    content_hash      char(64),
    teacher_signature char(64),
    closed_by         uuid,
    closed_at         timestamptz,
    created_at        timestamptz not null default now(),
    created_by        uuid,
    updated_at        timestamptz,
    updated_by        uuid,
    version           bigint      not null default 0,
    check (type = 'ORIGINAL' or (rectifies_act_id is not null and authorization_id is not null and length(trim(reason)) >= 10)),
    check (status = 'DRAFT' or (content_hash is not null and teacher_signature is not null and closed_at is not null and closed_by is not null)),
    check (status <> 'RECTIFIED' or superseded_by_id is not null)
);
create unique index uq_act_original on grade_act (section_id) where type = 'ORIGINAL';
create unique index uq_act_draft on grade_act (section_id) where status = 'DRAFT' and type = 'RECTIFICATION';

create table grade_act_line
(
    id                   uuid primary key       default uuidv7(),
    act_id               uuid          not null references grade_act (id),
    enrollment_detail_id uuid          not null references enrollment_detail (id),
    student_id           uuid          not null references student (id),
    subject_id           uuid          not null references subject (id),
    component_grades     jsonb         not null,
    final_grade          numeric(5, 2) not null check (final_grade >= 0),
    attendance_pct       numeric(5, 2) not null check (attendance_pct between 0 and 100),
    passing_grade        numeric(5, 2) not null,
    min_attendance_pct   numeric(5, 2) not null,
    result               varchar(20)   not null check (result in ('APPROVED', 'FAILED', 'FAILED_ATTENDANCE')),
    created_at           timestamptz   not null default now(),
    created_by           uuid,
    constraint uq_act_line unique (act_id, enrollment_detail_id),
    -- RN-15: el resultado es coherente con la nota y la asistencia; la base no acepta otra combinación
    constraint ck_line_result_approved check ((result = 'APPROVED') = (final_grade >= passing_grade and attendance_pct >= min_attendance_pct)),
    constraint ck_line_result_attendance check ((result = 'FAILED_ATTENDANCE') = (attendance_pct < min_attendance_pct))
);
create index ix_act_line_student on grade_act_line (student_id, subject_id);

-- RN-14 / RN-13 ------------------------------------------------------------------
create or replace function guard_grade_act() returns trigger
    language plpgsql as
$$
declare
    v_missing int;
begin
    if old.status in ('CLOSED', 'RECTIFIED') then
        if old.status = 'CLOSED' and new.status = 'RECTIFIED'
            and (to_jsonb(new) - 'status' - 'superseded_by_id' - 'updated_at' - 'updated_by' - 'version')
                = (to_jsonb(old) - 'status' - 'superseded_by_id' - 'updated_at' - 'updated_by' - 'version') then
            return new;
        end if;
        raise exception 'RN-14: el acta está cerrada y no puede modificarse; emita una rectificación autorizada' using errcode = 'P0001';
    end if;
    if new.status = 'CLOSED' and old.status = 'DRAFT' then
        if new.type = 'ORIGINAL' then
            select count(*)
            into v_missing
            from enrollment_detail d
                     cross join evaluation_component c
                     join evaluation_scheme s on s.id = c.scheme_id
                     join student st on st.id = d.student_id
            where d.section_id = new.section_id
              and d.status = 'ENROLLED'
              and s.period_id = d.period_id
              and (s.career_id = st.career_id or (s.career_id is null and not exists (
                select 1 from evaluation_scheme s2 where s2.period_id = d.period_id and s2.career_id = st.career_id)))
              and not exists (select 1 from grade g where g.enrollment_detail_id = d.id and g.component_id = c.id);
            if v_missing > 0 then
                raise exception 'RN-13: faltan % notas por registrar; el acta solo se cierra completa', v_missing using errcode = 'P0001';
            end if;
        end if;
        if exists (select 1
                   from enrollment_detail d
                   where d.section_id = new.section_id
                     and d.status = 'ENROLLED'
                     and not exists (select 1 from grade_act_line l where l.act_id = new.id and l.enrollment_detail_id = d.id)) then
            raise exception 'RN-13: el acta no incluye a todos los estudiantes matriculados' using errcode = 'P0001';
        end if;
    end if;
    return new;
end
$$;
create trigger trg_grade_act_guard before update on grade_act for each row execute function guard_grade_act();

create or replace function supersede_rectified_act() returns trigger
    language plpgsql as
$$
begin
    if new.type = 'RECTIFICATION' and new.status = 'CLOSED' and old.status = 'DRAFT' then
        update grade_act set status = 'RECTIFIED', superseded_by_id = new.id where id = new.rectifies_act_id and status = 'CLOSED';
        if not found then
            raise exception 'RN-14: solo se rectifica un acta cerrada y vigente' using errcode = 'P0001';
        end if;
    end if;
    return new;
end
$$;
create trigger trg_grade_act_supersede after update of status on grade_act for each row execute function supersede_rectified_act();

create or replace function guard_act_line_insert() returns trigger
    language plpgsql as
$$
begin
    if (select status from grade_act where id = new.act_id) <> 'DRAFT' then
        raise exception 'RN-14: no se agregan líneas a un acta cerrada' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_act_line_insert before insert on grade_act_line for each row execute function guard_act_line_insert();
create trigger trg_act_line_no_update before update on grade_act_line for each row execute function forbid_update();

-- RN-12 y RN-14 sobre las notas y la asistencia ------------------------------------
create or replace function section_is_closed(p_section uuid) returns boolean
    language sql stable as
$$ select exists (select 1 from grade_act a where a.section_id = p_section and a.type = 'ORIGINAL' and a.status in ('CLOSED', 'RECTIFIED')) $$;

create or replace function guard_grade() returns trigger
    language plpgsql as
$$
declare
    d enrollment_detail%rowtype;
    s evaluation_scheme%rowtype;
begin
    select * into d from enrollment_detail where id = new.enrollment_detail_id;
    if section_is_closed(d.section_id) then
        raise exception 'RN-14: el acta de este paralelo está cerrada; la nota es histórica e inmutable' using errcode = 'P0001';
    end if;
    if d.status <> 'ENROLLED' then
        raise exception 'RN-12: no se registran notas para una matrícula %', d.status using errcode = 'P0001';
    end if;
    select sc.* into s from evaluation_component c join evaluation_scheme sc on sc.id = c.scheme_id where c.id = new.component_id;
    if s.period_id <> d.period_id then
        raise exception 'RN-11: el componente no pertenece al esquema del período' using errcode = 'P0001';
    end if;
    if new.value < s.scale_min or new.value > s.scale_max then
        raise exception 'RN-12: la nota % está fuera de la escala vigente (% a %)', new.value, s.scale_min, s.scale_max using errcode = 'P0001';
    end if;
    if tg_op = 'UPDATE' and (new.enrollment_detail_id <> old.enrollment_detail_id or new.component_id <> old.component_id) then
        raise exception 'LEY-2: una nota no cambia de estudiante ni de componente' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_grade_guard before insert or update on grade for each row execute function guard_grade();

create or replace function log_grade_change() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'INSERT' or new.value <> old.value then
        insert into grade_history (grade_id, old_value, new_value, changed_by, ip)
        values (new.id, case when tg_op = 'UPDATE' then old.value end, new.value, app_actor(), app_ip());
    end if;
    return new;
end
$$;
create trigger trg_grade_history after insert or update of value on grade for each row execute function log_grade_change();

create or replace function guard_attendance() returns trigger
    language plpgsql as
$$
begin
    if section_is_closed((select section_id from class_session where id = new.class_session_id)) then
        raise exception 'RN-14: el acta está cerrada; la asistencia es histórica e inmutable' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger trg_attendance_guard before insert or update on attendance for each row execute function guard_attendance();

-- RN-17: el kardex solo existe a partir de actas cerradas vigentes y homologaciones aprobadas
create view v_kardex as
select l.student_id,
       l.subject_id,
       sb.code          as subject_code,
       sb.name          as subject_name,
       sb.total_hours,
       sb.credits,
       lv.number        as level_number,
       a.period_id,
       p.code           as period_code,
       d.attempt_number,
       l.final_grade,
       l.attendance_pct,
       l.result,
       'ACT'::varchar   as source,
       a.id             as source_id,
       a.closed_at      as recorded_at
from grade_act_line l
         join grade_act a on a.id = l.act_id and a.status = 'CLOSED'
         join enrollment_detail d on d.id = l.enrollment_detail_id
         join subject sb on sb.id = l.subject_id
         join curriculum_level lv on lv.id = sb.level_id
         join academic_period p on p.id = a.period_id
union all
select h.student_id,
       h.target_subject_id,
       sb.code,
       sb.name,
       sb.total_hours,
       sb.credits,
       lv.number,
       null::uuid,
       null::varchar,
       null::smallint,
       h.grade,
       null::numeric,
       'APPROVED'::varchar,
       'HOMOLOGATION'::varchar,
       h.id,
       h.decided_at
from homologation h
         join subject sb on sb.id = h.target_subject_id
         join curriculum_level lv on lv.id = sb.level_id
where h.status = 'APPROVED';

-- RN-16: promedio ponderado por horas, solo desde el kardex (actas cerradas)
create view v_student_average as
select k.student_id,
       round(sum(k.final_grade * k.total_hours) / nullif(sum(k.total_hours), 0), 2) as weighted_average,
       coalesce(sum(k.credits) filter (where k.result = 'APPROVED'), 0)             as approved_credits,
       count(*) filter (where k.result = 'APPROVED')                                as approved_subjects,
       count(*) filter (where k.result <> 'APPROVED')                               as failed_attempts
from v_kardex k
where k.result = 'APPROVED' or param_bool('kardex.average_includes_failed')
group by k.student_id;
