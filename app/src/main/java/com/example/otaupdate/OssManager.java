package com.example.otaupdate;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.alibaba.sdk.android.oss.ClientConfiguration;
import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.OSS;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback;
import com.alibaba.sdk.android.oss.callback.OSSProgressCallback; // 确保导入
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider;
import com.alibaba.sdk.android.oss.internal.OSSAsyncTask;
import com.alibaba.sdk.android.oss.model.GetObjectRequest;
import com.alibaba.sdk.android.oss.model.GetObjectResult;
import com.alibaba.sdk.android.oss.model.ListObjectsRequest;
import com.alibaba.sdk.android.oss.model.ListObjectsResult;
import com.alibaba.sdk.android.oss.model.OSSObjectSummary;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OssManager {
    private static final String TAG = "OssManager";
    private final OSS oss;
    private final String bucketName = "ota-firmware-carupdate";
    private final ExecutorService networkExecutor; // Network specific executor
    private final Handler mainThreadHandler;
    private final Context context; // 保存原始 context
    
    // 当前下载任务引用，用于取消下载
    private OSSAsyncTask<GetObjectResult> currentDownloadTask = null;
    
    // 用于保存暂停前的状态
    private String pausedObjectKey = null;
    private String pausedDestinationPath = null;
    private DownloadCallback pausedCallback = null;
    private boolean isPaused = false;
    private long downloadedSize = 0;
    private long totalSize = 0;

    // Callback Interfaces
    public interface OssCallback<T> {
        void onSuccess(@Nullable T result); // Allow null result

        void onFailure(@NonNull Exception e);
    }

    public interface DownloadCallback {
        void onProgress(long currentSize, long totalSize);

        void onSuccess();

        void onFailure(@NonNull Exception e);
    }

    public OssManager(@NonNull Context context) {
        String ossEndpoint = "https://oss-ap-southeast-1.aliyuncs.com";
        String accessKeyId = "LTAI5tHZRBzop6SQJBRXfjUG";
        String accessKeySecret = "CGYrtHahbigDUBPGFXSVHafqz3OjwM"; 
        String securityToken = ""; 
        
        this.context = context; // 保存原始 context
        
        OSSCredentialProvider credentialProvider = new OSSStsTokenCredentialProvider(accessKeyId, accessKeySecret, securityToken);
        ClientConfiguration conf = new ClientConfiguration();
        conf.setConnectionTimeout(15 * 1000);
        conf.setSocketTimeout(15 * 1000);
        conf.setMaxConcurrentRequest(5);
        conf.setMaxErrorRetry(2);
        oss = new OSSClient(context.getApplicationContext(), ossEndpoint, credentialProvider, conf);
        Log.d(TAG, "OSS SDK initialized with real credentials.");
        networkExecutor = Executors.newFixedThreadPool(3);
        mainThreadHandler = new Handler(Looper.getMainLooper());
    }

    public void checkMcuUpdate(@NonNull final OssCallback<UpdateInfo> callback) {
        networkExecutor.submit(() -> {
            final String prefix = "firmware/MCU/";
            Log.d(TAG, "Checking MCU updates: " + prefix);
            ListObjectsRequest request = new ListObjectsRequest(bucketName);
            request.setPrefix(prefix);
            request.setDelimiter("/");
            try {
                ListObjectsResult result = oss.listObjects(request);
                String cpuModel = DeviceInfoUtils.getCpuModel();
                String expectedMcuFileName;
                
                if ("UIS8581A".equals(cpuModel)) {
                    expectedMcuFileName = "L6315_MCU.zip";
                } else if ("UIS8141E".equals(cpuModel)) {
                    expectedMcuFileName = "L6523_MCU.zip";
                } else {
                    expectedMcuFileName = "L6315_MCU.zip"; // 默认值
                    Log.w(TAG, "未知CPU型号: " + cpuModel + "，默认使用: " + expectedMcuFileName);
                }

                String targetKey = prefix + expectedMcuFileName;
                UpdateInfo mcuUpdate = null;

                if (result != null && result.getObjectSummaries() != null) {
                    for (OSSObjectSummary summary : result.getObjectSummaries()) {
                        String key = summary.getKey();
                        if (key.equals(targetKey)) {
                            String version = expectedMcuFileName.replace(".zip", "");
                            mcuUpdate = new UpdateInfo(version, key);
                            break;
                        }
                    }
                }
                final UpdateInfo finalUpdate = mcuUpdate;
                mainThreadHandler.post(() -> callback.onSuccess(finalUpdate));
            } catch (Exception e) {
                Log.e(TAG, "检查MCU更新失败", e);
                String errorMessage = e.getMessage();
                // 检查是否是凭证无效错误
                if (errorMessage != null && (errorMessage.contains("InvalidAccessKeyId") || 
                        errorMessage.contains("Access Key Id") || 
                        errorMessage.contains("AccessKeyId"))) {
                    Log.e(TAG, "OSS凭证无效，请检查AccessKeyId和AccessKeySecret", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("OSS凭证无效，请联系管理员更新凭证")));
                } else if (errorMessage != null && (errorMessage.contains("Network") || 
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("connection"))) {
                    Log.e(TAG, "网络连接错误", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("网络连接错误，请检查网络设置")));
                } else {
                    mainThreadHandler.post(() -> callback.onFailure(e));
                }
            }
        });
    }

    public void checkSystemUpdate(String cpuModel, String resolution, @NonNull final OssCallback<UpdateInfo> callback) {
        networkExecutor.submit(() -> {
            String mappedCpuModel = cpuModel;
            String formattedResolution = resolution;
            if (resolution != null && resolution.contains("x")) {
                String[] parts = resolution.split("x");
                if (parts.length == 2) {
                    try {
                        int width = Integer.parseInt(parts[0]);
                        int height = Integer.parseInt(parts[1]);
                        if (width < height) {
                            formattedResolution = height + "x" + width;
                            Log.d(TAG, "分辨率格式已调整: " + resolution + " -> " + formattedResolution);
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "分辨率格式解析失败: " + resolution, e);
                    }
                }
            }
            // 新路径规则：直接在firmware/System/目录下查找
            final String prefix = "firmware/System/";
            Log.d(TAG, "Checking system updates: " + prefix + " (原始分辨率: " + resolution + ")");
            ListObjectsRequest request = new ListObjectsRequest(bucketName);
            request.setPrefix(prefix);
            request.setDelimiter("/");
            try {
                ListObjectsResult result = oss.listObjects(request);
                UpdateInfo latestUpdate = null;
                long latestDate = 0L;
                if (result != null && result.getObjectSummaries() != null) {
                    Log.d(TAG, "找到" + result.getObjectSummaries().size() + "个对象");
                    for (OSSObjectSummary summary : result.getObjectSummaries()) {
                        String key = summary.getKey();
                        Log.d(TAG, "检查对象: " + key + ", 大小: " + summary.getSize());
                        if (summary.getKey().endsWith("/") || summary.getSize() <= 0) {
                            Log.d(TAG, "跳过目录或空文件: " + key);
                            continue;
                        }
                        String fileName = key.substring(key.lastIndexOf('/') + 1);
                        Log.d(TAG, "提取文件名: " + fileName);
                        // 文件名格式: UIS8581A_1280x800_20250306.zip
                        if (fileName.endsWith(".zip")) {
                            String fileNameWithoutExt = fileName.replace(".zip", "");
                            String[] parts = fileNameWithoutExt.split("_");
                            Log.d(TAG, "文件名分段: " + String.join(", ", parts) + ", 分段数: " + parts.length);
                            // 匹配CPU+分辨率
                            if (parts.length >= 3) {
                                String cpuAndRes = parts[0] + "_" + parts[1];
                                if (cpuAndRes.equalsIgnoreCase(mappedCpuModel + "_" + formattedResolution)) {
                                    String dateStr = parts[parts.length - 1];
                                    Log.d(TAG, "提取日期字符串: " + dateStr);
                                    if (dateStr.matches("\\d{8}")) {
                                        try {
                                            long currentDate = Long.parseLong(dateStr);
                                            if (latestUpdate == null || currentDate > latestDate) {
                                                latestDate = currentDate;
                                                latestUpdate = new UpdateInfo(dateStr, key);
                                                Log.d(TAG, "找到更新版本: " + dateStr + ", 文件: " + key);
                                            }
                                        } catch (NumberFormatException e) {
                                            Log.w(TAG, "日期格式无效: " + dateStr, e);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                final UpdateInfo finalResult = latestUpdate;
                mainThreadHandler.post(() -> callback.onSuccess(finalResult));
            } catch (Exception e) {
                Log.e(TAG, "System update check failed", e);
                String errorMessage = e.getMessage();
                // 检查是否是凭证无效错误
                if (errorMessage != null && (errorMessage.contains("InvalidAccessKeyId") || 
                        errorMessage.contains("Access Key Id") || 
                        errorMessage.contains("AccessKeyId"))) {
                    Log.e(TAG, "OSS凭证无效，请检查AccessKeyId和AccessKeySecret", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("OSS凭证无效，请联系管理员更新凭证")));
                } else if (errorMessage != null && (errorMessage.contains("Network") || 
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("connection"))) {
                    Log.e(TAG, "网络连接错误", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("网络连接错误，请检查网络设置")));
                } else {
                    mainThreadHandler.post(() -> callback.onFailure(e));
                }
            }
        });
    }

    public void checkSystemAppUpdate(@NonNull final OssCallback<UpdateInfo> callback) {
        networkExecutor.submit(() -> {
            final String prefix = "firmware/System APP/";
            Log.d(TAG, "Checking System APP updates: " + prefix);
            ListObjectsRequest request = new ListObjectsRequest(bucketName);
            request.setPrefix(prefix);
            request.setDelimiter("/");

            // 获取当前设备的CPU型号和分辨率
            String cpuModel = DeviceInfoUtils.getCpuModel();
            String resolution = DeviceInfoUtils.getScreenResolution(context);
            String mappedCpuModel = cpuModel;
            String formattedResolution = resolution;

            // 格式化分辨率，确保宽度和高度按规则排序
            if (resolution != null && resolution.contains("x")) {
                String[] parts = resolution.split("x");
                if (parts.length == 2) {
                    try {
                        int width = Integer.parseInt(parts[0]);
                        int height = Integer.parseInt(parts[1]);
                        if (width < height) {
                            formattedResolution = height + "x" + width;
                            Log.d(TAG, "分辨率格式已调整: " + resolution + " -> " + formattedResolution);
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "分辨率格式解析失败: " + resolution, e);
                    }
                }
            }

            Log.d(TAG, "查找System APP更新: CPU=" + mappedCpuModel + ", 分辨率=" + formattedResolution);

            try {
                ListObjectsResult result = oss.listObjects(request);
                UpdateInfo latestUpdate = null;
                long latestDate = 0L;
                if (result != null && result.getObjectSummaries() != null) {
                    Log.d(TAG, "找到" + result.getObjectSummaries().size() + "个对象");
                    for (OSSObjectSummary summary : result.getObjectSummaries()) {
                        String key = summary.getKey();
                        Log.d(TAG, "检查对象: " + key + ", 大小: " + summary.getSize());
                        if (summary.getKey().endsWith("/") || summary.getSize() <= 0) {
                            Log.d(TAG, "跳过目录或空文件: " + key);
                            continue;
                        }
                        String fileName = key.substring(key.lastIndexOf('/') + 1);
                        Log.d(TAG, "提取文件名: " + fileName);
                        
                        // 文件名格式: ALLApp_UIS8581A_1280x800_20250306.zip
                        if (fileName.endsWith(".zip")) {
                            String fileNameWithoutExt = fileName.replace(".zip", "");
                            String[] parts = fileNameWithoutExt.split("_");
                            Log.d(TAG, "文件名分段: " + String.join(", ", parts) + ", 分段数: " + parts.length);
                            
                            // 检查是否符合新的命名格式，至少应该有4个部分
                            if (parts.length >= 4) {
                                // ALLApp_UIS8581A_1280x800_20250306
                                String cpuAndRes = parts[1] + "_" + parts[2];
                                if (cpuAndRes.equalsIgnoreCase(mappedCpuModel + "_" + formattedResolution)) {
                                    String dateStr = parts[parts.length - 1];
                                    Log.d(TAG, "提取日期字符串: " + dateStr);
                                    if (dateStr.matches("\\d{8}")) {
                                        try {
                                            long currentDate = Long.parseLong(dateStr);
                                            if (latestUpdate == null || currentDate > latestDate) {
                                                latestDate = currentDate;
                                                latestUpdate = new UpdateInfo(dateStr, key);
                                                Log.d(TAG, "找到System APP更新版本: " + dateStr + ", 文件: " + key);
                                            }
                                        } catch (NumberFormatException e) {
                                            Log.w(TAG, "日期格式无效: " + dateStr, e);
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "CPU型号或分辨率不匹配: 期望 " + mappedCpuModel + "_" + formattedResolution + 
                                           ", 实际 " + cpuAndRes);
                                }
                            } else {
                                // 兼容旧格式，仅根据日期判断
                                String dateStr = parts.length > 1 ? parts[parts.length - 1] : null;
                                if (dateStr != null && dateStr.matches("\\d{8}")) {
                                    Log.d(TAG, "使用旧格式解析: 日期 = " + dateStr);
                                    try {
                                        long currentDate = Long.parseLong(dateStr);
                                        if (latestUpdate == null || currentDate > latestDate) {
                                            latestDate = currentDate;
                                            latestUpdate = new UpdateInfo(dateStr, key);
                                            Log.d(TAG, "找到System APP更新版本: " + dateStr + ", 文件: " + key);
                                        }
                                    } catch (NumberFormatException e) {
                                        Log.w(TAG, "System APP日期格式无效: " + dateStr, e);
                                    }
                                }
                            }
                        }
                    }
                }
                final UpdateInfo finalResult = latestUpdate;
                mainThreadHandler.post(() -> callback.onSuccess(finalResult));
            } catch (Exception e) {
                Log.e(TAG, "System APP update check failed", e);
                String errorMessage = e.getMessage();
                // 检查是否是凭证无效错误
                if (errorMessage != null && (errorMessage.contains("InvalidAccessKeyId") || 
                        errorMessage.contains("Access Key Id") || 
                        errorMessage.contains("AccessKeyId"))) {
                    Log.e(TAG, "OSS凭证无效，请检查AccessKeyId和AccessKeySecret", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("OSS凭证无效，请联系管理员更新凭证")));
                } else if (errorMessage != null && (errorMessage.contains("Network") || 
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("connection"))) {
                    Log.e(TAG, "网络连接错误", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("网络连接错误，请检查网络设置")));
                } else {
                    mainThreadHandler.post(() -> callback.onFailure(e));
                }
            }
        });
    }

    public void checkMcuUpdate(String currentMcuVersion, @NonNull final OssCallback<UpdateInfo> callback) {
        networkExecutor.submit(() -> {
            final String prefix = "firmware/MCU/";
            Log.d(TAG, "Checking MCU updates: " + prefix + ", current MCU version: " + currentMcuVersion);
            ListObjectsRequest request = new ListObjectsRequest(bucketName);
            request.setPrefix(prefix);
            request.setDelimiter("/");
            try {
                ListObjectsResult result = oss.listObjects(request); // Blocking call
                UpdateInfo mcuUpdate = null;
                // 根据当前MCU版本确定要查找的文件名
                String expectedMcuFileName;
                if ("L6523".equals(currentMcuVersion)) {
                    expectedMcuFileName = "L6523_MCU.zip"; // UIS8141E对应的MCU
                } else if ("L6315".equals(currentMcuVersion)) {
                    expectedMcuFileName = "L6315_MCU.zip"; // UIS8581A对应的MCU
                } else {
                    // 默认查找L6315
                    expectedMcuFileName = "L6315_MCU.zip";
                    Log.w(TAG, "未知MCU版本: " + currentMcuVersion + "，默认查找: " + expectedMcuFileName);
                }
                Log.d(TAG, "查找MCU更新文件: " + expectedMcuFileName);
                
                if (result != null && result.getObjectSummaries() != null) {
                    for (OSSObjectSummary summary : result.getObjectSummaries()) {
                        if (summary.getKey().endsWith("/") || summary.getSize() <= 0) continue;
                        String key = summary.getKey();
                        String fileName = key.substring(key.lastIndexOf('/') + 1);
                        if (fileName.equalsIgnoreCase(expectedMcuFileName)) {
                            mcuUpdate = new UpdateInfo(fileName.replace(".zip", ""), key);
                            Log.d(TAG, "Matching MCU found: " + fileName);
                            break;
                        }
                    }
                }
                final UpdateInfo finalResult = mcuUpdate;
                mainThreadHandler.post(() -> callback.onSuccess(finalResult));
            } catch (Exception e) {
                Log.e(TAG, "MCU update check failed", e);
                String errorMessage = e.getMessage();
                // 检查是否是凭证无效错误
                if (errorMessage != null && (errorMessage.contains("InvalidAccessKeyId") || 
                        errorMessage.contains("Access Key Id") || 
                        errorMessage.contains("AccessKeyId"))) {
                    Log.e(TAG, "OSS凭证无效，请检查AccessKeyId和AccessKeySecret", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("OSS凭证无效，请联系管理员更新凭证")));
                } else if (errorMessage != null && (errorMessage.contains("Network") || 
                        errorMessage.contains("timeout") || 
                        errorMessage.contains("connection"))) {
                    Log.e(TAG, "网络连接错误", e);
                    mainThreadHandler.post(() -> callback.onFailure(
                            new Exception("网络连接错误，请检查网络设置")));
                } else {
                    mainThreadHandler.post(() -> callback.onFailure(e));
                }
            }
        });
    }

    @Nullable
    public OSSAsyncTask<GetObjectResult> downloadUpdate(
            String objectKey,
            String destinationPath,
            @NonNull final DownloadCallback callback
    ) {
        Log.d(TAG, "Download task started: " + objectKey);
        GetObjectRequest request = new GetObjectRequest(bucketName, objectKey);

        // 保存下载参数，以便暂停时使用
        pausedObjectKey = objectKey;
        pausedDestinationPath = destinationPath;
        pausedCallback = callback;

        // 正确设置进度回调
        request.setProgressListener(new OSSProgressCallback<GetObjectRequest>() {
            @Override
            public void onProgress(GetObjectRequest request, long currentSize, long totalSize) {
                // 保存当前下载进度
                downloadedSize = currentSize;
                OssManager.this.totalSize = totalSize;
                
                // 回调到主线程更新 UI
                mainThreadHandler.post(() -> callback.onProgress(currentSize, totalSize));
            }
        });
        // ===============================================

        currentDownloadTask = oss.asyncGetObject(request, new OSSCompletedCallback<GetObjectRequest, GetObjectResult>() {
            @Override
            public void onSuccess(GetObjectRequest completedRequest, GetObjectResult result) {
                Log.d(TAG, "OSS Download onSuccess: " + objectKey);
                networkExecutor.submit(() -> { // 在后台线程写入文件
                    FileOutputStream fos = null;
                    InputStream inputStream = null;
                    try {
                        inputStream = result.getObjectContent();
                        fos = new FileOutputStream(destinationPath);
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = inputStream.read(buffer)) != -1 && !Thread.currentThread().isInterrupted()) {
                            fos.write(buffer, 0, len);
                        }
                        if (Thread.currentThread().isInterrupted())
                            throw new IOException("Download cancelled during write");
                        fos.flush();
                        Log.d(TAG, "File write success: " + destinationPath);
                        
                        // 下载完成后解压文件
                        File downloadedFile = new File(destinationPath);
                        if (destinationPath.toLowerCase().endsWith(".zip")) {
                            // 创建解压目录
                            String extractDirPath = destinationPath.substring(0, destinationPath.lastIndexOf('.'));
                            File extractDir = new File(extractDirPath);
                            if (!extractDir.exists()) {
                                extractDir.mkdirs();
                            }
                            
                            Log.d(TAG, "Extracting zip file to: " + extractDirPath);
                            // 解压文件，并通过主线程回调更新UI
                            boolean extractSuccess = FileUtils.unzip(destinationPath, extractDirPath, progress -> {
                                Log.d(TAG, "Unzip progress: " + progress + "%");
                                // 通过主线程更新UI
                                mainThreadHandler.post(() -> {
                                    callback.onProgress(progress, 100); // 复用下载进度回调
                                });
                            });
                            
                            if (extractSuccess) {
                                Log.d(TAG, "Extraction successful");
                                // 删除原始下载的zip文件以释放空间
                                if (downloadedFile.exists()) {
                                    if (downloadedFile.delete()) {
                                        Log.d(TAG, "Deleted original zip file: " + destinationPath);
                                    } else {
                                        Log.w(TAG, "Failed to delete original zip file: " + destinationPath);
                                    }
                                }
                                
                                // 移动文件到/mnt/sdcard目录（直接移动文件内容，而不是整个文件夹）
                                String sdcardPath = "/mnt/sdcard";
                                Log.d(TAG, "Moving files to: " + sdcardPath);
                                
                                // 确保目标目录存在
                                File sdcardDir = new File(sdcardPath);
                                if (!sdcardDir.exists()) {
                                    sdcardDir.mkdirs();
                                    Log.d(TAG, "Created target directory: " + sdcardPath);
                                }
                                
                                // 获取解压目录中的所有文件和文件夹
                                File[] extractedItems = new File(extractDirPath).listFiles();
                                boolean moveSuccess = true;
                                if (extractedItems != null && extractedItems.length > 0) {
                                    int totalItems = extractedItems.length;
                                    int processedItems = 0;
                                    for (File item : extractedItems) {
                                        Log.d(TAG, "Moving item: " + item.getName());
                                        File targetPath = new File(sdcardPath, item.getName());
                                        // 如果目标已存在，先删除
                                        if (targetPath.exists()) {
                                            targetPath.delete();
                                        }
                                        // 只移动文件（不移动整个文件夹）
                                        if (item.isFile()) {
                                            if (!item.renameTo(targetPath)) {
                                                // 如果重命名失败，尝试复制
                                                if (!FileUtils.copyFile(item, targetPath) || !item.delete()) {
                                                    Log.e(TAG, "Failed to move file: " + item.getName());
                                                    moveSuccess = false;
                                                }
                                            }
                                        } else if (item.isDirectory()) {
                                            // 递归移动文件夹内所有文件
                                            File[] subFiles = item.listFiles();
                                            if (subFiles != null) {
                                                for (File subFile : subFiles) {
                                                    File subTarget = new File(sdcardPath, subFile.getName());
                                                    if (subTarget.exists()) subTarget.delete();
                                                    if (!subFile.renameTo(subTarget)) {
                                                        if (!FileUtils.copyFile(subFile, subTarget) || !subFile.delete()) {
                                                            Log.e(TAG, "Failed to move file: " + subFile.getName());
                                                            moveSuccess = false;
                                                        }
                                                    }
                                                }
                                            }
                                            // 删除空文件夹
                                            item.delete();
                                        }
                                        // 更新进度
                                        processedItems++;
                                        final int progress = (int)((processedItems * 100) / totalItems);
                                        Log.d(TAG, "Move progress: " + progress + "%");
                                        mainThreadHandler.post(() -> {
                                            callback.onProgress(progress, 100);
                                        });
                                    }
                                } else {
                                    Log.w(TAG, "No files found in extraction directory");
                                }
                                
                                if (moveSuccess) {
                                    Log.d(TAG, "Files moved successfully");
                                    // 删除解压目录
                                    if (extractDir.exists()) {
                                        if (FileUtils.deleteRecursive(extractDir)) {
                                            Log.d(TAG, "Deleted extraction directory: " + extractDirPath);
                                        } else {
                                            Log.w(TAG, "Failed to delete extraction directory: " + extractDirPath);
                                        }
                                    }
                                    
                                    // 重启系统
                                    rebootDevice();
                                } else {
                                    Log.e(TAG, "Failed to move files to " + sdcardPath);
                                    mainThreadHandler.post(() -> callback.onFailure(new IOException("Failed to move files to " + sdcardPath)));
                                    return;
                                }
                            } else {
                                Log.e(TAG, "Failed to extract zip file");
                                mainThreadHandler.post(() -> callback.onFailure(new IOException("Failed to extract zip file")));
                                return;
                            }
                        }
                        
                        mainThreadHandler.post(callback::onSuccess); // 回调成功
                        // 清空当前下载任务引用
                        currentDownloadTask = null;
                    } catch (Exception e) {
                        Log.e(TAG, "File write failed", e);
                        FileUtils.deleteRecursive(new File(destinationPath));
                        mainThreadHandler.post(() -> callback.onFailure(e)); // 回调失败
                        // 清空当前下载任务引用
                        currentDownloadTask = null;
                    } finally {
                        FileUtils.closeQuietly(fos); // 使用修正后的 public 方法
                        FileUtils.closeQuietly(inputStream); // 使用修正后的 public 方法
                    }
                });
            }

            @Override
            public void onFailure(GetObjectRequest failedRequest, ClientException clientEx, ServiceException serviceEx) {
                if (clientEx != null) clientEx.printStackTrace();
                if (serviceEx != null) serviceEx.printStackTrace();
                String msg = serviceEx != null ? serviceEx.getRawMessage() : (clientEx != null ? clientEx.getMessage() : "Unknown download error");
                Log.e(TAG, "OSS Download onFailure: " + msg);
                FileUtils.deleteRecursive(new File(destinationPath));
                
                // 检查是否是凭证无效错误
                if (msg != null && (msg.contains("InvalidAccessKeyId") || 
                        msg.contains("Access Key Id") || 
                        msg.contains("AccessKeyId"))) {
                    Log.e(TAG, "OSS凭证无效，请检查AccessKeyId和AccessKeySecret");
                    Exception e = new IOException("OSS凭证无效，请联系管理员更新凭证", clientEx != null ? clientEx : serviceEx);
                    mainThreadHandler.post(() -> callback.onFailure(e));
                } else if (msg != null && (msg.contains("Network") || 
                        msg.contains("timeout") || 
                        msg.contains("connection"))) {
                    Log.e(TAG, "网络连接错误");
                    Exception e = new IOException("网络连接错误，请检查网络设置", clientEx != null ? clientEx : serviceEx);
                    mainThreadHandler.post(() -> callback.onFailure(e));
                } else {
                    Exception e = new IOException("Download failed: " + msg, clientEx != null ? clientEx : serviceEx);
                    mainThreadHandler.post(() -> callback.onFailure(e)); // 回调失败
                }
                // 清空当前下载任务引用
                currentDownloadTask = null;
            }
        });
        
        return currentDownloadTask;
    }

    /**
     * 取消当前下载任务
     */
    public void cancelDownload() {
        if (currentDownloadTask != null && !currentDownloadTask.isCompleted()) {
            Log.d(TAG, "Cancelling download task");
            currentDownloadTask.cancel();
            currentDownloadTask = null;
        } else {
            Log.d(TAG, "No active download task to cancel");
        }
        
        // 清除暂停状态
        isPaused = false;
        pausedObjectKey = null;
        pausedDestinationPath = null;
        pausedCallback = null;
        downloadedSize = 0;
        totalSize = 0;
    }

    /**
     * 暂停当前下载任务
     * 注意：阿里云OSS SDK不直接支持暂停和恢复，这里模拟实现
     */
    public void pauseDownload() {
        Log.d(TAG, "Pausing download task");
        if (currentDownloadTask != null && !currentDownloadTask.isCompleted()) {
            // 设置暂停标志
            isPaused = true;
            
            // 取消当前下载任务，但保留任务参数，以便之后恢复
            currentDownloadTask.cancel();
            Log.d(TAG, "Download paused");
        } else {
            Log.d(TAG, "No active download task to pause");
        }
    }

    /**
     * 恢复下载任务
     * 注意：阿里云OSS SDK不直接支持暂停和恢复，这里通过重新开始下载来模拟实现
     * 
     * @param objectKey OSS对象键
     * @param destinationPath 目标文件路径
     */
    public void resumeDownload(String objectKey, String destinationPath) {
        Log.d(TAG, "Resuming download: " + objectKey);
        
        // 保存新的下载参数
        pausedObjectKey = objectKey;
        pausedDestinationPath = destinationPath;
        
        // 重置暂停状态
        isPaused = false;
        
        // 如果尚未启动下载或下载已完成
        if (currentDownloadTask == null || currentDownloadTask.isCompleted()) {
            currentDownloadTask = null;
        }
        
        // 重新开始下载
        if (pausedCallback != null) {
            downloadUpdate(objectKey, destinationPath, pausedCallback);
            Log.d(TAG, "Download resumed with original callback");
        } else {
            // 如果没有保存回调，创建一个新的回调（这种情况不应该发生）
            downloadUpdate(objectKey, destinationPath, new DownloadCallback() {
                @Override
                public void onProgress(long currentSize, long totalSize) {
                    Log.d(TAG, "Resume download progress: " + currentSize + "/" + totalSize);
                }

                @Override
                public void onSuccess() {
                    Log.d(TAG, "Resume download completed successfully");
                }

                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.e(TAG, "Resume download failed", e);
                }
            });
            Log.d(TAG, "Download resumed with new callback (abnormal)");
        }
    }

    public void shutdown() {
        if (currentDownloadTask != null && !currentDownloadTask.isCompleted()) {
            currentDownloadTask.cancel();
            currentDownloadTask = null;
        }
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            networkExecutor.shutdownNow();
        }
    }

    public void rebootDevice() {
        try {
            // 首先显示重启确认对话框
            if (context instanceof android.app.Activity) {
                android.app.Activity activity = (android.app.Activity) context;
                if (!activity.isFinishing()) {
                    activity.runOnUiThread(() -> {
                        new android.app.AlertDialog.Builder(activity)
                            .setTitle(R.string.reboot_dialog_title)
                            .setMessage(R.string.reboot_dialog_message)
                            .setPositiveButton(R.string.reboot_dialog_confirm, (dialog, which) -> {
                                // 用户点击"现在重启"按钮
                                try {
                                    // 直接使用shell命令重启
                                    Log.d(TAG, "尝试使用shell命令重启...");
                                    Runtime.getRuntime().exec("reboot recovery");
                                    Log.i(TAG, "已执行reboot recovery命令");
                                    
                                    // 显示正在重启提示
                                    Toast.makeText(activity, R.string.status_rebooting, Toast.LENGTH_LONG).show();
                                    
                                    // 延迟5秒后，如果应用还在运行，才显示手动重启提示
                                    new Handler().postDelayed(() -> {
                                        if (!activity.isFinishing() && !activity.isDestroyed()) {
                                            // 如果5秒后应用仍在运行，则显示手动重启提示
                                            new android.app.AlertDialog.Builder(activity)
                                                .setTitle(R.string.error)
                                                .setMessage(R.string.error_reboot)
                                                .setPositiveButton(R.string.ok, null)
                                                .setCancelable(false)
                                                .show();
                                        }
                                    }, 5000);
                                } catch (Exception e) {
                                    Log.e(TAG, "重启命令执行失败", e);
                                    // 显示手动重启提示
                                    new android.app.AlertDialog.Builder(activity)
                                        .setTitle(R.string.error)
                                        .setMessage(R.string.error_reboot)
                                        .setPositiveButton(R.string.ok, null)
                                        .setCancelable(false)
                                        .show();
                                }
                            })
                            .setNegativeButton(R.string.reboot_dialog_later, null) // 用户点击"稍后"按钮，仅关闭对话框
                            .setCancelable(false)
                            .show();
                    });
                    return; // 显示对话框后就返回，由对话框处理后续重启
                }
            }
            
            // 如果无法显示对话框，执行备用重启逻辑
            Log.d(TAG, "无法显示对话框，直接执行shell命令重启");
            Runtime.getRuntime().exec("reboot recovery");
            Log.i(TAG, "已执行reboot recovery命令");
            
        } catch (Exception e) {
            Log.e(TAG, "重启流程执行失败", e);
            // 广播通知MainActivity显示手动重启对话框
            try {
                android.content.Intent intent = new android.content.Intent("com.example.otaupdate.SHOW_REBOOT_DIALOG");
                context.sendBroadcast(intent);
                Log.d(TAG, "发送广播显示手动重启对话框");
            } catch (Exception ex) {
                Log.e(TAG, "发送广播失败", ex);
            }
        }
    }
}