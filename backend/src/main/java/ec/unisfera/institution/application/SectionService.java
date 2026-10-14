package ec.unisfera.institution.application;

import ec.unisfera.institution.persistence.SectionEntity;
import ec.unisfera.institution.persistence.SectionRepository;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.sql.Time;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Paralelos y horarios. Choques de aula los rechaza la base (restricción de exclusión). */
@Service
public class SectionService {

    public record SectionCommand(UUID periodId, UUID subjectId, String code, int capacity, UUID teacherId, UUID classroomId) {
    }

    public record BlockCommand(int weekday, LocalTime start, LocalTime end, UUID classroomId) {
    }

    private final SectionRepository sections;
    private final JdbcClient jdbc;
    private final Clock clock;

    public SectionService(SectionRepository sections, JdbcClient jdbc, Clock clock) {
        this.sections = sections;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public UUID create(SectionCommand c) {
        SectionEntity e = new SectionEntity(UuidV7.next(clock), c.periodId(), c.subjectId(), c.code(), c.capacity(), c.teacherId(), c.classroomId());
        sections.saveAndFlush(e);
        return e.getId();
    }

    @Transactional
    public void addBlock(UUID sectionId, BlockCommand b) {
        sections.findById(sectionId).orElseThrow(() -> new NotFoundException("El paralelo"));
        jdbc.sql("insert into schedule_block (id, section_id, weekday, start_time, end_time, classroom_id) values (:id, :s, :w, :st, :en, :c)")
                .param("id", UuidV7.next(clock)).param("s", sectionId).param("w", b.weekday())
                .param("st", Time.valueOf(b.start())).param("en", Time.valueOf(b.end())).param("c", b.classroomId()).update();
    }

    @Transactional
    public void assign(UUID sectionId, UUID teacherId, UUID classroomId, int capacity) {
        sections.findById(sectionId).orElseThrow(() -> new NotFoundException("El paralelo")).assign(teacherId, classroomId, capacity);
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogViews.SectionView> list(UUID periodId, UUID subjectId, PageQuery page) {
        String where = " where s.period_id = :p and (cast(:sub as uuid) is null or s.subject_id = :sub)";
        List<CatalogViews.SectionView> base = jdbc.sql("""
                        select s.id, s.period_id, s.subject_id, sb.code subject_code, sb.name subject_name, s.code, s.capacity,
                               s.enrolled_count, s.teacher_id, p.first_names || ' ' || p.last_names teacher_name, s.status
                        from section s join subject sb on sb.id = s.subject_id
                        left join teacher t on t.id = s.teacher_id left join person p on p.id = t.person_id"""
                        + where + " order by sb.code, s.code limit :l offset :o")
                .param("p", periodId).param("sub", subjectId).param("l", page.size()).param("o", page.offset())
                .query((rs, n) -> new CatalogViews.SectionView(rs.getObject("id", UUID.class), rs.getObject("period_id", UUID.class),
                        rs.getObject("subject_id", UUID.class), rs.getString("subject_code"), rs.getString("subject_name"),
                        rs.getString("code"), rs.getInt("capacity"), rs.getInt("enrolled_count"),
                        rs.getInt("capacity") - rs.getInt("enrolled_count"), rs.getObject("teacher_id", UUID.class),
                        rs.getString("teacher_name"), rs.getString("status"), List.of()))
                .list();
        Map<UUID, List<CatalogViews.BlockView>> blocks = blocksOf(base.stream().map(CatalogViews.SectionView::id).toList());
        long total = jdbc.sql("select count(*) from section s" + where).param("p", periodId).param("sub", subjectId).query(Long.class).single();
        return PageResponse.of(base.stream().map(s -> new CatalogViews.SectionView(s.id(), s.periodId(), s.subjectId(), s.subjectCode(),
                s.subjectName(), s.code(), s.capacity(), s.enrolledCount(), s.seatsLeft(), s.teacherId(), s.teacherName(), s.status(),
                blocks.getOrDefault(s.id(), List.of()))).toList(), page, total);
    }

    public Map<UUID, List<CatalogViews.BlockView>> blocksOf(List<UUID> sectionIds) {
        Map<UUID, List<CatalogViews.BlockView>> result = new HashMap<>();
        if (sectionIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        select b.section_id, b.weekday, b.start_time, b.end_time, c.code classroom
                        from schedule_block b left join classroom c on c.id = b.classroom_id
                        where b.section_id in (:ids) order by b.weekday, b.start_time""")
                .param("ids", sectionIds)
                .query((rs, n) -> result.computeIfAbsent(rs.getObject("section_id", UUID.class), k -> new ArrayList<>())
                        .add(new CatalogViews.BlockView(rs.getInt("weekday"), rs.getTime("start_time").toLocalTime(),
                                rs.getTime("end_time").toLocalTime(), rs.getString("classroom"))))
                .list();
        return result;
    }
}
