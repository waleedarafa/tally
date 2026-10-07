package spike.modulith;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SpikeConfiguration {

    @Bean
    SpikeListener spikeListener() {
        return new SpikeListener();
    }
}
