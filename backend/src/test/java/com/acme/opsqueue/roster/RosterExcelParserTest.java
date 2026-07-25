package com.acme.opsqueue.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class RosterExcelParserTest {
    private final RosterExcelParser parser = new RosterExcelParser();

    @Test
    void rejectsCompressedBombBeforeWorkbookObjectModelIsBuilt() {
        assertThat(parser.parse(zip("xl/sharedStrings.xml", "x".repeat(1_100_000))).errors())
                .containsExactly(new RosterImportError(1, "上传文件超过安全限制"));
    }

    @Test
    void rejectsTooManyZipEntriesBeforeWorkbookObjectModelIsBuilt() {
        assertThat(parser.parse(zipEntries(101)).errors())
                .containsExactly(new RosterImportError(1, "上传文件超过安全限制"));
    }

    private byte[] zip(String name, String body) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(body.getBytes()); zip.closeEntry(); return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private byte[] zipEntries(int count) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < count; index++) { zip.putNextEntry(new ZipEntry("entry-" + index)); zip.write(1); zip.closeEntry(); }
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
