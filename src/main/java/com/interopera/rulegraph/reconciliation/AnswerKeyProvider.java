package com.interopera.rulegraph.reconciliation;

import com.interopera.rulegraph.config.RuleGraphProperties;
import com.interopera.rulegraph.reconciliation.ExpectedFigures.Expected;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Supplies the expected figures for a firm. Firm A is read from the provided
 * {@code firm_A_answer_key.xlsx}. Firm B's answer key is the markdown brief rather than a spreadsheet,
 * so its three differences (and the basis-point utilisation styling) are taken from the transcribed
 * {@link ExpectedFigures}. If the Firm A spreadsheet is missing, the transcription is used as a
 * fallback so the pipeline still runs.
 */
@Component
public class AnswerKeyProvider {

    private final RuleGraphProperties props;
    private final AnswerKeyXlsxReader xlsxReader = new AnswerKeyXlsxReader();

    public AnswerKeyProvider(RuleGraphProperties props) {
        this.props = props;
    }

    public Map<String, Expected> forFirm(String firmId) {
        if ("firm_A".equals(firmId)) {
            Path xlsx = Path.of(props.sampleDocsPath(), "firm_A_answer_key.xlsx");
            if (Files.exists(xlsx)) {
                return xlsxReader.read(xlsx.toString());
            }
        }
        return ExpectedFigures.forFirm(firmId);
    }
}
