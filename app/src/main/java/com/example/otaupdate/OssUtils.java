package com.example.otaupdate;

import android.util.Log;

import com.alibaba.sdk.android.oss.OSS;
import com.alibaba.sdk.android.oss.OSSClient;
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider;
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider;
import com.alibaba.sdk.android.oss.model.GetObjectRequest;
import com.alibaba.sdk.android.oss.model.GetObjectResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class OssUtils {
    private static final String TAG = "OssUtils";
    
    // OSS配置信息
    private static final String ENDPOINT = "https://oss-ap-southeast-1.aliyuncs.com";
    private static final String ACCESS_KEY_ID = "LTAI5tHZRBzop6SQJBRXfjUG";
    private static final String ACCESS_KEY_SECRET = "CGYrtHahbigDUBPGFXSVHafqz3OjwM";
    private static final String BUCKET_NAME = "ota-firmware-carupdate";
    
    // 版本信息文件路径
    private static final String SYSTEM_VERSION_FILE = "system/version.txt";
    private static final String MCU_VERSION_FILE = "mcu/version.txt";
    
    private static OSS ossClient;
    
    private static synchronized OSS getOssClient(android.content.Context context) {
        if (ossClient == null) {
            OSSCredentialProvider credentialProvider = new OSSStsTokenCredentialProvider(ACCESS_KEY_ID, ACCESS_KEY_SECRET, "");
            ossClient = new OSSClient(context.getApplicationContext(), ENDPOINT, credentialProvider, null);
        }
        return ossClient;
    }
    
    public static String getLatestSystemVersion(android.content.Context context) {
        return getFileContent(SYSTEM_VERSION_FILE, context);
    }
    
    public static String getLatestMcuVersion(android.content.Context context) {
        return getFileContent(MCU_VERSION_FILE, context);
    }
    
    private static String getFileContent(String objectKey, android.content.Context context) {
        try {
            OSS oss = getOssClient(context);
            GetObjectRequest request = new GetObjectRequest(BUCKET_NAME, objectKey);
            GetObjectResult result = oss.getObject(request);
            
            try (InputStream inputStream = result.getObjectContent();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                return reader.readLine(); // 假设版本号在第一行
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read version from OSS: " + objectKey, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error accessing OSS: " + objectKey, e);
            return null;
        }
    }
    
    public static void shutdown() {
        if (ossClient != null) {
            // 阿里云OSS SDK可能没有shutdown方法，直接设置为null即可
            // ossClient.shutdown();
            ossClient = null;
        }
    }
}