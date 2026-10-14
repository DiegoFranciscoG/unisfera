package ec.unisfera.identity.security;

import ec.unisfera.shared.domain.ForbiddenException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Filtrado por identidad (OWASP API1 · BOLA): un estudiante solo opera sobre su propio registro
 * y un docente solo sobre sus paralelos. El identificador nunca se toma de la URL para el
 * portal del estudiante: se deriva del token.
 */
@Component
public class AccessGuard {

    private final JdbcClient jdbc;

    public AccessGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registro de estudiante vigente de la persona autenticada (el más reciente si cambió de carrera). */
    public UUID currentStudentId() {
        CurrentUser user = SecurityUtils.requireUser();
        return jdbc.sql("""
                        select id from student where person_id = :person
                        order by case when status in ('WITHDRAWN', 'GRADUATED') then 1 else 0 end, created_at desc limit 1""")
                .param("person", user.personId()).query(UUID.class).optional()
                .orElseThrow(() -> new ForbiddenException("student"));
    }

    public UUID currentTeacherId() {
        CurrentUser user = SecurityUtils.requireUser();
        return jdbc.sql("select id from teacher where person_id = :person and status = 'ACTIVE'")
                .param("person", user.personId()).query(UUID.class).optional()
                .orElseThrow(() -> new ForbiddenException("teacher"));
    }

    /** RN de aislamiento: el paralelo debe pertenecer al docente autenticado. */
    public UUID requireOwnSection(UUID sectionId) {
        UUID teacherId = currentTeacherId();
        boolean owns = jdbc.sql("select count(*) from section where id = :section and teacher_id = :teacher")
                .param("section", sectionId).param("teacher", teacherId).query(Long.class).single() > 0;
        if (!owns) {
            throw new ForbiddenException("section:" + sectionId);
        }
        return teacherId;
    }
}
