package ec.unisfera.notification.application;

import ec.unisfera.shared.config.AppProperties;
import ec.unisfera.shared.domain.UuidV7;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Avisos en la aplicación y por correo. El correo se envía después del commit, en segundo plano. */
@Service
public class NotificationService {

    public record Notification(UUID id, String category, String title, String body, String link, Instant createdAt, Instant readAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JdbcClient jdbc;
    private final JavaMailSender mail;
    private final ExecutorService executor;
    private final AppProperties props;
    private final Clock clock;

    public NotificationService(JdbcClient jdbc, JavaMailSender mail, ExecutorService backgroundExecutor, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.executor = backgroundExecutor;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public void notifyUser(UUID userId, String category, String title, String body, String link) {
        jdbc.sql("insert into notification (id, user_id, channel, category, title, body, link) values (:id, :user, 'IN_APP', :cat, :title, :body, :link)")
                .param("id", UuidV7.next(clock)).param("user", userId).param("cat", category)
                .param("title", title).param("body", body).param("link", link).update();
    }

    /** Correo a una dirección verificada; nunca bloquea la petición ni revela si el envío falló. */
    public void email(String to, String subject, String body) {
        Runnable send = () -> executor.submit(() -> {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(props.mailFrom());
                message.setTo(to);
                message.setSubject(subject);
                message.setText(body);
                mail.send(message);
            } catch (RuntimeException e) {
                log.warn("No se pudo enviar el correo '{}': {}", subject, e.getMessage());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Notification> forUser(UUID userId, PageQuery page) {
        var items = jdbc.sql("""
                        select id, category, title, body, link, created_at, read_at from notification
                        where user_id = :user order by created_at desc limit :limit offset :offset""")
                .param("user", userId).param("limit", page.size()).param("offset", page.offset())
                .query((rs, n) -> new Notification(rs.getObject("id", UUID.class), rs.getString("category"), rs.getString("title"),
                        rs.getString("body"), rs.getString("link"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()))
                .list();
        long total = jdbc.sql("select count(*) from notification where user_id = :user").param("user", userId).query(Long.class).single();
        return PageResponse.of(items, page, total);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        jdbc.sql("update notification set read_at = now() where id = :id and user_id = :user and read_at is null")
                .param("id", notificationId).param("user", userId).update();
    }

    public long unread(UUID userId) {
        return jdbc.sql("select count(*) from notification where user_id = :user and read_at is null")
                .param("user", userId).query(Long.class).single();
    }
}
