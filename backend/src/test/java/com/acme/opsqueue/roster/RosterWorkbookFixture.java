package com.acme.opsqueue.roster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.time.LocalDate;
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

    static byte[] workbookWithNumericDate(LocalDate date) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("值班表");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("值班日期");
            header.createCell(1).setCellValue("二线管理员账号");
            header.createCell(2).setCellValue("三线管理员账号");
            var row = sheet.createRow(1);
            var dateCell = row.createCell(0);
            dateCell.setCellValue(date);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            dateCell.setCellStyle(style);
            row.createCell(1).setCellValue("ops1");
            row.createCell(2).setCellValue("ops2");
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] headerOnlyOrExtraColumn(boolean extraColumn) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var header = workbook.createSheet("值班表").createRow(0);
            header.createCell(0).setCellValue("值班日期");
            header.createCell(1).setCellValue("二线管理员账号");
            header.createCell(2).setCellValue("三线管理员账号");
            if (extraColumn) header.createCell(3).setCellValue("多余列");
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
}
