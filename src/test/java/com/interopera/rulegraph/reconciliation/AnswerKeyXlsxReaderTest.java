package com.interopera.rulegraph.reconciliation;

import com.interopera.rulegraph.domain.FigureStatus;
import com.interopera.rulegraph.reconciliation.ExpectedFigures.Expected;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Confirms the provided Firm A answer key is read directly from the spreadsheet for reconciliation. */
class AnswerKeyXlsxReaderTest {

    private static final String XLSX = System.getProperty("rulegraph.sampleDocs",
            "/home/salcad/interopera-homework/rulegraph-docs/sample_docs/sample_docs")
            + "/firm_A_answer_key.xlsx";

    @Test
    void readsFirmAAnswerKeyFromSpreadsheet() {
        assumeTrue(Files.exists(Path.of(XLSX)), "sample answer key not present");
        Map<String, Expected> key = new AnswerKeyXlsxReader().read(XLSX);

        assertThat(key).hasSize(13);
        assertThat(key.get("cash"))
                .isEqualTo(new Expected("4.0%", FigureStatus.BREACH, "n/a"));
        assertThat(key.get("single_corporate_issuer").status()).isEqualTo(FigureStatus.AT_LIMIT);
        assertThat(key.get("aggregate_non_ig_exposure").value()).isEqualTo("15.0%");
        assertThat(key.get("portfolio_dv01").value()).isEqualTo("SGD 38,790 / bp");
    }
}
