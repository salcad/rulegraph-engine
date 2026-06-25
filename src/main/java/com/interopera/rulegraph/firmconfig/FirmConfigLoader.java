package com.interopera.rulegraph.firmconfig;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a firm's method configuration from a YAML file by firm id.
 *
 * <p>Files are resolved first from an optional external directory (so new firms can be added without
 * rebuilding the application) and otherwise from the bundled {@code firms/} resources. Adding or
 * switching a firm is therefore a configuration change, never an engine-code change: the calculators
 * read the resulting {@link FirmConfig} and behave accordingly.
 */
@Component
public class FirmConfigLoader {

    private final RuleGraphProperties props;

    public FirmConfigLoader(RuleGraphProperties props) {
        this.props = props;
    }

    public FirmConfig load(String firmId) {
        Map<String, Object> doc = read(firmId);
        return toConfig(firmId, doc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String firmId) {
        String fileName = firmId + ".yaml";
        Yaml yaml = new Yaml();

        // 1) External directory override, if configured.
        if (props.firmsPath() != null && !props.firmsPath().isBlank()) {
            Path external = Path.of(props.firmsPath(), fileName);
            if (Files.exists(external)) {
                try (InputStream in = Files.newInputStream(external)) {
                    return yaml.loadAs(in, Map.class);
                } catch (IOException e) {
                    throw new FirmConfigException("Failed to read firm config: " + external, e);
                }
            }
        }

        // 2) Bundled resource under firms/.
        String resource = "firms/" + fileName;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new FirmConfigException("No firm config found for '" + firmId
                        + "' (looked for " + resource + " on the classpath"
                        + (props.firmsPath() != null ? " and in " + props.firmsPath() : "") + ")");
            }
            return yaml.loadAs(in, Map.class);
        } catch (IOException e) {
            throw new FirmConfigException("Failed to parse firm config: " + resource, e);
        }
    }

    private FirmConfig toConfig(String firmId, Map<String, Object> doc) {
        String id = stringAt(doc, "firm_id", firmId);
        boolean fallenAngels = boolAt(section(doc, "non_ig_aggregate"), "include_fallen_angels", false);
        FirmConfig.GreGroupBy groupBy = parseGroupBy(
                stringAt(section(doc, "gre_concentration"), "group_by", "issuer"));
        FirmConfig.UtilizationFormat format = parseFormat(
                stringAt(section(doc, "utilization"), "format", "percent_1dp"));
        return new FirmConfig(id, fallenAngels, groupBy, format);
    }

    private FirmConfig.GreGroupBy parseGroupBy(String raw) {
        return switch (norm(raw)) {
            case "issuer" -> FirmConfig.GreGroupBy.ISSUER;
            case "parent_issuer" -> FirmConfig.GreGroupBy.PARENT_ISSUER;
            default -> throw new FirmConfigException("Unknown gre_concentration.group_by: " + raw);
        };
    }

    private FirmConfig.UtilizationFormat parseFormat(String raw) {
        return switch (norm(raw)) {
            case "percent_1dp" -> FirmConfig.UtilizationFormat.PERCENT_1DP;
            case "truncated_bps" -> FirmConfig.UtilizationFormat.TRUNCATED_BPS;
            default -> throw new FirmConfigException("Unknown utilization.format: " + raw);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> doc, String key) {
        Object v = doc == null ? null : doc.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String stringAt(Map<String, Object> map, String key, String def) {
        Object v = map == null ? null : map.get(key);
        return v == null ? def : v.toString();
    }

    private static boolean boolAt(Map<String, Object> map, String key, boolean def) {
        Object v = map == null ? null : map.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
