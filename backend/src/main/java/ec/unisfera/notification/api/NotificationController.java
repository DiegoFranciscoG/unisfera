package ec.unisfera.notification.api;

import ec.unisfera.identity.security.AuthenticatedOnly;
import ec.unisfera.identity.security.SecurityUtils;
import ec.unisfera.notification.application.NotificationService;
import ec.unisfera.shared.web.PageQuery;
import ec.unisfera.shared.web.PageResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/notifications")
@AuthenticatedOnly
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public PageResponse<NotificationService.Notification> list(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return notifications.forUser(SecurityUtils.requireUser().userId(), PageQuery.of(page, size));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("unread", notifications.unread(SecurityUtils.requireUser().userId()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        notifications.markRead(SecurityUtils.requireUser().userId(), id);
        return ResponseEntity.noContent().build();
    }
}
