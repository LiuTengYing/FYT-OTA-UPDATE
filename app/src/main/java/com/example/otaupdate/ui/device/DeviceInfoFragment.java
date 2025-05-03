package com.example.otaupdate.ui.device;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.otaupdate.BuildConfig;
import com.example.otaupdate.DeviceInfoUtils;
import com.example.otaupdate.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceInfoFragment extends Fragment {

    private static final String TAG = "DeviceInfoFragment";
    private static final String GITHUB_REPO_URL = "https://api.github.com/repos/LiuTengYing/FYT-OTA-UPDATE/releases/latest";

    private TextView tvCpuModel;
    private TextView tvResolution;
    private TextView tvMcuVersion;
    private TextView tvSystemVersion;
    private TextView tvSystemAppVersion;
    private TextView tvAppVersionCard;
    private Button btnCheckAppVersion;
    
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device_info, container, false);
        
        tvCpuModel = view.findViewById(R.id.tvCpuModel);
        tvResolution = view.findViewById(R.id.tvResolution);
        tvMcuVersion = view.findViewById(R.id.tvMcuVersion);
        tvSystemVersion = view.findViewById(R.id.tvSystemBuildDate);
        tvSystemAppVersion = view.findViewById(R.id.tvAppBuildDate);
        tvAppVersionCard = view.findViewById(R.id.tvAppVersionCard);
        btnCheckAppVersion = view.findViewById(R.id.btnCheckAppVersion);
        
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        btnCheckAppVersion.setOnClickListener(v -> checkAppVersion());
        
        fetchDeviceInfo();
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
    }
    
    private void fetchDeviceInfo() {
        backgroundExecutor.execute(() -> {
            String deviceCpuModel = DeviceInfoUtils.getCpuModel();
            String deviceResolution = DeviceInfoUtils.getScreenResolution(requireContext());
            String deviceMcuVersion = DeviceInfoUtils.getMcuVersion();
            String deviceSystemVersion = DeviceInfoUtils.getSystemBuildDate();
            String systemAppVersion = DeviceInfoUtils.getAppBuildTime();
            String appVersion = DeviceInfoUtils.getAppVersion(requireContext());
            
            mainThreadHandler.post(() -> {
                if (isAdded()) {
                    tvCpuModel.setText(getString(R.string.cpu_model_label, deviceCpuModel));
                    tvResolution.setText(getString(R.string.resolution_label, deviceResolution));
                    tvMcuVersion.setText(getString(R.string.mcu_version_label, deviceMcuVersion));
                    
                    // 修改显示方式，只显示日期部分
                    String systemVersionDate = deviceSystemVersion;
                    if (systemVersionDate != null && systemVersionDate.contains(" ")) {
                        systemVersionDate = systemVersionDate.split(" ")[0]; // 只保留日期部分
                    }
                    tvSystemVersion.setText(getString(R.string.system_version_label, systemVersionDate));
                    
                    // 修改显示方式，只显示日期部分
                    String appVersionDate = systemAppVersion;
                    if (appVersionDate != null && appVersionDate.contains(" ")) {
                        appVersionDate = appVersionDate.split(" ")[0]; // 只保留日期部分
                    }
                    tvSystemAppVersion.setText(getString(R.string.system_app_version_label, appVersionDate));
                    
                    tvAppVersionCard.setText(getString(R.string.app_version_label, appVersion));
                }
            });
        });
    }
    
    private void checkAppVersion() {
        btnCheckAppVersion.setEnabled(false);
        tvAppVersionCard.setText(R.string.app_version_label_placeholder);
        
        backgroundExecutor.execute(() -> {
            try {
                // 从GitHub获取最新版本信息
                Log.d(TAG, "正在检查更新，API URL: " + GITHUB_REPO_URL);
                URL url = new URL(GITHUB_REPO_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                
                // 检查响应码
                int responseCode = connection.getResponseCode();
                Log.d(TAG, "API响应码: " + responseCode);
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // 读取响应数据
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String responseData = response.toString();
                    Log.d(TAG, "API响应数据: " + responseData.substring(0, Math.min(500, responseData.length())) + "...");
                    
                    // 解析JSON响应
                    JSONObject jsonObject = new JSONObject(responseData);
                    String tagName = jsonObject.getString("tag_name");
                    String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String currentVersion = BuildConfig.VERSION_NAME;
                    String releaseNotes = jsonObject.getString("body");
                    
                    Log.d(TAG, "当前版本: " + currentVersion + ", 最新版本: " + latestVersion);
                    
                    // 获取APK下载链接
                    String downloadUrl = null;
                    JSONArray assets = jsonObject.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String fileName = asset.getString("name");
                        Log.d(TAG, "发现资源文件: " + fileName);
                        if (fileName.endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url");
                            Log.d(TAG, "找到APK下载链接: " + downloadUrl);
                            break;
                        }
                    }
                    
                    final String finalDownloadUrl = downloadUrl;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvAppVersionCard.setText(getString(R.string.app_version_label, currentVersion));
                            btnCheckAppVersion.setEnabled(true);
                            
                            // 比较版本号，判断是否需要更新
                            if (isNewerVersion(latestVersion, currentVersion) && finalDownloadUrl != null) {
                                Log.d(TAG, "发现新版本，需要更新");
                                showUpdateDialog(latestVersion, finalDownloadUrl, releaseNotes);
                            } else if (finalDownloadUrl != null) {
                                // 版本相同但有下载链接，询问是否需要强制更新
                                Log.d(TAG, "版本相同，询问是否强制更新");
                                showForceUpdateDialog(latestVersion, finalDownloadUrl, releaseNotes);
                            } else {
                                Log.d(TAG, "未找到APK下载链接");
                                Toast.makeText(requireContext(), R.string.no_update_available, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    Log.e(TAG, "GitHub API返回404，仓库或release不存在");
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvAppVersionCard.setText(getString(R.string.app_version_label, BuildConfig.VERSION_NAME));
                            btnCheckAppVersion.setEnabled(true);
                            Toast.makeText(requireContext(), R.string.no_update_available, Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    throw new IOException("HTTP响应码: " + responseCode);
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "检查更新失败", e);
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        tvAppVersionCard.setText(getString(R.string.app_version_label, BuildConfig.VERSION_NAME));
                        btnCheckAppVersion.setEnabled(true);
                        Toast.makeText(requireContext(), getString(R.string.check_update_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
    
    private boolean isNewerVersion(String remoteVersion, String localVersion) {
        Log.d(TAG, "比较版本号: 远程=" + remoteVersion + ", 本地=" + localVersion);
        try {
            // 版本号格式: x.y.z
            String[] remoteParts = remoteVersion.split("\\.");
            String[] localParts = localVersion.split("\\.");
            
            Log.d(TAG, "远程版本号部分: " + String.join(".", remoteParts));
            Log.d(TAG, "本地版本号部分: " + String.join(".", localParts));
            
            // 比较主版本号、次版本号和修订号
            for (int i = 0; i < Math.min(remoteParts.length, localParts.length); i++) {
                int remotePart = Integer.parseInt(remoteParts[i]);
                int localPart = Integer.parseInt(localParts[i]);
                
                Log.d(TAG, "比较第" + (i+1) + "部分: 远程=" + remotePart + ", 本地=" + localPart);
                
                if (remotePart > localPart) {
                    Log.d(TAG, "远程版本较新，需要更新");
                    return true;
                } else if (remotePart < localPart) {
                    Log.d(TAG, "本地版本较新，无需更新");
                    return false;
                }
            }
            
            // 如果前面的版本号都相同，但远程版本有更多的部分（例如1.0与1.0.1）
            boolean result = remoteParts.length > localParts.length;
            Log.d(TAG, "版本号主要部分相同，比较版本号长度: 远程长度=" + remoteParts.length + ", 本地长度=" + localParts.length);
            Log.d(TAG, "最终结果: " + (result ? "需要更新" : "无需更新"));
            return result;
        } catch (NumberFormatException e) {
            Log.e(TAG, "版本号比较失败", e);
            return false;
        }
    }
    
    private void showUpdateDialog(String version, String downloadUrl, String releaseNotes) {
        new AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.new_version_available) + ": " + version)
            .setMessage(getString(R.string.update_message, releaseNotes))
            .setPositiveButton(R.string.download_update, (dialog, which) -> {
                downloadAndInstallApk(downloadUrl, version);
            })
            .setNegativeButton(R.string.later, null)
            .show();
    }
    
    private void showForceUpdateDialog(String version, String downloadUrl, String releaseNotes) {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.force_update_title)
            .setMessage(getString(R.string.force_update_message) + "\n\n" + getString(R.string.update_message, releaseNotes))
            .setPositiveButton(R.string.force_update_confirm, (dialog, which) -> {
                downloadAndInstallApk(downloadUrl, version);
            })
            .setNegativeButton(R.string.force_update_cancel, null)
            .show();
    }
    
    private void downloadAndInstallApk(String downloadUrl, String version) {
        // 创建进度对话框
        ProgressDialog progressDialog = new ProgressDialog(requireContext());
        progressDialog.setTitle(getString(R.string.downloading));
        progressDialog.setMessage(getString(R.string.downloading_update, version));
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                // 创建下载目录
                File downloadDir = new File(requireContext().getExternalFilesDir(null), "updates");
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                
                // 从URL中获取文件名
                String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
                // 如果文件名为空，使用默认名称
                if (fileName.isEmpty()) {
                    fileName = "OTAUpdate-" + version + ".apk";
                }
                Log.d(TAG, "下载文件名: " + fileName);
                
                // 下载APK文件
                File outputFile = new File(downloadDir, fileName);
                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.connect();
                
                int fileLength = connection.getContentLength();
                Log.d(TAG, "文件大小: " + fileLength + " 字节");
                
                InputStream input = new BufferedInputStream(connection.getInputStream());
                OutputStream output = new FileOutputStream(outputFile);
                
                byte[] data = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        final int progress = (int) (total * 100 / fileLength);
                        mainThreadHandler.post(() -> progressDialog.setProgress(progress));
                    }
                    output.write(data, 0, count);
                }
                
                output.flush();
                output.close();
                input.close();
                
                Log.d(TAG, "下载完成，文件保存在: " + outputFile.getAbsolutePath());
                
                mainThreadHandler.post(() -> {
                    progressDialog.dismiss();
                    installApk(outputFile);
                });
            } catch (Exception e) {
                Log.e(TAG, "下载APK失败", e);
                mainThreadHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(), getString(R.string.download_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0及以上需要使用FileProvider
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String authority = requireContext().getPackageName() + ".provider";
            apkUri = FileProvider.getUriForFile(requireContext(), authority, apkFile);
        } else {
            // Android 7.0以下可以直接使用文件URI
            apkUri = Uri.fromFile(apkFile);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_app_to_open_apk, Toast.LENGTH_LONG).show();
        }
    }
}