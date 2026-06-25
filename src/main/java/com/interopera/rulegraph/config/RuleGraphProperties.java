package com.interopera.rulegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application configuration under the {@code rulegraph.*} prefix.
 *
 * @param sampleDocsPath absolute path to the provided {@code sample_docs/} materials
 * @param guidelinesPdf  filename of the guidelines PDF within {@code sampleDocsPath}
 * @param holdingsCsv    filename of the holdings CSV within {@code sampleDocsPath}
 * @param firmsPath      optional external directory holding per-firm YAML files; when unset, firm
 *                       configs are loaded from the bundled {@code firms/} resources
 */
@ConfigurationProperties(prefix = "rulegraph")
public record RuleGraphProperties(
        String sampleDocsPath,
        String guidelinesPdf,
        String holdingsCsv,
        String firmsPath
) {
    public String guidelinesPdfPath() {
        return sampleDocsPath + "/" + guidelinesPdf;
    }

    public String holdingsCsvPath() {
        return sampleDocsPath + "/" + holdingsCsv;
    }
}
