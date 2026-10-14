package ec.unisfera.institution.application;

import ec.unisfera.institution.persistence.AcademicPeriodEntity;
import ec.unisfera.institution.persistence.CareerEntity;
import ec.unisfera.institution.persistence.CurriculumEntity;
import ec.unisfera.institution.persistence.SubjectEntity;
import org.mapstruct.Mapper;

/** Mapeo entidad → vista generado en compilación (sin reflexión en tiempo de ejecución). */
@Mapper
public interface CatalogMapper {

    CatalogViews.CareerView toView(CareerEntity entity);

    CatalogViews.CurriculumView toView(CurriculumEntity entity);

    CatalogViews.SubjectView toView(SubjectEntity entity);

    CatalogViews.PeriodView toView(AcademicPeriodEntity entity);
}
