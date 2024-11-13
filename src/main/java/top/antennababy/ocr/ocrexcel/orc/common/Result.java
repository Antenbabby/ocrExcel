package top.antennababy.ocr.ocrexcel.orc.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class Result<T> {
    String code;
    String message;
    String trackId;
    T data;
    public static <T> Result<T> success(T data){
        return new Result<T>().setCode("200").setMessage("success").setData(data);
    }
    public static <T> Result<T> fail(String code,String message){
        return new Result<T>().setCode("200").setMessage("success");
    }
}
