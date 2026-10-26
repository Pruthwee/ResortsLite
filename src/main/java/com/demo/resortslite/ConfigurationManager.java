package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads configuration values from AWS Systems Manager Parameter Store.
 * Used to externalize hard-coded environment URLs (cr-java-0071).
 */
@Component
public class ConfigurationManager {

    @Value("${app.parameter.path:/resortslite/config/}")
    private String parameterPath;

    private SsmClient ssmClient;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private SsmClient ssmClient() {
        if (ssmClient == null) {
            ssmClient = SsmClient.builder().build();
        }
        return ssmClient;
    }

    /**
     * Retrieve a parameter value from Parameter Store with caching.
     * Falls back to the default value if Parameter Store is unavailable.
     *
     * @param key        the parameter key relative to the parameter path
     * @param defaultValue the fallback value if the parameter cannot be retrieved
     * @return the parameter value or the default
     */
    public String getParameter(String key, String defaultValue) {
        String fullPath = parameterPath + key;
        return cache.computeIfAbsent(fullPath, k -> {
            try {
                GetParameterRequest request = GetParameterRequest.builder()
                        .name(k)
                        .withDecryption(true)
                        .build();
                return ssmClient().getParameter(request).parameter().value();
            } catch (SdkException e) {
                return defaultValue;
            }
        });
    }
}
