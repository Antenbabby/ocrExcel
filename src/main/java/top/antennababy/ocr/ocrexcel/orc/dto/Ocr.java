package top.antennababy.ocr.ocrexcel.orc.dto;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

enum OcrMode {
    LOCAL_PROCESS,  // 本地进程
    SOCKET_SERVER  // 套接字服务器
}

class OcrCode {
    public static final int OK = 100;
    public static final int NO_TEXT = 101;
}

class OcrEntry {
    String text;
    int[][] box;
    double score;

    @Override
    public String toString() {
        return "RecognizedText{" +
                "text='" + text + '\'' +
                ", box=" + Arrays.toString(box) +
                ", score=" + score +
                '}';
    }
}


public class Ocr implements AutoCloseable {
    boolean ocrReady = false;
    Map<String, Object> arguments;
    BufferedReader reader;
    BufferedWriter writer;
    OcrMode mode;

    // 本地进程模式
    Process process;
    File exePath;


    // 套接字服务器模式
    String serverAddr;
    int serverPort;
    Socket clientSocket;
    boolean isLoopback = false;

    /**
     * 使用套接字模式初始化
     * @param serverAddr
     * @param serverPort
     * @param arguments
     * @throws IOException
     */
    public Ocr(String serverAddr, int serverPort, Map<String, Object> arguments) throws IOException {
        this.mode = OcrMode.SOCKET_SERVER;
        this.arguments = arguments;
        this.serverAddr = serverAddr;
        this.serverPort = serverPort;
        checkIfLoopback();
        initOcr();
    }

    /**
     * 使用本地进程模式初始化
     * @param exePath
     * @param arguments
     * @throws IOException
     */
    public Ocr(File exePath, Map<String, Object> arguments) throws IOException {
        this.mode = OcrMode.LOCAL_PROCESS;
        this.arguments = arguments;
        this.exePath = exePath;
        initOcr();
    }

    private void initOcr() throws IOException {
        List<String> commandList = new ArrayList<>();
        if (arguments != null) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                commandList.add("--" + entry.getKey() + "=" + entry.getValue().toString());
            }
        }

        for (String c: commandList) {
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(c)) {
                throw new IllegalArgumentException("参数不能含有非 ASCII 字符");
            }
        }

        switch (this.mode) {
            case LOCAL_PROCESS: {
                File workingDir = exePath.getParentFile();
                if (isLinux()) {
                    // Linux 下解压后的默认布局是 ../bin/exe，需要再往上一层
                    workingDir = workingDir.getParentFile();
                }
                commandList.add(0, exePath.toString());
                ProcessBuilder pb = new ProcessBuilder(commandList);
                pb.directory(workingDir);
                pb.redirectErrorStream(true);

                if (isLinux()) {
                    // Linux 下启动，需要设置 LD_LIBRARY_PATH，见 https://github.com/hiroi-sora/PaddleOCR-json/blob/main/cpp/README-linux.md
                    File libLocation = new File(workingDir, "lib");
                    pb.environment().put("LD_LIBRARY_PATH", libLocation.getAbsolutePath());
                }

                process = pb.start();

                InputStream stdout = process.getInputStream();
                OutputStream stdin = process.getOutputStream();
                reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));
                String line = "";
                ocrReady = false;
                while (!ocrReady) {
                    line = reader.readLine();
                    if (isLinux() && line.contains("not found (required by")) {
                        System.out.println("可能存在依赖库问题：" + line);
                        break;
                    }
                    if (line.contains("OCR init completed")) {
                        ocrReady = true;
                        break;
                    }
                }
                if (!ocrReady) {
                    System.out.println("初始化OCR失败，请检查输出");
                }
                break;
            }
            case SOCKET_SERVER: {
                clientSocket = new Socket(serverAddr, serverPort);
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
                ocrReady = true;
                System.out.println("已连接到OCR套接字服务器，假设服务器已初始化成功");
                break;
            }
        }


    }

    /**
     * 使用图片路径进行 OCR
     * @param imgFile
     * @return
     * @throws IOException
     */
    public OcrRes runOcr(File imgFile) throws IOException {
        if (mode == OcrMode.SOCKET_SERVER && !isLoopback) {
            System.out.println("套接字模式下服务器不在本地，发送路径可能失败");
        }
        Map<String, String> reqJson = new HashMap<>();
        reqJson.put("image_path", imgFile.toString());
        return this.sendJsonToOcr(reqJson);
    }

    /**
     * 使用剪贴板中图片进行 OCR
     * @return
     * @throws IOException
     */
    public OcrRes runOcrOnClipboard() throws IOException {
        if (mode == OcrMode.SOCKET_SERVER && !isLoopback) {
            System.out.println("套接字模式下服务器不在本地，发送剪贴板可能失败");
        }
        Map<String, String> reqJson = new HashMap<>();
        reqJson.put("image_path", "clipboard");
        return this.sendJsonToOcr(reqJson);
    }

    /**
     * 使用 Base64 编码的图片进行 OCR
     * @param base64str
     * @return
     * @throws IOException
     */
    public OcrRes runOcrOnImgBase64(String base64str) throws IOException {
        Map<String, String> reqJson = new HashMap<>();
        reqJson.put("image_base64", base64str);
        return this.sendJsonToOcr(reqJson);
    }

    /**
     * 使用图片 Byte 数组进行 OCR
     * @param fileBytes
     * @return
     * @throws IOException
     */
    public OcrRes runOcrOnImgBytes(byte[] fileBytes) throws IOException {
        return this.runOcrOnImgBase64(Base64.getEncoder().encodeToString(fileBytes));
    }

    private OcrRes sendJsonToOcr(Map<String, String> reqJson) throws IOException {
        if (!isAlive()) {
            throw new RuntimeException("OCR进程已经退出或连接已断开");
        }
        // 重建 socket，修复长时间无请求时 socket 断开（Software caused connection abort: socket write error ）
        // https://github.com/hiroi-sora/PaddleOCR-json/issues/106
        if (OcrMode.SOCKET_SERVER == mode) {
            writer.close();
            reader.close();
            clientSocket.close();
            clientSocket = new Socket(serverAddr, serverPort);
            clientSocket.setKeepAlive(true);
            reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
        }
        writer.write(JSONUtil.toJsonStr(reqJson));
        writer.write("\r\n");
        writer.flush();
        String resp = reader.readLine();
        System.out.println("识别结果:"+new String (resp.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        return JSONUtil.toBean(resp, OcrRes.class);
    }


    private void checkIfLoopback() {
        if (this.mode != OcrMode.SOCKET_SERVER) return;
        try {
            InetAddress address = InetAddress.getByName(serverAddr);
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
            if (networkInterface != null && networkInterface.isLoopback()) {
                this.isLoopback = true;
            } else {
                this.isLoopback = false;
            }
        } catch (Exception e) {
            // 非关键路径
            System.out.println("套接字模式，未能确认服务端是否在本地");
        }
        System.out.println("套接字模式下，服务端在本地：" + isLoopback);
    }

    private boolean isAlive() {
        switch (this.mode) {
            case LOCAL_PROCESS:
                return process.isAlive();
            case SOCKET_SERVER:
                return clientSocket.isConnected();
        }
        return false;
    }


    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    @Override
    public void close() {
        if (isAlive()) {
            switch (this.mode) {
                case LOCAL_PROCESS: {
                    process.destroy();
                    break;
                }
                case SOCKET_SERVER: {
                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
        }
    }

}
