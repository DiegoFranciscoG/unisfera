package ec.unisfera.institution.application;

import ec.unisfera.institution.persistence.AcademicPeriodEntity;
import ec.unisfera.institution.persistence.PeriodRepository;
import ec.unisfera.shared.domain.BusinessRuleException;
import ec.unisfera.shared.domain.NotFoundException;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Períodos académicos con sus ventanas de matrícula (RRA art. 71) y su ciclo de vida. */
@Service
public class PeriodService {

    public record PeriodCommand(String code, String name, String type, LocalDate ordinaryEnrollmentFrom, LocalDate ordinaryEnrollmentTo,
                                LocalDate extraordinaryEnrollmentTo, LocalDate specialEnrollmentTo, LocalDate classesStart,
                                LocalDate classesEnd, LocalDate gradesCloseDeadline) {
    }

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "PLANNED", Set.of("ENROLLMENT"),
            "ENROLLMENT", Set.of("IN_PROGRESS"),
            "IN_PROGRESS", Set.of("CLOSED"));

    private final PeriodRepository periods;
    private final CatalogMapper mapper;
    private final Clock clock;

    public PeriodService(PeriodRepository periods, CatalogMapper mapper, Clock clock) {
        this.periods = periods;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public CatalogViews.PeriodView create(PeriodCommand c) {
        AcademicPeriodEntity e = new AcademicPeriodEntity(UuidV7.next(clock), c.code(), c.name(), c.type(), c.ordinaryEnrollmentFrom(),
                c.ordinaryEnrollmentTo(), c.extraordinaryEnrollmentTo(), c.specialEnrollmentTo(), c.classesStart(), c.classesEnd(),
                c.gradesCloseDeadline());
        return mapper.toView(periods.saveAndFlush(e));
    }

    @Transactional
    public CatalogViews.PeriodView moveTo(UUID periodId, String status) {
        AcademicPeriodEntity e = periods.findById(periodId).orElseThrow(() -> new NotFoundException("El período"));
        if (!TRANSITIONS.getOrDefault(e.getStatus(), Set.of()).contains(status)) {
            throw new BusinessRuleException("RN-01", "El período no puede pasar de " + e.getStatus() + " a " + status);
        }
        e.moveTo(status);
        return mapper.toView(e);
    }

    @Transactional(readOnly = true)
    public PageResponse<CatalogViews.PeriodView> list(PageQuery page) {
        var result = periods.findAllByOrderByClassesStartDesc(PageRequest.of(page.page(), page.size()));
        return PageResponse.of(result.map(mapper::toView).getContent(), page, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public CatalogViews.PeriodView get(UUID id) {
        return mapper.toView(periods.findById(id).orElseThrow(() -> new NotFoundException("El período")));
    }

    /** Período vigente para matrícula y docencia: el más reciente en matrícula o en curso. */
    @Transactional(readOnly = true)
    public CatalogViews.PeriodView current() {
        return periods.findFirstByStatusInOrderByClassesStartDesc(List.of("ENROLLMENT", "IN_PROGRESS"))
                .map(mapper::toView)
                .orElseThrow(() -> new NotFoundException("Un período abierto"));
    }
}
