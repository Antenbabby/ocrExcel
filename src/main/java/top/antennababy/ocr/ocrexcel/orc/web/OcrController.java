package top.antennababy.ocr.ocrexcel.orc.web;

import cn.hutool.core.annotation.AnnotationAttributeValueProvider;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.antennababy.ocr.ocrexcel.orc.common.Result;
import top.antennababy.ocr.ocrexcel.orc.dto.OcrRes;
import top.antennababy.ocr.ocrexcel.orc.dto.OcrUtil;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@RequestMapping("/orc")
@RestController
@Slf4j
public class OcrController {
    public static final String workPath=System.getProperty("user.dir");

    @RequestMapping("/ocrText")
    @SneakyThrows
    public Result<OcrRes> ocrText(@RequestParam(value = "file") MultipartFile file) {
        return Result.success(OcrUtil.getOcrResult(file.getBytes()));
    }

    /**
     * 要求左上角第一个单元格有数据,且设置左对齐,否则可能会识别效果不佳
     * @param file
     */
    @PostMapping("/ocrExcel")
    @SneakyThrows
    public Result<String> ocrExcel(@RequestParam(value = "file") MultipartFile file, HttpServletResponse response) {
        TimeInterval timer = new TimeInterval();
        OcrRes ocrResult = OcrUtil.getOcrResult(file.getBytes());
        long t1 = timer.interval();
        timer.restart();
        Assert.notNull(ocrResult,"识别失败");
        Assert.notEmpty(ocrResult.getData(),"转换失败,识别后内容为空");
        if (ocrResult.getCode()==100) {
            //计算行高平均值,用作判定是否换行.如果大于1/3行高即换行
            double[] doubles = ocrResult.getData().stream().map(x -> (double) x.getBox().get(2).get(1) - x.getBox().get(0).get(1)).mapToDouble(x ->x).toArray();
            double averageY = StatUtils.mean(doubles);

            Comparator<OcrRes.DataDTO> comparator = (x, y) -> {
                Integer y1 = x.getBox().get(0).get(1);
                Integer y2 = y.getBox().get(0).get(1);
                return Math.abs(y1 - y2) < averageY/3 ? 0 : y1 - y2;
            };
            Comparator<OcrRes.DataDTO> comparator1=comparator.thenComparing((x,y)->{
                Integer x1 = x.getBox().get(0).get(0);
                Integer x2 = y.getBox().get(0).get(0);
                return x1-x2;
            });
            ocrResult.setData(ocrResult.getData().stream().sorted(comparator1).collect(Collectors.toList()));

            List<List<OcrRes.DataDTO>> rows =new ArrayList<>();
            List<OcrRes.DataDTO> row=CollUtil.newArrayList();
            Integer lastY=0;
            Integer lastX=0;
            for (OcrRes.DataDTO x : ocrResult.getData()) {
                if (lastY==0) {
                    lastY=x.getBox().get(0).get(1);
                    lastX=x.getBox().get(0).get(0);
                }
                //判断是否换行.
                Integer currY = x.getBox().get(0).get(1);
                Integer currX = x.getBox().get(0).get(0);
                if (currX-lastX<0||currY-lastY>averageY/3) {
                    rows.add(new ArrayList<>(row));
                    row=CollUtil.newArrayList();
                    lastY=currY;
                }
                lastX=currX;
                row.add(x);
            }
            rows.add(new ArrayList<>(row));
            //列对齐
            List<OcrRes.DataDTO> dataDTOS = getStandRow(rows);
            List<List<String>> rowStrs =new ArrayList<>();
            for (List<OcrRes.DataDTO> r : rows) {
                List<String> rowStr=CollUtil.newArrayList();
                for (int i = 0; i < dataDTOS.size(); i++) {
                    int finalI = i;
                    Predicate<OcrRes.DataDTO>  include= x->{
                        Integer i1 = x.getBox().get(0).get(0);
                        Integer i2 = x.getBox().get(1).get(0);
                        Integer s1 = dataDTOS.get(finalI).getBox().get(0).get(0);
                        Integer s2 = dataDTOS.get(finalI).getBox().get(1).get(0);
                        return (i1>=s1&&i1<=s2)||(i2>=s1&&i2<=s2)||(s1>=i1&&s1<=i2)||(s2>=i1&&s2<=i2);
                    };
                    r.stream().filter(include).findFirst().ifPresentOrElse(x->rowStr.add(x.getText()),()->rowStr.add(""));
                }
                rowStrs.add(rowStr);
            }

            String tempFile=workPath+"/temp.xlsx";
            //通过工具类创建writer
            ExcelWriter writer = ExcelUtil.getWriter(tempFile);
            //一次性写出内容，强制输出标题
            writer.write(rowStrs, true);
            //关闭writer，释放内存
            writer.close();
            long t2 = timer.interval();
            log.info("ocr耗时:{}ms,excel转换耗时:{}ms",t1,t2);
            return Result.success(tempFile);
        }
        return Result.fail("501","识别失败");
    }

    private static List<OcrRes.DataDTO> getStandRow(List<List<OcrRes.DataDTO>> rows) {
        ArrayList<Map.Entry<Integer, List<List<OcrRes.DataDTO>>>> rowsGroup = new ArrayList<>(rows.stream().collect(Collectors.groupingBy(List::size)).entrySet());
        rowsGroup.sort(((x,y)->y.getKey()-x.getKey()));
        List<List<OcrRes.DataDTO>> rowList = rowsGroup.get(0).getValue();
        rowList.sort((x,y)->{
            Integer x1 = CollUtil.getFirst(x).getBox().get(0).get(0);
            Integer x2 = CollUtil.getLast(x).getBox().get(1).get(0);
            Integer y1 = CollUtil.getFirst(y).getBox().get(0).get(0);
            Integer y2 = CollUtil.getLast(y).getBox().get(1).get(0);
            return y2-y1-(x2-x1);
        });
        return rowList.get(0);
    }

    @GetMapping("/download")
    @SneakyThrows
    public void download(HttpServletResponse response) {
        String tempFile=workPath+"/temp.xlsx";
        // 读到流中
        InputStream inputStream = FileUtil.getInputStream(tempFile);// 文件的存放路径
        response.reset();
        response.setContentType("application/octet-stream");
        String filename = new File(tempFile).getName();
        response.addHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        ServletOutputStream outputStream = response.getOutputStream();
        IoUtil.copy(inputStream, outputStream);
        IoUtil.closeIfPosible(inputStream);
        IoUtil.closeIfPosible(outputStream);
        FileUtil.del(tempFile);
    }
}
