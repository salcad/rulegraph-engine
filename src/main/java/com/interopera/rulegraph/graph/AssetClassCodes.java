package com.interopera.rulegraph.graph;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the raw asset-class labels in the holdings CSV to the canonical codes used by the rule
 * intents and the graph, so a {@code Position} and the {@code AssetClass} its limit is attached to
 * resolve to the same node.
 *
 * <p>The mapping is <em>data-driven</em>: alias rules are read from {@code asset_class_codes.yaml}
 * (a bundled resource, or an external file via {@code rulegraph.asset-class-codes-path}) rather than
 * hardcoded, so a new fund's vocabulary is a configuration change, not an engine-code change. A
 * label matches a code when its lower-cased text contains one of that code's configured substrings
 * (first match wins); an unmatched label falls back to a slugified form so it still yields a usable,
 * deterministic code. Resolution is pure and total, so reruns are byte-identical (constraint 1).
 */
@Component
public class AssetClassCodes {

    private static final Logger log = LoggerFactory.getLogger(AssetClassCodes.class);
    private static final String BUNDLED_RESOURCE = "asset_class_codes.yaml";

    /** One alias rule: any of {@code matches} (lower-case substrings) resolves to {@code code}. */
    private record Mapping(String code, List<String> matches) {
    }

    private final List<Mapping> mappings;

    public AssetClassCodes(RuleGraphProperties props) {
        this.mappings = load(props.assetClassCodesPath());
        log.info("Loaded {} asset-class code mapping(s)", mappings.size());
    }

    public String toCode(String rawAssetClass) {
        String s = rawAssetClass == null ? "" : rawAssetClass.toLowerCase(Locale.ROOT);
        for (Mapping m : mappings) {
            for (String needle : m.matches()) {
                if (s.contains(needle)) {
                    return m.code();
                }
            }
        }
        String slug = slug(s);
        log.warn("Asset-class label '{}' matched no configured mapping; using derived code '{}'. "
                + "Add it to asset_class_codes.yaml to give it a canonical code.", rawAssetClass, slug);
        return slug;
    }

    private static String slug(String lowerCased) {
        String slug = lowerCased.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? "unknown" : slug;
    }

    // --- loading -------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Mapping> load(String externalPath) {
        Map<String, Object> doc = read(externalPath);
        Object raw = doc == null ? null : doc.get("mappings");
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalStateException("asset_class_codes.yaml must contain a 'mappings' list");
        }
        List<Mapping> result = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> map = (Map<String, Object>) entry;
            String code = String.valueOf(map.get("code")).trim();
            List<String> matches = new ArrayList<>();
            Object m = map.get("match");
            if (m instanceof List<?> list) {
                for (Object item : list) {
                    matches.add(String.valueOf(item).toLowerCase(Locale.ROOT).trim());
                }
            }
            if (code.isBlank() || matches.isEmpty()) {
                throw new IllegalStateException(
                        "Each asset-class mapping needs a non-blank 'code' and at least one 'match'");
            }
            result.add(new Mapping(code, matches));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String externalPath) {
        Yaml yaml = new Yaml();

        // 1) External override file, if configured.
        if (externalPath != null && !externalPath.isBlank()) {
            Path external = Path.of(externalPath);
            if (Files.exists(external)) {
                try (InputStream in = Files.newInputStream(external)) {
                    return yaml.loadAs(in, Map.class);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Failed to read asset-class codes file: " + external, e);
                }
            }
            log.warn("rulegraph.asset-class-codes-path={} not found; using bundled mapping",
                    externalPath);
        }

        // 2) Bundled resource.
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing bundled resource on classpath: " + BUNDLED_RESOURCE);
            }
            return yaml.loadAs(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse " + BUNDLED_RESOURCE, e);
        }
    }
}
