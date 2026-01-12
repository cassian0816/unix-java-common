package exchange.antx.common.grpc.nameresolver;

import com.alibaba.nacos.api.annotation.NacosInjected;
import com.alibaba.nacos.api.naming.NamingService;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.internal.GrpcUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NacosNameResolverProvider extends NameResolverProvider {

    private static final String NACOS = "nacos";

    private final Set<NacosNameResolver> discoveryClientNameResolvers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(10);

    @NacosInjected
    private NamingService namingService;

    public NacosNameResolverProvider() {
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 4;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, final NameResolver.Args args) {
        final NacosNameResolver nameResolver = new NacosNameResolver(targetUri.getAuthority(), this.namingService, args,
                GrpcUtil.SHARED_CHANNEL_EXECUTOR, this.discoveryClientNameResolvers::remove);
        discoveryClientNameResolvers.add(nameResolver);

        // heartbeat
        executorService.scheduleAtFixedRate(() -> {
            for (final NacosNameResolver nacosNameResolver : discoveryClientNameResolvers) {
                nacosNameResolver.refreshFromExternal();
            }
        }, 3, 5, TimeUnit.SECONDS);

        return nameResolver;
    }

    @Override
    public String getDefaultScheme() {
        return NACOS;
    }

}
