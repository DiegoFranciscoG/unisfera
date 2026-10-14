package ec.unisfera.institution.application;

import ec.unisfera.institution.persistence.CareerEntity;
import ec.unisfera.institution.persistence.CareerRepository;
import ec.unisfera.institution.persistence.CurriculumEntity;
import ec.unisfera.institution.persistence.CurriculumRepository;
import ec.unisfera.institution.persistence.SubjectEntity;
import ec.unisfera.institution.persistence.SubjectRepository;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Carreras, mallas versionadas, asignaturas y requisitos. */
@Service
public class CatalogService {

    public record CareerCommand(UUID facultyId, UUID campusId, String code, String senescytCode, String name, String degreeTitle,
                                String modality, String educationLevel, short durationPeriods) {
    }

    public record CurriculumCommand(String versionCode, String name, LocalDate validFrom, String approvalResolution, int levels) {
    }

    public record SubjectCommand(int levelNumber, String code, String name, int teacherContactHours, int practicalHours,
                                 int autonomousHours, BigDecimal credits, String organizationUnit, boolean titling) {
    }

    private final CareerRepository careers;
    private final CurriculumRepository curricula;
    private final SubjectRepository subjects;
    private final CatalogMapper mapper;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CatalogService(CareerRepository careers, CurriculumRepository curricula, SubjectRepository subjects, CatalogMapper mapper,
                          JdbcClient jdbc, Clock clock) {
        this.careers = careers;
        this.curricula = curricula;
        this.subjects = subjects;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogViews.CareerView> careers(PageQuery page) {
        var result = careers.findByStatus("ACTIVE", PageRequest.of(page.page(), page.size()));
        return PageResponse.of(result.map(mapper::toView).getContent(), page, result.getTotalElements());
    }

    @Transactional
    public CatalogViews.CareerView createCareer(CareerCommand c) {
        CareerEntity e = new CareerEntity(UuidV7.next(clock), c.facultyId(), c.campusId(), c.code(), c.senescytCode(), c.name(),
                c.degreeTitle(), c.modality(), c.educationLevel(), c.durationPeriods());
        return mapper.toView(careers.saveAndFlush(e));
    }

    @Transactional(readOnly = true)
    public List<CatalogViews.CurriculumView> curricula(UUID careerId) {
        return curricula.findByCareerIdOrderByValidFromDesc(careerId).stream().map(mapper::toView).toList();
    }

    /** Nueva versión de malla con sus niveles. Las versiones anteriores siguen intactas para sus estudiantes. */
    @Transactional
    public CatalogViews.CurriculumView createCurriculum(UUID careerId, CurriculumCommand c) {
        careers.findById(careerId).orElseThrow(() -> new NotFoundException("La carrera"));
        CurriculumEntity e = curricula.saveAndFlush(new CurriculumEntity(UuidV7.next(clock), careerId, c.versionCode(), c.name(),
                c.validFrom(), c.approvalResolution()));
        for (int level = 1; level <= c.levels(); level++) {
            jdbc.sql("insert into curriculum_level (id, curriculum_id, number, name) values (:id, :c, :n, :name)")
                    .param("id", UuidV7.next(clock)).param("c", e.getId()).param("n", level).param("name", "Nivel " + level).update();
        }
        return mapper.toView(e);
    }

    @Transactional
    public CatalogViews.CurriculumView activateCurriculum(UUID curriculumId) {
        CurriculumEntity e = curricula.findById(curriculumId).orElseThrow(() -> new NotFoundException("La malla"));
        e.activate();
        return mapper.toView(e);
    }

    @Transactional
    public CatalogViews.SubjectView addSubject(UUID curriculumId, SubjectCommand c) {
        CurriculumEntity curriculum = curricula.findById(curriculumId).orElseThrow(() -> new NotFoundException("La malla"));
        requireDraft(curriculum);
        UUID levelId = jdbc.sql("select id from curriculum_level where curriculum_id = :c and number = :n")
                .param("c", curriculumId).param("n", c.levelNumber()).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("El nivel " + c.levelNumber()));
        SubjectEntity e = new SubjectEntity(UuidV7.next(clock), curriculumId, levelId, c.code(), c.name(), c.teacherContactHours(),
                c.practicalHours(), c.autonomousHours(), c.credits(), c.organizationUnit(), c.titling());
        return mapper.toView(subjects.saveAndFlush(e));
    }

