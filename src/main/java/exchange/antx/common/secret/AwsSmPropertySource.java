package exchange.antx.common.secret;

import com.google.common.collect.ImmutableMap;
import org.springframework.core.env.PropertySource;

public class AwsSmPropertySource extends PropertySource.StubPropertySource {

    private static final String AWS_SECRET_PREFIX = "AwsSecret::";

    private final ImmutableMap<String, String> secretMap;

    AwsSmPropertySource(ImmutableMap<String, String> secretMap) {
        super("AwsSecretManager");
        this.secretMap = secretMap;
    }

    @Override
    public String getProperty(String property) {
        if (!property.startsWith(AWS_SECRET_PREFIX)) {
            return null;
        }
        return this.secretMap.get(property.substring(AWS_SECRET_PREFIX.length()));
    }

}
