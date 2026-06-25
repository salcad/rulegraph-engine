package com.interopera.rulegraph.reconciliation;

import com.interopera.rulegraph.domain.FigureStatus;
import com.interopera.rulegraph.reconciliation.ExpectedFigures.Expected;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Reads the provided {@code firm_A_answer_key.xlsx} into the expected-figure map used for
 * reconciliation. The workbook stores its cells as inline strings, so it is parsed directly with the
 * JDK's streaming XML reader; no spreadsheet library is required. Reading the actual answer key (as
 * opposed to a transcription) is what makes the Firm A reconciliation a genuine check.
 */
public class AnswerKeyXlsxReader {

    /** Maps the human metric labels in the spreadsheet to the engine's figure codes. */
    private static final Map<String, String> METRIC_TO_CODE = Map.ofEntries(
            Map.entry("singapore government securities", "singapore_government_securities"),
            Map.entry("mas bills", "mas_bills"),
            Map.entry("investment grade corporate bonds", "investment_grade_corporate_bonds"),
            Map.entry("high yield bonds", "high_yield"),
            Map.entry("foreign currency bonds (hedged)", "foreign_currency_bonds"),
            Map.entry("structured credit (abs/mbs)", "structured_credit"),
            Map.entry("cash & cash equivalents", "cash"),
            Map.entry("aggregate non-ig exposure", "aggregate_non_ig_exposure"),
            Map.entry("largest single corporate issuer", "single_corporate_issuer"),
            Map.entry("largest gre issuer", "gre_issuer"),
            Map.entry("liquid assets ratio", "liquid_assets_ratio"),
            Map.entry("portfolio modified duration", "modified_duration"),
            Map.entry("portfolio dv01", "portfolio_dv01"));

    public Map<String, Expected> read(String xlsxPath) {
        Map<String, Expected> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(xlsxPath);
             InputStream sheet = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml"))) {

            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader xml = factory.createXMLStreamReader(sheet);

            Map<String, String> row = new LinkedHashMap<>();
            String cellRef = null;
            boolean inValue = false;
            StringBuilder text = new StringBuilder();

            while (xml.hasNext()) {
                int e = xml.next();
                if (e == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "row" -> row.clear();
                        case "c" -> cellRef = xml.getAttributeValue(null, "r");
                        case "t", "v" -> {
                            inValue = true;
                            text.setLength(0);
                        }
                        default -> { }
                    }
                } else if (e == XMLStreamConstants.CHARACTERS && inValue) {
                    text.append(xml.getText());
                } else if (e == XMLStreamConstants.END_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "t", "v" -> {
                            if (inValue && cellRef != null) {
                                row.put(column(cellRef), text.toString());
                            }
                            inValue = false;
                        }
                        case "row" -> addRow(result, row);
                        default -> { }
                    }
                }
            }
            xml.close();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read answer key: " + xlsxPath, ex);
        }
        return result;
    }

    /** Columns: A Section, B Metric, C Value, D Limit, E Utilization, F Status. */
    private void addRow(Map<String, Expected> result, Map<String, String> row) {
        String metric = row.getOrDefault("B", "").trim();
        String code = METRIC_TO_CODE.get(metric.toLowerCase(Locale.ROOT));
        if (code == null) {
            return; // header row or unmapped line
        }
        String value = row.getOrDefault("C", "").trim();
        String util = row.getOrDefault("E", "").trim();
        FigureStatus status = parseStatus(row.getOrDefault("F", "").trim());
        result.put(code, new Expected(value, status, util));
    }

    private FigureStatus parseStatus(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "OK" -> FigureStatus.OK;
            case "BREACH" -> FigureStatus.BREACH;
            case "AT LIMIT", "AT_LIMIT" -> FigureStatus.AT_LIMIT;
            default -> FigureStatus.ERROR;
        };
    }

    /** Strips the row digits from a cell reference, e.g. {@code "C7" -> "C"}. */
    private String column(String cellRef) {
        int i = 0;
        while (i < cellRef.length() && Character.isLetter(cellRef.charAt(i))) {
            i++;
        }
        return cellRef.substring(0, i);
    }
}