    @Transactional
    public void addPrerequisite(UUID subjectId, UUID requiredSubjectId) {
        requireDraft(curriculumOf(subjectId));
        jdbc.sql("insert into subject_prerequisite (subject_id, required_subject_id) values (:s, :r)")
                .param("s", subjectId).param("r", requiredSubjectId).update();
    }

    @Transactional
    public void addCorequisite(UUID subjectId, UUID coSubjectId) {
        requireDraft(curriculumOf(subjectId));
        jdbc.sql("insert into subject_corequisite (subject_id, corequisite_subject_id) values (:s, :c), (:c, :s)")
                .param("s", subjectId).param("c", coSubjectId).update();
    }

    /** Árbol completo de la malla (niveles, asignaturas, requisitos) en tres consultas. */
    @Transactional(readOnly = true)
    public CatalogViews.CurriculumTree tree(UUID curriculumId) {
        CurriculumEntity curriculum = curricula.findById(curriculumId).orElseThrow(() -> new NotFoundException("La malla"));
        CareerEntity career = careers.findById(curriculum.getCareerId()).orElseThrow();
        Map<UUID, List<UUID>> prereqs = new HashMap<>();
        Map<UUID, List<UUID>> coreqs = new HashMap<>();
        jdbc.sql("select p.subject_id, p.required_subject_id from subject_prerequisite p join subject s on s.id = p.subject_id where s.curriculum_id = :c")
                .param("c", curriculumId)
                .query((rs, n) -> prereqs.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class)))
                .list();
        jdbc.sql("select q.subject_id, q.corequisite_subject_id from subject_corequisite q join subject s on s.id = q.subject_id where s.curriculum_id = :c")
                .param("c", curriculumId)
                .query((rs, n) -> coreqs.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class)))
                .list();
        List<SubjectEntity> all = subjects.findByCurriculumId(curriculumId);
        List<CatalogViews.LevelNode> levels = jdbc.sql("select id, number, name from curriculum_level where curriculum_id = :c order by number")
                .param("c", curriculumId)
                .query((rs, n) -> {
                    UUID levelId = rs.getObject("id", UUID.class);
                    List<CatalogViews.SubjectNode> nodes = all.stream().filter(s -> s.getLevelId().equals(levelId))
                            .sorted(java.util.Comparator.comparing(SubjectEntity::getCode))
                            .map(s -> new CatalogViews.SubjectNode(s.getId(), s.getCode(), s.getName(), s.getTeacherContactHours(),
                                    s.getPracticalHours(), s.getAutonomousHours(), s.totalHours(), s.getCredits(), s.getOrganizationUnit(),
                                    prereqs.getOrDefault(s.getId(), List.of()), coreqs.getOrDefault(s.getId(), List.of())))
                            .toList();
                    return new CatalogViews.LevelNode(levelId, rs.getInt("number"), rs.getString("name"), nodes);
                })
                .list();
        return new CatalogViews.CurriculumTree(mapper.toView(curriculum), mapper.toView(career), levels);
    }

    private CurriculumEntity curriculumOf(UUID subjectId) {
        SubjectEntity s = subjects.findById(subjectId).orElseThrow(() -> new NotFoundException("La asignatura"));
        return curricula.findById(s.getCurriculumId()).orElseThrow();
    }

    private static void requireDraft(CurriculumEntity curriculum) {
        if (!curriculum.isDraft()) {
            throw new BusinessRuleException("RN-03", "La malla " + curriculum.getVersionCode()
                    + " ya está vigente y no se modifica; cree una nueva versión");
        }
    }
}
