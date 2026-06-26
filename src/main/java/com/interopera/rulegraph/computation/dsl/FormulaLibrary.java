package com.interopera.rulegraph.computation.dsl;

import com.interopera.rulegraph.domain.FormulaKey;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The trusted registry of figure arithmetic, loaded from {@code formulas.yaml}. Each
 * {@link FormulaKey} maps to a parsed {@link Expr}; calculators evaluate it against the scalar inputs
 * they bind from graph traversals.
 *
 * <p>The file is parsed once at startup, so a malformed formula or an unknown {@link FormulaKey}
 * fails fast at boot rather than mid-report. The registry also exposes the verbatim expressions and
 * a SHA-256 over their canonical form, which the run records in its audit log — this is what makes
 * the arithmetic-in-config auditable: an examiner can see exactly which formulas produced a report
 * and verify the file was not altered between runs.
 */
@Component
public class FormulaLibrary {

    private final Resource resource;
    private final Map<FormulaKey, Expr> compiled = new EnumMap<>(FormulaKey.class);
    private final Map<FormulaKey, String> expressions = new EnumMap<>(FormulaKey.class);
    private String sha256;

    public FormulaLibrary(@Value("${rulegraph.formulas-resource:classpath:formulas.yaml}") Resource resource) {
        this.resource = resource;
    }

    @PostConstruct
    void load() {
        Map<String, Object> doc;
        try (InputStream in = resource.getInputStream()) {
            doc = new Yaml().loadAs(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read formula registry: " + resource, e);
        }
        if (doc == null || doc.isEmpty()) {
            throw new IllegalStateException("Formula registry is empty: " + resource);
        }
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            FormulaKey key = parseKey(e.getKey());
            String expr = String.valueOf(e.getValue()).trim();
            // Parse eagerly so a bad formula is a boot failure, not a runtime surprise.
            compiled.put(key, FormulaParser.parse(expr));
            expressions.put(key, expr);
        }
        this.sha256 = hashOf(expressions);
    }

    /** Evaluate the formula for {@code key} against the given variable bindings. */
    public BigDecimal evaluate(FormulaKey key, Map<String, BigDecimal> vars) {
        Expr expr = compiled.get(key);
        if (expr == null) {
            throw new FormulaException("no formula registered for " + key);
        }
        return FormulaEvaluator.eval(expr, vars);
    }

    public boolean hasFormula(FormulaKey key) {
        return compiled.containsKey(key);
    }

    /** The verbatim expression for a key (for display / the reconciliation viewer). */
    public String expression(FormulaKey key) {
        return expressions.get(key);
    }

    /** All registered expressions, key name -> expression, in stable order (for the audit event). */
    public Map<String, String> expressions() {
        Map<String, String> out = new LinkedHashMap<>();
        new TreeMap<>(expressions).forEach((k, v) -> out.put(k.name(), v));
        return out;
    }

    /** SHA-256 over the canonical {@code KEY=expr} form — recorded in the audit log per run. */
    public String sha256() {
        return sha256;
    }

    private static FormulaKey parseKey(String raw) {
        try {
            return FormulaKey.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown formula key in registry: '" + raw + "'");
        }
    }

    private static String hashOf(Map<FormulaKey, String> exprs) {
        StringBuilder canonical = new StringBuilder();
        // Sort by key name so the hash is independent of file ordering.
        new TreeMap<>(exprs).forEach((k, v) -> canonical.append(k.name()).append('=').append(v).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
