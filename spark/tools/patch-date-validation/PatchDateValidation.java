package com.ssafy.thispatch.spark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Applies the publication-date policy to saved Steam announcement inputs. */
public final class PatchDateValidation {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: input.tsv new-output.tsv");
        try (BufferedReader input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             BufferedWriter output = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE_NEW)) {
            output.write("appid\tgid\tpublished_at\tpatch_date\n");
            int count = 0;
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 7) throw new IllegalArgumentException("Expected 7 fields at row " + (count + 1));
                Instant publishedAt = Instant.ofEpochSecond(Long.parseLong(fields[2]));
                output.write(String.join("\t", fields[0], fields[1], publishedAt.toString(),
                        PatchDateResolver.resolve(publishedAt).toString()));
                output.newLine();
                count++;
            }
            System.out.println("Complete: " + count + " notices; " + PatchDateResolver.RULE_VERSION);
        }
    }
}
