package com.example.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model registry — the agent's model-selection layer.
 * <p>
 * The agent is <b>model-agnostic</b>: analysis code talks to the Spring AI
 * {@link org.springframework.ai.chat.model.ChatModel} abstraction, never to a
 * specific vendor. This registry owns the mapping from human-friendly aliases
 * (as supplied in the webhook's {@code model} field) to concrete model
 * identifiers, and is the single place a new model is added — a config change,
 * not a code change.
 * <p>
 * Implementation: each alias is materialized as a {@link ChatClient} built from
 * the auto-configured default builder with the alias's {@link OpenAiChatOptions}
 * applied as default options. Because Spring AI applies request options on top
 * of the model's defaults, overriding the model identifier per client is enough
 * to switch models — no manual construction of provider clients required.
 * <p>
 * Design:
 * <ul>
 *   <li>The default model (Spring AI auto-configured, from
 *       {@code spring.ai.openai.chat.options.model}) is always registered under
 *       its model identifier.</li>
 *   <li>Additional aliases declared in {@code app.ai.models} are built lazily
 *       at startup. Unknown or missing aliases fall back to the default —
 *       the agent never fails because a caller asked for an unregistered model.</li>
 *   <li>{@link #list()} only returns aliases that actually exist, so a caller
 *       can discover what is available (GET /api/models).</li>
 * </ul>
 */
@Component
public class AiModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiModelRegistry.class);

    private final Map<String, ChatClient> clients = new LinkedHashMap<>();
    private final String defaultName;

    /**
     * @param defaultChatClientBuilder the Spring AI auto-configured builder bound
     *                                 to the default {@link ChatModel} (default options applied)
     * @param props                    the {@code app.ai.*} configuration block
     * @param defaultModel             the configured default model identifier
     */
    public AiModelRegistry(
            ChatClient.Builder defaultChatClientBuilder,
            AiModelProperties props,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String defaultModel) {
        this.defaultName = defaultModel;

        // 1. Default model — preserves the auto-configured options (temperature, max tokens, ...).
        clients.put(defaultName, defaultChatClientBuilder.build());
        log.info("Model registry: default model = {}", defaultName);

        // 2. Additional aliases from app.ai.models.
        if (props != null && props.models() != null) {
            for (AiModelProperties.Model alias : props.models()) {
                if (alias.name() == null || alias.name().isBlank() || alias.model() == null) {
                    log.warn("Model registry: skipping invalid alias entry {}", alias);
                    continue;
                }
                if (clients.containsKey(alias.name())) {
                    log.debug("Model registry: alias '{}' already registered, skipping", alias.name());
                    continue;
                }
                OpenAiChatOptions options = OpenAiChatOptions.builder()
                        .withModel(alias.model())
                        .withTemperature(0.1)
                        .withMaxTokens(1000)
                        .build();
                clients.put(alias.name(), defaultChatClientBuilder.clone().defaultOptions(options).build());
                log.info("Model registry: registered alias '{}' → {}", alias.name(), alias.model());
            }
        }
    }

    /**
     * Returns the {@link ChatClient} for the given alias, falling back to the
     * default model for null, blank, or unknown names.
     */
    public ChatClient clientFor(String alias) {
        String resolved = resolveName(alias);
        return clients.get(resolved);
    }

    /**
     * Resolves a requested alias to the concrete registered name (default for
     * null / blank / unknown). Used to record which model actually served a request.
     */
    public String resolveName(String alias) {
        if (alias != null && clients.containsKey(alias)) {
            return alias;
        }
        return defaultName;
    }

    /** Names of all registered models, in registration order (default first). */
    public List<String> list() {
        return List.copyOf(clients.keySet());
    }

    /** The default model alias. */
    public String defaultName() {
        return defaultName;
    }
}
