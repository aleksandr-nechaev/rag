package com.nechaev.service;

import com.nechaev.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);
    private static final int HASH_PREFIX_LEN = 8;

    public record Prompt(String text, String version, String hash) {}

    private final Prompt systemPrompt;

    public PromptCatalog(AppProperties props) {
        String version = props.prompts().systemVersion();
        this.systemPrompt = load("prompts/system-" + version + ".txt", version);
        log.info("PromptCatalog loaded: system version={} sha256={}",
                systemPrompt.version(), systemPrompt.hash());
    }

    public Prompt systemPrompt() {
        return systemPrompt;
    }

    private static Prompt load(String classpathPath, String version) {
        ClassPathResource resource = new ClassPathResource(classpathPath);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt resource not found: " + classpathPath);
        }
        try (InputStream is = resource.getInputStream()) {
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
            return new Prompt(text, version, sha256Short(text));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt: " + classpathPath, e);
        }
    }

    private static String sha256Short(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_PREFIX_LEN);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
