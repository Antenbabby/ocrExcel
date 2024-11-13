package top.antennababy.ocr.ocrexcel;

import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.system.SystemUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.awt.*;
import java.net.URI;

@SpringBootApplication
@Slf4j
public class OcrExcelApplication {

    @SneakyThrows
    public static void main(String[] args) {
        SpringApplication.run(OcrExcelApplication.class, args);
        log.info("启动完成^-^");
        RuntimeUtil.exec("cmd /c start http://127.0.0.1:19999/static/a.html");
    }


}
