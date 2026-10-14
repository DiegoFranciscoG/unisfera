package ec.unisfera.shared.persistence;

import ec.unisfera.shared.web.RequestContext;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import java.util.UUID;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Al abrir cada transacción copia actor, IP, request-id y proceso a variables locales de la
 * sesión de PostgreSQL (set_config(..., true)). Los triggers de auditoría y de LEY-2 las leen,
 * así la base sabe quién hizo cada cambio aunque el SQL lo ejecute otra capa.
 */
public class ActorAwareTransactionManager extends JpaTransactionManager {

    private static final String SQL = "select set_config('unisfera.actor_id', ?1, true), set_config('unisfera.actor_ip', ?2, true), "
            + "set_config('unisfera.request_id', ?3, true), set_config('unisfera.process', ?4, true)";

    public ActorAwareTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            return;
        }
        holder.getEntityManager().createNativeQuery(SQL)
                .setParameter(1, RequestContext.actorId().map(UUID::toString).orElse(""))
                .setParameter(2, Objects.toString(RequestContext.ip(), ""))
                .setParameter(3, Objects.toString(RequestContext.requestId(), ""))
                .setParameter(4, Objects.toString(RequestContext.process(), ""))
                .getSingleResult();
    }
}
