package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ContextCompileOptions {

    PreloadMode preloadMode;
    String nodeType;
    Boolean fallbackPreloadEnabled;

    public static ContextCompileOptions auto() {
        return ContextCompileOptions.builder()
                .preloadMode(PreloadMode.AUTO)
                .fallbackPreloadEnabled(null)
                .build();
    }

    public static ContextCompileOptions minimal() {
        return ContextCompileOptions.builder()
                .preloadMode(PreloadMode.MINIMAL)
                .fallbackPreloadEnabled(false)
                .build();
    }

    public static ContextCompileOptions full() {
        return ContextCompileOptions.builder()
                .preloadMode(PreloadMode.FULL)
                .fallbackPreloadEnabled(true)
                .build();
    }

    public enum PreloadMode {
        AUTO,
        MINIMAL,
        FULL
    }
}
