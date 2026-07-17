package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

public class ExcelHandler implements DocumentHandler {

    private static final int LARGE_TABLE_THRESHOLD = 50;

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("xlsx", fileName);
        int position = 0;

        try (Workbook wb = WorkbookFactory.create(is)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                result.addElement(new Element(position++, "sheet_header", "[Sheet: " + sheetName + "]"));

                List<List<String>> allRows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> rowData = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        rowData.add(getCellValue(row.getCell(c)));
                    }
                    allRows.add(rowData);
                }

                if (allRows.isEmpty()) continue;

                if (allRows.size() <= LARGE_TABLE_THRESHOLD && !allRows.get(0).isEmpty()
                        && allRows.get(0).size() <= 10) {
                    StringBuilder md = new StringBuilder();
                    for (int r = 0; r < allRows.size(); r++) {
                        md.append("| ").append(String.join(" | ", allRows.get(r))).append(" |\n");
                        if (r == 0) {
                            md.append("| ").append(allRows.get(r).stream()
                                    .map(c -> "---")
                                    .collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
                        }
                    }
                    result.addElement(new Element(position++, "table", md.toString().trim()));
                } else {
                    int headerIdx = findHeaderRowIndex(allRows);
                    List<String> headerRow = allRows.get(headerIdx);

                    // Clean schema: only non-empty cells
                    StringJoiner schema = new StringJoiner(" | ");
                    for (String cell : headerRow) {
                        if (cell != null && !cell.trim().isEmpty()) {
                            schema.add(cell.trim());
                        }
                    }

                    // Preview: header row + first 2 data rows as clean key-value
                    StringBuilder preview = new StringBuilder();
                    preview.append("Columns: ").append(schema).append("\n");
                    int dataStart = headerIdx + 1;
                    int previewEnd = Math.min(dataStart + 2, allRows.size());
                    for (int r = dataStart; r < previewEnd; r++) {
                        preview.append("Row ").append(r - dataStart + 1).append(": ");
                        StringJoiner rowStr = new StringJoiner(", ");
                        for (String cell : allRows.get(r)) {
                            if (cell != null && !cell.trim().isEmpty()) {
                                rowStr.add(cell.trim());
                            }
                        }
                        preview.append(rowStr).append("\n");
                    }

                    LargeTableInfo lti = new LargeTableInfo(
                            sheetName, position++, schema.toString(),
                            preview.toString().trim(), allRows.size(), allRows
                    );
                    result.addLargeTable(lti);
                }
            }

            // Extract images
            extractImages(wb, result, position);

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse excel: " + fileName, e);
        }

        return result;
    }

    private void extractImages(Workbook wb, ExtractionResult result, int startPosition) {
        try {
            List<? extends PictureData> pictures = wb.getAllPictures();
            int pos = startPosition;
            for (PictureData pic : pictures) {
                String format = switch (pic.getMimeType()) {
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpg";
                    case "image/gif" -> "gif";
                    case "image/bmp" -> "bmp";
                    default -> "png";
                };
                String imgName = "excel_image_" + pos + "." + format;
                result.addImage(new ImageFile(imgName, pos, pic.getData(), format));
                pos++;
            }
        } catch (Exception e) {
            // silently skip
        }
    }

    private int findHeaderRowIndex(List<List<String>> allRows) {
        for (int i = 0; i < allRows.size(); i++) {
            long nonEmpty = allRows.get(i).stream()
                    .filter(c -> c != null && !c.trim().isEmpty())
                    .count();
            if (nonEmpty >= 2) return i;
        }
        return 0;
    }

    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) yield String.valueOf((long) v);
                yield String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield String.valueOf(cell.getNumericCellValue()); }
                catch (Exception e) {
                    try { yield cell.getStringCellValue(); }
                    catch (Exception e2) { yield cell.getCellFormula(); }
                }
            }
            default -> "";
        };
    }
}
