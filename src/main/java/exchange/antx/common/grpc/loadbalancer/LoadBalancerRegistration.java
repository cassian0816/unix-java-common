package exchange.antx.common.grpc.loadbalancer;

import com.google.common.collect.ImmutableList;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class LoadBalancerRegistration implements DisposableBean {

    private final List<LoadBalancerRegistry> registries = new ArrayList<>(1);
    private final List<LoadBalancerProvider> providers;

    public LoadBalancerRegistration(List<LoadBalancerProvider> providers) {
        this.providers = providers == null ? ImmutableList.of() : ImmutableList.copyOf(providers);
    }

    public void register(LoadBalancerRegistry registry) {
        this.registries.add(registry);
        for (LoadBalancerProvider provider : this.providers) {
            try {
                registry.register(provider);
                log.info("{} is available -> Added to the LoadBalancerRegistry", provider);
            } catch (IllegalArgumentException e) {
                log.error("{} is not available -> Not added to the LoadBalancerRegistry", provider, e);
            }
        }
    }

    @Override
    public void destroy() {
        for (LoadBalancerRegistry registry : this.registries) {
            for (LoadBalancerProvider provider : this.providers) {
                registry.deregister(provider);
                log.info("{} was removed from the LoadBalancerRegistry", provider);
            }
        }
        this.registries.clear();
    }
}
