package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Binds the {@code app.ai.*} configuration block.
 * <p>
 * Lets an operator declare which model aliases the agent exposes without code
 * changes — e.g. {@code gpt-4o-mini} as the fast default, {@code gpt-4o} as a
 * stronger option. Each alias maps to a concrete model identifier used by the
 * model provider.
 *
 * <pre>
 * app:
 *   ai:
 *     models:
 *       - name: gpt-4o-mini
 *         model: gpt-4o-mini
 *       - name: gpt-4o
 *         model: gpt-4o
 * </pre>
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiModelProperties(List<Model> models) {

    /**
     * One selectable model alias.
     *
     * @param name  the alias exposed to callers (webhook {@code model} field)
     * @param model the concrete provider model identifier (e.g. {@code gpt-4o-mini})
     */
    public record Model(String name, String model) {
    }
}
