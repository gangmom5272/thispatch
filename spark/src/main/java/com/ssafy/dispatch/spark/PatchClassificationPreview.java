package com.ssafy.dispatch.spark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/** Local audit adapter. Optional sixth TSV field is the base64-encoded source game name. */
public final class PatchClassificationPreview {
    private PatchClassificationPreview() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: PatchClassificationPreview <input.tsv> <new-output.tsv>");
        }
        try (BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8,
                     java.nio.file.StandardOpenOption.CREATE_NEW)) {
            output.write("appid\tgid\tdecision\tscope\tstage\treason\tevidence_base64\n");
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 5 && fields.length != 6) {
                    throw new IllegalArgumentException("Expected five or six TSV fields");
                }
                PatchClassifier.Result result = PatchClassifier.classify(fields.length == 6 ? decode(fields[5]) : null,
                        decode(fields[2]), decode(fields[3]),
                        Arrays.asList(decode(fields[4]).split(",")));
                String evidence = Base64.getEncoder().encodeToString(result.evidence().getBytes(StandardCharsets.UTF_8));
                output.write(String.join("\t", fields[0], fields[1], result.decision().name(), result.scope().name(),
                        Integer.toString(result.stage()), result.reason(), evidence));
                output.newLine();
            }
        }
    }

    private static String decode(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
