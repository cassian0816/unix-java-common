package exchange.antx.common.secret;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import exchange.antx.common.util.GsonUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.io.Reader;
import java.io.StringReader;
import java.util.Map;

public class AwsSmEnvPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean awsSmEnable = Boolean.parseBoolean(environment.getProperty("aws.sm.enable", "false"));
        if (!awsSmEnable) {
            System.out.println("aws secret disabled");
            return;
        }
        Region region = Region.of(environment.getProperty("aws.sm.region", "ap-southeast-1"));
        String secretId = environment.getProperty("aws.sm.secret-id");
        try (SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder()
                .region(region)
                .build()) {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretId)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);

            Map<String, String> secretMap;
            try (Reader reader = new StringReader(response.secretString())) {
                secretMap = GsonUtil.gson().fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            }
            environment.getPropertySources()
                    .addFirst(new AwsSmPropertySource(ImmutableMap.copyOf(secretMap)));
            System.out.println("aws secret load success. keySet: " + secretMap.keySet());
        } catch (Throwable t) {
            System.out.println("aws secret load failed: " + t.getMessage());
            Throwables.throwIfUnchecked(t);
            throw new RuntimeException(t);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

}
