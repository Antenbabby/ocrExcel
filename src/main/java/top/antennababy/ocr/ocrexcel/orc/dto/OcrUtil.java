package top.antennababy.ocr.ocrexcel.orc.dto;

import cn.hutool.core.io.FileUtil;
import lombok.SneakyThrows;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class OcrUtil {
    @SneakyThrows
    public static OcrRes getOcrResult(byte[] fileBytes) {
        String exePath =System.getProperty("user.dir")+ "/PaddleOCR-json_v1.4.1/PaddleOCR-json.exe"; // paddleocr_json 的可执行文件所在路径
        if (!FileUtil.exist(exePath)) {
            exePath=System.getProperty("user.dir")+ "/ocrExcelUtil/PaddleOCR-json_v1.4.1/PaddleOCR-json.exe";
        }
        try (Ocr ocr = new Ocr(new File(exePath), Map.of("ensure_ascii", false))) {
            return ocr.runOcrOnImgBytes(fileBytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

}
