package com.example.aibuild;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Chains multiple {@link AiProvider} instances together in sequence.
 * If the primary provider exhausts its configured models or fails, execution
 * automatically fails over to the next provider in the chain.
 */
final class ChainedAiProvider implements AiProvider {

    private final List<AiProvider> providers;

    ChainedAiProvider(List<AiProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("ChainedAiProvider requires at least one provider");
        }
        this.providers = List.copyOf(providers);
    }

    List<AiProvider> providers() {
        return providers;
    }

    @Override
    public String label() {
        if (providers.size() == 1) {
            return providers.get(0).label();
        }
        return providers.get(0).label() + " (+" + (providers.size() - 1) + " provider fallbacks)";
    }

    @Override
    public String complete(String system, String user, Consumer<String> statusListener)
            throws IOException, InterruptedException {
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < providers.size(); i++) {
            AiProvider current = providers.get(i);
            if (providers.size() > 1) {
                statusListener.accept("Using provider " + (i + 1) + "/" + providers.size() + " (" + current.label() + ")...");
            }
            long start = System.currentTimeMillis();
            try {
                return current.complete(system, user, statusListener);
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                errors.add(current.label() + " (" + elapsed + "ms): " + msg);
                if (i + 1 < providers.size()) {
                    AiProvider next = providers.get(i + 1);
                    statusListener.accept("Provider '" + current.label() + "' failed completely (" + msg
                            + "). Failing over to provider '" + next.label() + "'...");
                }
            }
        }

        throw new IOException("All configured AI providers failed:\n- " + String.join("\n- ", errors));
    }
}

