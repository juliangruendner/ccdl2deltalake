package de.medizininformatik.ccdl2deltalake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class SqlWriter {

    private static final Path OUTPUT_DIR = Path.of("target/sql");

    static String write(String testName, String sql) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            Files.writeString(OUTPUT_DIR.resolve(testName + ".sql"), sql);
        } catch (IOException e) {
            System.err.println("Could not write SQL file: " + e.getMessage());
        }
        return sql;
    }
}
