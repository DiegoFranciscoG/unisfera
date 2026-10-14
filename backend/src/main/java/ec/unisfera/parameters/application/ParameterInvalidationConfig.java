package ec.unisfera.parameters.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Cada instancia escucha el canal de invalidación: escala horizontal sin cachés desactualizadas. */
@Configuration
public class ParameterInvalidationConfig {

    @Bean
    public RedisMessageListenerContainer parameterInvalidationListener(RedisConnectionFactory factory, ParameterService parameters) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> parameters.invalidateLocal(),
                new ChannelTopic(ParameterService.INVALIDATION_CHANNEL));
        return container;
    }
}
