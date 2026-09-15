package com.ssafy.thispatch.spark;

import com.ssafy.thispatch.common.TimeRule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** Runs the actual frozen Java rules. Counterfactuals are diagnostics, never production predictions. */
public final class PatchDateValidation {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: input.tsv new-output.tsv");
        try (BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE_NEW)) {
            output.write("appid\tgid\tdecision\tscope\tclassifier_reason\tstatus\tdate_reason\tpatch_date\tapplied_at\tsource\tevidence_b64"
                    + "\tdiagnostic_kst_status\tdiagnostic_kst_date\tdiagnostic_forced_status\tdiagnostic_forced_kst_status"
                    + "\tdiagnostic_forced_kst_reason\tdiagnostic_evidence_b64\n");
            int count = 0;
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 7) throw new IllegalArgumentException("Expected 7 fields at row " + (count + 1));
                Instant publishedAt = Instant.ofEpochSecond(Long.parseLong(fields[2]));
                String title = decode(fields[3]);
                String body = decode(fields[4]);
                var classification = PatchClassifier.classify(decode(fields[6]), title, body,
                        Arrays.asList(decode(fields[5]).split(",")));
                var result = PatchDateResolver.resolve(classification, title, body, publishedAt, null);
                var assumedKst = PatchDateResolver.resolve(classification, title, body, publishedAt, TimeRule.ZONE);
                var forcedClassification = new PatchClassifier.Result(PatchClassifier.Decision.PATCH,
                        PatchClassifier.Scope.DEFAULT, 0, "DIAGNOSTIC_ONLY", "");
                var forced = PatchDateResolver.resolve(forcedClassification, title, body, publishedAt, null);
                var forcedKst = PatchDateResolver.resolve(forcedClassification, title, body, publishedAt, TimeRule.ZONE);
                output.write(String.join("\t", fields[0], fields[1], classification.decision().name(),
                        classification.scope().name(), classification.reason(), result.status().name(), result.reason(),
                        value(result.patchDate()), value(result.appliedAt()), result.source().name(), encode(result.evidence()),
                        assumedKst.status().name(), value(assumedKst.patchDate()), forced.status().name(),
                        forcedKst.status().name(), forcedKst.reason(), encode(forcedKst.evidence())));
                output.newLine();
                if (++count % 2000 == 0) {
                    output.flush();
                    System.out.println("Processed " + count + " real notices");
                }
            }
            System.out.println("Complete: " + count + " notices; " + PatchClassifier.RULE_VERSION + "/" + PatchDateResolver.RULE_VERSION);
        }
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static String decode(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
    private static String encode(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
}
