package com.acme.opsqueue.roster;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class RosterExcelParser {
    static final List<String> REQUIRED_HEADERS = List.of("值班日期", "二线管理员账号", "三线管理员账号");
    private static final int MAX_COMPRESSED_BYTES = 1_000_000;
    private static final int MAX_ZIP_ENTRIES = 100;
    private static final long MAX_UNCOMPRESSED_BYTES = 4_000_000L;
    private static final long MAX_ENTRY_BYTES = 4_000_000L;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_STRING_LENGTH = 512;
    private static final LocalDate MYSQL_MIN_DATE = LocalDate.of(1000, 1, 1);
    private static final LocalDate MYSQL_MAX_DATE = LocalDate.of(9999, 12, 31);

    ParsedWorkbook parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_COMPRESSED_BYTES) {
            return ParsedWorkbook.invalid("上传文件超过安全限制");
        }
        try {
            checkZipBudget(bytes);
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                if (workbook.getNumberOfSheets() != 1) return ParsedWorkbook.invalid("Excel 文件必须且只能包含一个工作表");
                var sheet = workbook.getSheetAt(0);
                if (sheet.getLastRowNum() > MAX_ROWS) return ParsedWorkbook.invalid("Excel 行数超过安全限制");
                Row header = sheet.getRow(0);
                if (header == null || header.getLastCellNum() != 3) return ParsedWorkbook.invalid("Excel 模板必须恰好包含三列表头");
                for (int column = 0; column < 3; column++) {
                    Cell cell = header.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null || cell.getCellType() != CellType.STRING || !REQUIRED_HEADERS.get(column).equals(cell.getStringCellValue())) {
                        return ParsedWorkbook.invalid("Excel 模板表头必须为：值班日期、二线管理员账号、三线管理员账号");
                    }
                }
                List<ParsedRow> rows = new ArrayList<>();
                List<RosterImportError> errors = new ArrayList<>();
                Set<String> coveredDates = new TreeSet<>();
                int observedRows = 0;
                for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                    Row row = sheet.getRow(index);
                    if (row == null) continue;
                    if (blank(row) && row.getLastCellNum() <= 3) continue;
                    observedRows++;
                    LocalDate date = date(row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                    if (date != null) coveredDates.add(date.toString());
                    if (row.getLastCellNum() > 3) { errors.add(new RosterImportError(index + 1, "数据行不能包含第四列")); continue; }
                    if (hasFormula(row)) { errors.add(new RosterImportError(index + 1, "不支持公式单元格")); continue; }
                    String second = text(row, 1);
                    String third = text(row, 2);
                    if (second == null || third == null) { errors.add(new RosterImportError(index + 1, "管理员账号格式无效")); continue; }
                    rows.add(new ParsedRow(index + 1, date, second, third));
                }
                if (rows.isEmpty() && errors.isEmpty()) return ParsedWorkbook.invalid("Excel 文件至少需要一条非空数据");
                return new ParsedWorkbook(rows, errors, observedRows, String.join(",", coveredDates));
            }
        } catch (LimitException exception) {
            return ParsedWorkbook.invalid("上传文件超过安全限制");
        } catch (IOException | RuntimeException exception) {
            return ParsedWorkbook.invalid("无法读取 .xlsx 值班表文件");
        }
    }

    private void checkZipBudget(byte[] bytes) throws IOException, LimitException {
        int entries = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new LimitException();
                long entryTotal = 0;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryTotal += read;
                    total += read;
                    if (entryTotal > MAX_ENTRY_BYTES || total > MAX_UNCOMPRESSED_BYTES) throw new LimitException();
                }
            }
        }
    }

    private boolean blank(Row row) { return dateBlank(row.getCell(0)) && textBlank(row.getCell(1)) && textBlank(row.getCell(2)); }
    private boolean dateBlank(Cell cell) { return cell == null || cell.getCellType() == CellType.BLANK || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()); }
    private boolean textBlank(Cell cell) { return cell == null || cell.getCellType() == CellType.BLANK || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()); }
    private boolean hasFormula(Row row) { for (int i = 0; i < 3; i++) { Cell cell = row.getCell(i); if (cell != null && cell.getCellType() == CellType.FORMULA) return true; } return false; }
    private String text(Row row, int column) {
        Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell != null && cell.getCellType() == CellType.STRING && cell.getStringCellValue().length() <= MAX_STRING_LENGTH ? cell.getStringCellValue().trim() : null;
    }
    private LocalDate date(Cell cell) {
        try {
            if (cell == null) return null;
            LocalDate parsed = cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate()
                    : cell.getCellType() == CellType.STRING ? LocalDate.parse(cell.getStringCellValue()) : null;
            return parsed != null && !parsed.isBefore(MYSQL_MIN_DATE) && !parsed.isAfter(MYSQL_MAX_DATE) ? parsed : null;
        } catch (RuntimeException exception) { return null; }
    }

    record ParsedWorkbook(List<ParsedRow> rows, List<RosterImportError> errors, int observedRowCount, String coveredDates) {
        static ParsedWorkbook invalid(String message) {
            return new ParsedWorkbook(List.of(), List.of(new RosterImportError(1, message)), 0, "");
        }
    }
    record ParsedRow(int sourceRowNumber, LocalDate date, String secondLineUsername, String thirdLineUsername) { LocalDate parseDate() { return date; } }
    private static class LimitException extends Exception { }
}
