package com.interopera.rulegraph.firmconfig;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Loads, lists, and saves firm method configurations as YAML files by firm id.
 *
 * <p>Files are resolved first from the writable firms directory (the configured external
 * {@code firms-path}, or {@code artifacts/firms} by default) and otherwise from the bundled
 * {@code firms/} resources. Adding, switching, or saving a firm is therefore a configuration change,
 * never an engine-code change: the calculators read the resulting {@link FirmConfig} and behave
 * accordingly. The writable directory is also where {@link #save(FirmConfig)} persists a new firm so
 * it is immediately loadable and appears in {@link #listFirms()}.
 */
@Component
public class FirmConfigLoader {

    /** Firms always available from the bundled resources. */
    private static final List<String> BUNDLED_FIRMS = List.of("firm_A", "firm_B");

    private final RuleGraphProperties props;

    public FirmConfigLoader(RuleGraphProperties props) {
        this.props = props;
    }

    public FirmConfig load(String firmId) {
        Map<String, Object> doc = read(firmId);
        return toConfig(firmId, doc);
    }

    /** The bundled firms plus any saved in the writable firms directory, bundled first. */
    public List<String> listFirms() {
        List<String> firms = new ArrayList<>(BUNDLED_FIRMS);
        Path dir = writableFirmsDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                new TreeSet<>(files
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".yaml"))
                        .map(n -> n.substring(0, n.length() - ".yaml".length()))
                        .toList())
                        .forEach(id -> {
                            if (!firms.contains(id)) {
                                firms.add(id);
                            }
                        });
            } catch (IOException e) {
                throw new FirmConfigException("Failed to list firms in " + dir, e);
            }
        }
        return firms;
    }

    /** Persists a firm config to the writable firms directory and returns the file written. */
    public Path save(FirmConfig config) {
        Path dir = writableFirmsDir();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(config.firmId() + ".yaml");
            Files.writeString(file, toYaml(config), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new FirmConfigException("Failed to save firm config for " + config.firmId(), e);
        }
    }

    /** Configured external {@code firms-path}, or {@code artifacts/firms} when none is set. */
    private Path writableFirmsDir() {
        String configured = props.firmsPath();
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of("artifacts", "firms");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String firmId) {
        String fileName = firmId + ".yaml";
        Yaml yaml = new Yaml();

        // 1) Writable firms directory (external override / saved firms).
        Path external = writableFirmsDir().resolve(fileName);
        if (Files.exists(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                return yaml.loadAs(in, Map.class);
            } catch (IOException e) {
                throw new FirmConfigException("Failed to read firm config: " + external, e);
            }
        }

        // 2) Bundled resource under firms/.
        String resource = "firms/" + fileName;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new FirmConfigException("No firm config found for '" + firmId
                        + "' (looked for " + resource + " on the classpath and in "
                        + writableFirmsDir() + ")");
            }
            return yaml.loadAs(in, Map.class);
        } catch (IOException e) {
            throw new FirmConfigException("Failed to parse firm config: " + resource, e);
        }
    }

    /** Serialises a config to the same YAML shape as the bundled firm files. */
    private static String toYaml(FirmConfig c) {
        String groupBy = c.greGroupBy() == FirmConfig.GreGroupBy.PARENT_ISSUER
                ? "parent_issuer" : "issuer";
        String format = c.utilizationFormat() == FirmConfig.UtilizationFormat.TRUNCATED_BPS
                ? "truncated_bps" : "percent_1dp";
        return String.join("\n",
                "# " + c.firmId() + " method (saved from the method DSL).",
                "firm_id: " + c.firmId(),
                "",
                "non_ig_aggregate:",
                "  include_fallen_angels: " + c.includeFallenAngels(),
                "",
                "gre_concentration:",
                "  group_by: " + groupBy + "        # issuer | parent_issuer",
                "",
                "utilization:",
                "  format: " + format + "         # percent_1dp | truncated_bps",
                "") + "\n";
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
