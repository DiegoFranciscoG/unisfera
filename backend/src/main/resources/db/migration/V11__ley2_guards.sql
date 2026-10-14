-- =============================================================================
-- V11 · LEY-2 aplicada a todo el esquema:
--   * DELETE y TRUNCATE prohibidos en cada tabla de negocio.
--   * created_*/updated_* los fija la base con el actor de la transacción.
--   * Auditoría (valor anterior y nuevo) en las tablas de negocio; las bitácoras
--     propias (historiales, libro mayor, intentos de acceso) no se auditan dos veces.
-- =============================================================================

do
$$
    declare
        t                text;
        v_no_audit       text[] := array ['audit_log', 'login_attempt', 'grade_history', 'student_status_history',
            'student_request_event', 'background_job', 'process_log', 'gateway_transaction', 'notification',
            'auth_session', 'enrollment_time_slot', 'account_entry', 'payment_allocation', 'grade_act_line',
            'password_history', 'password_reset_token', 'application_score', 'scholarship_followup',
            'reconciliation_item', 'class_session', 'curriculum_level', 'authorization_type', 'permission'];
        v_update_only    text[] := array ['attendance', 'grade'];
    begin
        for t in
            select c.relname
            from pg_class c
                     join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = current_schema()
              and c.relkind in ('r', 'p')
              and not c.relispartition
              and c.relname not like 'flyway%'
            loop
                if t <> 'audit_log' then
                    execute format('create trigger trg_zz_no_delete before delete on %I for each row execute function forbid_delete()', t);
                    execute format('create trigger trg_zz_no_truncate before truncate on %I for each statement execute function forbid_delete()', t);
                end if;

                if exists (select 1 from information_schema.columns
                           where table_schema = current_schema() and table_name = t and column_name = 'created_by') then
                    execute format('create trigger trg_00_touch_insert before insert on %I for each row execute function touch_insert()', t);
                end if;
                if exists (select 1 from information_schema.columns
                           where table_schema = current_schema() and table_name = t and column_name = 'updated_at') then
                    execute format('create trigger trg_00_touch_update before update on %I for each row execute function touch_update()', t);
                end if;

                if t = any (v_update_only) then
                    execute format('create trigger trg_zz_audit after update on %I for each row execute function audit_row_change()', t);
                elsif not (t = any (v_no_audit)) then
                    execute format('create trigger trg_zz_audit after insert or update on %I for each row execute function audit_row_change()', t);
                end if;
            end loop;
    end
$$;
