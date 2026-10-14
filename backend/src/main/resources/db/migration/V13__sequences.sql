-- =============================================================================
-- V13 · Secuencias para códigos institucionales (sin "max + 1", seguras en concurrencia).
-- =============================================================================

create sequence student_code_seq start with 1001;
create sequence teacher_code_seq start with 101;
create sequence request_number_seq start with 1;
