package exchange.antx.common.grpc.loadbalancer;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;

public class JraftLoadBalancerProvider extends LoadBalancerProvider {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public String getPolicyName() {
        return "jraft";
    }

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        return new JraftLoadBalancer(helper);
    }

}
