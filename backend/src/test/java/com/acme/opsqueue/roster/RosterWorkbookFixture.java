package com.acme.opsqueue.roster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class RosterWorkbookFixture {
    private RosterWorkbookFixture() {
    }

    static byte[] workbook(List<String[]> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("值班表");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("值班日期");
            header.createCell(1).setCellValue("二线管理员账号");
            header.createCell(2).setCellValue("三线管理员账号");
            for (int index = 0; index < rows.size(); index++) {
                var row = sheet.createRow(index + 1);
                String[] values = rows.get(index);
                for (int column = 0; column < values.length; column++) {
                    row.createCell(column).setCellValue(values[column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
