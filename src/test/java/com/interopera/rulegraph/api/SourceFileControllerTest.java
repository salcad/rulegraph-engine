package com.interopera.rulegraph.api;

import com.interopera.rulegraph.config.RuleGraphProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the holdings perturbation endpoints used by the reconciliation demo: a valid edit is written,
 * an invalid one is rejected with a readable 400, and restore puts the original back from the snapshot.
 */
@WebMvcTest(SourceFileController.class)
class SourceFileControllerTest {

    private static final String HEADER =
            "instrument_id,instrument_name,asset_class,issuer_name,issuer_type,parent_issuer," +
            "credit_rating,downgraded_from,market_value_sgd,modified_duration\n";
    private static final String ORIGINAL = HEADER +
            "SG001,SGS Bond,government_bond,SG Govt,sovereign,,AAA,,1000000,5.2\n";

    @TempDir
    static Path docs;

    @TestConfiguration
    static class Config {
        @Bean
        RuleGraphProperties props() {
            return new RuleGraphProperties(docs.toString(), "guidelines.pdf", "sample_holdings.csv",
                    null, null);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private Path holdings;
    private Path snapshot;

    @BeforeEach
    void resetFile() throws Exception {
        holdings = docs.resolve("sample_holdings.csv");
        snapshot = docs.resolve("sample_holdings.csv.orig");
        Files.writeString(holdings, ORIGINAL, StandardCharsets.UTF_8);
        Files.deleteIfExists(snapshot);
    }

    @Test
    void validEditIsWrittenAndSnapshotsOriginal() throws Exception {
        String edited = HEADER +
                "SG001,SGS Bond,government_bond,SG Govt,sovereign,,AAA,,2000000,5.2\n";

        mockMvc.perform(put("/rulegraph-api/source/holdings")
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .content(edited))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"saved":true}
                        """));

        assertThat(Files.readString(holdings)).isEqualTo(edited);
        assertThat(Files.readString(snapshot)).isEqualTo(ORIGINAL);
    }

    @Test
    void missingColumnIsRejected() throws Exception {
        String bad = "instrument_id,market_value_sgd\nSG001,1000000\n";

        mockMvc.perform(put("/rulegraph-api/source/holdings")
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .content(bad))
                .andExpect(status().isBadRequest());

        // The file is untouched when validation fails.
        assertThat(Files.readString(holdings)).isEqualTo(ORIGINAL);
    }

    @Test
    void nonNumericMarketValueIsRejected() throws Exception {
        String bad = HEADER +
                "SG001,SGS Bond,government_bond,SG Govt,sovereign,,AAA,,not-a-number,5.2\n";

        mockMvc.perform(put("/rulegraph-api/source/holdings")
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .content(bad))
                .andExpect(status().isBadRequest());

        assertThat(Files.readString(holdings)).isEqualTo(ORIGINAL);
    }

    @Test
    void restorePutsTheOriginalBack() throws Exception {
        String edited = HEADER +
                "SG001,SGS Bond,government_bond,SG Govt,sovereign,,AAA,,2000000,5.2\n";

        mockMvc.perform(put("/rulegraph-api/source/holdings")
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .content(edited))
                .andExpect(status().isOk());
        assertThat(Files.readString(holdings)).isEqualTo(edited);

        mockMvc.perform(post("/rulegraph-api/source/holdings/restore"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"restored":true}
                        """));

        assertThat(Files.readString(holdings)).isEqualTo(ORIGINAL);
    }

    @Test
    void restoreWithoutAnEditIsANoOp() throws Exception {
        mockMvc.perform(post("/rulegraph-api/source/holdings/restore"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"restored":false}
                        """));
    }
}
