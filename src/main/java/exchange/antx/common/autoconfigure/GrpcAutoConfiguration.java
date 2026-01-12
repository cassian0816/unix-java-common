package exchange.antx.common.autoconfigure;

import com.alibaba.boot.nacos.discovery.NacosDiscoveryConstants;
import exchange.antx.common.grpc.interceptor.LogAndMetricsClientInterceptor;
import exchange.antx.common.grpc.interceptor.LogAndMetricsServerInterceptor;
import exchange.antx.common.grpc.loadbalancer.JraftLoadBalancerProvider;
import exchange.antx.common.grpc.loadbalancer.LoadBalancerRegistration;
import exchange.antx.common.grpc.nameresolver.NacosNameResolverProvider;
import io.grpc.ClientInterceptor;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ServerInterceptor;
import io.grpc.util.TransmitStatusRuntimeExceptionInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import net.devh.boot.grpc.client.nameresolver.StaticNameResolverProvider;
import net.devh.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;

@Slf4j
@Configuration
@AutoConfigureBefore(value = {
        GrpcServerAutoConfiguration.class,
        GrpcClientAutoConfiguration.class})
public class GrpcAutoConfiguration {

    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    @GrpcGlobalServerInterceptor
    public ServerInterceptor transmitStatusRuntimeExceptionInterceptor() {
        return TransmitStatusRuntimeExceptionInterceptor.instance();
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    @GrpcGlobalServerInterceptor
    public ServerInterceptor logAndMetricsServerInterceptor() {
        return new LogAndMetricsServerInterceptor();
    }

    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    @GrpcGlobalClientInterceptor
    public ClientInterceptor logAndMetricsClientInterceptor() {
        return new LogAndMetricsClientInterceptor();
    }

    @Bean
    public StaticNameResolverProvider staticNameResolverProvider() {
        return new StaticNameResolverProvider();
    }

    @Bean
    @ConditionalOnProperty(name = NacosDiscoveryConstants.ENABLED, matchIfMissing = true)
    public NacosNameResolverProvider nacosNameResolverProvider() {
        log.info("using nacosNameResolverProvider");
        return new NacosNameResolverProvider();
    }

    @Bean
    public JraftLoadBalancerProvider jraftLoadBalancerProvider() {
        log.info("using jraftLoadBalancerProvider");
        return new JraftLoadBalancerProvider();
    }

    @Bean
    public LoadBalancerRegistration grpcLoadBalancerRegistration(
            @Autowired(required = false) final List<LoadBalancerProvider> loadBalancerProviders) {
        final LoadBalancerRegistration loadBalancerRegistration = new LoadBalancerRegistration(loadBalancerProviders);
        loadBalancerRegistration.register(LoadBalancerRegistry.getDefaultRegistry());
        return loadBalancerRegistration;
    }

}
