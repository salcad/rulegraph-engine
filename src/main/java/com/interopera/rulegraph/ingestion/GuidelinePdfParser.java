package com.interopera.rulegraph.ingestion;

import com.interopera.rulegraph.common.Hashing;
import com.interopera.rulegraph.config.RuleGraphProperties;
import com.interopera.rulegraph.domain.GuidelineChunk;
import com.interopera.rulegraph.domain.Provenance;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the guidelines PDF into page-anchored {@link GuidelineChunk}s using Apache PDFBox.
 *
 * <p>Chunking is deterministic: text is taken per page, split into paragraph-sized chunks on blank
 * lines, and each chunk gets a stable {@code chunk_xxxx} id derived from its page and content. The
 * same PDF always yields the same chunk ids, so citations are reproducible across runs (constraint 1).
 *
 * <p>This parser only locates and labels source text; it does not interpret rules. Turning a chunk
 * into a rule is the job of the extraction layer.
 */
@Component
public class GuidelinePdfParser {

    private final RuleGraphProperties props;

    public GuidelinePdfParser(RuleGraphProperties props) {
        this.props = props;
    }

    public List<GuidelineChunk> parse() {
        File file = new File(props.guidelinesPdfPath());
        List<GuidelineChunk> chunks = new ArrayList<>();
        Instant now = Instant.now();
        try (PDDocument doc = Loader.loadPDF(file)) {
            int pageCount = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(doc);
                for (String passage : splitIntoPassages(pageText)) {
                    String chunkId = Hashing.chunkId(page, passage);
                    Provenance prov = new Provenance(
                            props.guidelinesPdf(), page, chunkId, now, 1.0);
                    chunks.add(new GuidelineChunk(
                            chunkId, page, passage, summarize(passage), prov));
                }
            }
        } catch (IOException e) {
            throw new IngestionException("Failed to read guidelines PDF: " + file, e);
        }
        return chunks;
    }

    /**
     * Split page text into passage-level chunks. The guidelines are mostly table rows and short
     * clauses, so we chunk per line (collapsing internal whitespace) and drop trivially short
     * fragments. Line-level chunking gives each rule a tight, specific citation rather than a
     * coarse page-level one — which is what makes the {@code figure -> source} trace precise.
     */
    private List<String> splitIntoPassages(String pageText) {
        List<String> passages = new ArrayList<>();
        for (String line : pageText.split("\\R")) {
            String trimmed = line.strip().replaceAll("[ \\t]+", " ");
            if (trimmed.length() >= 20) {
                passages.add(trimmed);
            }
        }
        return passages;
    }

    /** First line (or first ~80 chars) of a chunk, used as the citation's passage summary. */
    private String summarize(String paragraph) {
        String firstLine = paragraph.split("\\R", 2)[0].strip();
        return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 77) + "...";
    }
}
