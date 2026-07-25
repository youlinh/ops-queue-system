package com.acme.opsqueue.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class RosterExcelParserTest {
    private final RosterExcelParser parser = new RosterExcelParser();

    @Test
    void rejectsCompressedInputAboveTheParserBudget() {
        assertThat(parser.parse(new byte[1_000_001]).errors())
                .containsExactly(new RosterImportError(1, "上传文件超过安全限制"));
    }

    @Test
    void rejectsCompressedBombAndTooManyZipEntriesBeforeWorkbookObjectModelIsBuilt() {
        assertThat(parser.parse(zip("xl/sharedStrings.xml", "x".repeat(4_100_000))).errors())
                .containsExactly(new RosterImportError(1, "上传文件超过安全限制"));
        assertThat(parser.parse(zipEntries(101)).errors())
                .containsExactly(new RosterImportError(1, "上传文件超过安全限制"));
    }

    @Test
    void rejectsZipWhoseTotalUncompressedSizeExceedsBudget() {
        assertThat(parser.parse(zipEntriesWithBody(5, "x".repeat(900_000))).errors())
                .containsExactly(new RosterImportError(1, "上传文件超过安全限制"));
    }

    @Test
    void rejectsStructuralWorkbookVariants() {
        assertThat(parser.parse(workbook(book -> book.createSheet("extra"))).errors()).isNotEmpty();
        assertThat(parser.parse(workbook(book -> book.getSheetAt(0).getRow(0).getCell(0).setCellValue(" 值班日期"))).errors()).isNotEmpty();
        assertThat(parser.parse(workbook(book -> { })).errors()).isNotEmpty();
        assertThat(parser.parse(workbook(book -> book.getSheetAt(0).createRow(1).createCell(3).setCellValue("unexpected"))).errors())
                .contains(new RosterImportError(2, "数据行不能包含第四列"));
    }

    @Test
    void rejectsFormulaAndOversizedStringCells() {
        assertThat(parser.parse(workbook(book -> {
            var row = book.getSheetAt(0).createRow(1);
            row.createCell(0).setCellFormula("TODAY()");
            row.createCell(1).setCellValue("ops1");
            row.createCell(2).setCellValue("ops2");
        })).errors()).contains(new RosterImportError(2, "不支持公式单元格"));
        assertThat(parser.parse(workbook(book -> addRow(book, 1, "2026-07-25", "x".repeat(513), "ops2"))).errors()).isNotEmpty();
    }

    @Test
    void skipsRealBlankAndSparseRowsWhileKeepingObservedRowsAndDates() {
        var parsed = parser.parse(workbook(book -> {
            var blank = book.getSheetAt(0).createRow(1);
            blank.createCell(0, CellType.BLANK);
            blank.createCell(1, CellType.BLANK);
            blank.createCell(2, CellType.BLANK);
            addRow(book, 3, "2026-07-25", "ops1", "ops2");
        }));

        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.observedRowCount()).isEqualTo(1);
        assertThat(parsed.coveredDates()).isEqualTo("2026-07-25");
    }

    @Test
    void acceptsTheMySqlUpperDateLimitAndRejectsValuesOutsideTheSupportedRange() {
        var upper = parser.parse(workbook(book -> addRow(book, 1, "9999-12-31", "ops1", "ops2")));
        assertThat(upper.errors()).isEmpty();
        assertThat(upper.rows().getFirst().date()).isEqualTo(LocalDate.of(9999, 12, 31));
        assertThat(parser.parse(workbook(book -> addRow(book, 1, "0001-01-01", "ops1", "ops2"))).rows())
                .first().extracting(RosterExcelParser.ParsedRow::date).isNull();
    }

    @Test
    void acceptsExactlyTenThousandDataRowsAndRejectsTheNextRow() {
        var accepted = parser.parse(workbook(book -> {
            for (int index = 1; index <= 10_000; index++) addRow(book, index, "2026-07-25", "ops1", "ops2");
        }));
        assertThat(accepted.rows()).hasSize(10_000);
        assertThat(parser.parse(workbook(book -> book.getSheetAt(0).createRow(10_001))).errors()).isNotEmpty();
    }

    private byte[] workbook(java.util.function.Consumer<XSSFWorkbook> mutate) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var header = book.createSheet("值班表").createRow(0);
            header.createCell(0).setCellValue("值班日期");
            header.createCell(1).setCellValue("二线管理员账号");
            header.createCell(2).setCellValue("三线管理员账号");
            mutate.accept(book);
            book.write(out);
            return out.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private void addRow(XSSFWorkbook book, int index, String date, String second, String third) {
        var row = book.getSheetAt(0).createRow(index);
        row.createCell(0).setCellValue(date);
        row.createCell(1).setCellValue(second);
        row.createCell(2).setCellValue(third);
    }

    private byte[] zip(String name, String body) { return zipEntriesWithNames(new String[] {name}, body); }
    private byte[] zipEntriesWithBody(int count, String body) {
        String[] names = new String[count];
        for (int index = 0; index < count; index++) names[index] = "entry-" + index;
        return zipEntriesWithNames(names, body);
    }
    private byte[] zipEntriesWithNames(String[] names, String body) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(body.getBytes()); zip.closeEntry(); }
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private byte[] zipEntries(int count) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < count; index++) { zip.putNextEntry(new ZipEntry("entry-" + index)); zip.write(1); zip.closeEntry(); }
            return output.toByteArray();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
