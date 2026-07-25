package com.acme.opsqueue.roster;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

@Component
public class RosterExcelParser {
    static final List<String> REQUIRED_HEADERS = List.of("值班日期", "二线管理员账号", "三线管理员账号");

    ParsedWorkbook parse(byte[] bytes) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                return ParsedWorkbook.invalid("Excel 文件没有工作表");
            }
            var sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(0);
            for (int column = 0; column < REQUIRED_HEADERS.size(); column++) {
                String actual = value(header, column, formatter);
                if (!REQUIRED_HEADERS.get(column).equals(actual)) {
                    return ParsedWorkbook.invalid("Excel 模板表头必须为：值班日期、二线管理员账号、三线管理员账号");
                }
            }
            List<ParsedRow> rows = new ArrayList<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter)) {
                    continue;
                }
                rows.add(new ParsedRow(index + 1,
                        value(row, 0, formatter), value(row, 1, formatter), value(row, 2, formatter)));
            }
            return new ParsedWorkbook(rows, List.of());
        } catch (IOException | RuntimeException exception) {
            return ParsedWorkbook.invalid("无法读取 .xlsx 值班表文件");
        }
    }

    private boolean isBlank(Row row, DataFormatter formatter) {
        return value(row, 0, formatter).isBlank()
                && value(row, 1, formatter).isBlank()
                && value(row, 2, formatter).isBlank();
    }

    private String value(Row row, int column, DataFormatter formatter) {
        if (row == null || row.getCell(column) == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(column)).trim();
    }

    record ParsedWorkbook(List<ParsedRow> rows, List<RosterImportError> errors) {
        static ParsedWorkbook invalid(String message) {
            return new ParsedWorkbook(List.of(), List.of(new RosterImportError(1, message)));
        }
    }

    record ParsedRow(int sourceRowNumber, String date, String secondLineUsername, String thirdLineUsername) {
        LocalDate parseDate() {
            try {
                return date.isBlank() ? null : LocalDate.parse(date);
            } catch (DateTimeParseException exception) {
                return null;
            }
        }
    }
}
