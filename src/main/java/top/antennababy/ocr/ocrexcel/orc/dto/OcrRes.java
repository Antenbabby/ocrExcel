package top.antennababy.ocr.ocrexcel.orc.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class OcrRes {

    /**
     * code
     */
    private Integer code;
    /**
     * data
     */
    private List<DataDTO> data;

    /**
     * DataDTO
     */
    @NoArgsConstructor
    @Data
    public static class DataDTO {
        /**
         * box
         */
        private List<List<Integer>> box;
        /**
         * score
         */
        private Double score;
        /**
         * text
         */
        private String text;
    }
}
